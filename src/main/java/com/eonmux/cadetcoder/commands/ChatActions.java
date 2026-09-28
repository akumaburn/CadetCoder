package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.ai.parsing.ParsedAction;
import com.eonmux.cadetcoder.ai.parsing.ParsedResponse;
import com.eonmux.cadetcoder.ai.parsing.ResponseParsingEngine;
import com.eonmux.cadetcoder.ai.parsing.toolcalls.ToolCalls;
import com.eonmux.cadetcoder.ui.CommandOutputVisibility;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Reading a model's reply as actions, and saying what they are.
 *
 * <h2>What a reply can turn out to be</h2>
 *
 * <p>Three things, and telling them apart is the whole job: an action to run, a final answer, or an
 * attempt at an action that came out malformed. The third is the one that costs something to get
 * wrong -- read as an answer, the run reports success having done nothing at all -- so it is
 * detected positively rather than assumed from the absence of a parse.</p>
 */
final class ChatActions {

    /** The completion marker the chat prompt instructs the model to use for its final answer. */
    private static final String SUCCESS_MARKER = "SUCCESS:";

    /**
     * How sure the engine has to be about an inferred action before it counts as one.
     *
     * <p>Only consulted for a result the engine did not already mark as fallback-only; see
     * {@link #isFallbackOnly}.</p>
     */
    private static final double CONFIDENCE_THRESHOLD = 0.7;

    /**
     * An {@code action:} label at the start of a line.
     *
     * <p>Anchored, so ordinary prose that happens to mention the word "action" is not read as a
     * botched block and does not trigger a needless retry of a perfectly good final answer.</p>
     */
    private static final Pattern ACTION_LABEL_LINE =
            Pattern.compile("(?m)^\\s*action\\s*:", Pattern.CASE_INSENSITIVE);

    /**
     * A line that is the bare keyword {@code ACTION}, with no colon and no framing.
     *
     * <p>"ACTION\ncat README.md" is an attempted action block, not an answer: the parser extracts no
     * command from it, and without this signal the turn is misclassified as a terminal explanation
     * and the loop reports success having done nothing.</p>
     */
    private static final Pattern BARE_ACTION_LINE = Pattern.compile("(?im)^\\s*action\\s*$");

    private ChatActions() {
    }

    /**
     * Whether the reply carries the prompt's own completion marker.
     *
     * @param reply the raw model reply
     * @return {@code true} when the model is presenting its final answer rather than a tool call
     */
    static boolean carriesSuccessMarker(String reply) {
        return reply != null && reply.trim().startsWith(SUCCESS_MARKER);
    }

    /**
     * Whether the engine could not find a structured action and inferred one from prose.
     *
     * <p>Such a parse must never be executed: the fuzzy/semantic layer accepts any text and invents
     * a command from it -- a plain answer became a bogus {@code read}; an ACTION block missing only
     * its {@code REASON:} line became a {@code bash} execution of the surrounding prose. The engine
     * marks these results explicitly; the confidence check below is a second signal for any fallback
     * result that reaches here unmarked.</p>
     *
     * @param parsed the engine's result for this turn
     * @return {@code true} when no structured ACTION/JSON/XML action was found
     */
    static boolean isFallbackOnly(ParsedResponse parsed) {
        if (parsed == null) {
            return false;
        }
        if (ResponseParsingEngine.isFallbackOnly(parsed)) {
            return true;
        }
        ParsedResponse.ParsingStrategy strategy = parsed.getStrategyUsed();
        boolean inferred = strategy == ParsedResponse.ParsingStrategy.FUZZY_PARSING
                           || strategy == ParsedResponse.ParsingStrategy.SEMANTIC_PARSING;
        return inferred && parsed.getConfidence() < CONFIDENCE_THRESHOLD;
    }

    /**
     * Whether a reply that parsed as no action was nonetheless trying to be one.
     *
     * <p>Only structural markers count: a label at the start of a line, the bare keyword on its own
     * line, the XML tags, the {@code ACTION_START}/{@code ACTION_END} fragments, or the opening of
     * one of the tool-call notations a model writes when it reaches past the format it was asked
     * for.</p>
     *
     * <h2>Why a broken tool call counts</h2>
     *
     * <p>A reply carrying a notation that this could not read is an attempt at an action, exactly as
     * a broken ACTION block is. Without the notations listed here such a reply fell through to the
     * branch that treats a reply as the model's final answer, so the run ended reporting success
     * over a call it had never made -- the worst of the three outcomes, because nothing on screen
     * says the work did not happen.</p>
     *
     * <p>A reply that opens with the completion marker is the exception. The model has said it is
     * finished, and an answer that quotes one of these notations -- which an answer about this
     * codebase will -- is an answer, not an attempt.</p>
     *
     * @param reply the raw model reply
     * @return {@code true} when the turn should be sent back for format correction
     */
    static boolean looksLikeABotchedAction(String reply) {
        if (reply == null) {
            return false;
        }
        if (ACTION_LABEL_LINE.matcher(reply).find() || BARE_ACTION_LINE.matcher(reply).find()) {
            return true;
        }
        if (ToolCalls.appearIn(reply) && !carriesSuccessMarker(reply)) {
            return true;
        }
        String lower = reply.toLowerCase();
        return lower.contains("<action>")
               || lower.contains("</action>")
               || lower.contains("action_start")
               || lower.contains("action_end");
    }

    /**
     * The actions of a parse, in the form the dispatcher takes them.
     *
     * @param parsed the engine's result
     * @param log    where to record what was converted, skipped, and doubted
     * @return the runnable actions, in order
     */
    static List<ChatCommand.AIAction> runnable(ParsedResponse parsed, LoggingCommandSupport log) {
        List<ChatCommand.AIAction> runnable = new ArrayList<>();

        for (ParsedAction action : parsed.getActions()) {
            log.logDebug("Action Conversion",
                    String.format("Converting action: %s (confidence: %.2f, validation: %s)",
                                  action.getCommand(), action.getConfidence(), action.getValidation()));

            if (isUnexecutable(action)) {
                log.logWarning("Action Validation", String.format(
                        "Skipping unexecutable action '%s' (%s) - diverting to format correction",
                        action.getCommand(), action.getValidation()));
                continue;
            }

            runnable.add(action.toLegacyAction());

            if (action.getValidation() != ParsedAction.ValidationResult.VALID) {
                log.logWarning("Action Validation",
                        String.format("Action '%s' has validation issues: %s",
                                      action.getCommand(), action.getValidationMessage()));
            }
            if (action.isLowConfidence()) {
                log.logWarning("Action Confidence",
                        String.format("Action '%s' has low confidence: %.2f",
                                      action.getCommand(), action.getConfidence()));
            }
        }
        return runnable;
    }

    /**
     * Whether an action the parser produced must not be run whatever else it says.
     *
     * <h2>Why the verdict is read here and not only upstream</h2>
     *
     * <p>{@code ResponseParsingEngine} drops a dangerous action before a caller ever sees it, so in
     * a normal turn nothing reaches this method that it needs to refuse. That is exactly why the
     * check belongs here as well: this conversion is the last thing between a parse and a dispatch,
     * and reading a verdict of {@code SECURITY_RISK} and running the action anyway is a decision no
     * code should make on the strength of a filter somewhere else still being in the path.</p>
     *
     * <p>{@code INVALID_PARAMETERS} is deliberately not on the list. That verdict means the parser
     * could not find an argument it expected, and a command asked for without its argument answers
     * with its own usage -- which tells the model what to fix. Dropping it silently would not.</p>
     *
     * <p>An unknown or empty command is refused for a different reason: dispatching one turns a
     * garbled reply into a spurious "Executing: &lt;junk&gt;" and a failure the model then tries to
     * recover from. Leaving no actions routes the turn to the format-correction re-prompt instead.</p>
     */
    private static boolean isUnexecutable(ParsedAction action) {
        return action.getValidation() == ParsedAction.ValidationResult.INVALID_COMMAND
               || action.getValidation() == ParsedAction.ValidationResult.SECURITY_RISK
               || action.getCommand() == null
               || action.getCommand().trim().isEmpty();
    }

    /**
     * What to tell a model whose reply could not be parsed.
     *
     * <h2>Why this restates the system prompt rather than offering a way round it</h2>
     *
     * <p>The correction has to ask for the format the model was instructed to use. It asked for the
     * opposite: it opened with "Please use this EXACT JSON format", showed a JSON object, and
     * offered the ACTION block as the "Alternative" -- while the system prompt says to use only
     * ACTION_START/ACTION_END and lists that same JSON object among the formats that must not be
     * used. The one moment the model is being corrected was the moment it was told to switch to
     * the format its instructions forbid, and a model that complied was corrected again.</p>
     *
     * <p>The failure is a reply that carried neither an action nor a completion, so both accepted
     * replies are named: one ACTION block, or a SUCCESS line.</p>
     *
     * @param parsed the engine's result, which names what it tried and what went wrong
     * @return the correction to send back
     */
    static String formatHint(ParsedResponse parsed) {
        StringBuilder hint = new StringBuilder();
        hint.append("Your response could not be parsed. ");

        if (parsed.getStrategyUsed() != null) {
            hint.append(String.format("Last attempted strategy: %s. ", parsed.getStrategyUsed()));
        }
        if (!parsed.getErrors().isEmpty()) {
            hint.append("Errors encountered: ");
            hint.append(String.join(", ", parsed.getErrors()));
            hint.append(". ");
        }

        hint.append("\n\nReply with exactly ONE action block, in this format and no other:\n\n");
        hint.append("ACTION_START\n");
        hint.append("COMMAND: read\n");
        hint.append("ARGS: src/main/java/Example.java\n");
        hint.append("REASON: Read the example file to understand the structure\n");
        hint.append("ACTION_END\n\n");
        hint.append("COMMAND must be one of the available commands, ARGS the arguments it takes, "
                    + "and REASON why this step helps.\n\n");
        hint.append("If instead you already have everything you need, reply with a single line "
                    + "beginning:\n\n");
        hint.append("SUCCESS: <your complete answer>\n\n");
        hint.append("Generate your response now:");

        return hint.toString();
    }

    /**
     * Announces the action about to run, and why.
     *
     * <p>Emitted as a <em>sub-header</em>: the interactive shell turns a sub-header inside a
     * command's result into a nested, Tab-navigable section, so each action the model takes becomes
     * one browsable step of the run.</p>
     *
     * <p>The model's stated reason follows on its own line. It had been printed while
     * {@code System.out} was still redirected into the capture buffer, and that buffer had already
     * been snapshotted, so the reason reached neither the console nor the model -- it was simply
     * discarded. It is the one piece of an action that explains the run, so it is worth a line.</p>
     *
     * @param action the action about to be executed
     */
    static void announce(ChatCommand.AIAction action) {
        if (action == null) {
            return;
        }
        StringBuilder step = new StringBuilder(CommandOutputVisibility.describe(
                action.command,
                action.arguments == null ? "" : String.join(" ", action.arguments)));

        // The reason goes on a continuation line of the announcement rather than on an information
        // line of its own. It is not the tool telling the user something; it is part of what this
        // step is, and read as a separate marked line it was indistinguishable from the run's
        // telemetry and from the step's outcome, which wore the same marker.
        if (action.explanation != null && !action.explanation.isBlank()) {
            step.append('\n').append(action.explanation.strip());
        }
        OutputFormatter.printSubheader(step.toString());
    }

    /**
     * An action as a concise {@code command arg1 arg2} descriptor, for display and failure messages.
     *
     * <p>Deliberately the command and its arguments rather than {@code explanation}, the model's
     * free-text reasoning: that can be a large blob -- and, before response sanitization, even
     * leaked chat-template tokens -- which must never be surfaced as the thing being executed.</p>
     *
     * @param action the action to describe
     * @return the descriptor
     */
    static String describe(ChatCommand.AIAction action) {
        if (action == null) {
            return "(no action)";
        }
        String command = action.command != null ? action.command.trim() : "";
        String joined  = action.arguments != null ? String.join(" ", action.arguments).trim() : "";
        if (!command.isEmpty() && !joined.isEmpty()) {
            return command + " " + joined;
        }
        return command.isEmpty() ? "(empty action)" : command;
    }

    /**
     * @param candidates the strings to consider, in order of preference
     * @return the first that has content, trimmed, or {@code null} when none do
     */
    static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.trim();
            }
        }
        return null;
    }
}
