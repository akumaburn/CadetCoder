package com.eonmux.cadetcoder.net;

import java.time.Duration;
import java.util.Random;

/**
 * Retry rules for provider calls: which failures are worth repeating, how many times, and how long
 * to wait in between.
 *
 * <p>Only failures that can plausibly change outcome are retried — HTTP 408, 429 and 5xx, plus
 * transport failures. 400/401/403 are never retried: a malformed request stays malformed and a
 * rejected key stays rejected, so repeating them only multiplies the latency before the user sees
 * the real error.</p>
 *
 * <p>Backoff is exponential with full jitter, so concurrent CadetCoder runs hitting the same
 * rate-limited provider do not resynchronise. A provider-supplied {@code Retry-After} always wins
 * over the computed delay; when it exceeds {@link #getMaxDelayMillis()} the request is not retried
 * at all — waiting minutes inside a CLI invocation is worse than failing with "retry later".</p>
 *
 * <p>The caps are tunable through system properties, matching the convention used elsewhere in the
 * codebase (e.g. {@code cadet.iterative.maxIterations}); out-of-range or unparseable values fall
 * back to the defaults.</p>
 */
public final class RetryPolicy {

    /**
     * Total attempts, including the first one.
     *
     * <p>Only failures that can plausibly change outcome are retried at all -- 429, 5xx and transport
     * -- and those are exactly the ones that reward persistence, so the budget is generous. With the
     * default delays this is a worst case of roughly a minute and a half of waiting before a run
     * gives up, and about half that on average once jitter is accounted for; a provider that is
     * rate-limiting or briefly down is usually back well inside it.</p>
     */
    public static final int  DEFAULT_MAX_ATTEMPTS  = 10;
    public static final long DEFAULT_BASE_DELAY_MS = 500L;
    public static final long DEFAULT_MAX_DELAY_MS  = 20_000L;

    public static final String MAX_ATTEMPTS_PROPERTY  = "cadet.llm.maxAttempts";
    public static final String BASE_DELAY_MS_PROPERTY = "cadet.llm.retry.baseDelayMs";
    public static final String MAX_DELAY_MS_PROPERTY  = "cadet.llm.retry.maxDelayMs";

    /** Ceiling for {@link #MAX_ATTEMPTS_PROPERTY}; above the default so the knob can also go up. */
    private static final int  MAX_ALLOWED_ATTEMPTS = 20;
    private static final long MAX_ALLOWED_DELAY_MS = 300_000L;

    private final int    maxAttempts;
    private final long   baseDelayMillis;
    private final long   maxDelayMillis;
    private final Random random;

    public RetryPolicy(int maxAttempts, long baseDelayMillis, long maxDelayMillis) {
        this(maxAttempts, baseDelayMillis, maxDelayMillis, new Random());
    }

    /**
     * @param random source of jitter; injectable so tests can make the backoff deterministic
     */
    public RetryPolicy(int maxAttempts, long baseDelayMillis, long maxDelayMillis, Random random) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        if (baseDelayMillis < 0 || maxDelayMillis < 0) {
            throw new IllegalArgumentException("delays must not be negative");
        }
        this.maxAttempts     = maxAttempts;
        this.baseDelayMillis = baseDelayMillis;
        this.maxDelayMillis  = Math.max(baseDelayMillis, maxDelayMillis);
        this.random          = random != null ? random : new Random();
    }

    /** Builds a policy from the {@code cadet.llm.*} system properties, falling back to defaults. */
    public static RetryPolicy fromSystemProperties() {
        int  attempts  = intProperty(MAX_ATTEMPTS_PROPERTY, DEFAULT_MAX_ATTEMPTS, 1, MAX_ALLOWED_ATTEMPTS);
        long baseDelay = longProperty(BASE_DELAY_MS_PROPERTY, DEFAULT_BASE_DELAY_MS, 0, MAX_ALLOWED_DELAY_MS);
        long maxDelay  = longProperty(MAX_DELAY_MS_PROPERTY, DEFAULT_MAX_DELAY_MS, 0, MAX_ALLOWED_DELAY_MS);
        return new RetryPolicy(attempts, baseDelay, maxDelay);
    }

    /**
     * Whether a status code is worth retrying: request timeout, rate limit, or a server-side error.
     * Everything else — notably 400/401/403 — is terminal.
     */
    public static boolean isRetryableStatus(int statusCode) {
        return statusCode == 408
                || statusCode == 429
                || (statusCode >= 500 && statusCode <= 599);
    }

    /**
     * @param failure the failure just observed, may be null
     * @param attempt 1-based number of the attempt that produced {@code failure}
     * @return whether another attempt should be made
     */
    public boolean shouldRetry(LLMException failure, int attempt) {
        if (failure == null || attempt >= maxAttempts || !failure.isRetryable()) {
            return false;
        }
        Duration retryAfter = failure.getRetryAfter();
        // A Retry-After longer than the cap means "come back much later"; honouring it would stall
        // the CLI, and ignoring it would hammer a provider that just asked us to stop.
        return retryAfter == null || retryAfter.toMillis() <= maxDelayMillis;
    }

    /**
     * Delay before the attempt following {@code attempt}.
     *
     * @param attempt    1-based number of the attempt that just failed
     * @param retryAfter provider hint, may be null
     * @return milliseconds to sleep, never negative
     */
    public long delayMillis(int attempt, Duration retryAfter) {
        if (retryAfter != null) {
            return Math.min(Math.max(0L, retryAfter.toMillis()), maxDelayMillis);
        }
        int  exponent = Math.max(0, attempt - 1);
        long ceiling  = baseDelayMillis;
        for (int i = 0; i < exponent && ceiling < maxDelayMillis; i++) {
            ceiling = Math.min(ceiling * 2, maxDelayMillis);
        }
        if (ceiling <= 0) {
            return 0L;
        }
        long half = ceiling / 2;
        return half + (long) (random.nextDouble() * (ceiling - half));
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public long getBaseDelayMillis() {
        return baseDelayMillis;
    }

    public long getMaxDelayMillis() {
        return maxDelayMillis;
    }

    private static int intProperty(String key, int fallback, int min, int max) {
        long value = longProperty(key, fallback, min, max);
        return (int) value;
    }

    private static long longProperty(String key, long fallback, long min, long max) {
        String configured = System.getProperty(key);
        if (configured == null) {
            return fallback;
        }
        try {
            long value = Long.parseLong(configured.trim());
            if (value >= min && value <= max) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // fall through to the default
        }
        return fallback;
    }
}
