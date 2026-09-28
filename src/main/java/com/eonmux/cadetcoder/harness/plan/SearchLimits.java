package com.eonmux.cadetcoder.harness.plan;

/**
 * What stops a search that would otherwise not stop.
 *
 * <p>Searching inside a model is the one place the harness runs an unbounded computation on a
 * program an LLM wrote. A model whose {@code key} leaks a counter has an infinite state space and no
 * way to know it, so the bounds are not a tuning knob: without them the agent hangs rather than
 * reporting that its model cannot be planned in.</p>
 *
 * @param maxNodes  how many states may be expanded
 * @param maxMillis how long the search may run
 * @param maxDepth  how many actions deep the search may look
 */
public record SearchLimits(int maxNodes, long maxMillis, int maxDepth) {

    /** What {@link #maxDepth} is when the plan may be as long as it needs to be. */
    public static final int ANY_DEPTH = Integer.MAX_VALUE;

    private static final int  STANDARD_NODES  = 200_000;
    private static final long STANDARD_MILLIS = 30_000;

    public SearchLimits {
        if (maxNodes < 0 || maxMillis < 0 || maxDepth < 0) {
            throw new IllegalArgumentException("a search limit cannot be negative");
        }
    }

    /** Generous enough for a real problem, small enough that a hopeless search comes back. */
    public static SearchLimits standard() {
        return new SearchLimits(STANDARD_NODES, STANDARD_MILLIS, ANY_DEPTH);
    }

    /** The same limits with a different ceiling on plan length. */
    public SearchLimits toDepth(int depth) {
        return new SearchLimits(maxNodes, maxMillis, depth);
    }
}
