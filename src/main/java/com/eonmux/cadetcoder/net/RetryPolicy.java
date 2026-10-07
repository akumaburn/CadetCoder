package com.eonmux.cadetcoder.net;

import java.time.Duration;
import java.util.Random;

/**
 * Retry rules for provider calls: which failures are worth repeating, and how long to wait in
 * between.
 *
 * <p>Only failures that can plausibly change outcome are retried -- HTTP 408, 429 and 5xx, plus
 * transport failures. 400/401/403 are never retried: a malformed request stays malformed and a
 * rejected key stays rejected, so repeating them only multiplies the latency before the user sees
 * the real error.</p>
 *
 * <p>A failure that is worth retrying is retried until it clears. There is no limit on attempts
 * by default: a provider that is down or rate limiting is very often back later, and ending the
 * run throws away everything the run had built. The person at the terminal stops a retry with F2
 * or Ctrl-C, and the wait between attempts ends on that stop.</p>
 *
 * <p>Backoff is exponential with jitter, so concurrent CadetCoder runs hitting the same
 * rate-limited provider do not resynchronise. The delay doubles from {@link #getBaseDelayMillis()}
 * to {@link #getMaxDelayMillis()}, thirty minutes by default, and then holds there. A
 * provider-supplied {@code Retry-After} wins over the computed delay, up to the same ceiling.</p>
 *
 * <p>The values are tunable through system properties, matching the convention used elsewhere in
 * the codebase (e.g. {@code cadet.iterative.maxIterations}); out-of-range or unparseable values
 * fall back to the defaults. {@link #MAX_ATTEMPTS_PROPERTY} sets a limit on attempts for a script
 * that would rather fail than wait.</p>
 */
public final class RetryPolicy {

    /** The attempt count that means no limit. */
    public static final int UNLIMITED = 0;

    public static final int  DEFAULT_MAX_ATTEMPTS  = UNLIMITED;
    public static final long DEFAULT_BASE_DELAY_MS = 500L;
    public static final long DEFAULT_MAX_DELAY_MS  = 30 * 60_000L;

    public static final String MAX_ATTEMPTS_PROPERTY  = "cadet.llm.maxAttempts";
    public static final String BASE_DELAY_MS_PROPERTY = "cadet.llm.retry.baseDelayMs";
    public static final String MAX_DELAY_MS_PROPERTY  = "cadet.llm.retry.maxDelayMs";

    /** The longest single wait any setting may ask for. */
    private static final long MAX_ALLOWED_DELAY_MS = DEFAULT_MAX_DELAY_MS;

    private final int    maxAttempts;
    private final long   baseDelayMillis;
    private final long   maxDelayMillis;
    private final Random random;

    public RetryPolicy(int maxAttempts, long baseDelayMillis, long maxDelayMillis) {
        this(maxAttempts, baseDelayMillis, maxDelayMillis, new Random());
    }

    /**
     * @param maxAttempts total attempts including the first, or {@link #UNLIMITED}
     * @param random      source of jitter; injectable so tests can make the backoff deterministic
     */
    public RetryPolicy(int maxAttempts, long baseDelayMillis, long maxDelayMillis, Random random) {
        if (maxAttempts < 0) {
            throw new IllegalArgumentException("maxAttempts must be >= 0, where 0 means no limit");
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
        int  attempts  = intProperty(MAX_ATTEMPTS_PROPERTY, DEFAULT_MAX_ATTEMPTS, 0,
                                     Integer.MAX_VALUE);
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
        if (failure == null || !failure.isRetryable()) {
            return false;
        }
        return isUnlimited() || attempt < maxAttempts;
    }

    /** @return whether a retryable failure is retried for as long as it lasts */
    public boolean isUnlimited() {
        return maxAttempts == UNLIMITED;
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
        // Counted as a long and stopped at the ceiling, so an attempt number in the millions costs
        // a few dozen doublings rather than overflowing.
        long exponent = Math.max(0L, (long) attempt - 1);
        long ceiling  = baseDelayMillis;
        for (long i = 0; i < exponent && ceiling > 0 && ceiling < maxDelayMillis; i++) {
            ceiling = Math.min(ceiling * 2, maxDelayMillis);
        }
        if (ceiling <= 0) {
            return 0L;
        }
        long half = ceiling / 2;
        return half + (long) (random.nextDouble() * (ceiling - half));
    }

    /** @return total attempts including the first, or {@link #UNLIMITED} */
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
