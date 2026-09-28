package com.eonmux.cadetcoder.harness.budget;

import java.util.List;

/**
 * Whether a run has stopped learning.
 *
 * <h2>Why this is asked from outside the loop</h2>
 *
 * <p>An agent inside a loop always has a reason for the next step, which is exactly why it cannot
 * tell that the last several produced nothing. The judgement is therefore made on the record rather
 * than on the reasoning: two snapshots a window apart, and the question of whether anything the
 * agent is being paid to improve has moved between them.</p>
 *
 * <h2>Why nothing here stops the run</h2>
 *
 * <p>A plateau is not an error. Certification can legitimately stand still while the agent explores
 * a part of the world it has no model for yet. What the warning buys the driver is a decision --
 * a stronger model, a shorter commit horizon, a different goal -- and taking that decision out of
 * this class is what keeps it a measurement rather than a policy.</p>
 */
final class Plateau {

    private Plateau() {
    }

    /**
     * A description of the plateau, or {@code null} if the run is still getting somewhere.
     *
     * @param history every deliberation so far, oldest first
     * @param window  how many deliberations back to compare against
     */
    static String warning(List<Progress> history, int window) {
        if (window < 1 || history.size() < window + 1) {
            return null;
        }
        Progress before = history.get(history.size() - window - 1);
        Progress now    = history.get(history.size() - 1);
        if (now.certifiedOk() > before.certifiedOk() || now.mismatches() < before.mismatches()) {
            return null;
        }
        if (now.ledgerLength() <= before.ledgerLength()) {
            return window + " deliberations with no new observations and no model improvement";
        }
        return window + " deliberations with new observations but no model improvement"
               + " (certified " + before.certifiedOk() + "->" + now.certifiedOk()
               + ", mispredictions " + before.mismatches() + "->" + now.mismatches() + ")";
    }
}
