package com.eonmux.cadetcoder.harness.certify;

import com.eonmux.cadetcoder.harness.Json;
import com.eonmux.cadetcoder.harness.env.StepOutcome;
import com.eonmux.cadetcoder.harness.model.ModelException;
import com.eonmux.cadetcoder.harness.model.WorldModel;
import com.eonmux.cadetcoder.harness.spec.Violation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The verdicts the environment reported, checked against the ones the model claims.
 *
 * <h2>Why the goal predicate is never optional</h2>
 *
 * <p>{@code is_goal} decides when planning stops and whether a run succeeded, and a model whose
 * observations all match can still be completely wrong about it. Since every environment reports
 * whether the goal was reached, the predicate can always be contradicted, so it always is -- a
 * belief that is never checked is not a belief the agent is entitled to act on.</p>
 *
 * <h2>Why only the flags a model names</h2>
 *
 * <p>Environments report signals a model has no business predicting. Requiring an answer for all of
 * them would push model authors into writing {@code false} for everything they do not model, which
 * is a false claim that would then have to be true. Naming a flag is how a model volunteers to be
 * wrong about it.</p>
 */
final class Flags {

    private Flags() {
    }

    /**
     * Checks a model's verdicts about a state against what the environment recorded.
     *
     * @param model    the model being certified
     * @param state    the state the model says the world is in
     * @param recorded the flags the environment reported
     * @return every disagreement, empty when there is none
     */
    static List<Violation> check(WorldModel model, Object state, Map<String, Object> recorded) {
        List<Violation> found = new ArrayList<>();
        boolean         reached;
        try {
            reached = model.isGoal(state);
        } catch (ModelException failure) {
            return List.of(new Violation("goal_predicate", failure.getMessage()));
        }
        boolean really = Json.truthy(recorded.get(StepOutcome.GOAL_FLAG));
        if (reached != really) {
            found.add(new Violation("goal_predicate", "is_goal said " + reached
                                                      + ", the environment reported " + really));
        }
        Map<String, Object> claimed;
        try {
            claimed = model.flags(state);
        } catch (ModelException failure) {
            found.add(new Violation("flags", failure.getMessage()));
            return List.copyOf(found);
        }
        claimed.forEach((name, value) -> {
            boolean predicted = Json.truthy(value);
            boolean happened  = Json.truthy(recorded.get(name));
            if (predicted != happened) {
                found.add(new Violation("flag:" + name, "predicted " + predicted
                                                        + ", the environment reported " + happened));
            }
        });
        return List.copyOf(found);
    }
}
