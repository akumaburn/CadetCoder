package com.eonmux.cadetcoder.harness.budget;

import java.util.ArrayList;
import java.util.List;

/**
 * What a run has spent, and what it is still allowed to spend.
 *
 * <h2>Why this one thing is allowed to change</h2>
 *
 * <p>Everything else the harness records is a value: an observation, a transition, a certificate.
 * Spending is not, because it is a fact about the run rather than about the world, and there is
 * exactly one run. Making it a value would mean threading a new budget back out of every call that
 * could cost anything -- through the commit gate, through the planner, through each tool -- and the
 * first place that forgot to would silently spend for free. It is a service, like the ledger, and
 * it hands out immutable {@link Spend} and {@link Progress} values so that nothing which reads it
 * can change it.</p>
 *
 * <h2>Why a charge that breaks the budget still counts</h2>
 *
 * <p>The action has already happened. A tally that rolled the last one back so as to stay under its
 * limit would be a record of a world that does not exist, and the next certification would be
 * checked against it. The allowance is a licence to keep going, not a constraint on arithmetic:
 * spending is recorded first and the exception raised afterwards.</p>
 */
public final class Budget {

    /** How many deliberations back {@link #plateau()} looks by default. */
    public static final int PLATEAU_WINDOW = 4;

    private static final long NANOS_PER_MILLI = 1_000_000L;

    private final BudgetLimits   limits;
    private final long           startedNanos;
    private final List<Progress> history = new ArrayList<>();

    private int  actions;
    private int  resets;
    private long tokensIn;
    private long tokensOut;
    private int  toolCalls;
    private int  deliberations;
    private int  surprises;

    public Budget(BudgetLimits limits) {
        this.limits       = limits;
        this.startedNanos = System.nanoTime();
    }

    public BudgetLimits limits() {
        return limits;
    }

    /**
     * Charges actions that changed the world.
     *
     * @param count how many
     * @throws BudgetExceededException if that was more than the run is allowed
     */
    public void chargeActions(int count) {
        actions += count;
        if (BudgetLimits.limited(limits.maxActions()) && actions > limits.maxActions()) {
            throw new BudgetExceededException(actionsOverspent());
        }
    }

    /**
     * Why the run may not change the world again, or {@code null} while it still may.
     *
     * <p>Asked before an action rather than found out by charging for one. A charge is a record of
     * something that happened, so a caller that charged first and stopped on the exception would
     * have counted an action the world never took: the tally would say six where the ledger says
     * five, and everything that reads the two together would be reading about a run that did not
     * happen.</p>
     *
     * @return the reason, worded as the charge would have worded it, or {@code null}
     */
    public String actionsSpent() {
        return BudgetLimits.limited(limits.maxActions()) && actions >= limits.maxActions()
               ? actionsOverspent() : null;
    }

    private String actionsOverspent() {
        return "the action allowance is spent (" + actions + "/" + limits.maxActions() + ")";
    }

    /**
     * Charges tokens in both directions against the one allowance they share.
     *
     * @param in  tokens sent
     * @param out tokens received
     * @throws BudgetExceededException if that was more than the run is allowed
     */
    public void chargeTokens(long in, long out) {
        tokensIn  += in;
        tokensOut += out;
        if (BudgetLimits.limited(limits.maxTokens()) && tokensIn + tokensOut > limits.maxTokens()) {
            throw new BudgetExceededException("the token allowance is spent ("
                                              + (tokensIn + tokensOut) + "/" + limits.maxTokens()
                                              + ")");
        }
    }

    /**
     * Asks whether the run still has time.
     *
     * <p>Time is the one allowance nothing spends on purpose, so it is asked about at the points a
     * run can safely stop rather than charged at the points it passes.</p>
     *
     * @throws BudgetExceededException if the run has been going longer than it was allowed
     */
    public void checkTime() {
        long elapsed = elapsedMillis();
        if (BudgetLimits.limited(limits.maxMillis()) && elapsed >= limits.maxMillis()) {
            throw new BudgetExceededException("the time allowance is spent (" + elapsed + "ms/"
                                              + limits.maxMillis() + "ms)");
        }
    }

    /**
     * Counts one deliberation, having first established that the run is allowed another.
     *
     * @throws BudgetExceededException if it is not
     */
    public void deliberate() {
        if (BudgetLimits.limited(limits.maxDeliberations())
            && deliberations >= limits.maxDeliberations()) {
            throw new BudgetExceededException("the deliberation allowance is spent ("
                                              + deliberations + "/" + limits.maxDeliberations()
                                              + ")");
        }
        deliberations++;
    }

    /** Counts an episode started over, which costs an episode rather than an action. */
    public void countReset() {
        resets++;
    }

    /** Counts a call that read the world without changing it. */
    public void countToolCall() {
        toolCalls++;
    }

    /** Counts a time the world contradicted a prediction. */
    public void countSurprise() {
        surprises++;
    }

    /** Records where the run stood at the end of a deliberation. */
    public void record(Progress progress) {
        history.add(progress);
    }

    /** Every deliberation so far, oldest first. */
    public List<Progress> history() {
        return List.copyOf(history);
    }

    /** What has been spent, as at now. */
    public Spend spend() {
        return new Spend(actions, resets, tokensIn, tokensOut, toolCalls, deliberations, surprises,
                         elapsedMillis());
    }

    /** A description of the plateau over the default window, or {@code null} if there is none. */
    public String plateau() {
        return plateau(PLATEAU_WINDOW);
    }

    /**
     * A description of the plateau over a given window, or {@code null} if there is none.
     *
     * @param window how many deliberations back to compare against
     */
    public String plateau(int window) {
        return Plateau.warning(history, window);
    }

    /** The line the agent is shown, so that it knows what it is spending before it spends more. */
    public String render() {
        StringBuilder out = new StringBuilder();
        out.append("actions ").append(part(actions, limits.maxActions()));
        out.append(", tokens ").append(part(tokensIn + tokensOut, limits.maxTokens()));
        out.append(", deliberations ").append(part(deliberations, limits.maxDeliberations()));
        out.append(", elapsed ").append(part(elapsedMillis(), limits.maxMillis())).append("ms");
        String warning = plateau();
        if (warning != null) {
            out.append("\n  plateau: ").append(warning);
        }
        return out.toString();
    }

    @Override
    public String toString() {
        return render();
    }

    private long elapsedMillis() {
        return (System.nanoTime() - startedNanos) / NANOS_PER_MILLI;
    }

    private static String part(long used, long allowance) {
        return BudgetLimits.limited(allowance) ? used + "/" + allowance : Long.toString(used);
    }
}
