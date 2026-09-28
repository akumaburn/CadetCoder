package com.eonmux.cadetcoder.harness.plan;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A whole plan run inside the model, with what each step predicts and what each step turns on.
 *
 * <h2>Why a plan is simulated before it is committed</h2>
 *
 * <p>The commit gate halts at the first step whose prediction is contradicted, and it can only do
 * that if it was told what to expect at every step rather than at the end. Simulating also produces
 * the per-step coverage the audit needs, and it is the last chance to find out that the model breaks
 * on step four before the first three have already happened to the world.</p>
 *
 * @param start   where the plan begins
 * @param steps   every step that ran, which is every step unless the model broke
 * @param goalAt  the index of the step after which the model first calls the state the goal
 * @param error   what the model said when it broke, {@code null} when it did not
 * @param errorAt the index of the step it broke on, {@code null} when it did not
 */
public record Simulation(Object start, List<SimulatedStep> steps, Integer goalAt,
                         String error, Integer errorAt) {

    public Simulation {
        steps = List.copyOf(steps);
    }

    /** Whether the model got through the whole plan. */
    public boolean ok() {
        return error == null;
    }

    /** Where the model says the world ends up. */
    public Object finalState() {
        return steps.isEmpty() ? start : steps.get(steps.size() - 1).state();
    }

    /** Every rule this plan exercises. */
    public Set<String> arms() {
        Set<String> exercised = new LinkedHashSet<>();
        for (SimulatedStep step : steps) {
            exercised.addAll(step.arms());
        }
        return Set.copyOf(exercised);
    }

    /** The plan as a person reads it before deciding whether to run it. */
    public String render() {
        List<String> lines = new ArrayList<>();
        for (SimulatedStep step : steps) {
            lines.add(step.render());
        }
        if (!ok()) {
            lines.add("step " + errorAt + " broke the model: " + error);
        }
        return lines.isEmpty() ? "nothing to do" : String.join(System.lineSeparator(), lines);
    }
}
