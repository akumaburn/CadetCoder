package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ai.parsing.ParsedResponse;
import com.eonmux.cadetcoder.commands.IterativeCommand.StepResult;
import com.eonmux.cadetcoder.OutputFormatter;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The two turns of the chat machine that are nothing but reading what the model said back: the turn
 * after an action ran, and the turn after one failed.
 *
 * <h2>Why these two and not the others</h2>
 *
 * <p>The other steps do something -- take a request, call the model, run a command. These two only
 * interpret a reply and decide where the machine goes next, which is a different job with a
 * different failure mode: misreading prose as a tool call, or reading a botched action block as a
 * final answer. Keeping them together makes those two readings sit side by side, where a change to
 * one is visibly a change to the other.</p>
 *
 * <h2>Why it holds the command</h2>
 *
 * <p>Both turns hand control back to the state machine -- a recovered action re-enters at
 * {@code execute_single_action}, and so does a follow-up the model asked for. Re-entry is the whole
 * point of these turns, so the machine is what they are written against.</p>
 */
final class ChatFollowUp {

    /** How many times one failed action is retried before the run moves on without it. */
    private static final int MAX_ACTION_RETRIES = 3;

    /** What the model is allowed to answer when asked what to do about a failure. */
    private static final Pattern CHOICE =
            Pattern.compile("\\b(retry|skip|stop)\\b", Pattern.CASE_INSENSITIVE);

    /** A reasoning block some models emit, which is not part of the answer. */
    private static final Pattern THINKING = Pattern.compile("<think>.*?</think>", Pattern.DOTALL);

    private static final String WHAT_NEXT =
            "Based on the information available, what should I do next? Provide another ACTION block "
            + "or explain your findings.";

    private final ChatCommand    chat;
    private final ActionRecovery recovery;

    /**
     * @param chat     the machine these turns return control to
     * @param recovery what tries to mend a failed action before it is retried unchanged
     */
    ChatFollowUp(ChatCommand chat, ActionRecovery recovery) {
        if (chat == null || recovery == null) {
            throw new IllegalArgumentException("a follow-up turn needs the chat it belongs to and a "
                                               + "way to mend what failed");
        }
        this.chat     = chat;
        this.recovery = recovery;
    }

    /**
     * An action has run and the model has been asked what follows.
     *
     * <p>A reply carries either another action, a botched attempt at one, or the answer. The middle
     * case is the one worth naming: a block that nearly parsed is the model trying to act, and
     * accepting it as prose would report an answer the run never reached.</p>
     *
     * @param args        the command arguments, carried through re-entry
     * @param context     the run's context
     * @param llmResponse what the model said
     * @return where the machine goes next
     */
    StepResult afterAnAction(String[] args, ChatContext context, String llmResponse) {
        if (llmResponse == null || llmResponse.trim().isEmpty()) {
            return StepResult.failure("No response provided", context.toMap());
        }
        // Parsed with the SAME multi-tier engine used by analyze_request so a follow-up turn is
        // interpreted with identical confidence/validation/security logic (rather than the legacy
        // regex which fabricated a "read" from plain prose).
        ParsedResponse parsed = chat.parseResponseWithEngine(llmResponse, context.getUserRequest());

        List<ChatCommand.AIAction> actions = actionsIn(parsed);
        if (!actions.isEmpty()) {
            RefusedAction.noteAnActionRan(context);
            context.setCurrentAction(actions.get(0));
            context.setStep("execute_single_action");
            return chat.executeStep(args, context.toMap(), null);
        }
        String refused = RefusedAction.reason(parsed);
        if (refused != null) {
            return RefusedAction.tellTheModel(context, refused);
        }
        if (ChatActions.looksLikeABotchedAction(llmResponse)) {
            return correctTheFormat(context, llmResponse);
        }
        return theAnswer(context, llmResponse);
    }

    /**
     * The model was told its reply could not be read as an action, and has answered again.
     *
     * <p>A corrected reply that does parse resets the count: the ceiling is on consecutive failures
     * to follow the format, not on how many corrections a long run needed in total.</p>
     *
     * @param args        the command arguments, carried through re-entry
     * @param context     the run's context
     * @param llmResponse what the model said this time
     * @return where the machine goes next
     */
    StepResult afterACorrection(String[] args, ChatContext context, String llmResponse) {
        if (llmResponse == null || llmResponse.trim().isEmpty()) {
            return StepResult.failure("No response provided for format retry", context.toMap());
        }
        ParsedResponse parsed = chat.parseResponseWithEngine(llmResponse, context.getUserRequest());

        // Only a reply carrying the prompt's SUCCESS marker counts as "the model answered". Any other
        // unstructured reply is still a format failure, and must not have a guessed command run for
        // it -- reporting "done" having executed nothing is what made the agent look like it acted.
        if (ChatActions.isFallbackOnly(parsed) && ChatActions.carriesSuccessMarker(llmResponse)) {
            context.setFormatRetryCount(0);
            StepResult questioned = CompletionChallenge.questioning(context, llmResponse);
            if (questioned != null) {
                return questioned;
            }
            OutputFormatter.println(llmResponse.trim());
            return StepResult.success("", context.toMap());
        }
        List<ChatCommand.AIAction> actions = actionsIn(parsed);
        if (!actions.isEmpty()) {
            context.setFormatRetryCount(0);
            RefusedAction.noteAnActionRan(context);
            context.setCurrentAction(actions.get(0));
            context.setStep("execute_single_action");
            return chat.executeStep(args, context.toMap(), null);
        }
        // Checked before the ceiling: a corrected reply that the screen refused DID follow the
        // format, and giving up on its format is how a run ended above a well-formed block.
        String refused = RefusedAction.reason(parsed);
        if (refused != null) {
            return RefusedAction.tellTheModel(context, refused);
        }
        if (FormatRetry.exhausted(context)) {
            return FormatRetry.giveUp(context, llmResponse);
        }
        return FormatRetry.askAgain(context, "Format still incorrect, attempt",
                FormatRetry.correction("Your format is still wrong.",
                                       "Generate the ACTION block now:"));
    }

    /**
     * The actions a parsed reply actually asks for.
     *
     * <p>A low-confidence fuzzy or semantic fallback means the model answered in prose rather than
     * asking for a tool, so nothing is run for it: executing the parser's guess is how a run ends up
     * reading a file nobody named.</p>
     */
    private List<ChatCommand.AIAction> actionsIn(ParsedResponse parsed) {
        return ChatActions.isFallbackOnly(parsed)
                ? new ArrayList<>()
                : ChatActions.runnable(parsed, chat);
    }

    /** A reply that meant to be an action but could not be read as one. */
    private StepResult correctTheFormat(ChatContext context, String llmResponse) {
        if (FormatRetry.exhausted(context)) {
            return FormatRetry.giveUp(context, llmResponse);
        }
        return FormatRetry.askAgain(context, "Format error in next_step attempt",
                FormatRetry.correction(
                        "Your previous response had incorrect ACTION format.",
                        "DO NOT use 'ACTION:', '<action></action>', or any other format - use "
                        + "'ACTION_START' and 'ACTION_END'. Generate the ACTION block now:"));
    }

    /**
     * The model has finished and said so.
     *
     * <p>A reply already in the {@code SUCCESS:} form is passed through untouched, because that is
     * the form {@link IterativeExecutor} reads as a terminal answer; anything else is labelled so it
     * is not mistaken for one.</p>
     */
    private StepResult theAnswer(ChatContext context, String llmResponse) {
        // The one place a run that did work ends by answering, and therefore where uber mode has to
        // question it. Checked before anything is logged as final: a claim being sent back is not a
        // final answer, and recording it as one would put two of them in the session history.
        StepResult questioned = CompletionChallenge.questioning(context, llmResponse);
        if (questioned != null) {
            return questioned;
        }

        String answer = llmResponse.trim();
        boolean alreadyMarked = ChatActions.carriesSuccessMarker(answer);

        chat.logDebug("Final AI Response", String.format("USER_REQUEST: %s", context.getUserRequest()));
        chat.logDebug("Final AI Response",
                      String.format("RESPONSE_LENGTH: %d characters", answer.length()));
        chat.logDebug("Final AI Response", String.format("SUCCESS_FORMAT: %s", alreadyMarked));
        chat.logDebug("Final AI Response", "=== FINAL AI EXPLANATION START ===");
        chat.logDebug("Final AI Response", answer);
        chat.logDebug("Final AI Response", "=== FINAL AI EXPLANATION END ===");

        return StepResult.success((alreadyMarked ? "" : "Response: ") + answer, context.toMap());
    }

    /**
     * An action has failed and the model has been asked what to do about it.
     *
     * <p>Three words answer it -- {@code retry}, {@code skip}, {@code stop} -- and so does a new
     * action block, which is read first because it says all three things at once: not this command,
     * that one, now.</p>
     *
     * @param args        the command arguments, carried through re-entry
     * @param context     the run's context
     * @param llmResponse what the model said
     * @return where the machine goes next
     */
    StepResult afterAFailure(String[] args, ChatContext context, String llmResponse) {
        if (llmResponse == null) {
            // The step is deliberately left alone: the question has not been answered, so the next
            // reply has to come back to this same turn rather than be read as what to do instead.
            return new StepResult(false, "Please choose an option", context.toMap(),
                                  "Please reply with a new ACTION block, or with 'retry', 'skip' "
                                  + "or 'stop'.")
                    .addressedToModel();
        }
        // An action block is an answer to the question, and the most useful one there is: a model
        // that has read the error and written a different command has said "retry, like this".
        // Read only as one of the three words, it was none of them, so the run skipped the step it
        // had just been told how to do and asked what to do next instead -- a whole turn spent
        // discarding the correction.
        ParsedResponse parsed = chat.parseResponseWithEngine(llmResponse, context.getUserRequest());
        List<ChatCommand.AIAction> actions = actionsIn(parsed);
        if (!actions.isEmpty()) {
            context.setActionRetryCount(0);
            RefusedAction.noteAnActionRan(context);
            context.setCurrentAction(actions.get(0));
            context.setStep("execute_single_action");
            return chat.executeStep(args, context.toMap(), null);
        }
        String refused = RefusedAction.reason(parsed);
        if (refused != null) {
            return RefusedAction.tellTheModel(context, refused);
        }

        switch (choiceIn(llmResponse)) {
            case "retry":
                return retry(args, context);
            case "skip":
                return moveOn(context, "Action skipped", "The action was skipped. " + WHAT_NEXT);
            case "stop":
                // A deliberate 'stop' is a clean termination, not a failure: it preserves the
                // pre-refactor exit code of 0.
                return StepResult.success("Execution stopped by user", context.toMap());
            default:
                return moveOn(context,
                        "Action failed - continuing (the reply was neither an action nor "
                        + "retry/skip/stop)",
                        "Your previous response carried no ACTION block and was not 'retry', "
                        + "'skip' or 'stop', so the failed action was skipped. What would you like "
                        + "to do next? Provide another ACTION block or explain based on available "
                        + "information.");
        }
    }

    /**
     * Which of the three choices a reply carries.
     *
     * <p>The word is looked for anywhere in the reply rather than required to be the whole of it,
     * because a model asked a three-way question routinely answers in a sentence. A reasoning block
     * is stripped first: it discusses all three words, and matching inside it would pick whichever
     * the model happened to mention first rather than the one it settled on. The pattern matches
     * across lines, which the original did not -- a reasoning block is almost always multi-line, so
     * without that the strip did nothing and every such reply was decided by its reasoning.</p>
     *
     * @param llmResponse what the model said
     * @return {@code retry}, {@code skip}, {@code stop}, or the reply itself when it is none of them
     */
    static String choiceIn(String llmResponse) {
        String cleaned = THINKING.matcher(llmResponse).replaceAll("").trim();
        Matcher found = CHOICE.matcher(cleaned);
        return found.find() ? found.group(1).toLowerCase() : cleaned.toLowerCase();
    }

    /** Retries the failed action, mending it first when the failure is one that can be mended. */
    private StepResult retry(String[] args, ChatContext context) {
        int attempts = context.getActionRetryCount();
        if (attempts >= MAX_ACTION_RETRIES) {
            return moveOn(context, "Action failed after " + MAX_ACTION_RETRIES + " retries - continuing",
                          "The action failed after " + MAX_ACTION_RETRIES + " retry attempts. "
                          + WHAT_NEXT);
        }
        ChatCommand.AIAction failed  = context.getFailedAction();
        String               type    = context.getLastErrorType();
        String               details = context.getLastErrorDetails();

        if (ActionRecovery.isMendable(failed, type, details)) {
            chat.logDebug("Error Recovery", "Attempting automatic recovery for " + failed.command);
            ChatCommand.AIAction mended = recovery.mended(failed, type, details, context);
            if (mended != null) {
                chat.logStep("Error Recovery", "Successfully recovered from " + type + " error");
                OutputFormatter.printInfo(
                        "Automatically fixed the issue with " + failed.command + " command");
                context.setCurrentAction(mended);
            } else {
                chat.logDebug("Error Recovery",
                              "Automatic recovery failed, proceeding with normal retry");
            }
        }
        context.setActionRetryCount(attempts + 1);
        context.setStep("execute_single_action");
        return chat.executeStep(args, context.toMap(), null);
    }

    /** Leaves the failed action behind and asks the model what it wants to do instead. */
    private StepResult moveOn(ChatContext context, String outcome, String prompt) {
        context.setStep("next_step");
        return new StepResult(false, outcome, context.toMap(), prompt).addressedToModel();
    }
}
