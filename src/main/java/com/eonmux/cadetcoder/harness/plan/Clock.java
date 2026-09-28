package com.eonmux.cadetcoder.harness.plan;

/**
 * The budget a search is spending, asked the same way by every search.
 *
 * <p>Both searches have to stop for the same two reasons and report the same four numbers. Asking
 * one object is what keeps a node cap from being checked in one search and forgotten in the
 * other -- the failure that turns a stopped search into a hung agent.</p>
 */
final class Clock {

    private static final long NANOS_PER_MILLI = 1_000_000L;

    private final SearchLimits limits;
    private final long         started = System.nanoTime();

    Clock(SearchLimits limits) {
        this.limits = limits;
    }

    /**
     * Whether the search must stop now.
     *
     * @param expanded how many states have been taken apart so far
     * @return which budget ran out, or {@code null} while there is budget left
     */
    SearchStatus stopped(int expanded) {
        if (expanded >= limits.maxNodes()) {
            return SearchStatus.NODE_BUDGET;
        }
        if (System.nanoTime() - started >= limits.maxMillis() * NANOS_PER_MILLI) {
            return SearchStatus.TIME_BUDGET;
        }
        return null;
    }

    SearchEffort effort(int expanded, int distinct, int depthReached) {
        return new SearchEffort(expanded, distinct, depthReached,
                                (System.nanoTime() - started) / NANOS_PER_MILLI);
    }
}
