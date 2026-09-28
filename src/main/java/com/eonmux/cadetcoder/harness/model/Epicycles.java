package com.eonmux.cadetcoder.harness.model;

import java.util.List;

/**
 * The one judgement the history of a model can make that a single version cannot.
 *
 * <p>A model gets bigger for two reasons. Either it learned something -- and then the ledger starts
 * exercising rules it did not exercise before -- or it is being patched to make the last
 * contradiction go away, and then it grows while the set of rules reality has actually tested stays
 * exactly where it was. The second is the classical epicycle, and the cure is never another case in
 * {@code step}: it is a different way of representing the world.</p>
 *
 * <p>This warns and decides nothing. A complexity count is far too crude a description-length proxy
 * to refuse a model over, and a model author who has a reason is entitled to it.</p>
 */
final class Epicycles {

    /** How much bigger a model has to get before growth is worth remarking on at all. */
    private static final double GROWTH = 1.3;

    private Epicycles() {
    }

    /**
     * Reads a run of versions, oldest first.
     *
     * @param versions the versions to compare; fewer than two says nothing
     * @param covered  how many arms the ledger had exercised for each, in the same order
     * @return the warning, or {@code null} when there is nothing to warn about
     */
    static String warning(List<ModelRecord> versions, List<Integer> covered) {
        if (versions.size() < 2 || covered.contains(null)) {
            return null;
        }
        int first = versions.get(0).complexity().nodes();
        int last  = versions.get(versions.size() - 1).complexity().nodes();
        int knew  = covered.get(0);
        int knows = covered.get(covered.size() - 1);
        if (last <= first * GROWTH || knows > knew) {
            return null;
        }
        return "model complexity grew " + first + " -> " + last + " nodes over the last "
               + versions.size() + " versions while the rules the ledger has exercised stayed "
               + knew + " -> " + knows + ": that is what epicycles look like. Change how the model "
               + "represents the world rather than adding cases to step.";
    }
}
