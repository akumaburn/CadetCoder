package com.eonmux.cadetcoder.harness.plan;

import com.eonmux.cadetcoder.harness.Json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The moment two theories part company, kept in full because it is the evidence.
 *
 * <h2>Why the predictions are carried rather than the verdict</h2>
 *
 * <p>An agent about to spend a real action on an experiment needs to know what it will learn from
 * the outcome, and that is exactly the list of what each candidate said would happen. A bare "these
 * disagree" tells it to act and leaves it unable to read the result: whichever observation arrives,
 * nothing has been refuted unless it was written down beforehand which model claimed it.</p>
 *
 * <h2>Why the camps are separate from the predictions</h2>
 *
 * <p>Three candidates can hold two positions. Which models fall together is what says whether one
 * action settles the whole argument or only half of it, and deriving that from the predictions at
 * every reading would mean re-deciding what counts as agreement -- the one question this search
 * exists to answer once.</p>
 *
 * @param step        how many actions into the plan the candidates part company
 * @param action      the action they part company on
 * @param predictions what each candidate said, by the digest of the model that said it
 * @param groups      the candidates' digests, partitioned by what they predicted
 */
public record Disagreement(int step, Object action, Map<String, String> predictions,
                           List<List<String>> groups) {

    public Disagreement {
        predictions = Collections.unmodifiableMap(new LinkedHashMap<>(predictions));
        List<List<String>> camps = new ArrayList<>();
        for (List<String> camp : groups) {
            camps.add(List.copyOf(camp));
        }
        groups = List.copyOf(camps);
    }

    /** How the parting reads in a report. */
    public String render() {
        StringBuilder text = new StringBuilder("the candidates part company at step ").append(step)
                .append(" on ").append(Json.readable(action));
        predictions.forEach((digest, prediction) -> text.append(System.lineSeparator())
                .append("    ").append(digest).append(": ").append(prediction));
        return text.toString();
    }

    /** The parting as a value the harness can keep. */
    public Map<String, Object> toValue() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("step", step);
        value.put("action", action);
        value.put("predictions", predictions);
        value.put("groups", groups);
        return value;
    }

    @Override
    public String toString() {
        return render();
    }
}
