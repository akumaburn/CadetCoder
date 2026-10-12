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
    private final List<Message>      messages        = new ArrayList<>();

    /**
     * Loop protection for this agent run. The agent loop previously had none at all: only
     * {@link ChatCommand} checked for repetition, so an agent that kept re-running one command
     * burned its whole step budget on it.
     */
    private final ActionLoopGuard loopGuard = new ActionLoopGuard();

    private int     currentStep    = 0;
    private boolean complete       = false;
    private int     outputsLeftOut = 0;

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

    /**
     * Records a message the person sent while the run went on, after the actions that ran so far.
     *
     * @param text what they sent
     */
    void addMessage(String text) {
        messages.add(new Message(executedActions.size(), text));
    }

    /** @return the messages the person sent, oldest first */
    List<Message> getMessages() {
        return messages;
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

    /** @return how many of the oldest actions have their output left out of the prompt */
    int outputsLeftOut() {
        return outputsLeftOut;
    }

    /**
     * Leaves the output of every action before {@code index} out of the prompt.
     *
     * <p>An output left out stays out. Bringing one back would change the prompt's prefix again on
     * the next step, and a provider serves only an unchanged prefix from its cache.</p>
     *
     * @param index the first action whose output stays in the prompt
     */
    void leaveOutOutputsBefore(int index) {
        outputsLeftOut = Math.max(outputsLeftOut, Math.min(index, executedActions.size()));
    }

    /**
     * One message the person sent, and where it falls among the actions.
     *
     * @param afterActions how many actions had run when it arrived
     * @param text         what they sent
     */
    record Message(int afterActions, String text) {
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
