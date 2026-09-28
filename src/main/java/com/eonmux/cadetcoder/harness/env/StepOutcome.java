package com.eonmux.cadetcoder.harness.env;

import com.eonmux.cadetcoder.harness.Json;

import java.util.Map;

/**
 * What an environment reports after one action really happened.
 *
 * <h2>Why flags are separate from the observation</h2>
 *
 * <p>The observation is what a model is asked to predict, so it must contain only what an agent
 * could see. Whether the episode ended, or the goal was reached, is a verdict the environment
 * passes down from outside that view -- a scorekeeper's fact, not a pixel. Keeping the two apart is
 * what makes the goal predicate falsifiable: the model claims {@code is_goal(state)} from the
 * observation alone, and certification checks that claim against {@code flags.goal}, which the model
 * never gets to see.</p>
 *
 * @param observation what can be seen after the action, in the same shape as every other observation
 * @param flags       verdicts from outside the observation, conventionally {@code goal} and
 *                    {@code terminal}
 * @param info        diagnostics for the record; never predicted and never checked
 */
public record StepOutcome(Object observation, Map<String, Object> flags, Map<String, Object> info) {

    /**
     * The flag that says the goal was reached.
     *
     * <h2>Why the two flags are named here</h2>
     *
     * <p>The environment raises these, the ledger stores them, certification checks a model's own
     * {@code flags} against them and the driver reads them to decide whether the run is over. Four
     * places have to agree on two words; spelled out at each, one can be renamed and the rest left
     * behind, and the failure is a run that never notices it has finished.</p>
     */
    public static final String GOAL_FLAG = "goal";

    /** The flag that says the episode cannot continue, whether or not it succeeded. */
    public static final String TERMINAL_FLAG = "terminal";

    public StepOutcome {
        flags = Json.frozenMap(flags);
        info  = Json.frozenMap(info);
    }

    /** An outcome carrying nothing but the observation. */
    public static StepOutcome of(Object observation) {
        return new StepOutcome(observation, Map.of(), Map.of());
    }

    /** An outcome that reached the goal. */
    public static StepOutcome goal(Object observation) {
        return new StepOutcome(observation, Map.of(GOAL_FLAG, true), Map.of());
    }

    /** Whether the environment says this observation satisfies the goal. */
    public boolean isGoal() {
        return Json.truthy(flags.get(GOAL_FLAG));
    }

    /** Whether the environment says the episode cannot continue. */
    public boolean isTerminal() {
        return Json.truthy(flags.get(TERMINAL_FLAG));
    }
}
