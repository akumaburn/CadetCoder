package com.eonmux.cadetcoder.commands;

/**
 * One step the agent proposed: a command, its arguments, and the reply it was read out of.
 *
 * <h2>Why the whole reply is kept</h2>
 *
 * <p>{@code result} is the model's entire response, not a summary of it. When the command is
 * {@code complete} it is the completion message the run reports; for every other command it is what
 * the warning quotes when a step has to be explained. A field that held only the parsed fragment
 * left both of those with nothing to show.</p>
 */
final class AgentAction {

    final String   command;
    final String[] args;
    final String   result;

    /**
     * @param command what to run
     * @param args    what to run it with
     * @param result  the model's reply this was read out of
     */
    AgentAction(String command, String[] args, String result) {
        this.command = command;
        this.args    = args;
        this.result  = result;
    }
}
