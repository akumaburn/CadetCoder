package com.eonmux.cadetcoder.harness.plan;

import java.util.Locale;

/**
 * Why a search stopped, which is the half of a search result a bare plan never carries.
 *
 * <p>"No plan" is five different facts. {@link #EXHAUSTED} says the goal cannot be reached from
 * here and the agent should change the goal or the model. {@link #NODE_BUDGET} and
 * {@link #TIME_BUDGET} say nothing about the goal at all -- only that the search was stopped -- and
 * the right response is a bigger budget, a better key, or a heuristic. {@link #ERROR} says the model
 * broke, which is a fact about the model rather than the world. An agent handed an empty list
 * instead of one of these will either abandon a solvable problem or grind on an unsolvable one.</p>
 */
public enum SearchStatus {

    /** The goal was reached; the plan is the sequence that reaches it. */
    FOUND,

    /** Every state reachable from the start was examined and none was the goal. */
    EXHAUSTED,

    /** The search was stopped after expanding as many states as it was allowed. */
    NODE_BUDGET,

    /** The search was stopped after taking as long as it was allowed. */
    TIME_BUDGET,

    /** The model could not answer somewhere in the middle of the search. */
    ERROR;

    /** How it reads in a report and in a stored result. */
    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }
}
