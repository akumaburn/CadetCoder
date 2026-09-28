package com.eonmux.cadetcoder.harness.plan;

import com.eonmux.cadetcoder.harness.Json;
import com.eonmux.cadetcoder.harness.spec.Prediction;

import java.util.List;

/**
 * One step of a plan, run inside the model before anything is done to the world.
 *
 * @param index      where in the plan it is
 * @param action     what is done
 * @param state      where the model says that leads
 * @param prediction what the model says will be observed there
 * @param arms       the rules this step is the first in the plan to exercise
 * @param goal       whether the model calls the state it reaches the goal
 */
public record SimulatedStep(int index, Object action, Object state, Object prediction,
                            List<String> arms, boolean goal) {

    public SimulatedStep {
        arms = List.copyOf(arms);
    }

    /** How it reads in a plan a person is shown before it runs. */
    public String render() {
        return index + ": " + Json.readable(action)
               + " -> " + Prediction.describe(prediction) + (goal ? "  [goal]" : "");
    }
}
