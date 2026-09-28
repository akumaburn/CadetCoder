package com.eonmux.cadetcoder.harness.spec;

import java.util.List;
import java.util.stream.Collectors;

/**
 * What checking one prediction against the world found.
 *
 * @param kind       how the model committed to what would happen
 * @param violations everything it claimed that turned out not to be so, in the order claimed
 */
public record PredictionOutcome(PredictionKind kind, List<Violation> violations) {

    public PredictionOutcome {
        violations = List.copyOf(violations);
    }

    /** Whether the world agreed with the model. */
    public boolean held() {
        return violations.isEmpty();
    }

    /** How the outcome reads in a report. */
    public String render() {
        return held() ? "prediction held"
                      : violations.stream().map(Violation::render)
                              .collect(Collectors.joining("; "));
    }

    @Override
    public String toString() {
        return render();
    }
}
