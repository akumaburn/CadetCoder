package com.eonmux.cadetcoder.harness.commit;

/**
 * How much the gate lets through, and on what evidence.
 *
 * <h2>Why the policy is not the agent's to set</h2>
 *
 * <p>Every setting here is a bound on what the agent may do to the world, so it belongs to whoever
 * started the run. The agent argues its case in the only currency the gate accepts -- a certified
 * model and a plan that simulates -- and the policy decides what that case is worth. An agent that
 * could relax its own policy would have no gate at all.</p>
 *
 * @param maxBlindActions     how far a commit with no model at all may go
 * @param maxActionsPerCommit how long any single plan may be
 * @param untested            where to stop when a plan turns on a rule nothing has exercised
 * @param requireGreen        whether a contradicted model may still be committed against
 * @param irreversible        what an irreversible action needs
 * @param costlyRequiresModel whether spending something real needs a model behind it
 * @param approval            who to ask, when the irreversible rule is to ask
 */
public record CommitPolicy(int maxBlindActions, int maxActionsPerCommit, UntestedRule untested,
                           boolean requireGreen, IrreversibleRule irreversible,
                           boolean costlyRequiresModel, Approval approval) {

    /** Far enough to see what an action does, not far enough to build on what it did. */
    private static final int STANDARD_BLIND_ACTIONS = 3;

    /** Long enough for any plan worth calling one, short enough to bound a runaway. */
    private static final int STANDARD_ACTIONS_PER_COMMIT = 200;

    public CommitPolicy {
        if (maxBlindActions < 0) {
            throw new IllegalArgumentException("a blind probe cannot have a negative length");
        }
        if (maxActionsPerCommit < 1) {
            throw new IllegalArgumentException("a commit has to be allowed at least one action");
        }
        if (untested == null || irreversible == null) {
            throw new IllegalArgumentException("a policy has to say what to do in every case");
        }
    }

    /** What a run gets when nobody has said otherwise. */
    public static CommitPolicy standard() {
        return new CommitPolicy(STANDARD_BLIND_ACTIONS, STANDARD_ACTIONS_PER_COMMIT,
                                UntestedRule.AFTER, true, IrreversibleRule.CERTIFIED, false, null);
    }

    public CommitPolicy withMaxActionsPerCommit(int actions) {
        return new CommitPolicy(maxBlindActions, actions, untested, requireGreen, irreversible,
                                costlyRequiresModel, approval);
    }

    public CommitPolicy withUntested(UntestedRule rule) {
        return new CommitPolicy(maxBlindActions, maxActionsPerCommit, rule, requireGreen,
                                irreversible, costlyRequiresModel, approval);
    }

    public CommitPolicy withIrreversible(IrreversibleRule rule) {
        return new CommitPolicy(maxBlindActions, maxActionsPerCommit, untested, requireGreen, rule,
                                costlyRequiresModel, approval);
    }

    public CommitPolicy withApproval(Approval hook) {
        return new CommitPolicy(maxBlindActions, maxActionsPerCommit, untested, requireGreen,
                                irreversible, costlyRequiresModel, hook);
    }

    /** Lets a contradicted model be committed against, for a run that is deliberately exploring. */
    public CommitPolicy allowingRed() {
        return new CommitPolicy(maxBlindActions, maxActionsPerCommit, untested, false, irreversible,
                                costlyRequiresModel, approval);
    }

    /** Makes anything that spends something real need a model behind it. */
    public CommitPolicy requiringModelForCostly() {
        return new CommitPolicy(maxBlindActions, maxActionsPerCommit, untested, requireGreen,
                                irreversible, true, approval);
    }

    /** The policy as the agent is told it, so it knows what it has to produce to act. */
    public String render() {
        return "commit policy: at most " + maxActionsPerCommit + " actions per commit, "
               + maxBlindActions + " without a model; a " + (requireGreen ? "green " : "")
               + "certificate covering the current ledger head is required; an untested rule stops "
               + "the plan " + (untested == UntestedRule.AFTER ? "after" : "before")
               + " that step; irreversible actions: " + irreversible.name().toLowerCase()
               + (costlyRequiresModel ? "; costly actions need a model" : "");
    }
}
