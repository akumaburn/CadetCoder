package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.ExitCode;
import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.ai.AIManager;
import com.eonmux.cadetcoder.ai.PromptData;
import com.eonmux.cadetcoder.ai.RequestSettings;
import com.eonmux.cadetcoder.ai.UberMode;
import com.eonmux.cadetcoder.ai.parsing.ResponseParsingEngine;
import com.eonmux.cadetcoder.ai.parsing.ParsedResponse;
import com.eonmux.cadetcoder.prompts.TemplatePromptBuilder;
import com.eonmux.cadetcoder.net.LLMException;
import com.eonmux.cadetcoder.resume.ResumeBriefing;
import com.eonmux.cadetcoder.resume.ResumeScope;
import com.eonmux.cadetcoder.resume.ResumeTranscript;
import com.eonmux.cadetcoder.session.ResumePoint;
import com.eonmux.cadetcoder.session.SessionManager;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.*;

/**
 * Default chat mode command that allows the AI to decide what steps to take
 * based on the user's request.
 */
@Command (name = "chat", description = "Ask the AI for an explanation or a change")
public class ChatCommand extends LoggingCommandSupport implements IterativeCommand, CommandRegistry.InterruptibleCommand, java.util.concurrent.Callable<Integer> {

    @Parameters (index = "0..*", description = "The chat request or question")
    String[] requestParts;

    private CommandRegistry commandRegistry;
    private CommandRegistry.InterruptionContext interruptionContext;
    private ResponseParsingEngine parsingEngine;

    private final ActionPaths    paths;
    private final ActionRecovery recovery;
    private final ActionRun      run;
    private final ChatFollowUp   followUp;

    /** The interrupted chat this run carries on, or {@code null} for a new one. */
    private ResumePoint.Chat resumed;

    /** What the resumed run is told about how it was interrupted and what it left running. */
    private String resumeBriefing = "";

    public ChatCommand() {
        // Initialize commandRegistry lazily to avoid circular dependency
        this.paths    = new ActionPaths(this);
        this.recovery = new ActionRecovery(this);
        this.run      = new ActionRun(this, this.paths);
        this.followUp = new ChatFollowUp(this, this.recovery);
    }

    public void setCommandRegistry(CommandRegistry commandRegistry) {
        this.commandRegistry = commandRegistry;
    }

    @Override
    public void setInterruptionContext(CommandRegistry.InterruptionContext context) {
        this.interruptionContext = context;
    }

    @Override
    public StepResult executeStep(String[] args, Map<String, Object> contextAsMap, String llmResponse) {
        ChatContext context = ChatContext.fromMap(contextAsMap, this);
        String step = context.getStep() != null ? context.getStep() : "initial";

        switch (step) {
            case "initial":
                // Check if user provided a request
                String[] parts = (args != null && args.length > 0) ?
                                 args :
                                 (requestParts != null ? requestParts : new String[0]);
                if (parts.length == 0) {
                    context.setStep("get_request");
                    // Left undeclared deliberately. This prompt IS aimed at a person -- there is
                    // no request yet, so there is nothing for the model to work from -- but the
                    // established behaviour routes it to the model, and changing that is a separate
                    // decision from stopping command output being read as a question.
                    return new StepResult(false, "No request provided", context.toMap(),
                            "What would you like me to help you with today?");
                }

                if (resumed != null) {
                    context.setUserRequest(resumed.request());
                    context.setUberChecksPassed(resumed.uberChecksPassed());
                    context.setStep("analyze_request");
                    return executeStep(args, context.toMap(), null);
                }

                String userRequest = String.join(" ", parts);
                // Recorded here, where the user's ACTUAL words are still separable from the prompt
                // they get rendered into, so a resumed session restores an exchange rather than a
                // list of answers with no questions.
                SessionManager.getInstance().addUserRequest(userRequest);
                context.setUserRequest(userRequest);
                context.setStep("analyze_request");
                return executeStep(args, context.toMap(), null);

            case "get_request":
                if (llmResponse != null && !llmResponse.trim().isEmpty() &&
                    !llmResponse.trim().equalsIgnoreCase("no request provided")) {
                    context.setUserRequest(llmResponse.trim());
                    context.setStep("analyze_request");
                    return executeStep(args, context.toMap(), null);
                }
                return StepResult.failure("No request provided, chat cancelled", context.toMap());

            case "analyze_request":
                return handleAnalyzeRequestStep(args, context);

            case "format_retry":
                return followUp.afterACorrection(args, context, llmResponse);

            case "clarify_request":
                if (llmResponse != null && !llmResponse.trim().isEmpty()) {
                    context.setUserRequest(llmResponse.trim());
                    context.setStep("analyze_request");
                    return executeStep(args, context.toMap(), null);
                }
                return StepResult.failure("No clarification provided, chat cancelled", context.toMap());

            case "execute_single_action":
                return handleExecuteSingleActionStep(args, context);

            case "next_step":
                return followUp.afterAnAction(args, context, llmResponse);

            case "handle_failure":
                return followUp.afterAFailure(args, context, llmResponse);

        }

        return StepResult.failure("Unknown step: " + step, context.toMap());
    }

    /**
     * Handles the analyze_request step of the chat command execution flow.
     * 
     * This method analyzes the user's request using AI to determine what actions to take.
     * It builds an AI prompt, sends it to the AI, parses the response to extract actions,
     * and sets up the next step in the execution flow.
     * 
     * @param args The command arguments
     * @param context The chat context
     * @return A StepResult indicating the next step in the execution flow
     */
    private StepResult handleAnalyzeRequestStep(String[] args, ChatContext context) {
        // Check for interruption before starting analysis
        if (shouldInterrupt()) {
            logStep("Request analysis", "Interrupted by user");
            return StepResult.interrupted("Command interrupted by user", context.toMap());
        }

        String userReq = context.getUserRequest();
        logStep("Analyzing user request", "Request: " + userReq);
        addContext("userRequest", userReq);
        // The request is NOT echoed here. It is already on screen twice by this point, and on both
        // surfaces: in the interactive shell the transcript opens a result heading carrying it and
        // then echoes the submitted line verbatim under it, and on the command line the user's own
        // prompt line holds what they typed. A third copy, prefixed with words that say only that
        // the run has started, pushes the first thing the run actually does off the first screen.

        // Initialize command registry lazily
        if (commandRegistry == null) {
            logStep("Initializing command registry");
            commandRegistry = new CommandRegistry();
        }

        // Build AI prompt to analyze the request and determine actions
        logStep("Building AI prompt for request analysis");
        String systemPrompt = buildSystemPrompt();
        String userPrompt = buildUserPrompt(userReq);

        try {
            // Check for interruption before AI call
            if (shouldInterrupt()) {
                logStep("AI request preparation", "Interrupted by user");
                return StepResult.interrupted("Command interrupted by user", context.toMap());
            }

            Map<String, Object> aiParams = new HashMap<>();
            // Low, because this reply has to parse as an action block rather than read well: that is
            // a requirement of the format, not a preference, so it is not left to configuration.
            // How LONG the reply may be is a preference, so it is left to ai.maxTokens and not set
            // here. Setting it here would override the user's own ceiling on every chat turn.
            aiParams.put(RequestSettings.TEMPERATURE, 0.1);

            logAIStart("chat-analysis", userPrompt, aiParams);

            PromptData promptData = new PromptData(systemPrompt, userPrompt);
            long aiStartTime = System.currentTimeMillis();
            String aiResponse = AIManager.getInstance().complete(promptData, aiParams);
            long aiDuration = System.currentTimeMillis() - aiStartTime;

            logAIComplete("chat-analysis", aiResponse, aiDuration);

            // Log full AI response to debug log
            logDebug("AI Response", String.format("REQUEST: %s", userReq));
            logDebug("AI Response", String.format("MODEL: chat-analysis"));
            logDebug("AI Response", String.format("DURATION: %dms", aiDuration));
            logDebug("AI Response", String.format("RESPONSE_LENGTH: %d characters", aiResponse != null ? aiResponse.length() : 0));
            logDebug("AI Response", "=== AI RESPONSE START ===");
            logDebug("AI Response", aiResponse != null ? aiResponse : "<null>");
            logDebug("AI Response", "=== AI RESPONSE END ===");

            // Check for interruption after AI call
            if (shouldInterrupt()) {
                logStep("AI response processing", "Interrupted by user");
                return StepResult.interrupted("Command interrupted by user", context.toMap());
            }

            // Parse AI response to extract actions using new multi-tier parsing engine
            logStep("Parsing AI response for actions");
            if (parsingEngine == null) {
                parsingEngine = new ResponseParsingEngine();
            }
            ParsedResponse parsedResponse = parsingEngine.parseResponse(aiResponse, userReq);
            // A fallback-only parse holds a GUESS, not the model's request: it is never converted into
            // an executable action (see the routing below).
            boolean fallbackOnly = ChatActions.isFallbackOnly(parsedResponse);
            List<AIAction> actions = fallbackOnly
                    ? new ArrayList<>()
                    : ChatActions.runnable(parsedResponse, this);
            logDataProcessing("Parse actions", "AIAction", actions.size(), System.currentTimeMillis() - aiStartTime);

            // Log parsing details for debugging
            logDebug("Parsing Details", String.format("Strategy: %s, Confidence: %.2f, Result: %s",
                parsedResponse.getStrategyUsed(), parsedResponse.getConfidence(), parsedResponse.getResult()));
            if (parsedResponse.hasWarnings()) {
                for (String warning : parsedResponse.getWarnings()) {
                    logWarning("Parsing Warning", warning);
                }
            }

            // Read, and refused for what it would do. Not a format failure: see RefusedAction.
            String refused = RefusedAction.reason(parsedResponse);
            if (refused != null) {
                logWarning("Action parsing", "The safety screen refused the action: " + refused);
                return RefusedAction.tellTheModel(context, refused);
            }

            // No structured ACTION could be parsed - only a semantic/fuzzy guess over prose. Two very
            // different situations look alike here and must NOT be conflated:
            //   * the model answered and said so with the prompt's own SUCCESS: marker -> that is the
            //     answer; surface it and finish.
            //   * anything else -> the model did not follow the documented format. Treat it as a
            //     format failure and re-prompt, instead of silently succeeding with an empty result
            //     (which reported "done" having executed nothing) or executing the invented command.
            if (fallbackOnly) {
                if (ChatActions.carriesSuccessMarker(aiResponse)) {
                    // Uber mode does not believe this yet. The claim is sent back to be checked
                    // against the request that started the run, and only what survives that gets to
                    // be the answer -- so the answer is NOT printed on this path.
                    StepResult questioned = CompletionChallenge.questioning(context, aiResponse);
                    if (questioned != null) {
                        logStep("Completion questioned", String.format(
                                "Question %d of %d", context.getUberChecksPassed(),
                                com.eonmux.cadetcoder.ai.UberMode.questionCount()));
                        return questioned;
                    }
                    logStep("Conversational response", String.format(
                            "Strategy: %s, Confidence: %.2f -> returning the model's answer directly",
                            parsedResponse.getStrategyUsed(), parsedResponse.getConfidence()));
                    OutputFormatter.println(aiResponse.trim());
                    return StepResult.success("", context.toMap());
                }

                logWarning("Action parsing", String.format(
                        "No structured action found (strategy: %s, confidence: %.2f) and no SUCCESS marker"
                        + " -> requesting a format correction",
                        parsedResponse.getStrategyUsed(), parsedResponse.getConfidence()));
            }

            if (actions.isEmpty()) {
                logWarning("Action parsing", "AI response parsing failed with all strategies");
                if (FormatRetry.exhausted(context)) {
                    return FormatRetry.giveUp(context, parsedResponse, aiResponse);
                }
                // The hint names what the parser tried and what went wrong, so the correction is
                // about this reply rather than a generic restatement of the format.
                return FormatRetry.askAgain(context, "Format error attempt",
                                            ChatActions.formatHint(parsedResponse));
            }

            // Execute ONLY the first action for iterative flow
            AIAction firstAction = actions.get(0);
            logStep("Preparing to execute action",
                String.format("Command: %s, Args: %s, Reason: %s",
                    firstAction.command,
                    String.join(" ", firstAction.arguments),
                    firstAction.explanation));
            addContext("currentCommand", firstAction.command);
            addContext("currentAction", firstAction.explanation);

            RefusedAction.noteAnActionRan(context);
            context.setCurrentAction(firstAction);
            context.setStep("execute_single_action");

            return executeStep(args, context.toMap(), null);

        } catch (LLMException requestNeverArrived) {
            // Not turned into a step failure, which is what this used to do. The request never
            // reached the provider, so there is nothing for the run to work from and every turn
            // after this one fails the same way. Let out, it ends the run through the executor's
            // own handler, which reports it as unreachable rather than as work that failed -- and
            // that is the difference `loop` reads to stop rather than run its remaining passes
            // against a provider that has cut this account off for the next five hours.
            throw requestNeverArrived;
        } catch (Exception e) {
            // Logged quietly: the failure returned below is surfaced by the executor, so reporting
            // it here as well printed the same sentence twice -- once shaped for a developer
            // ("analyze_request: ... (LLMTransportException)") and once for the user.
            logErrorQuietly("analyze_request", "Error analyzing request", e);
            return StepResult.failure("Error analyzing request: " + e.getMessage(), context.toMap());
        }
    }

    /**
     * Handles the execute_single_action step of the chat command execution flow.
     * 
     * This method executes a single action, checking for infinite loops before execution,
     * tracking the action for loop detection, executing the action, and handling success or failure.
     * On success, it sets up the next step in the execution flow. On failure, it sets up the
     * handle_failure step.
     * 
     * @param args The command arguments
     * @param context The chat context
     * @return A StepResult indicating the next step in the execution flow
     */
    private StepResult handleExecuteSingleActionStep(String[] args, ChatContext context) {
        AIAction action = context.getCurrentAction();

        // Refuse only repetition that provably cannot make progress (see ActionLoopGuard).
        ActionLoopGuard.Decision loopDecision =
                context.getLoopGuard().check(action.command, action.arguments);
        if (loopDecision.isBlocked()) {
            logWarning("Loop detection", loopDecision.getReason());
            context.getLoopGuard().recordBlocked();

            // There is no iteration ceiling any more, so lack of progress -- not a step count -- is
            // what ends a run. After being told what repeated and what to do instead, a model that
            // keeps proposing non-productive work is stuck, and nudging it again will not help.
            if (context.getLoopGuard().isStuck()) {
                String message = "Stopping: " + context.getLoopGuard().consecutiveBlocks()
                        + " proposed actions in a row made no progress (" + loopDecision.getReason() + ")";
                logWarning("Loop detection", message);
                OutputFormatter.printError(message);
                OutputFormatter.printInfo(
                        "Nothing further was executed. Re-run with a more specific request, "
                        + "or check the last command output above for what blocked progress.");
                return StepResult.failure(message, context.toMap());
            }

            OutputFormatter.printWarning("Skipping repeated action: " + loopDecision.getReason());

            context.setStep("next_step");
            return new StepResult(false,
                    "Skipped a repeated action: " + loopDecision.getReason(),
                    context.toMap(),
                    loopDecision.getGuidance())
                    .addressedToModel();
        }

        // Track this action for loop detection
        trackAction(action, context);

        // The run is about to do something, so uber mode's closing questions start again from the
        // first one. Without this a model could answer one question, work for another hour, and
        // then end on the remaining question alone -- with everything it changed in between never
        // checked against the request. See CompletionChallenge.
        CompletionChallenge.workWasDone(context);

        logStep("Executing action", ChatActions.describe(action));
        ChatActions.announce(action);

        long actionStartTime = System.currentTimeMillis();
        boolean success = executeAction(action, context);
        long actionDuration = System.currentTimeMillis() - actionStartTime;

        logPerformance("Action execution: " + action.command, actionDuration);

        // Record what the action actually produced. The guard compares RESULTS, not just command
        // names, so this is what lets it tell "read the same file twice, unchanged" (a loop) apart
        // from "read, edit, read again to verify" (progress).
        context.getLoopGuard().observe(action.command, action.arguments, success,
                context.getLastCommandOutput());

        if (!success) {
            logWarning("Action execution", "Action failed: " + ChatActions.describe(action));
            context.setStep("handle_failure");
            context.setFailedAction(action);

            // Get detailed error information to help AI make better decisions
            String lastCommandOutput = context.getLastCommandOutput();
            String errorDetails = context.getLastCommandError();

            // The error text and the captured output are the same string on a failure (executeAction
            // sets both from the command's output), so only one of them is included. Appending both
            // put the whole error in the prompt twice.
            String diagnosis = ChatActions.firstNonBlank(errorDetails, lastCommandOutput);

            StringBuilder failurePrompt = new StringBuilder();
            failurePrompt.append("The action '").append(ChatActions.describe(action)).append("' failed.\n\n");
            failurePrompt.append("Command: ").append(action.command).append(" ").append(String.join(" ", action.arguments)).append("\n");

            if (diagnosis != null) {
                failurePrompt.append("Error: ").append(diagnosis).append("\n");
            }

            // Two ways to answer, because the useful one was not accepted before. A model that has
            // read the error and written a corrected command is answering the question in the most
            // direct form there is; requiring one of three words meant that answer counted as none
            // of them and the corrected command was thrown away.
            failurePrompt.append("\nReply in ONE of two ways: either a new ACTION block "
                                 + "(ACTION_START ... ACTION_END) with the command to run instead, "
                                 + "or exactly one word -- 'retry' to try the same command again, "
                                 + "'skip' to move on without it, or 'stop' to end execution.");

            // Change step to handle_failure so the response is processed correctly
            context.setStep("handle_failure");

            // The step's output is what the CONSOLE shows, so it has to carry the reason. Reporting
            // only "Action failed: <command>" told the reader that something broke and nothing about
            // what -- while the record beside it counted the lines of an error they were never shown.
            // The console elides the bulk of this as it does any other command output.
            // The invocation is NOT repeated here. ChatActions.announce named it immediately above,
            // and the outcome record names it again when output is visible; a third copy of a
            // seventy-character command line pushes the one new thing -- why it failed -- down the
            // screen. What this adds is the reason.
            StringBuilder shown = new StringBuilder("Action failed");
            if (diagnosis != null) {
                shown.append(": ").append(diagnosis.strip());
            }

            return new StepResult(false,
                    shown.toString(),
                    context.toMap(),
                    failurePrompt.toString())
                    .addressedToModel();
        }

        logStep("Action completed successfully", String.format("Command: %s completed in %dms", action.command, actionDuration));

        // Reset retry count on successful action
        context.setActionRetryCount(0);

        // Action succeeded - now ask AI what to do next based on results
        context.setStep("next_step");
        String commandDesc = action.command + " " + String.join(" ", action.arguments);
        String capturedOutput = context.getLastCommandOutput();

        // Include the actual command output in the step result and prompt
        String fullOutput = "Command executed: " + commandDesc;
        if (capturedOutput != null && !capturedOutput.trim().isEmpty()) {
            fullOutput += "\n\nCommand Output:\n" + capturedOutput;
        }

        String promptWithOutput = "I just executed '" + commandDesc + "'";
        if (capturedOutput != null && !capturedOutput.trim().isEmpty()) {
            promptWithOutput += " and here are the results:\n\n" + capturedOutput + "\n\n";
        } else {
            promptWithOutput += " (no output produced). ";
        }
        promptWithOutput += "Based on these results, determine the next step for the request: '" +
                context.getUserRequest() + "'.\n" +
                "- If more work is required, respond with exactly ONE action in this format and nothing else:\n" +
                "ACTION_START\n" +
                "COMMAND: <one of the available commands>\n" +
                "ARGS: <arguments>\n" +
                "REASON: <why this step is needed>\n" +
                "ACTION_END\n" +
                "- If the request is now fully satisfied, respond with a single line beginning with " +
                "'SUCCESS:' followed by a short summary.\n" +
                "Do not stop until the request is actually complete, and do not use shell commands " +
                "such as 'cat'; only use commands from the available list.";

        return new StepResult(false, fullOutput, context.toMap(), promptWithOutput)
                .addressedToModel();
    }

    @Override
    public String getInitialPrompt(String[] args) {
        String[] parts =
                (args != null && args.length > 0) ? args : (requestParts != null ? requestParts : new String[0]);
        if (parts.length == 0) {
            return "The user wants to chat but didn't specify what they need help with. What should we help them with?";
        }
        return null;
    }

    /**
     * No executor-level announcement.
     *
     * <p>The transcript already opens a result heading titled with the submitted line, and the shell
     * echoes that line verbatim underneath it. An announcement here would put the request on screen
     * a third time.</p>
     */
    @Override
    public String getRunTitle(String[] args) {
        return null;
    }

    @Override
    public boolean supportsIterativeExecution(String[] args) {
        // Always support iterative execution for better interaction
        return true;
    }

    /**
     * Follow-up turns must be held to the SAME contract as the first turn. The first turn uses
     * {@link #buildSystemPrompt()} (the catalog-aware chat system prompt) via {@code analyze_request},
     * but the executor drives every subsequent turn. Returning the same system prompt here makes
     * {@link IterativeExecutor} use the catalog/format-aware prompt on every follow-up LLM call, so the
     * model keeps emitting valid {@code ACTION_START/ACTION_END} blocks with real catalog commands
     * instead of improvising shell-isms (e.g. {@code cat}) or a bare {@code ACTION} header that parse to
     * nothing and cause the loop to declare success without doing the work.
     */
    @Override
    public String getIterativeSystemPrompt() {
        return buildSystemPrompt();
    }

    private String buildSystemPrompt() {
        // Available commands come from the shared CommandCatalog so the chat and agent paths always
        // advertise the same, accurate set of options to the model.
        //
        // The uber-mode directive is appended last so it qualifies the completion contract above it
        // rather than being qualified by it. It is empty unless the mode is on, so the prompt -- and
        // with it the cacheable prefix every turn of a run shares -- is untouched the rest of the
        // time.
        return UberMode.applyTo(TemplatePromptBuilder.chat()
                                    .with("available_commands", CommandCatalog.coreCommands())
                                    .build());
    }

    private String buildUserPrompt(String userRequest) {
        StringBuilder prompt = new StringBuilder();

        // A resumed session's conversation LEADS, before the request that continues it. This call
        // decides what to do with the user's follow-up, so making it without the thing being
        // followed up is the one place restored context matters most -- and it is stable across
        // turns, so leading with it also keeps it inside the cacheable prefix.
        for (String entry : com.eonmux.cadetcoder.session.ResumedContext.forCurrentSession()) {
            prompt.append(entry).append("\n");
        }

        prompt.append("User request: ").append(userRequest).append("\n\n");

        if (resumed != null) {
            prompt.append(resumedWork()).append("\n");
        }

        // Add context about the current project
        prompt.append("Current directory: ").append(System.getProperty("user.dir")).append("\n");

        // Add current todos if any
        List<com.eonmux.cadetcoder.commands.TodoReadCommand.TodoItem> todos =
                SessionManager.getInstance().getTodoList();
        if (!todos.isEmpty()) {
            prompt.append("\nCurrent todo list:\n");
            for (com.eonmux.cadetcoder.commands.TodoReadCommand.TodoItem todo : todos) {
                prompt.append("- [").append(todo.getStatus()).append("] ").append(todo.getContent()).append("\n");
            }
        }

        prompt.append("\nDetermine the appropriate actions to fulfill this request.");
        return prompt.toString();
    }

    /**
     * What an interrupted run did, for the first request of the run that carries it on.
     *
     * <p>Later requests are built from the transcript, which starts with the same record; see
     * {@link #priorTranscript()}. The first one is built here, so it has to be told as well.</p>
     */
    private String resumedWork() {
        StringBuilder work = new StringBuilder(interruptionNote()).append("\n");
        if (!resumed.transcript().isEmpty()) {
            work.append("What that run did, oldest first:\n");
            for (String entry : resumed.transcript()) {
                work.append(entry).append("\n");
            }
        }
        return work.append(ResumeBriefing.CARRY_ON).append("\n").toString();
    }

    /** Tells a resumed run that it carries on an earlier one. */
    private static final String RESUMED_NOTE =
            "The user interrupted an earlier run at this request, and has now resumed it. "
            + ResumeBriefing.STOPPED_IS_NOT_REFUSED;

    /** @return the line that tells a resumed run it was interrupted, and what it left running */
    private String interruptionNote() {
        return resumeBriefing.isBlank() ? RESUMED_NOTE
                                        : RESUMED_NOTE + "\n" + resumeBriefing.strip();
    }

    /** @return the transcript entry that tells a resumed run it was interrupted */
    private String interruptionEntry() {
        return "System: " + interruptionNote();
    }

    @Override
    public List<String> priorTranscript() {
        if (resumed == null) {
            return List.of();
        }
        List<String> prior = new ArrayList<>(resumed.transcript());
        prior.add(interruptionEntry());
        return prior;
    }

    /**
     * Carries on an interrupted chat.
     *
     * @param chat     what the chat had done
     * @param briefing what the run is told about the interrupt, beyond its own record
     * @return the exit code
     */
    public int resume(ResumePoint.Chat chat, String briefing) {
        this.resumed        = chat;
        this.resumeBriefing = briefing == null ? "" : briefing;
        try {
            return execute(new String[] {chat.request()});
        } finally {
            this.resumed        = null;
            this.resumeBriefing = "";
        }
    }

    /**
     * Parses a follow-up LLM response with the multi-tier {@link ResponseParsingEngine}, the same
     * pipeline used for the initial {@code analyze_request} step. Centralizing parsing here keeps the
     * confidence/validation/security logic and the conversational fast-path identical across turns.
     * A second, regex-based parser used to live here and inferred different actions on follow-ups
     * (at one point fabricating a {@code read}); it was unreachable from production and is gone.
     *
     * @param aiResponse  the raw LLM response for this turn
     * @param userRequest the originating user request (for parsing context)
     * @return the engine's {@link ParsedResponse}; never null
     */
    ParsedResponse parseResponseWithEngine(String aiResponse, String userRequest) {
        if (parsingEngine == null) {
            parsingEngine = new ResponseParsingEngine();
        }
        return parsingEngine.parseResponse(aiResponse, userRequest);
    }

    private boolean executeAction(AIAction action, ChatContext context) {
        if (commandRegistry == null) {
            commandRegistry = new CommandRegistry();
        }
        ActionOutcome result = run.run(action, context, commandRegistry);

        // The ordered record: which step this was, what ran, whether it worked, and how much it
        // produced. It is emitted HERE, after the run has restored System.out -- printing it inside
        // the capture window wrote it into the buffer instead of the console.
        //
        // It is printed in BOTH visibility modes. It used to appear only when output was hidden, so
        // turning output on silently removed the outcome and the ordering, leaving the reader to
        // infer from a wall of raw text whether a command had actually succeeded.
        int order = context.getActionHistory() == null ? 1 : context.getActionHistory().size();
        // The command is named in the announcement above. When output is hidden the two lines are
        // adjacent, so the record only has to carry the outcome; when output is visible the whole of
        // it stands between them, and the record restates what it is reporting on.
        String record = com.eonmux.cadetcoder.ui.CommandOutputVisibility.isVisible()
                ? com.eonmux.cadetcoder.ui.CommandOutputVisibility.summarize(
                        order, action.command,
                        action.arguments == null ? "" : String.join(" ", action.arguments),
                        result.success, result.output)
                : com.eonmux.cadetcoder.ui.CommandOutputVisibility.summarizeOutcome(
                        order, result.success, result.output);
        // Marked as a success rather than as information. A step's outcome is a status, a failed one
        // already reads as a warning, and marking the two alike left a run with no column to scan
        // down to find where it went wrong.
        //
        // A step that worked says so behind the click: the position in the run and the size of the
        // output are bookkeeping, and a dozen of them crowd out the dozen lines that say what the
        // run is doing. A step that FAILED stays on screen, because that column is the whole reason
        // the record is marked as a status.
        if (result.success) {
            com.eonmux.cadetcoder.ui.CollapsedOutput.hiding(() -> OutputFormatter.printSuccess(record));
        } else {
            OutputFormatter.printWarning(record);
        }

        // Store captured output and error information in context for LLM to access
        context.setLastCommandOutput(result.output);
        if (!result.success) {
            // Persist the human-readable error message so handleExecuteSingleActionStep
            // can surface it via context.getLastCommandError() (otherwise it stays null).
            context.setLastCommandError(result.output);
            context.setLastErrorType(result.errorType);
            context.setLastErrorDetails(result.errorDetails);
        }
        return result.success;
    }

    @Override
    public Integer call() throws Exception {
        return execute(requestParts);
    }

    @Override
    public int execute(String[] args) {
        try {
            startCommandLogging("chat", args);
            logStep("Initializing iterative chat execution");

            // Use iterative executor for better multi-step handling
            IterativeExecutor executor = new IterativeExecutor();

            logStep("Starting iterative execution");
            int result;
            try (ResumeScope scope = ResumeScope.open()) {
                result = executor.execute(this, args);
                ResumePoint.Chat done = progress(executor.contextAtEnd(), args);
                // Cut off from the model, the run is worth carrying on only when it did something.
                if (result == ExitCode.INTERRUPTED
                    || result == ExitCode.UNREACHABLE && !done.transcript().isEmpty()) {
                    scope.stopped(ResumePoint.chat(
                            args == null ? List.of() : Arrays.asList(args), done));
                }
            }

            completeCommandLogging(result);
            return result;
        } catch (Exception e) {
            logError("execute", "Chat command execution failed", e);
            completeCommandLogging(1);
            return 1;
        }
    }


    /**
     * What this chat had done when it stopped.
     *
     * <p>A resumed run's transcript holds what it was told about the interrupt it carries on:
     * which jobs still ran, and the ids its timers were set again under. That is out of date at the
     * next resume, which tells its own, so only the line saying the run was resumed is kept.</p>
     *
     * @param context the context its run ended with
     * @param args    its arguments
     * @return its request, the run's own conversation, and uber mode's count of passed questions
     */
    private ResumePoint.Chat progress(Map<String, Object> context, String[] args) {
        ChatContext chat    = ChatContext.fromMap(context, this);
        String      request = chat.getUserRequest() != null ? chat.getUserRequest()
                                                            : String.join(" ", args == null
                                                                               ? new String[0]
                                                                               : args);
        String       told       = resumed == null ? null : interruptionEntry();
        List<String> transcript = new ArrayList<>();
        for (String entry : IterativeExecutor.ownTranscript(context)) {
            transcript.add(entry.equals(told) ? "System: " + RESUMED_NOTE : entry);
        }
        return new ResumePoint.Chat(request, ResumeTranscript.bounded(transcript),
                                    chat.getUberChecksPassed());
    }

    @Override
    public String getUsage() {
        return "chat \"<request>\"\n"
             + "  The command name is optional: anything that is not a command name is sent here.";
    }



    // Maximum number of actions to keep in history
    private static final int MAX_ACTION_HISTORY_SIZE = 20;
    
    /**
     * Tracks an action in the history for loop detection.
     * 
     * This method adds the action signature to the action history and
     * trims the history to prevent it from growing too large over time.
     * 
     * @param action The action to track
     * @param context The chat context containing the action history
     */
    private void trackAction(AIAction action, ChatContext context) {
        String actionSignature = action.command + ":" + String.join(",", action.arguments);

        List<String> actionHistory = context.getActionHistory();
        actionHistory.add(actionSignature);
        
        // Trim the action history to the maximum size
        context.trimActionHistory(MAX_ACTION_HISTORY_SIZE);

        logDebug("Action Tracking", String.format("Tracked action: %s (history size: %d)", 
            actionSignature, actionHistory.size()));
    }

    /**
     * Represents an action determined by the AI
     */
    public static class AIAction {
        String   command;
        String[] arguments;
        String   explanation;

        public AIAction(String command, String[] arguments, String explanation) {
            this.command     = command;
            this.arguments   = arguments;
            this.explanation = explanation;
        }
    }
}