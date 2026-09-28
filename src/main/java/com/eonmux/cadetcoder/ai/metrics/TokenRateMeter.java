package com.eonmux.cadetcoder.ai.metrics;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.LongSupplier;

/**
 * Throughput over a trailing time window, in tokens per second.
 *
 * <h2>What the number means</h2>
 *
 * <p>Every completed request contributes its output tokens, stamped with the moment the request
 * finished. The rate is the tokens contributed within the last {@link #DEFAULT_WINDOW_MILLIS
 * window} divided by the window length, so it answers "how fast is this session producing output
 * right now" and decays to zero when nothing is happening.</p>
 *
 * <p>Requests are not streamed, so output arrives in one lump when a request completes rather than
 * continuously. That makes the meter bursty by nature: a single 400-token reply lands as 400 tokens
 * at one instant and then decays over the following seconds. It is a session-level throughput
 * indicator, not a live decoder speed -- {@link RequestMetrics#tokensPerSecond()} is the right
 * number for "how fast did THAT request generate".</p>
 *
 * <p>Thread-safe: the agentic loop can complete a request on a worker thread while the TUI renders
 * the rate on another.</p>
 */
public final class TokenRateMeter {

    /** The trailing window the rate is computed over. */
    public static final long DEFAULT_WINDOW_MILLIS = 5_000L;

    private final long         windowMillis;
    private final LongSupplier clock;

    /** Samples inside the window, oldest first. Pruned on every read and write. */
    private final Deque<Sample> samples = new ArrayDeque<>();

    private long tokensInWindow;

    private static final class Sample {
        private final long timestampMillis;
        private final long tokens;

        private Sample(long timestampMillis, long tokens) {
            this.timestampMillis = timestampMillis;
            this.tokens          = tokens;
        }
    }

    /** Creates a meter over the default five-second window using the system clock. */
    public TokenRateMeter() {
        this(DEFAULT_WINDOW_MILLIS, System::currentTimeMillis);
    }

    /**
     * Creates a meter with an explicit window and clock.
     *
     * @param windowMillis the trailing window length; must be positive
     * @param clock        the millisecond clock, injectable so the window is testable without sleeping
     */
    public TokenRateMeter(long windowMillis, LongSupplier clock) {
        if (windowMillis <= 0) {
            throw new IllegalArgumentException("windowMillis must be positive, got " + windowMillis);
        }
        this.windowMillis = windowMillis;
        this.clock        = clock;
    }

    /**
     * Records tokens produced now.
     *
     * @param tokens how many tokens were produced; non-positive values are ignored
     */
    public synchronized void record(long tokens) {
        if (tokens <= 0) {
            return;
        }
        long now = clock.getAsLong();
        prune(now);
        samples.addLast(new Sample(now, tokens));
        tokensInWindow += tokens;
    }

    /**
     * The current rate.
     *
     * @return tokens per second over the trailing window; {@code 0.0} when the window is empty
     */
    public synchronized double tokensPerSecond() {
        prune(clock.getAsLong());
        if (tokensInWindow == 0) {
            return 0.0;
        }
        return tokensInWindow / (windowMillis / 1000.0);
    }

    /**
     * @return how many tokens fall inside the trailing window right now
     */
    public synchronized long tokensInWindow() {
        prune(clock.getAsLong());
        return tokensInWindow;
    }

    /** Forgets every sample. */
    public synchronized void reset() {
        samples.clear();
        tokensInWindow = 0;
    }

    /** @return the window length in milliseconds */
    public long windowMillis() {
        return windowMillis;
    }

    /** Drops samples that have fallen out of the trailing window. */
    private void prune(long now) {
        long cutoff = now - windowMillis;
        while (!samples.isEmpty() && samples.peekFirst().timestampMillis <= cutoff) {
            tokensInWindow -= samples.removeFirst().tokens;
        }
        if (samples.isEmpty()) {
            // Guard against drift if a caller ever supplies a non-monotonic clock.
            tokensInWindow = 0;
        }
    }
}
