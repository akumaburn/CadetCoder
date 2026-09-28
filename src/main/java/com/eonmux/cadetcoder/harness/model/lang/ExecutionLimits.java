package com.eonmux.cadetcoder.harness.model.lang;

/**
 * What stops a model that does not stop by itself.
 *
 * <p>The reference harness bounds a model by running it in a forked process with a wall-clock alarm.
 * That is not available here and would not be wanted: a limit counted in the evaluation loop is
 * deterministic, so a model that is refused for running too long is refused for the same reason on
 * every machine, and a replay of a thousand transitions gives the same verdict every time.</p>
 *
 * @param maxSteps        how many statements and expressions one call may evaluate
 * @param maxMilliseconds how long one call may take, as a backstop for very cheap steps
 */
public record ExecutionLimits(long maxSteps, long maxMilliseconds) {

    /** How deep calls may nest before recursion is treated as runaway. */
    public static final int MAX_CALL_DEPTH = 200;

    public ExecutionLimits {
        if (maxSteps <= 0 || maxMilliseconds <= 0) {
            throw new IllegalArgumentException("execution limits must be positive");
        }
    }

    /**
     * What the harness uses: generous enough for a search over a few hundred thousand states, small
     * enough that a runaway model is caught in well under a second.
     */
    public static ExecutionLimits standard() {
        return new ExecutionLimits(2_000_000, 2_000);
    }
}
