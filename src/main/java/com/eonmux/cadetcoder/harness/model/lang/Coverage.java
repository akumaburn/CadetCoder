package com.eonmux.cadetcoder.harness.model.lang;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Which of a model's rules reality has actually exercised.
 *
 * <p>A certificate without this is worth much less than it looks. "The model reproduced every
 * transition in the ledger" is true of a model whose interesting half was never reached, and that
 * untested half is exactly where a plan tends to go wrong.</p>
 *
 * @param arms every rule the model states
 * @param hit  the ids of the ones a replay reached
 */
public record Coverage(List<Arm> arms, Set<String> hit) {

    public Coverage {
        arms = List.copyOf(arms);
        hit  = Set.copyOf(hit);
    }

    /** Nothing measured yet, against a model with these arms. */
    public static Coverage nothing(List<Arm> arms) {
        return new Coverage(arms, Set.of());
    }

    /** The arms reality never put to the test, in source order. */
    public List<Arm> uncovered() {
        List<Arm> missing = new ArrayList<>();
        for (Arm arm : arms) {
            if (!hit.contains(arm.id())) {
                missing.add(arm);
            }
        }
        return missing;
    }

    /** Whether every rule the model states has been exercised at least once. */
    public boolean complete() {
        return uncovered().isEmpty();
    }

    /** The share of rules exercised; a model that states none is trivially complete. */
    public double ratio() {
        return arms.isEmpty() ? 1.0 : (double) covered() / arms.size();
    }

    /** How many rules have been exercised. */
    public int covered() {
        int count = 0;
        for (Arm arm : arms) {
            if (hit.contains(arm.id())) {
                count++;
            }
        }
        return count;
    }

    /** The report a model author reads. */
    public String render() {
        StringBuilder out = new StringBuilder();
        out.append("coverage ").append(covered()).append('/').append(arms.size()).append(" arms");
        List<Arm> missing = uncovered();
        if (missing.isEmpty()) {
            return out.append("; every rule has been exercised").toString();
        }
        out.append("; never exercised:");
        for (Arm arm : missing) {
            out.append(System.lineSeparator()).append("  ").append(arm.render());
        }
        return out.toString();
    }
}
