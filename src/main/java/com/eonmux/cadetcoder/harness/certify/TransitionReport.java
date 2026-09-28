package com.eonmux.cadetcoder.harness.certify;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What went wrong at one transition, in enough detail to act on.
 *
 * <p>A certificate that only said "red" would send the agent back to guessing. This says which
 * transition, which belief, and what the model was doing when it failed, because the next model is
 * written from exactly that.</p>
 *
 * @param index          which transition of the ledger this is about
 * @param ok             whether every prediction about it held
 * @param kind           why it is here
 * @param violations     what failed, one line each
 * @param groundingDrift how the rule and the representation disagreed, or {@code null} when they did not
 * @param action         what was done, so the report can be read without the ledger beside it
 */
public record TransitionReport(int index, boolean ok, ReportKind kind, List<String> violations,
                               String groundingDrift, Object action) {

    public TransitionReport {
        violations = List.copyOf(violations);
    }

    /** The report as it is written down. */
    public Map<String, Object> toValue() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("index", index);
        value.put("ok", ok);
        value.put("kind", kind.label());
        value.put("violations", violations);
        value.put("drift", groundingDrift);
        value.put("action", action);
        return value;
    }

    /** How it reads in a report. */
    public String render() {
        StringBuilder out = new StringBuilder();
        out.append('#').append(index).append(' ').append(kind.label());
        for (String violation : violations) {
            out.append(System.lineSeparator()).append("    ").append(violation);
        }
        if (groundingDrift != null) {
            out.append(System.lineSeparator()).append("    ").append(groundingDrift);
        }
        return out.toString();
    }

    @Override
    public String toString() {
        return render();
    }
}
