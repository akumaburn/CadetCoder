package com.eonmux.cadetcoder.harness.env;

import java.util.List;
import java.util.Map;

/**
 * The world, reduced to the six questions the harness is allowed to ask it.
 *
 * <h2>What an environment must not do</h2>
 *
 * <p>{@link #describe()} returns the INTERFACE -- what an action looks like, what an observation
 * contains, what the goal is. It must never return the rules, because the rules are exactly what
 * the agent is supposed to discover and write down as a falsifiable model. An environment that
 * explains its mechanics in {@code describe()} turns every certificate into a tautology.</p>
 *
 * <h2>Why observations are plain values</h2>
 *
 * <p>Observations and actions are written to the ledger, hashed into its chain, compared against
 * predictions and handed to a model written in the model language. All four need one representation,
 * which is the one {@link com.eonmux.cadetcoder.harness.Json} defines.</p>
 */
public interface Environment {

    /** The action that means "start a new episode"; the gate and the ledger both recognise it. */
    Map<String, Object> RESET_ACTION = Map.of("type", "reset");

    /**
     * Starts a new episode.
     *
     * @return the first observation of it
     */
    Object reset();

    /**
     * The current observation.
     *
     * @return what can be seen now, without changing anything
     */
    Object observe();

    /**
     * Takes one action for real. The only method with an effect on the world.
     *
     * @param action the action to take
     * @return what happened
     */
    StepOutcome act(Object action);

    /**
     * The actions available from an observation.
     *
     * @param observation the observation to enumerate from
     * @return the actions, or {@code null} when the space is open-ended -- a shell, say -- in which
     *         case a model must supply its own {@code actions(state)} before anything can be planned
     */
    List<Object> actionSpace(Object observation);

    /**
     * How much of the world an action spends.
     *
     * @param action the action to classify
     * @return its reversibility; when in doubt the safer answer is the correct one
     */
    Reversibility reversibility(Object action);

    /**
     * The interface, for the prompt.
     *
     * @return what actions and observations look like and what counts as success -- never the rules
     */
    String describe();
}
