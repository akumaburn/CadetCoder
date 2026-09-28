package com.eonmux.cadetcoder.harness.plan;

/**
 * What a search cost, which is what says whether its answer means anything.
 *
 * <p>Expanded against distinct is the diagnosis: a search where nearly every state reached was new
 * is not a hard problem, it is a model whose {@code key} does not identify states. Reporting both
 * numbers is what lets that be said rather than guessed at.</p>
 *
 * @param expanded      how many states were taken apart
 * @param distinct      how many distinct states were reached at all
 * @param depthReached  the longest sequence of actions the search looked down
 * @param elapsedMillis how long it took
 */
public record SearchEffort(int expanded, int distinct, int depthReached, long elapsedMillis) {

    /** A search that never started. */
    public static SearchEffort nothing() {
        return new SearchEffort(0, 0, 0, 0L);
    }

    /** How it reads in a report. */
    public String render() {
        return "expanded=" + expanded + " distinct=" + distinct + " depth=" + depthReached
               + " t=" + elapsedMillis + "ms";
    }
}
