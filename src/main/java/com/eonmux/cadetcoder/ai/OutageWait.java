package com.eonmux.cadetcoder.ai;

import com.eonmux.cadetcoder.InterruptSignal;
import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.net.LLMException;
import com.eonmux.cadetcoder.timers.TimerInterval;

import java.time.Duration;

/**
 * How long a run that nobody is watching waits for a provider that stopped answering.
 *
 * <h2>Why this exists beside the backend's retries</h2>
 *
 * <p>The backend retries a failed call for about a minute and a half ({@code RetryPolicy}), which
 * covers a provider that is briefly overloaded. An outage lasts longer. A person at the terminal is
 * then asked whether to try again ({@link ManualRetry}), but a loop is started so that it runs
 * without anyone, and one that met a 524 overnight waited on that question for a day. This waits
 * instead: longer and longer, up to a budget, and then the request fails as it did before.</p>
 *
 * <h2>What is waited out</h2>
 *
 * <p>Only a 5xx ({@link LLMException.Kind#SERVER_ERROR}) or a failure to connect
 * ({@link LLMException.Kind#TRANSPORT}). A rejected key, a malformed request and a rate limit are
 * about the account or the request, and waiting does not change them: a provider that cut an
 * account off answers 401 or 429 for as long as anyone asks, and a loop that went on against one
 * would spend its budget and then run its remaining passes against it too.</p>
 *
 * <p>The limits are system properties, as {@code RetryPolicy}'s are; a value out of range or
 * unreadable falls back to the default.</p>
 *
 * @param firstWaitMillis   the first wait, and the smallest
 * @param longestWaitMillis the longest single wait
 * @param budgetMillis      how long after its first failure a request stops waiting and fails
 * @param pause             how a wait is spent; a test passes one that records instead of sleeping
 */
public record OutageWait(long firstWaitMillis, long longestWaitMillis, long budgetMillis,
                         Pause pause) {

    public static final long DEFAULT_FIRST_WAIT_MS   = 60_000L;
    public static final long DEFAULT_LONGEST_WAIT_MS = 15 * 60_000L;
    public static final long DEFAULT_BUDGET_MS       = 2 * 60 * 60_000L;

    public static final String FIRST_WAIT_MS_PROPERTY   = "cadet.loop.outage.firstWaitMs";
    public static final String LONGEST_WAIT_MS_PROPERTY = "cadet.loop.outage.longestWaitMs";
    public static final String BUDGET_MS_PROPERTY       = "cadet.loop.outage.budgetMs";

    /** What {@link #waitOut} answers when the request should fail now. */
    public static final long GIVE_UP = -1L;

    /** The most any limit may be set to: a day. */
    private static final long MOST_MS = 24 * 60 * 60_000L;

    /** How often a wait looks for a stop, so Ctrl-C ends it at once and not at its end. */
    private static final long SLICE_MS = 1_000L;

    /** Spends part of a wait. */
    @FunctionalInterface
    public interface Pause {
        void pause(long millis) throws InterruptedException;
    }

    /** @return the limits the system properties name, spent by sleeping */
    public static OutageWait fromSystemProperties() {
        return new OutageWait(property(FIRST_WAIT_MS_PROPERTY, DEFAULT_FIRST_WAIT_MS, 1),
                              property(LONGEST_WAIT_MS_PROPERTY, DEFAULT_LONGEST_WAIT_MS, 1),
                              property(BUDGET_MS_PROPERTY, DEFAULT_BUDGET_MS, 0),
                              Thread::sleep);
    }

    /**
     * Waits before the failed request is made again, if waiting can help.
     *
     * <p>Each wait is as long as the outage has lasted so far, between the first wait and the
     * longest, so the waits double and then hold: with the defaults about 1, 1, 2, 4, 8, 15, 15 ...
     * minutes. The caller times the outage from its first failure, so the attempts in between count
     * against the budget as well as the waits.</p>
     *
     * @param failure     what the last attempt ended with
     * @param outageSoFar how long ago this request first failed, in milliseconds
     * @return how long the outage will have lasted when the wait ends, or {@link #GIVE_UP}
     * @throws LLMException of kind {@link LLMException.Kind#STOPPED} when a stop is asked for
     *                      during the wait
     */
    public long waitOut(LLMException failure, long outageSoFar) {
        if (failure == null || !isAnOutage(failure.getKind())) {
            return GIVE_UP;
        }
        long left = budgetMillis - outageSoFar;
        if (left <= 0) {
            return GIVE_UP;
        }
        long wait = Math.min(Math.min(longestWaitMillis, Math.max(firstWaitMillis, outageSoFar)),
                             left);
        OutputFormatter.printWarning(
                "The provider is not answering: " + failure.getMessage() + " Asking again in "
                + spoken(wait) + "; a loop waits up to " + spoken(budgetMillis)
                + " for it. Ctrl-C stops the loop.");
        spend(wait, failure);
        return outageSoFar + wait;
    }

    private static boolean isAnOutage(LLMException.Kind kind) {
        return kind == LLMException.Kind.SERVER_ERROR || kind == LLMException.Kind.TRANSPORT;
    }

    /** Waits in slices, and ends the wait as a stop the moment one is asked for. */
    private void spend(long wait, LLMException failure) {
        long remaining = wait;
        while (remaining > 0) {
            if (InterruptSignal.isRequested() || Thread.currentThread().isInterrupted()) {
                throw stopped(failure, null);
            }
            long slice = Math.min(SLICE_MS, remaining);
            try {
                pause.pause(slice);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw stopped(failure, interrupted);
            }
            remaining -= slice;
        }
    }

    private static LLMException stopped(LLMException failure, InterruptedException cause) {
        return LLMException.stopped(failure.getProvider(), failure.getModel(),
                                    failure.getEndpoint(), cause != null ? cause : failure);
    }

    private static String spoken(long millis) {
        return millis < SLICE_MS ? millis + "ms" : TimerInterval.render(Duration.ofMillis(millis));
    }

    private static long property(String key, long fallback, long least) {
        String configured = System.getProperty(key);
        if (configured == null) {
            return fallback;
        }
        try {
            long value = Long.parseLong(configured.trim());
            return value >= least && value <= MOST_MS ? value : fallback;
        } catch (NumberFormatException unreadable) {
            return fallback;
        }
    }
}
