package com.eonmux.cadetcoder.ai.parsing;

import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * Parsing strategy that handles the legacy ACTION_START/ACTION_END format.
 * This format is maintained for backward compatibility.
 *
 * Expected format:
 * ACTION_START
 * COMMAND: command_name
 * ARGS: arg1, arg2, arg3
 * REASON: Brief explanation
 * ACTION_END
 *
 * <p>An argument payload may also span several lines - either as the continuation lines that follow
 * {@code ARGS:} (everything up to the next structural label belongs to the payload), or inside an
 * explicit {@code ARGS_BEGIN}/{@code ARGS_END} block when the payload itself contains lines that look
 * like labels. Multi-line payloads are what make {@code write} content and {@code multiedit} edit
 * blocks expressible at all; the single-line form is unchanged and still parses exactly as before.</p>
 */
public class ActionBlockParser implements ParsingStrategy {

    /**
     * Line-leading labels that end a multi-line {@code ARGS:} payload. A payload runs until the next
     * one of these (or the end of the block), which is what allows an argument value to span lines
     * without swallowing the rest of the response - critically, without swallowing a following
     * {@code ACTION_END}/{@code ACTION_START} pair and merging two blocks into one.
     */
    private static final String ARGS_TERMINATOR =
        "(?:REASON|REASONING|WHY|COMMAND|CMD|ARGS|ARGUMENTS|PARAMS)\\s*:|ACTION_(?:START|END)|ARGS_(?:BEGIN|END)";

    /** Payload of an {@code ARGS:} field: any text up to the next structural label line. */
    private static final String INLINE_ARGS_PAYLOAD =
        "((?:(?!\\r?\\n[ \\t]*(?:" + ARGS_TERMINATOR + ")).)*)";

    /** Payload of an {@code ARGS_BEGIN}/{@code ARGS_END} block: any text up to the closing marker. */
    private static final String DELIMITED_ARGS_PAYLOAD =
        "((?:(?!\\r?\\n[ \\t]*ARGS_END).)*)";

    // Strict block. Group 1 = command, group 2 = ARGS_BEGIN payload (if used), group 3 = ARGS: payload
    // (if used), group 4 = reason.
    private static final Pattern ACTION_BLOCK_PATTERN = Pattern.compile(
        "ACTION_START\\s*\\n" +
        "COMMAND:\\s*([^\\n]+)\\s*\\n" +
        "(?:ARGS_BEGIN[ \\t]*\\r?\\n" + DELIMITED_ARGS_PAYLOAD + "\\r?\\n[ \\t]*ARGS_END" +
        "|ARGS:[ \\t]*" + INLINE_ARGS_PAYLOAD + ")\\s*\\n" +
        "REASON:\\s*([^\\n]+)\\s*\\n" +
        "ACTION_END",
        Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL
    );

    // More flexible pattern for malformed blocks
    private static final Pattern FLEXIBLE_ACTION_PATTERN = Pattern.compile(
        "ACTION_START\\s*(.+?)\\s*ACTION_END",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    // Individual field patterns for flexible parsing
    private static final Pattern COMMAND_PATTERN = Pattern.compile(
        "(?:COMMAND|CMD):\\s*([^\\n]+)",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern ARGS_PATTERN = Pattern.compile(
        "(?:ARGS|ARGUMENTS|PARAMS):[ \\t]*" + INLINE_ARGS_PAYLOAD,
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    private static final Pattern DELIMITED_ARGS_PATTERN = Pattern.compile(
        "ARGS_BEGIN[ \\t]*\\r?\\n" + DELIMITED_ARGS_PAYLOAD + "\\r?\\n[ \\t]*ARGS_END",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    /** The spelling a key must have for the {@code key=value, key=value} form to apply. */
    private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]*");

    private static final Pattern REASON_PATTERN = Pattern.compile(
        "(?:REASON|REASONING|WHY):\\s*([^\\n]+)",
        Pattern.CASE_INSENSITIVE
    );

    /** Confidence for a block that matched the strict, fully-labelled format. */
    private static final double STRICT_BASE_CONFIDENCE = 0.9;

    /** Confidence for a block recovered by the flexible field-by-field parser. */
    private static final double FLEXIBLE_BASE_CONFIDENCE = 0.8;

    /**
     * Multiplier applied when a block carries no {@code REASON:}. A missing rationale is a cosmetic
     * defect, not a parse failure: the block still says exactly which command to run with which
     * arguments, so it must stay ABOVE the engine's acceptance threshold. Scoring it below (as the old
     * flat 0.5 did) is what let the semantic parser re-interpret a valid {@code read} block as a
     * {@code bash} execution of prose.
     */
    private static final double MISSING_REASON_MULTIPLIER = 0.95;

    @Override
    public ParsedResponse parse(String response, ParsingContext context) {
        long startTime = System.currentTimeMillis();
        
        if (response == null || response.trim().isEmpty()) {
            return new ParsedResponse.Builder(ParsedResponse.ParseResult.EMPTY_RESPONSE, 
                                            ParsedResponse.ParsingStrategy.ACTION_BLOCK, response)
                .addError("Empty or null response")
                .setProcessingTime(System.currentTimeMillis() - startTime)
                .build();
        }
        
        List<ParsedAction> actions = new ArrayList<>();
        
        // First try strict pattern matching
        Matcher strictMatcher = ACTION_BLOCK_PATTERN.matcher(response);
        while (strictMatcher.find()) {
            ParsedAction action = parseStrictActionBlock(strictMatcher, context);
            if (action != null) {
                actions.add(action);
            }
        }
        
        // If no strict matches found, try flexible parsing
        if (actions.isEmpty()) {
            actions.addAll(parseFlexibleActionBlocks(response, context));
        }
        
        if (actions.isEmpty()) {
            return new ParsedResponse.Builder(ParsedResponse.ParseResult.FORMAT_ERROR, 
                                            ParsedResponse.ParsingStrategy.ACTION_BLOCK, response)
                .addError("No valid ACTION_START/ACTION_END blocks found")
                .addError("Expected format: ACTION_START\\nCOMMAND: command\\nARGS: args\\nREASON: reason\\nACTION_END")
                .setProcessingTime(System.currentTimeMillis() - startTime)
                .build();
        }
        
        // Calculate overall confidence
        double overallConfidence = calculateOverallConfidence(actions);
        
        ParsedResponse.Builder responseBuilder = new ParsedResponse.Builder(
            ParsedResponse.ParseResult.SUCCESS, 
            ParsedResponse.ParsingStrategy.ACTION_BLOCK, 
            response
        );
        
        for (ParsedAction action : actions) {
            responseBuilder.addAction(action);
        }
        
        return responseBuilder
            .setConfidence(overallConfidence)
            .setProcessingTime(System.currentTimeMillis() - startTime)
            .build();
    }
    
    private ParsedAction parseStrictActionBlock(Matcher matcher, ParsingContext context) {
        String command = verbIn(matcher.group(1));
        // Group 2 is the ARGS_BEGIN/ARGS_END payload, group 3 the single "ARGS:" payload; exactly one
        // of the two alternatives matched.
        String argsStr = matcher.group(2) != null ? matcher.group(2).trim()
                                                  : matcher.group(3).trim();
        argsStr = whatTheVerbLineAlsoCarried(matcher.group(1), argsStr);
        String reasoning = matcher.group(4).trim();

        if (command.isEmpty() || reasoning.isEmpty()) {
            return null;
        }

        // Parse arguments into parameters
        Map<String, Object> parameters = parseArguments(argsStr, command);

        // Validate the action
        ParsedAction.ValidationResult validation = validateAction(command, parameters, context);
        String validationMessage = getValidationMessage(validation, command);

        return new ParsedAction.Builder(command)
            .setParameters(parameters)
            .setReasoning(reasoning)
            .setConfidence(applyValidationPenalty(STRICT_BASE_CONFIDENCE, validation))
            .setValidation(validation, validationMessage)
            .addMetadata("parser", "action_block_strict")
            .addMetadata("args_string", argsStr)
            .build();
    }
    
    private List<ParsedAction> parseFlexibleActionBlocks(String response, ParsingContext context) {
        List<ParsedAction> actions = new ArrayList<>();
        
        Matcher flexibleMatcher = FLEXIBLE_ACTION_PATTERN.matcher(response);
        while (flexibleMatcher.find()) {
            String blockContent = flexibleMatcher.group(1);
            ParsedAction action = parseFlexibleBlock(blockContent, context);
            if (action != null) {
                actions.add(action);
            }
        }
        
        return actions;
    }
    
    /**
     * The command a COMMAND line names.
     *
     * <h2>Why the line is not the name</h2>
     *
     * <p>The whole line used to be lower-cased and looked up as a command. The catalogue teaches
     * {@code job start}, {@code job output} and {@code job wait} as the names of things to do, and
     * every example of them is two words -- so a model writing {@code COMMAND: job start} named a
     * command that does not exist, the block was refused as a format error, and after two retries
     * the run ended. No command has a space in its name, so the first word is the command and what
     * follows it is an argument that was written a line early.</p>
     *
     * <p>Only the verb is lower-cased. The rest keeps the case it was written in, because it is as
     * likely to be {@code README.md} as {@code start}.</p>
     *
     * @param commandLine the COMMAND field as it was written
     * @return the command name, lower-cased
     */
    private static String verbIn(String commandLine) {
        String line  = commandLine.trim();
        int    space = firstSpaceIn(line);
        return (space < 0 ? line : line.substring(0, space)).toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * The arguments, with anything the verb line carried past the verb put in front of them.
     *
     * <h2>Why words the arguments already begin with are not added again</h2>
     *
     * <p>A model wrote {@code COMMAND: job start} and then, under it, the several-line form that
     * begins with {@code start}. Joined, the arguments began {@code start start}, and the second
     * {@code start} was run as a job of its own. What the verb line carries is an argument written a
     * line early; when the arguments repeat it, it was written twice, not meant twice.</p>
     *
     * @param commandLine the COMMAND field as it was written
     * @param argsStr     the ARGS payload
     * @return the payload to read, which is the ARGS payload when the verb line held only the verb
     *         or the payload already begins with what it carried
     */
    private static String whatTheVerbLineAlsoCarried(String commandLine, String argsStr) {
        String line  = commandLine.trim();
        int    space = firstSpaceIn(line);
        if (space < 0) {
            return argsStr;
        }
        String carried = line.substring(space + 1).trim();
        if (carried.isEmpty()) {
            return argsStr;
        }
        if (argsStr.isEmpty()) {
            return carried;
        }
        boolean repeated = argsStr.startsWith(carried)
                           && (argsStr.length() == carried.length()
                               || Character.isWhitespace(argsStr.charAt(carried.length())));
        return repeated ? argsStr : carried + " " + argsStr;
    }

    /** @return where the first run of whitespace begins, or {@code -1} when there is none */
    private static int firstSpaceIn(String line) {
        for (int i = 0; i < line.length(); i++) {
            if (Character.isWhitespace(line.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Whether an {@code ARGS:} payload stopped at a label that belongs inside it.
     *
     * <p>A payload runs to the next structural label, which is what lets it span lines. A file
     * being written can contain such a line -- {@code COMMAND: the verb} in a document about this
     * tool is the obvious one -- and the payload then ends there, with the rest of the content
     * read as the block's own fields. The file was written truncated and the action reported
     * success, which is the worst of the three things that could happen.</p>
     *
     * <p>A reason label is the ordinary end of a payload and means nothing is wrong. Any other one
     * means the block cannot be read as written, and the model is told to delimit it instead.</p>
     *
     * @param blockContent the whole block
     * @param payloadEnd   where the payload match ended
     * @return whether what follows is a label that should have been part of the payload
     */
    private static boolean wasCutShort(String blockContent, int payloadEnd) {
        String rest = blockContent.substring(payloadEnd);
        Matcher label = Pattern.compile("^\\s*(?:" + ARGS_TERMINATOR + ")",
                                        Pattern.CASE_INSENSITIVE).matcher(rest);
        if (!label.find()) {
            return false;
        }
        String found = label.group().trim().toUpperCase(java.util.Locale.ROOT);
        return !(found.startsWith("REASON") || found.startsWith("WHY")
                 || found.startsWith("ACTION_END"));
    }

    /** The refusal a block whose payload was cut short is reported as. */
    private ParsedAction truncatedPayload(String command, String blockContent) {
        return new ParsedAction.Builder(command)
            .setReasoning("")
            .setConfidence(0.0)
            .setValidation(ParsedAction.ValidationResult.INVALID_PARAMETERS,
                    "The ARGS payload contains a line that reads as one of this format's own "
                    + "labels, so it cannot be told apart from the end of the payload. Send it "
                    + "between ARGS_BEGIN and ARGS_END instead.")
            .addMetadata("parser", "action_block_flexible")
            .addMetadata("block_content", blockContent)
            .build();
    }

    private ParsedAction parseFlexibleBlock(String blockContent, ParsingContext context) {
        // Extract command
        Matcher commandMatcher = COMMAND_PATTERN.matcher(blockContent);
        if (!commandMatcher.find()) {
            return null; // Command is required
        }
        String command = verbIn(commandMatcher.group(1));

        // Extract arguments (optional). An explicit ARGS_BEGIN/ARGS_END block wins over the ARGS:
        // label so a payload containing label-like lines survives intact.
        String argsStr = "";
        Matcher delimitedMatcher = DELIMITED_ARGS_PATTERN.matcher(blockContent);
        if (delimitedMatcher.find()) {
            argsStr = delimitedMatcher.group(1).trim();
        } else {
            Matcher argsMatcher = ARGS_PATTERN.matcher(blockContent);
            if (argsMatcher.find()) {
                argsStr = argsMatcher.group(1).trim();
                if (wasCutShort(blockContent, argsMatcher.end())) {
                    return truncatedPayload(command, blockContent);
                }
            }
        }

        argsStr = whatTheVerbLineAlsoCarried(commandMatcher.group(1), argsStr);

        // Extract reasoning (optional, but preferred)
        String reasoning = "";
        Matcher reasonMatcher = REASON_PATTERN.matcher(blockContent);
        if (reasonMatcher.find()) {
            reasoning = reasonMatcher.group(1).trim();
        }

        // Parse arguments into parameters
        Map<String, Object> parameters = parseArguments(argsStr, command);

        // Validate the action
        ParsedAction.ValidationResult validation = validateAction(command, parameters, context);
        String validationMessage = getValidationMessage(validation, command);

        // Confidence is DERIVED from the validation result (as JSONSchemaParser already does) rather
        // than from the presence of prose: a block naming an available command with usable parameters
        // is trustworthy even without a REASON line, while one whose parameters are missing must fall
        // below the engine's acceptance threshold.
        double confidence = FLEXIBLE_BASE_CONFIDENCE;
        if (reasoning.isEmpty()) {
            confidence *= MISSING_REASON_MULTIPLIER;
        }
        confidence = applyValidationPenalty(confidence, validation);

        return new ParsedAction.Builder(command)
            .setParameters(parameters)
            .setReasoning(reasoning)
            .setConfidence(confidence)
            .setValidation(validation, validationMessage)
            .addMetadata("parser", "action_block_flexible")
            .addMetadata("args_string", argsStr)
            .addMetadata("block_content", blockContent)
            .build();
    }
    
    /**
     * The parameters an ACTION block's fields amount to, for a reader outside the parse itself.
     *
     * <h2>Why the recovery path asks this class</h2>
     *
     * <p>{@link ErrorRecoveryManager} used to read a block's fields with a loop of its own that
     * took the first line after {@code ARGS:} and stopped. Every block whose payload spans lines --
     * the content of a file being written, a diff, an edit -- was recovered truncated to its first
     * line and then run, so a recovery could silently write a one-line file. The payload rules
     * belong here, with the format they describe, and there is now one reader for them.</p>
     *
     * @param blockContent the text between {@code ACTION_START} and {@code ACTION_END}
     * @param command      the verb the block named
     * @return the parameters, empty when the block carries no arguments
     */
    static Map<String, Object> argumentsIn(String blockContent, String command) {
        String  argsStr   = "";
        Matcher delimited = DELIMITED_ARGS_PATTERN.matcher(blockContent);
        if (delimited.find()) {
            argsStr = delimited.group(1).trim();
        } else {
            Matcher inline = ARGS_PATTERN.matcher(blockContent);
            if (inline.find()) {
                argsStr = inline.group(1).trim();
            }
        }
        return parseArguments(argsStr, command);
    }

    private static Map<String, Object> parseArguments(String argsStr, String command) {
        Map<String, Object> parameters = new HashMap<>();
        
        if (argsStr.isEmpty()) {
            return parameters;
        }
        
        // Handle different argument formats
        // Check if this looks like shell-style arguments (contains quotes, spaces, or starts with -)
        boolean isShellStyle = argsStr.contains("\"") || argsStr.contains("'") ||
                              argsStr.contains(" -") || argsStr.startsWith("-") ||
                              argsStr.matches(".*\\s+--\\w+.*");

        // A multi-line payload is always positional: the "key=value, key=value" form is single-line by
        // construction, and treating a multi-line body as key/value pairs would shred file content.
        if (!isShellStyle && !argsStr.contains("\n") && everyPartIsAPair(argsStr)) {
            // Key-value format: file_path=src/Main.java, limit=50
            parseKeyValueArgs(argsStr, parameters);
        } else {
            // Positional/shell format: "SessionManager" --include=*.java
            //
            // Read as the command it will be DISPATCHED to, not as the word the model wrote. The
            // two differ for every synonym, and ActionArguments writes the argv from the canonical
            // one -- so `list src -R` was read into arg0/arg1 by the default arm and then written
            // by the `ls` arm, which looks for a path and a recursive flag and found neither. The
            // action dispatched with no arguments at all, listed the wrong directory, and reported
            // success.
            ActionParameters.fromPositional(argsStr,
                    com.eonmux.cadetcoder.commands.CommandAliases.canonicalize(command),
                    parameters);
        }
        
        return parameters;
    }
    
    /**
     * <h2>Why a bare comma is not enough to read the payload as pairs</h2>
     * The key/value form is {@code key=value, key=value}: every comma separates one pair from the
     * next. A payload where only the first part is a pair is prose with a comma in it, and
     * {@code parseKeyValueArgs} drops every part it cannot split -- so
     * {@code message=fix: nulls, and empties} committed with the message {@code fix: nulls}. The
     * payload is read as pairs only when each part is one, and falls back to positional otherwise.
     */
    private static boolean everyPartIsAPair(String argsStr) {
        if (!argsStr.contains(",")) {
            return false;
        }
        String[] parts = argsStr.split(",", -1);
        for (String part : parts) {
            int equals = part.indexOf('=');
            if (equals <= 0) {
                return false;
            }
            if (!KEY_PATTERN.matcher(part.substring(0, equals).trim()).matches()) {
                return false;
            }
        }
        return true;
    }

    private static void parseKeyValueArgs(String argsStr, Map<String, Object> parameters) {
        String[] pairs = argsStr.split(",");
        for (String pair : pairs) {
            String[] keyValue = pair.split("=", 2);
            if (keyValue.length == 2) {
                String key = keyValue[0].trim();
                String value = keyValue[1].trim();
                
                // Remove quotes if present
                if ((value.startsWith("\"") && value.endsWith("\"")) ||
                    (value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                
                // Try to parse as number if possible
                try {
                    if (value.contains(".")) {
                        parameters.put(key, Double.parseDouble(value));
                    } else {
                        parameters.put(key, Integer.parseInt(value));
                    }
                } catch (NumberFormatException e) {
                    // Store as string
                    parameters.put(key, value);
                }
            }
        }
    }
    
    
    private ParsedAction.ValidationResult validateAction(String command, Map<String, Object> parameters, 
                                                       ParsingContext context) {
        // Check if command is available
        if (!context.getAvailableCommands().isEmpty() && !context.isCommandAvailable(command)) {
            return ParsedAction.ValidationResult.INVALID_COMMAND;
        }
        
        // Basic parameter validation
        if (FilePathRule.requiresPath(command) && !FilePathRule.isSatisfiedBy(parameters)) {
            return ParsedAction.ValidationResult.INVALID_PARAMETERS;
        }
        
        return ParsedAction.ValidationResult.VALID;
    }
    
    /**
     * Scales a block's base confidence by its validation outcome, mirroring
     * {@code JSONSchemaParser#calculateOverallConfidence}. A structurally fine block that names an
     * unknown command or lacks required parameters must land BELOW the engine's acceptance threshold
     * so it is re-prompted; a valid one keeps its base score so it is never displaced by a guess.
     *
     * @param baseConfidence confidence earned by the block's structure
     * @param validation     the validation outcome for the parsed action
     * @return the adjusted confidence, clamped to [0.0, 1.0]
     */
    private double applyValidationPenalty(double baseConfidence, ParsedAction.ValidationResult validation) {
        double confidence = baseConfidence;

        switch (validation) {
            case INVALID_COMMAND:
                confidence *= 0.3;
                break;
            case INVALID_PARAMETERS:
                confidence *= 0.5;
                break;
            case SECURITY_RISK:
                confidence *= 0.2;
                break;
            case REQUIRES_INFERENCE:
                confidence *= 0.7;
                break;
            default:
                break;
        }

        return Math.max(0.0, Math.min(1.0, confidence));
    }

    private String getValidationMessage(ParsedAction.ValidationResult validation, String command) {
        switch (validation) {
            case INVALID_COMMAND:
                return "Command '" + command + "' is not available";
            case INVALID_PARAMETERS:
                return "Missing or invalid parameters for command '" + command + "'";
            default:
                return null;
        }
    }
    
    private double calculateOverallConfidence(List<ParsedAction> actions) {
        if (actions.isEmpty()) return 0.0;
        
        double total = 0.0;
        for (ParsedAction action : actions) {
            total += action.getConfidence();
        }
        return total / actions.size();
    }
    
    @Override
    public String getStrategyName() {
        return "ACTION Block Parser";
    }
    
    @Override
    public int getPriority() {
        return 1; // Second priority after JSON
    }
    
    @Override
    public boolean canHandle(String response) {
        return response != null && 
               response.contains("ACTION_START") && 
               response.contains("ACTION_END");
    }
    
    @Override
    public double getExpectedConfidence() {
        return 0.8; // Good confidence for structured blocks
    }
    
    @Override
    public String getParsingErrorDetails(String response) {
        return "Failed to parse ACTION block format. Expected format:\n" +
               "ACTION_START\n" +
               "COMMAND: command_name\n" +
               "ARGS: arg1, arg2, arg3\n" +
               "REASON: Brief explanation\n" +
               "ACTION_END";
    }
    
    @Override
    public String[] getExampleFormats() {
        return new String[] {
            "ACTION_START\nCOMMAND: read\nARGS: src/Main.java\nREASON: Read the main class file\nACTION_END",
            "ACTION_START\nCOMMAND: grep\nARGS: pattern=class.*, path=src/\nREASON: Find class definitions\nACTION_END",
            "ACTION_START\nCOMMAND: bash\nARGS: ls -la\nREASON: List directory contents\nACTION_END",
            // Multi-line payload: the file path on the ARGS line, the content on the lines after it.
            "ACTION_START\nCOMMAND: write\nARGS: notes.md\nfirst line\nsecond line\nREASON: Save the notes\nACTION_END"
        };
    }
}