package com.eonmux.cadetcoder.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * One agent run, as it stands: what was asked, what has been done, and whether it is finished.
 *
 * <h2>Why the clock is supplied rather than read</h2>
 *
 * <p>A time budget is only testable if the test can move the clock. Reading
 * {@code System.currentTimeMillis()} here would make the timeout assertions depend on a real wait,
 * which is either slow or flaky and usually both.</p>
 */
final class AgentState {

    private final String             task;
    final         int                maxSteps;
    private final LongSupplier       clock;
    private final long               startTimeMillis;
    private final List<ActionResult> executedActions = new ArrayList<>();

    /**
     * Loop protection for this agent run. The agent loop previously had none at all: only
     * {@link ChatCommand} checked for repetition, so an agent that kept re-running one command
     * burned its whole step budget on it.
     */
    private final ActionLoopGuard loopGuard = new ActionLoopGuard();

    private int     currentStep = 0;
    private boolean complete    = false;

    /**
     * @param task     what the agent was asked to do
     * @param maxSteps the step budget, or {@link AgentOptions#UNLIMITED}
     * @param clock    where the current time comes from
     */
    AgentState(String task, int maxSteps, LongSupplier clock) {
        this.task            = task;
        this.maxSteps        = maxSteps;
        this.clock           = clock;
        this.startTimeMillis = clock.getAsLong();
    }

    /** Whether the agent has exceeded its wall-clock time budget of {@code timeoutSec} seconds. */
    boolean isTimedOut(int timeoutSec) {
        return timeoutSec > AgentOptions.UNLIMITED
               && (clock.getAsLong() - startTimeMillis)
                  > timeoutSec * AgentOptions.MILLIS_PER_SECOND;
    }

    void incrementStep() {
        currentStep++;
    }

    void markComplete() {
        complete = true;
    }

    void addExecutedAction(AgentAction action, int exitCode, String output) {
        executedActions.add(new ActionResult(action, exitCode, output));
    }

    /** @return this run's loop guard, never {@code null} */
    ActionLoopGuard getLoopGuard() {
        return loopGuard;
    }

    String getTask() {
        return task;
    }

    int getCurrentStep() {
        return currentStep;
    }

    boolean isComplete() {
        return complete;
    }

    List<ActionResult> getExecutedActions() {
        return executedActions;
    }

    /** One step that ran, with what it returned and what it printed. */
    static final class ActionResult {

        final AgentAction action;
        final int         exitCode;
        final String      output;

        ActionResult(AgentAction action, int exitCode, String output) {
            this.action   = action;
            this.exitCode = exitCode;
            this.output   = output;
        }
    }
}
