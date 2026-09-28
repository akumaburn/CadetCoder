package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.agents.WorkerPool;
import com.eonmux.cadetcoder.ai.UberMode;
import com.eonmux.cadetcoder.context.ContextEngine;
import com.eonmux.cadetcoder.git.RepositoryContext;
import com.eonmux.cadetcoder.prompts.TemplatePromptBuilder;
import com.eonmux.cadetcoder.jobs.JobNotice;
import com.eonmux.cadetcoder.timers.TimerNotice;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * What the agent is told, each step.
 *
 * <h2>Why the system prompt has a fallback</h2>
 *
 * <p>The maintained template carries the capabilities, the command catalog, the step-by-step loop
 * and both reply formats. If the template engine cannot render it for any reason, an agent with no
 * system prompt at all would propose replies nothing can read; the inline prompt is the smallest
 * thing that still states the format, so a template failure costs quality rather than the run.</p>
 */
final class AgentPrompts {

    private AgentPrompts() {}

    /**
     * The agent's system prompt for one step.
     *
     * @param state          the run so far
     * @param timeoutSeconds this run's wall-clock budget, or {@link AgentOptions#UNLIMITED}
     * @param warn           where to report a template that could not be rendered
     * @return the prompt
     */
    static String system(AgentState state, int timeoutSeconds, Consumer<String> warn) {
        try {
            String rendered = TemplatePromptBuilder.agent()
                    .with("task_description", state != null ? state.getTask() : "(see user request)")
                    .with("step_limit", AgentCommand.describeStepBudget(
                            state != null ? state.maxSteps : AgentOptions.UNLIMITED))
                    .with("timeout_minutes", timeoutSeconds > AgentOptions.UNLIMITED
                            ? String.valueOf(Math.max(1, timeoutSeconds / 60))
                            : "no limit")
                    .with("available_commands", catalog())
                    .build();
            if (rendered != null && !rendered.isBlank()) {
                return UberMode.applyTo(rendered);
            }
        } catch (Exception e) {
            warn.accept("Falling back to inline agent prompt: " + e.getMessage());
        }
        // The fallback is held to the same contract: a template that would not render is a reason to
        // say less about how to act, not a reason to stop finishing what was asked for.
        return UberMode.applyTo(fallbackSystem());
    }

    /**
     * The command list this agent is shown.
     *
     * <p>A worker is itself an agent, so it renders the same prompt -- and would be told it can spawn
     * workers, which it cannot. Offering a capability that is refused on use costs a turn and teaches
     * nothing, so the entry is withheld from the only agents that may not use it.</p>
     *
     * @return the catalog appropriate to this agent
     */
    static String catalog() {
        return WorkerPool.insideWorker()
                ? CommandCatalog.workerCommands()
                : CommandCatalog.coreCommands();
    }

    /** Minimal self-contained agent prompt used only if the agent template cannot be rendered. */
    private static String fallbackSystem() {
        return "You are an AI agent that completes tasks one step at a time using these commands:\n"
               + catalog() + "\n\n"
               + "Each step, emit exactly ONE action block:\n"
               + "ACTION_START\nCOMMAND: <command>\nARGS: <arguments>\nREASON: <why>\nACTION_END\n\n"
               + "You will be shown the command's output before the next step. Never repeat the same "
               + "command. When the task is done, respond with 'TASK COMPLETE: <summary>' (or "
               + "'COMMAND: complete ARGS: <summary>') instead of an action block.";
    }

    /**
     * The agent's user prompt for one step: the task, what has been run, what the project says about
     * it, and any correction the last step earned.
     *
     * @param state   the run so far
     * @param context the iterative-execution context, which carries the corrections
     * @param project where relevant snippets are searched for
     * @return the prompt
     */
    static String user(AgentState state, Map<String, Object> context, ContextEngine project) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Task: ").append(state.getTask()).append("\n\n");

        appendHistory(prompt, state);
        // Before the index snippets: where the work stands is the frame the snippets are read in,
        // and it is a handful of lines against their hundreds.
        prompt.append(RepositoryContext.forPrompt());
        appendProjectContext(prompt, state, project);
        appendCorrections(prompt, context);

        prompt.append("Based on the results above, decide the next step. Respond with exactly ONE "
                      + "ACTION_START/ACTION_END block, or 'TASK COMPLETE: <summary>' if the task is done.");

        // Last, and never anywhere else. A firing is true of this moment rather than of the run, so
        // placing it among the history would change what every earlier turn looks like each time one
        // came due. It is appended after the instruction rather than before it so the instruction
        // still reads as the thing being answered.
        prompt.append(TimerNotice.dueNow());
        // Same placement, same reason: a job that has just ended is true of this moment, not of the
        // run. See JobNotice.
        prompt.append(JobNotice.dueNow());
        // What is still going is true of this moment too, and is what keeps a run from starting the
        // same work twice or waiting on one job when it could start the next beside it.
        prompt.append(JobNotice.stillRunning());
        return prompt.toString();
    }

    /** Everything that has run so far, with its exit code and its output. */
    private static void appendHistory(StringBuilder prompt, AgentState state) {
        if (state.getExecutedActions().isEmpty()) {
            return;
        }
        prompt.append("Previous actions and their results:\n");
        for (AgentState.ActionResult entry : state.getExecutedActions()) {
            prompt.append("- ").append(entry.action.command).append(" ")
                  .append(String.join(" ", entry.action.args))
                  .append(" (exit code: ").append(entry.exitCode).append(")\n");
            String out = entry.output;
            if (out != null && !out.isBlank()) {
                prompt.append("  Output:\n");
                for (String line : truncateForPrompt(out).split("\n")) {
                    prompt.append("    ").append(line).append("\n");
                }
            }
        }
        prompt.append("\n");
    }

    /** What the project's own index has to say about the task. */
    private static void appendProjectContext(StringBuilder prompt, AgentState state,
                                             ContextEngine project) {
        try {
            List<String> snippets = project.searchRelevantSnippets(state.getTask());
            if (snippets.isEmpty()) {
                return;
            }
            prompt.append("Relevant context:\n");
            for (String snippet : snippets.subList(0, Math.min(3, snippets.size()))) {
                prompt.append(snippet).append("\n");
            }
            prompt.append("\n");
        } catch (Exception e) {
            // A run is still worth continuing without the index; the person watching is told why the
            // prompt is thinner than usual rather than left to guess.
            OutputFormatter.printWarning("Could not retrieve context: " + e.getMessage());
        }
    }

    /**
     * What the last step got wrong, said concretely.
     *
     * <p>An unparseable reply earns the exact required format, so the model self-corrects rather than
     * repeating it; a refused repetition earns the guard's own words, so the model changes approach
     * rather than proposing the same action again.</p>
     */
    private static void appendCorrections(StringBuilder prompt, Map<String, Object> context) {
        if (context == null) {
            return;
        }
        if (Boolean.TRUE.equals(context.get("agentParseFailed"))) {
            prompt.append("Your previous response could not be parsed. Respond with EXACTLY ONE "
                          + "action block in this format and nothing else:\n")
                  .append("ACTION_START\nCOMMAND: <command>\nARGS: <arguments>\nREASON: <why>\n"
                          + "ACTION_END\n\n");
        }
        if (context.get("loopGuidance") instanceof String) {
            prompt.append((String) context.get("loopGuidance")).append("\n\n");
        }
        // A completion this run claimed and had questioned. Carried like the others rather than
        // asked for at the point of the claim, because the claim ends a step and the question has to
        // be in the NEXT step's prompt to be answered.
        if (context.get("uberChallenge") instanceof String) {
            prompt.append((String) context.get("uberChallenge")).append("\n\n");
        }
        // Jobs still running when the run said it was done, asked about for the same reason.
        if (context.get(AgentCommand.JOBS_QUESTION) instanceof String question) {
            prompt.append(question).append("\n\n");
        }
    }

    /**
     * Bounds captured command output for inclusion in a prompt, sharing {@link CommandOutputBudget}
     * with the chat loop so the two cannot disagree about how much of a command's result the model
     * is allowed to see.
     */
    private static String truncateForPrompt(String output) {
        return CommandOutputBudget.forPrompt(output == null ? null : output.strip());
    }
}
