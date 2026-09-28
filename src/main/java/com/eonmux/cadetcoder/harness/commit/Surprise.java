package com.eonmux.cadetcoder.harness.commit;

import com.eonmux.cadetcoder.harness.Json;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The step where the world stopped agreeing with the model.
 *
 * <h2>Why this is kept rather than only counted</h2>
 *
 * <p>A misprediction is the most valuable thing that happens in a run: it is the only moment the
 * agent learns something it did not already believe. A counter says the model is wrong somewhere; a
 * surprise says which action, against which claim, and what the world did instead -- which is the
 * whole of what the next model has to explain, and the reason it can be pointed at the ledger entry
 * that proves it.</p>
 *
 * @param step        where in the committed plan it happened
 * @param ledgerIndex the transition that recorded it, so the claim can be read back
 * @param action      what was done
 * @param expected    the claim, as the model made it
 * @param violations  what failed, one line each
 */
public record Surprise(int step, int ledgerIndex, Object action, String expected,
                       List<String> violations) {

    public Surprise {
        violations = List.copyOf(violations);
    }

    /** How it reads in a report, over as many lines as it takes. */
    public String render() {
        StringBuilder text = new StringBuilder("SURPRISE at step ").append(step)
                                                                   .append(" (ledger #")
                                                                   .append(ledgerIndex)
                                                                   .append(") action=")
                                                                   .append(Json.readable(action));
        text.append(System.lineSeparator()).append("  expected ").append(expected);
        for (String violation : violations) {
            text.append(System.lineSeparator()).append("  ").append(violation);
        }
        return text.toString();
    }

    /** The surprise as it is stored and read back. */
    public Map<String, Object> toValue() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("step", step);
        value.put("ledger_index", ledgerIndex);
        value.put("action", action);
        value.put("expected", expected);
        value.put("violations", List.copyOf(violations));
        return value;
    }

    @Override
    public String toString() {
        return render();
    }
}
