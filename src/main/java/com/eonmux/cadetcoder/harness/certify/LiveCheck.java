package com.eonmux.cadetcoder.harness.certify;

import com.eonmux.cadetcoder.harness.spec.Prediction;

import java.util.List;

/**
 * The verdict on one step that was actually taken.
 *
 * <p>The commit gate needs the same question the replay asks, one transition at a time and while
 * there is still time to stop: did the model's prediction survive what really happened. It has to be
 * the same check, or a plan could pass live and fail certification for reasons nothing reported.</p>
 *
 * <p>The prediction is carried back rather than recomputed, because {@code predict} is the model's
 * own code and asking it twice is both a wasted call and a second chance for it to answer
 * differently. The gate writes exactly the claim that was checked into the ledger.</p>
 *
 * @param state      the state the model says the world is in now
 * @param prediction what the model claimed about it, or {@code null} when it could not say
 * @param held       whether every prediction about this step held
 * @param violations what failed, one line each
 */
public record LiveCheck(Object state, Object prediction, boolean held, List<String> violations) {

    public LiveCheck {
        violations = List.copyOf(violations);
    }

    /** The claim, as it reads in a report. */
    public String describe() {
        return Prediction.describe(prediction);
    }

    /** How it reads in a report. */
    public String render() {
        return held ? "the prediction held"
                    : "the prediction failed: " + String.join("; ", violations);
    }

    @Override
    public String toString() {
        return render();
    }
}
