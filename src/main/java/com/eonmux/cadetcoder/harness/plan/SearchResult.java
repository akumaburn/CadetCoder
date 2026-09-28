package com.eonmux.cadetcoder.harness.plan;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a search found, and everything about the search that says what that is worth.
 *
 * <h2>Why there is no such thing as an empty result</h2>
 *
 * <p>A plan of no steps and no plan at all are different answers, and no plan for four different
 * reasons. The status is therefore never inferred from the plan: {@link #found()} is a question
 * about {@link SearchStatus}, and a caller that reads {@link #plan()} without it is reading a list
 * that may mean the search was stopped before it started.</p>
 *
 * @param status why the search stopped
 * @param plan   the actions that reach the goal, empty unless the search found one
 * @param effort what the search cost
 * @param error  what the model said when it broke, {@code null} unless the status is an error
 * @param audit  which steps reach rules nothing has exercised, once something has checked
 */
public record SearchResult(SearchStatus status, List<Object> plan, SearchEffort effort,
                           String error, PlanAudit audit) {

    /** Below this many expansions, the ratio of new states to expansions says nothing. */
    private static final int HINT_MIN_EXPANDED = 1_000;

    /** Above this many new states per expansion, a search is not exploring, it is drifting. */
    private static final double HINT_NEW_STATES_PER_NODE = 1.8;

    public SearchResult {
        plan = List.copyOf(plan);
    }

    static SearchResult found(List<Object> plan, SearchEffort effort) {
        return new SearchResult(SearchStatus.FOUND, plan, effort, null, PlanAudit.nothing());
    }

    static SearchResult stopped(SearchStatus status, SearchEffort effort) {
        return new SearchResult(status, List.of(), effort, null, PlanAudit.nothing());
    }

    static SearchResult broken(String error, SearchEffort effort) {
        return new SearchResult(SearchStatus.ERROR, List.of(), effort, error, PlanAudit.nothing());
    }

    /** Whether there is a plan here at all. */
    public boolean found() {
        return status == SearchStatus.FOUND;
    }

    /** The same result, carrying what an audit against a certificate found. */
    public SearchResult audited(PlanAudit found) {
        return new SearchResult(status, plan, effort, error, found);
    }

    /**
     * The diagnosis for the one search failure that always looks like a budget problem.
     *
     * <p>A search where nearly every state reached had never been seen before is not a large
     * problem; it is a {@code key} that distinguishes states reality does not, almost always because
     * a counter -- moves taken, time elapsed, score -- was left in it. No budget will ever be big
     * enough, so saying "increase the budget" would send the agent in a circle.</p>
     *
     * @return what to do about it, or {@code null} when the search was merely stopped
     */
    public String hint() {
        boolean stopped = status == SearchStatus.NODE_BUDGET || status == SearchStatus.TIME_BUDGET;
        if (!stopped || effort.expanded() < HINT_MIN_EXPANDED
            || effort.distinct() < effort.expanded() * HINT_NEW_STATES_PER_NODE) {
            return null;
        }
        return "almost every state this search reached was one it had never seen: key() probably "
               + "includes a counter -- moves, time, score -- that does not change what can happen "
               + "next. Project it out rather than raising the budget.";
    }

    /** The one line an agent is told. */
    public String summary() {
        StringBuilder out = new StringBuilder("search ").append(status.label()).append(": ")
                .append(effort.render());
        if (found()) {
            out.append(" plan=").append(plan.size());
        }
        if (!audit.clean()) {
            out.append(" untested=").append(audit.untestedSteps());
        }
        if (error != null) {
            out.append(" error=").append(error);
        }
        String hint = hint();
        if (hint != null) {
            out.append(System.lineSeparator()).append("  hint: ").append(hint);
        }
        return out.toString();
    }

    /** The result as a value the harness can keep. */
    public Map<String, Object> toValue() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("status", status.label());
        value.put("plan", plan);
        value.put("expanded", effort.expanded());
        value.put("distinct", effort.distinct());
        value.put("depth", effort.depthReached());
        value.put("elapsed_ms", effort.elapsedMillis());
        value.put("error", error);
        value.put("hint", hint());
        value.putAll(audit.toValue());
        return value;
    }

    @Override
    public String toString() {
        return summary();
    }
}
