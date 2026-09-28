package com.eonmux.cadetcoder.ai.parsing;

import com.eonmux.cadetcoder.ai.parsing.toolcalls.ToolCall;
import com.eonmux.cadetcoder.ai.parsing.toolcalls.ToolCalls;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reads a tool call a model wrote in its own trained notation.
 *
 * <h2>Why a reply in the wrong notation is still an instruction</h2>
 *
 * <p>The system prompt asks for an ACTION block, and a model that was trained to call tools reaches
 * past it for the notation its own vendor taught it -- DSML, invoke tags, a JSON call behind a
 * marker, a Harmony channel header. Nothing read those, so a reply naming the command, its
 * arguments and the types of its arguments was refused as a format error, re-prompted twice and the
 * run ended. The remedy is not to ask harder. A call written in a documented notation says what to
 * run at least as precisely as the block does, and the only thing missing was a reader.</p>
 *
 * <h2>Why this outranks the XML and inference strategies</h2>
 *
 * <p>The notations here are all explicit: the model named a tool and named its arguments. That is a
 * statement of intent, not something recovered from prose, so a call must never be displaced by a
 * parser that guesses. It sits below the ACTION block only because the block is the format actually
 * asked for, and a reply carrying both meant the block.</p>
 */
public class ToolCallParser implements ParsingStrategy {

    /**
     * Confidence for a call read out of a documented notation.
     *
     * <p>The same figure the strict ACTION block earns, for the same reason: the notation states the
     * command and its arguments outright, so there is nothing left to be unsure about. Scoring it
     * lower would leave it under the engine's acceptance threshold, where the reply is sent back for
     * a format correction that it does not need.</p>
     */
    private static final double BASE_CONFIDENCE = 0.9;

    /** Where a tool name that is really a command and a subcommand divides. */
    private static final Pattern NAME_SEPARATOR = Pattern.compile("[\\s_-]+");

    /** A model's own reasoning block, which is not the rationale for the call. */
    private static final Pattern THINKING_BLOCK = Pattern.compile("<think>.*?</think>",
                                                                  Pattern.CASE_INSENSITIVE
                                                                  | Pattern.DOTALL);

    /** How much of the prose before a call is kept as its rationale. */
    private static final int REASONING_LIMIT = 300;

    /** The markers that open one of the notations, used to find where the rationale ends. */
    private static final String[] CALL_MARKERS = {"<｜DSML｜", "<|DSML|", "<invoke",
                                                  "<tool_call>", "[TOOL_CALLS]", "<|python_tag|>",
                                                  "<function=", "<|channel|>",
                                                  "<｜tool▁calls▁begin｜>",
                                                  "<｜tool▁call▁begin｜>"};

    @Override
    public ParsedResponse parse(String response, ParsingContext context) {
        long startTime = System.currentTimeMillis();

        if (response == null || response.trim().isEmpty()) {
            return new ParsedResponse.Builder(ParsedResponse.ParseResult.EMPTY_RESPONSE,
                                              ParsedResponse.ParsingStrategy.TOOL_CALL, response)
                .addError("Empty or null response")
                .setProcessingTime(System.currentTimeMillis() - startTime)
                .build();
        }

        List<ToolCall> calls = ToolCalls.readFrom(response);
        if (calls.isEmpty()) {
            return new ParsedResponse.Builder(ParsedResponse.ParseResult.FORMAT_ERROR,
                                              ParsedResponse.ParsingStrategy.TOOL_CALL, response)
                .addError("No tool call found in any known notation")
                .setProcessingTime(System.currentTimeMillis() - startTime)
                .build();
        }

        String           reasoning = reasoningIn(response);
        List<ParsedAction> actions = new ArrayList<>();
        for (ToolCall call : calls) {
            actions.add(actionFor(call, reasoning, context));
        }

        ParsedResponse.Builder built = new ParsedResponse.Builder(
                ParsedResponse.ParseResult.SUCCESS,
                ParsedResponse.ParsingStrategy.TOOL_CALL, response);
        for (ParsedAction action : actions) {
            built.addAction(action);
        }
        return built
            .setConfidence(averageConfidence(actions))
            .addMetadata("tool_call_syntax", calls.get(0).syntax())
            .setProcessingTime(System.currentTimeMillis() - startTime)
            .build();
    }

    /**
     * Turns one call into the action the dispatcher runs.
     *
     * @param call      the call as the model wrote it
     * @param reasoning the prose that preceded it
     * @param context   what the engine knows about this turn
     * @return the action, valid or not; an invalid one is scored below the acceptance threshold
     */
    private ParsedAction actionFor(ToolCall call, String reasoning, ParsingContext context) {
        Map<String, Object> parameters = new LinkedHashMap<>(call.arguments());
        String              command    = commandFor(call.name(), parameters, context);

        ParsedAction.ValidationResult validation = validate(command, parameters, context);
        return new ParsedAction.Builder(command)
            .setParameters(parameters)
            .setReasoning(reasoning.isEmpty() ? "Called " + call.name() : reasoning)
            .setConfidence(withValidationApplied(validation))
            .setValidation(validation, validationMessage(validation, command))
            .addMetadata("parser", "tool_call")
            .addMetadata("tool_call_syntax", call.syntax())
            .addMetadata("tool_name", call.name())
            .build();
    }

    /**
     * The command a tool name asks for, splitting a name that is a command and a subcommand.
     *
     * <h2>Why the split is decided by the registry</h2>
     *
     * <p>A tool name is written the way an identifier is written, and a command name is one word,
     * so the two disagree in two ways. {@code todowrite} is offered as {@code todo_write}, and the
     * catalogue teaches {@code job start} as the name of a thing to do, so the tool is named
     * {@code job start} or {@code job_start} -- but no command has a space in its name, and the
     * subcommand belongs in the arguments. Joining or splitting on the separator alone would be
     * wrong for every command that has one in its name, so either is done only when it turns a name
     * nothing answers to into one that something does. That is evidence about this installation's
     * commands rather than a guess about how the model spells things.</p>
     *
     * @param toolName   the tool name the model wrote
     * @param parameters the call's arguments, which gain the subcommand when the name is split
     * @param context    what the engine knows about this turn
     * @return the command to dispatch
     */
    private static String commandFor(String toolName, Map<String, Object> parameters,
                                     ParsingContext context) {
        if (context.getAvailableCommands().isEmpty() || context.isCommandAvailable(toolName)) {
            return toolName;
        }
        // A tool name is written the way an identifier is written, so a command spelled as one word
        // is offered as two: todo_write, multi_read, web-search. Joining them back is only done when
        // the result is a command that exists.
        String joined = NAME_SEPARATOR.matcher(toolName.trim()).replaceAll("");
        if (!joined.equals(toolName) && context.isCommandAvailable(joined)) {
            return joined;
        }
        String[] parts = NAME_SEPARATOR.split(toolName.trim(), 2);
        if (parts.length < 2 || parts[1].isBlank() || !context.isCommandAvailable(parts[0])) {
            return toolName;
        }
        parameters.putIfAbsent("subcommand", parts[1].trim());
        return parts[0];
    }

    /**
     * The prose a reply carried before its first call.
     *
     * <p>Kept as the action's rationale, which is what the ACTION block's {@code REASON:} line
     * carries and what the run log prints beside each step. A notation has nowhere to put one, so
     * the sentence the model wrote before calling is the nearest true thing. Its own thinking block
     * is not that sentence and is left out.</p>
     *
     * @param response the whole reply
     * @return the rationale, empty when the reply is nothing but the call
     */
    private static String reasoningIn(String response) {
        String prose = THINKING_BLOCK.matcher(response).replaceAll(" ");
        int    first = firstMarkerIn(prose);
        if (first >= 0) {
            prose = prose.substring(0, first);
        } else if (startsWithJson(prose)) {
            // The unmarked JSON form, where the reply is the call and nothing else. Without this the
            // call itself would be recorded as the rationale for making it.
            return "";
        }
        String reasoning = prose.replaceAll("\\s+", " ").trim();
        return reasoning.length() <= REASONING_LIMIT ? reasoning
                                                     : reasoning.substring(0, REASONING_LIMIT).trim();
    }

    /**
     * Where a reply stops being prose and starts being a call.
     *
     * <p>Only a marker that opens one of the notations counts. A brace is not one: a sentence may
     * hold any character, and cutting the rationale at the first brace in it would lose the part of
     * the sentence that says why.</p>
     *
     * @param prose the reply with any thinking block removed
     * @return the offset of the first marker, or {@code -1} when the reply carries none
     */
    private static int firstMarkerIn(String prose) {
        int earliest = -1;
        for (String marker : CALL_MARKERS) {
            int at = prose.indexOf(marker);
            if (at >= 0 && (earliest < 0 || at < earliest)) {
                earliest = at;
            }
        }
        return earliest;
    }

    /**
     * @param prose the reply with any thinking block removed
     * @return whether it begins with the JSON of an unmarked call
     */
    private static boolean startsWithJson(String prose) {
        String trimmed = prose.trim();
        return trimmed.startsWith("{") || trimmed.startsWith("[");
    }

    /**
     * @param command    the command the call names
     * @param parameters its arguments
     * @param context    what the engine knows about this turn
     * @return whether the action can be run as written
     */
    private static ParsedAction.ValidationResult validate(String command,
                                                          Map<String, Object> parameters,
                                                          ParsingContext context) {
        if (!context.getAvailableCommands().isEmpty() && !context.isCommandAvailable(command)) {
            return ParsedAction.ValidationResult.INVALID_COMMAND;
        }
        if (FilePathRule.requiresPath(command) && !FilePathRule.isSatisfiedBy(parameters)) {
            return ParsedAction.ValidationResult.INVALID_PARAMETERS;
        }
        return ParsedAction.ValidationResult.VALID;
    }

    /**
     * Scales the base confidence by the validation outcome, as the other structured parsers do: a
     * call that names a command nothing answers to must land below the engine's acceptance threshold
     * so the turn is re-prompted rather than dispatched.
     *
     * @param validation the validation outcome
     * @return the confidence to record on the action
     */
    private static double withValidationApplied(ParsedAction.ValidationResult validation) {
        switch (validation) {
            case INVALID_COMMAND:
                return BASE_CONFIDENCE * 0.3;
            case INVALID_PARAMETERS:
                return BASE_CONFIDENCE * 0.5;
            case SECURITY_RISK:
                return BASE_CONFIDENCE * 0.2;
            case REQUIRES_INFERENCE:
                return BASE_CONFIDENCE * 0.7;
            default:
                return BASE_CONFIDENCE;
        }
    }

    private static String validationMessage(ParsedAction.ValidationResult validation, String command) {
        switch (validation) {
            case INVALID_COMMAND:
                return "Command '" + command + "' is not available";
            case INVALID_PARAMETERS:
                return "Missing or invalid parameters for command '" + command + "'";
            default:
                return null;
        }
    }

    private static double averageConfidence(List<ParsedAction> actions) {
        double total = 0.0;
        for (ParsedAction action : actions) {
            total += action.getConfidence();
        }
        return total / actions.size();
    }

    @Override
    public String getStrategyName() {
        return "Tool Call Parser";
    }

    @Override
    public int getPriority() {
        return 2; // After JSON and the ACTION block, ahead of XML and the inference strategies.
    }

    @Override
    public boolean canHandle(String response) {
        return ToolCalls.appearIn(response);
    }

    @Override
    public double getExpectedConfidence() {
        return BASE_CONFIDENCE;
    }

    /**
     * A call is explicit, never inferred, so it must survive alongside the other structured parses
     * rather than be skipped once one of them has produced actions.
     */
    @Override
    public boolean isFallbackStrategy() {
        return false;
    }

    @Override
    public String getParsingErrorDetails(String response) {
        return "A tool-call notation was recognised but held no usable call. This client reads"
               + " DSML, invoke tags, Harmony channel headers and JSON tool calls, but the format"
               + " it asks for is the ACTION_START/ACTION_END block.";
    }
}
