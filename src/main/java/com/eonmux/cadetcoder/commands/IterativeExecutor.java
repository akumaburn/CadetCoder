package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ExitCode;
import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.ai.AIManager;
import com.eonmux.cadetcoder.ai.PromptData;
import com.eonmux.cadetcoder.ai.SystemPromptProvider;
import com.eonmux.cadetcoder.ai.metrics.RequestMetricsRecorder;
import com.eonmux.cadetcoder.logging.DebugLogger;
import com.eonmux.cadetcoder.net.LLMException;
import com.eonmux.cadetcoder.session.ResumedContext;
import com.eonmux.cadetcoder.jobs.JobNotice;
import com.eonmux.cadetcoder.timers.TimerNotice;
import com.eonmux.cadetcoder.ui.CollapsedOutput;
import com.eonmux.cadetcoder.ui.InteractivePrompts;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Drives a command that works one step at a time, carrying the conversation between the steps.
 *
 * <h2>The two shapes a run can take</h2>
 *
 * <p>A command that needs nothing said to it, in a process with nobody to say it, is stepped for
 * its effects alone and never calls a model. Everything else is a conversation: a step reports where
 * things stand, whoever is being asked answers, and the answer becomes the next step's input.</p>
 */
public class IterativeExecutor {

    /**
     * Iterations are unbounded by default.
     *
     * <p>A fixed ceiling is the wrong stop condition for an agentic loop: it cuts off long tasks that
     * are making steady progress, while doing nothing about a run that is stuck on step three. The
     * ceiling is therefore gone, and what actually ends a stuck run is
     * {@link ActionLoopGuard} -- it refuses repetition that provably cannot produce new information,
     * and ends the run once several proposed actions in a row have made no progress.</p>
     *
     * <p>An explicit ceiling can still be imposed with {@code -Dcadet.iterative.maxIterations=<n>};
     * {@code 0} or a negative value means unlimited.</p>
     */
    private static final int UNLIMITED_ITERATIONS = Integer.MAX_VALUE;

    private final AIManager      aiManager;
    private final boolean        isInteractive;
    private final int            maxIterations;
    private final UserAsk        ask;

    /**
     * The context key for the session's conversation that the run's transcript starts with.
     *
     * <p>A run in a restored session starts with the session's conversation, which the session
     * keeps for itself. What comes after it is the run's own; see {@link #ownTranscript}.</p>
     */
    static final String SESSION_TRANSCRIPT = "sessionTranscript";

    /** The context of the last run, as it stood when the run ended. */
    private Map<String, Object> lastContext = Map.of();

    public IterativeExecutor() {
        this.aiManager      = AIManager.getInstance();
        this.isInteractive  = InteractivePrompts.isOn();
        this.maxIterations  = resolveMaxIterations();
        this.ask            = new UserAsk(this.isInteractive);
    }

    /**
     * Resolves the per-run iteration ceiling.
     *
     * <p>Unlimited unless {@code cadet.iterative.maxIterations} names a positive count. {@code 0}, a
     * negative value, an unparseable value, or an unset property all mean unlimited -- so a task runs
     * until it finishes or until the progress guard stops it, and never because of a number nobody
     * chose.</p>
     *
     * @return the ceiling, or {@link #UNLIMITED_ITERATIONS}
     */
    private static int resolveMaxIterations() {
        String configured = System.getProperty("cadet.iterative.maxIterations");
        if (configured == null || configured.isBlank()) {
            return UNLIMITED_ITERATIONS;
        }
        try {
            int parsed = Integer.parseInt(configured.trim());
            return parsed > 0 ? parsed : UNLIMITED_ITERATIONS;
        } catch (NumberFormatException e) {
            return UNLIMITED_ITERATIONS;
        }
    }

    /**
     * Executes a command iteratively if it supports it, otherwise falls back to regular execution.
     *
     * @param command The command to execute
     * @param args    The command arguments
     * @return The exit code
     */
    public int execute(IterativeCommand command, String[] args) {
        if (!command.supportsIterativeExecution(args)) {
            return command.execute(args);
        }

        try {
            Map<String, Object> context          = newContext(command, args);
            lastContext = context;
            String              initialPrompt    = command.getInitialPrompt(args);
            boolean             hasInitialPrompt = initialPrompt != null && !initialPrompt.isEmpty();

            // Nothing to open a conversation with, and nobody to open one with: step the command for
            // its effects and report what each step said.
            // Whether anybody is THERE, not whether they want to be asked things: a user who turned
            // confirmations off still expects a conversation, and reading the preference here turned
            // the chat loop into a command runner for them.
            if (!hasInitialPrompt && !InteractivePrompts.someoneIsThere()) {
                return runWithoutAModel(command, args, context);
            }

            announce(command, args);
            return runConversation(command, args, context, initialPrompt, hasInitialPrompt);

        } catch (LLMException e) {
            // The request itself failed (auth, rate limit, transport, ...), so there is no model
            // output to feed into the next step and the run must stop. Previously the backend's
            // error text came back as if it were the model's answer, the loop then "completed", and
            // the command printed "Command completed successfully" and exited 0 on a failed request.
            // A run the user stopped did not fail, and saying it did puts an error on screen for
            // something they asked for. It leaves on the interrupted code, like every other way a
            // command is taken back.
            if (e.getKind() == LLMException.Kind.STOPPED) {
                OutputFormatter.printWarning("Stopped before the model answered.");
                return ExitCode.INTERRUPTED;
            }
            reportAiFailure(e);
            // Not an ordinary failure: nothing was attempted, because the request never arrived.
            // A caller that runs this many times over has to tell the two apart. See ExitCode.
            return ExitCode.UNREACHABLE;
        } catch (Exception e) {
            // Named for what the user did, not for the class that was driving it. "Iterative
            // execution failed" put an internal concept in front of the one part of the line the
            // reader can act on -- and the branch above exists precisely to avoid that, but only
            // catches LLMException, so the absent-provider failure (an IllegalStateException whose
            // message is already the remedy) arrived here wearing it.
            OutputFormatter.printError("The command could not finish: " + e.getMessage());
            // Route the stack trace to the debug log instead of dumping it raw to stderr, which would
            // bypass output routing, corrupt the TUI's immediate-mode screen, and leak internal
            // class/line details. The printError above already communicates the failure to the user.
            DebugLogger.getInstance().error("IterativeExecutor", "Iterative execution failed", e);
            return 1;
        }
    }

    /**
     * The state a run starts from.
     *
     * <p>A resumed run reopens the conversation it was told to continue. Restored history was
     * written and persisted on every turn but never read back, so {@code --continue} used to return
     * a model that had been told nothing about what it was resuming.</p>
     */
    private static Map<String, Object> newContext(IterativeCommand command, String[] args) {
        Map<String, Object> context = new HashMap<>();
        context.put("command", command.getClass().getSimpleName());
        context.put("args", args != null ? Arrays.asList(args) : new ArrayList<>());
        context.put("startTime", System.currentTimeMillis());
        List<String> session = ResumedContext.forCurrentSession();
        context.put(SESSION_TRANSCRIPT, List.copyOf(session));
        List<String> history = new ArrayList<>(session);
        history.addAll(command.priorTranscript());
        context.put("conversationHistory", history);
        return context;
    }

    /**
     * The run's own part of its transcript: every entry but those of the session's conversation.
     *
     * <h2>Why the entries are matched in order</h2>
     *
     * <p>A long transcript is folded: a head of it is kept, the middle becomes one summary entry,
     * and a tail is kept. The session's entries can then sit on both sides of that entry, so no
     * single index marks where the run's own entries start. The session's entries are matched in
     * the order they were given, wherever the fold left them, and all else is the run's own.</p>
     *
     * @param context the context a run ended with
     * @return the run's own entries, oldest first
     */
    public static List<String> ownTranscript(Map<String, Object> context) {
        List<String> own = new ArrayList<>();
        if (!(context.get("conversationHistory") instanceof List<?> history)) {
            return own;
        }
        List<?> session = context.get(SESSION_TRANSCRIPT) instanceof List<?> given ? given : List.of();
        int     next    = 0;
        for (Object entry : history) {
            int at = session.subList(next, session.size()).indexOf(entry);
            if (at >= 0) {
                next += at + 1;
            } else {
                own.add(String.valueOf(entry));
            }
        }
        return own;
    }

    /**
     * The context the last run ended with.
     *
     * <p>Read by a command that saves where its run stopped. The executor owns the run's transcript,
     * and the command's own state is in the same map.</p>
     *
     * @return the context, empty before any run
     */
    public Map<String, Object> contextAtEnd() {
        return lastContext;
    }

    /**
     * Opens the run on screen and in the metrics.
     *
     * <p>A new run starts from an empty transcript, so its first request shares no prefix with
     * whatever ran before it. Without the reset the metrics would claim a cache hit against the
     * previous run's prompt, which the provider will not actually have.</p>
     */
    private static void announce(IterativeCommand command, String[] args) {
        String runTitle = command.getRunTitle(args);
        // A command a model dispatched was already announced by the loop that dispatched it, naming
        // the same command and the same arguments one line above. A run title here repeats the
        // whole invocation a second time, inside the captured output, out of order with the record
        // that reports on it.
        if (runTitle != null && !runTitle.isBlank() && !InteractivePrompts.isModelDrivenWork()) {
            OutputFormatter.printHeader(runTitle);
        }
        RequestMetricsRecorder.getInstance().resetConversation();
    }

    /**
     * Steps a command that has nothing to ask and nobody to ask it, reporting what each step says.
     *
     * @return the exit code
     */
    private int runWithoutAModel(IterativeCommand command, String[] args,
                                 Map<String, Object> context) {
        IterationProgressGuard guard = new IterationProgressGuard();

        for (int iteration = 0; iteration < maxIterations; iteration++) {
            IterativeCommand.StepResult result = command.executeStep(args, context, null);
            String                      output = result.getOutput();

            if (output != null && !output.isEmpty()) {
                if (result.isError()) {
                    OutputFormatter.printError(output);
                    return ExitCode.FAILED;
                }
                if (result.isInterrupted()) {
                    OutputFormatter.printWarning(output);
                    return ExitCode.INTERRUPTED;
                }
                if (result.isComplete()) {
                    OutputFormatter.printSuccess(output);
                } else {
                    // A step that would normally prompt the user. There is nobody to answer, so
                    // surface the diagnostic (e.g. "File not found: ...") instead of silently
                    // dropping it and dead-ending on the follow-up step.
                    OutputFormatter.printInfo(output);
                }
            }

            if (result.isComplete()) {
                // An interruption with nothing to say still ended the run: the branch above only
                // reports the ones that said something.
                if (result.isInterrupted()) {
                    return ExitCode.INTERRUPTED;
                }
                return result.isError() ? ExitCode.FAILED : ExitCode.OK;
            }

            // Only a non-blank output is judged for repetition. There is no model here to nudge, so
            // the guard can only stop the run -- and a step that advances internal state while
            // reporting nothing would otherwise look identical to one that is stuck.
            if (output != null && !output.isBlank()) {
                guard.record(output);
                if (guard.isStuck()) {
                    StepOutput.reportNoProgress(guard);
                    return 1;
                }
            }

            context.putAll(result.getContext());
        }
        return stoppedAtCeiling();
    }

    /**
     * Runs the back-and-forth: a step, then whoever it is addressed to, then the next step.
     *
     * @return the exit code
     */
    private int runConversation(IterativeCommand command, String[] args, Map<String, Object> context,
                                String initialPrompt, boolean hasInitialPrompt) {
        // One guard and one compactor per run: their history describes this run and no other.
        IterationProgressGuard guard      = new IterationProgressGuard();
        TranscriptCompactor    compactor  = new TranscriptCompactor();
        List<String>           transcript = transcriptOf(context);

        String llmResponse = null;
        int    iteration   = 0;

        while (iteration < maxIterations) {
            iteration++;

            IterativeCommand.StepResult stepResult =
                    iteration == 1 && hasInitialPrompt
                    ? new IterativeCommand.StepResult(false, "", context, initialPrompt)
                    : takeAStep(command, args, context, llmResponse);

            if (stepResult.isComplete()) {
                // Kept, so the context the run ended with holds what its last step knew.
                context.putAll(stepResult.getContext());
                context.put("conversationHistory", transcript);
                return finish(stepResult);
            }

            String nextPrompt = stepResult.getNextPrompt();
            if (nextPrompt == null || nextPrompt.isEmpty()) {
                OutputFormatter.printWarning("No next prompt provided, ending execution");
                return 1;
            }

            context.putAll(stepResult.getContext());
            // Re-assert ownership of the transcript AFTER putAll. The executor owns this list and
            // only ever appends to it; a step whose context happened to carry a "conversationHistory"
            // key would otherwise swap in a different (possibly shorter) list, the rendered prompt
            // would rewind, and every following request would miss the cache. The old guard only
            // caught null, which left the more damaging case -- a non-null replacement -- silent.
            context.put("conversationHistory", transcript);
            recordTurn(transcript, llmResponse, stepResult, nextPrompt);

            llmResponse = ask.requiresUserInput(stepResult)
                          // getUserInput opens its own "User Input Required" sub-header; announcing
                          // the same thing here put two consecutive sub-headers on screen (and, in
                          // the shell, opened two nested sections for one prompt).
                          ? ask.getUserInput(nextPrompt, stepResult.getOutput())
                          : askTheModel(command, iteration, nextPrompt, transcript, guard, compactor);

            // Record what this turn produced, and stop if the run has stopped moving. This is the
            // executor's backstop: the commands it drives do not propose actions, so ActionLoopGuard
            // -- which watches actions -- does not apply to them, and there is no iteration ceiling
            // by default.
            guard.record(llmResponse);
            if (guard.isStuck()) {
                StepOutput.reportNoProgress(guard);
                return 1;
            }

            // The model's raw reply is NOT echoed. For a command that acts on it (chat, agent) it is
            // an ACTION block whose command, arguments and reason are already rendered, in a readable
            // form, by the step that runs it; echoing the block as well showed the same decision
            // twice. For a command whose reply IS the answer, finish() prints it. Either way the full
            // text is still recorded in the session history -- by AIManager, at the one point all
            // completions funnel through -- and in the debug log.
            DebugLogger.getInstance().debug("IterativeExecutor", "LLM response: " + llmResponse);
        }
        return stoppedAtCeiling();
    }

    /**
     * The transcript this run appends to.
     *
     * <p>Owned by the executor and APPEND-ONLY, so each turn's prompt is a literal extension of the
     * previous turn's and a provider can serve the shared prefix from cache. See
     * {@link #buildPromptWithHistory}.</p>
     */
    @SuppressWarnings ("unchecked")
    private static List<String> transcriptOf(Map<String, Object> context) {
        List<String> transcript = (List<String>) context.get("conversationHistory");
        if (transcript == null) {
            transcript = new ArrayList<>();
            context.put("conversationHistory", transcript);
        }
        return transcript;
    }

    /**
     * Runs one step and shows what it said.
     *
     * <p>The output is collapsed rather than dropped when command output is hidden. In the shell it
     * is sent whole, between the markers {@link com.eonmux.cadetcoder.ui.CollapsedOutput} defines,
     * so clicking the result opens it. Where nothing can open it the head is kept and the rest is
     * counted: a step's output is not all noise, because an intermediate step reports its own
     * diagnostics here ("File not found", "No suggestion type specified", "Format error attempt 1"),
     * and those lead the message. See {@link StepOutput#printForConsole}.</p>
     *
     * <p>A COMPLETING step is not printed here. Its output is the answer, and {@link #finish} renders
     * it -- once, as prose. Printing it here as well put the final answer on screen twice (three
     * times counting the raw reply echoed one iteration earlier).</p>
     */
    private static IterativeCommand.StepResult takeAStep(IterativeCommand command, String[] args,
                                                         Map<String, Object> context,
                                                         String llmResponse) {
        IterativeCommand.StepResult result = command.executeStep(args, context, llmResponse);
        if (!result.isComplete() && result.getOutput() != null && !result.getOutput().isEmpty()) {
            // Printed as lines rather than as a fenced code block. A step's output is console text,
            // and a bare fence renders in the shell as a block labelled "code" -- a label that says
            // nothing, above text that is not code.
            StepOutput.printForConsole(result.getOutput());
        }
        return result;
    }

    /**
     * Ends a run on the step that completed it.
     *
     * <p>An explicit (or heuristically-detected) failure exits non-zero. The completing step is not
     * echoed anywhere else, so the reason is reported here; without this a failed run ended with
     * nothing on screen at all.</p>
     *
     * @return the exit code
     */
    private static int finish(IterativeCommand.StepResult stepResult) {
        String output = stepResult.getOutput();
        if (stepResult.isError()) {
            if (output != null && !output.isBlank()) {
                OutputFormatter.printError(output.trim());
            }
            return ExitCode.FAILED;
        }
        // Asked before the success path, because a step that was taken back IS "not an error" and
        // would otherwise be reported, and exited, as work that finished.
        if (stepResult.isInterrupted()) {
            if (output != null && !output.isBlank()) {
                OutputFormatter.printWarning(output.trim());
            }
            return ExitCode.INTERRUPTED;
        }
        StepOutput.reportCompletion(StepOutput.extractAnswer(output));
        return ExitCode.OK;
    }

    /**
     * Appends one turn to the transcript.
     *
     * <p>The step's output and the next prompt often carry the SAME command output: the chat loop
     * builds one string that embeds it and another that embeds it again, and appending both put every
     * command's result into the prompt twice. Measured on a four-turn run, a single file listing
     * appeared twice in a 61,000-character prompt. The condensed form is used only when the next
     * prompt provably already contains the body, so nothing can be lost.</p>
     */
    private static void recordTurn(List<String> transcript, String llmResponse,
                                   IterativeCommand.StepResult stepResult, String nextPrompt) {
        if (llmResponse != null) {
            transcript.add("LLM: " + llmResponse);
        }
        transcript.add("System: "
                       + StepOutput.condenseForTranscript(stepResult.getOutput(), nextPrompt));
        transcript.add("Next prompt: " + nextPrompt);
    }

    /**
     * Puts the turn to the model.
     *
     * <p>The command's own system prompt is preferred so every follow-up turn is held to the same
     * contract (command catalog + required action format) the command established on its first turn;
     * the generic one below is the fallback for commands that provide none. Without this, follow-up
     * turns lose the catalog and the format, and the model emits unparseable output that ends the
     * loop prematurely.</p>
     *
     * @return the model's reply
     */
    private String askTheModel(IterativeCommand command, int iteration, String nextPrompt,
                               List<String> transcript, IterationProgressGuard guard,
                               TranscriptCompactor compactor) {
        // Marks where one iteration ends and the next begins. Collapsed with the rest of the
        // bookkeeping: a number that counts turns says nothing about what the turn did, and a run
        // of twenty spent a fifth of the screen saying so. A focused result has them all, in order.
        // Named by LoopPass, which is the only thing that knows whether this run is one pass of a
        // loop and what number that pass is; outside a loop the label is the bare iteration.
        String opening = LoopPass.opening(iteration);
        CollapsedOutput.hiding(() -> OutputFormatter.printIteration(opening));

        String systemPrompt = command.getIterativeSystemPrompt();
        if (systemPrompt == null || systemPrompt.isEmpty()) {
            systemPrompt = genericSystemPrompt();
        }

        // Fold the transcript if this prompt would not otherwise fit. Done after the turn's entries
        // are appended and BEFORE the prompt is rendered, so what is measured is what would be sent
        // -- and so the render below is the only one. Refused while the guard is seeing repeats:
        // rewriting history under a model that is already going in circles removes the context that
        // would let it notice.
        String folded = compactor.compactIfNeeded(transcript,
                                                  SystemPromptProvider.compose(systemPrompt),
                                                  guard.consecutiveRepeats() == 0);
        if (folded != null) {
            OutputFormatter.printInfo("Context compacted: " + folded + ".");
        }

        // Rendered once, after any compaction. The retry nudge and any timer that has come due are
        // appended to the rendered string and deliberately never enter the transcript, so they have
        // to be added last -- a re-render after them would silently drop them.
        String fullPrompt = turnPrompt(buildPromptWithHistory(nextPrompt, transcript),
                                       guard.perturbation(), TimerNotice.dueNow(),
                                       JobNotice.dueNow(), JobNotice.stillRunning());
        try {
            String answer = aiManager.complete(new PromptData(systemPrompt, fullPrompt),
                                               new HashMap<>());
            TimerNotice.delivered();
            JobNotice.delivered();
            return answer;
        } catch (RuntimeException failed) {
            // The turn's notices went out with a prompt that never arrived, so they are owed again
            // rather than lost: being told is the whole point of having set the timer, and of
            // having been left to get on with something while the job ran.
            TimerNotice.undelivered();
            JobNotice.undelivered();
            throw failed;
        }
    }

    /**
     * What is sent for one turn: the conversation, then whatever is true of this moment alone.
     *
     * <h2>Why the momentary parts go last, and only last</h2>
     *
     * <p>A retry nudge and a timer that has come due are both about this turn rather than about the
     * conversation, and neither is recorded in the transcript. Placed anywhere but the end they
     * would change the prompt's leading bytes every time one of them appeared -- which is the
     * property prompt caching depends on, so a run where a timer fired every few minutes would pay
     * full price for its whole history every few minutes. See
     * {@link #buildPromptWithHistory(String, List)}.</p>
     *
     * <h2>Why a turn with nothing to add is byte-identical to the conversation</h2>
     *
     * <p>That is the ordinary case, and it is the one the cache is for. Appending a separator, a
     * newline or an empty section "for consistency" would make every turn differ from the one it
     * extends by exactly the bytes nobody needed, and the whole mechanism above would be defeated by
     * punctuation.</p>
     *
     * @param conversation the rendered transcript, which every turn extends
     * @param momentary    things true of this turn alone, in the order they should be read; entries
     *                     may be null or empty and are then not there at all
     * @return the prompt to send
     */
    static String turnPrompt(String conversation, String... momentary) {
        StringBuilder prompt = new StringBuilder(conversation == null ? "" : conversation);
        if (momentary != null) {
            for (String part : momentary) {
                if (part != null && !part.isEmpty()) {
                    prompt.append(part);
                }
            }
        }
        return prompt.toString();
    }

    /**
     * Reports a run that ran out of the iterations it was given.
     *
     * <p>Only reachable when an explicit ceiling was configured; iterations are unbounded by
     * default.</p>
     *
     * @return the exit code
     */
    private int stoppedAtCeiling() {
        OutputFormatter.printError("Stopped after the configured limit of " + maxIterations
                                   + " iterations without completing.");
        OutputFormatter.printInfo(
                "Raise or remove the limit with -Dcadet.iterative.maxIterations=<n> (0 = unlimited).");
        return 1;
    }

    /**
     * Prints an AI failure in the actionable form the user needs: what failed, and what to do about
     * it. The exception message already names the provider, the model, the HTTP status and the
     * remedy; the rate-limit case adds the wait the provider asked for.
     */
    private void reportAiFailure(LLMException e) {
        OutputFormatter.printError("AI request failed: " + e.getMessage());
        if (e.getKind() == LLMException.Kind.RATE_LIMITED && e.getRetryAfter() != null) {
            OutputFormatter.printWarning("Provider asked to retry after "
                    + Math.max(1, e.getRetryAfter().toSeconds()) + "s.");
        }
    }

    /**
     * Renders the conversation so far as the user prompt.
     *
     * <h2>Append-only, because prompt caching matches on a shared prefix</h2>
     *
     * <p>The rendering is a plain in-order concatenation of every history entry, which makes each
     * turn's prompt a literal extension of the previous turn's. That is the property prompt caching
     * needs: a provider can serve the shared leading prefix from cache and only pay for the tail.</p>
     *
     * <p>What this replaced did the opposite. It emitted a sliding window of the last five entries
     * under a {@code "Previous context:"} heading, then repeated the current prompt again under
     * {@code "Current request:"}. Three entries are recorded per iteration, so from the second
     * iteration onwards the window advanced every single turn and the prompt's opening bytes changed
     * every single turn -- guaranteeing a cache miss on the whole conversation, forever, and
     * additionally dropping context older than about a turn and a half while paying to send the
     * newest entry twice.</p>
     *
     * <p>Two consequences to keep in mind when editing this:</p>
     * <ul>
     *   <li>The current request must NOT be appended again at the end. It is already the last history
     *       entry; the "respond to the most recent entry" instruction belongs in the system prompt,
     *       which is stable, rather than in a trailing line that would sit between one turn's text and
     *       the next and break the prefix.</li>
     *   <li>Nothing volatile (a timestamp, an iteration counter, a re-computed context snippet) may be
     *       inserted anywhere but the very end, for the same reason.</li>
     * </ul>
     *
     * @param currentPrompt the current turn's prompt; already present as the last history entry, and
     *                      used only as a fallback when history is somehow empty
     * @param history       every entry recorded so far, oldest first
     * @return the full conversation, oldest first
     */
    String buildPromptWithHistory(String currentPrompt, List<String> history) {
        if (history == null || history.isEmpty()) {
            return currentPrompt == null ? "" : currentPrompt;
        }

        StringBuilder prompt = new StringBuilder();
        for (String entry : history) {
            if (entry != null) {
                prompt.append(entry).append("\n");
            }
        }
        return prompt.toString();
    }

    /** What a command is held to when it states no contract of its own. */
    private static String genericSystemPrompt() {
        return "You are an AI assistant working iteratively to complete a software task. " +
               "You proceed ONE step at a time: respond to the current question, and you will be " +
               "shown the result before the next step. " +
               "Respond concisely and directly in exactly the format the current prompt asks for, " +
               "without extra commentary. " +
               "When the task is fully complete, say so clearly so the loop can finish.";
    }
}
