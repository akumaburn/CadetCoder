package com.eonmux.cadetcoder.harness.budget;

/**
 * What a run is allowed to spend before it has to stop.
 *
 * <h2>Why four allowances and not one</h2>
 *
 * <p>They fail differently and a run can be stopped by any of them for reasons the others cannot
 * see. Actions are the only allowance denominated in changes to the world, so it is the one that
 * protects the world rather than the wallet; tokens and time protect the wallet; deliberations
 * bound the loop itself, which is what stops an agent that has found a cheap way to think in
 * circles from doing so indefinitely inside the other three.</p>
 *
 * <h2>Why unlimited is a value and not an absent one</h2>
 *
 * <p>{@link #UNLIMITED} is a real number a caller can pass, so a partly-limited run is written the
 * same way as a fully-limited one and there is no nullable field to forget to check. It is negative
 * rather than {@code Integer.MAX_VALUE} because a very large finite limit is a different statement
 * from no limit at all, and only one of the two should be silently reachable.</p>
 *
 * @param maxActions       how many actions may change the world
 * @param maxTokens        how many tokens may be spent in both directions together
 * @param maxMillis        how long the run may take from the moment the budget was made
 * @param maxDeliberations how many times the agent may stop and think
 */
public record BudgetLimits(int maxActions, long maxTokens, long maxMillis, int maxDeliberations) {

    /** The value that means an allowance was never set. */
    public static final int UNLIMITED = -1;

    public BudgetLimits {
        check(maxActions, "action");
        check(maxTokens, "token");
        check(maxMillis, "time");
        check(maxDeliberations, "deliberation");
    }

    /** A budget that stops nothing, for a run whose bounds come from somewhere else. */
    public static BudgetLimits unlimited() {
        return new BudgetLimits(UNLIMITED, UNLIMITED, UNLIMITED, UNLIMITED);
    }

    /** Whether an allowance was set at all. */
    public static boolean limited(long allowance) {
        return allowance != UNLIMITED;
    }

    private static void check(long allowance, String what) {
        if (allowance < UNLIMITED) {
            throw new IllegalArgumentException("a " + what + " allowance cannot be negative; pass "
                                               + "BudgetLimits.UNLIMITED to set none");
        }
    }
}
