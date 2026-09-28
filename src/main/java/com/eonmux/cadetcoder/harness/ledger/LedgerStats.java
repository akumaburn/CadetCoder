package com.eonmux.cadetcoder.harness.ledger;

/**
 * The state of the evidence, in the few numbers a decision actually turns on.
 *
 * @param transitions      every line in the ledger
 * @param realActions      those that spent something: resets are free and are not counted
 * @param resets           episodes started
 * @param episodes         distinct episodes seen
 * @param liveChecked      steps taken with a model's prediction attached
 * @param liveMispredicted those of them reality contradicted
 * @param goals            steps the environment flagged as reaching the goal
 * @param head             the hash every certificate is measured against
 */
public record LedgerStats(int transitions, int realActions, int resets, int episodes,
                          int liveChecked, int liveMispredicted, int goals, String head) {

    /** A one-line summary for the status block the model reads every turn. */
    public String render() {
        return "ledger: " + transitions + " transitions (" + realActions + " real, "
               + resets + " resets, " + episodes + " episodes), "
               + liveChecked + " predicted / " + liveMispredicted + " wrong, "
               + goals + " goals, head " + head;
    }
}
