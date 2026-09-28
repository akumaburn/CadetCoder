package com.eonmux.cadetcoder.harness.plan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which steps of a plan walk into rules the ledger has never put to the test.
 *
 * <h2>Why a plan needs this and a certificate does not provide it</h2>
 *
 * <p>A certificate says the model reproduced everything that really happened, and which of the
 * model's rules that replay exercised. It says nothing about the rules it did not reach -- and a
 * plan that turns on one of those is running on a belief no evidence supports. That is not a reason
 * to refuse the plan; it is a reason to stop at the step where it happens and look, which is what
 * the commit gate does with {@link #firstUntested()}.</p>
 *
 * @param untestedSteps the indices of the steps that first reach an unexercised rule, in order
 * @param untestedArms  the rules each of those steps reaches, by step index
 */
public record PlanAudit(List<Integer> untestedSteps, Map<Integer, List<String>> untestedArms) {

    /** What {@link #firstUntested()} answers when the whole plan is on tested ground. */
    public static final int NOTHING_UNTESTED = -1;

    public PlanAudit {
        untestedSteps = List.copyOf(untestedSteps);
        Map<Integer, List<String>> copy = new LinkedHashMap<>();
        untestedArms.forEach((step, arms) -> copy.put(step, List.copyOf(arms)));
        untestedArms = Collections.unmodifiableMap(copy);
    }

    /** A plan that has not been audited, and a plan with nothing to report, read the same way. */
    public static PlanAudit nothing() {
        return new PlanAudit(List.of(), Map.of());
    }

    /** Whether every rule this plan uses has already been exercised by something that happened. */
    public boolean clean() {
        return untestedSteps.isEmpty();
    }

    /** The step a cautious commit stops before, or {@link #NOTHING_UNTESTED}. */
    public int firstUntested() {
        return clean() ? NOTHING_UNTESTED : untestedSteps.get(0);
    }

    /** The report a person reads. */
    public String render() {
        if (clean()) {
            return "every rule this plan uses has already been exercised by something that happened";
        }
        List<String> lines = new ArrayList<>();
        lines.add("steps reaching rules the ledger never exercised: " + untestedSteps);
        untestedArms.forEach((step, arms) -> lines.add("  step " + step + ": "
                                                       + String.join(", ", arms)));
        return String.join(System.lineSeparator(), lines);
    }

    /** The audit as a value a result can be stored with. */
    public Map<String, Object> toValue() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("untested_steps", List.copyOf(untestedSteps));
        Map<String, Object> arms = new LinkedHashMap<>();
        untestedArms.forEach((step, hit) -> arms.put(String.valueOf(step), hit));
        value.put("untested_arms", arms);
        return value;
    }
}
