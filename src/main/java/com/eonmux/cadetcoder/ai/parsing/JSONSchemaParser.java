package com.eonmux.cadetcoder.ai.parsing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * Parsing strategy that handles JSON-formatted AI responses.
 * This is the format the system prompt asks for first.
 * 
 * Expected format:
 * {
 *   "action": "command_name",
 *   "parameters": {
 *     "file_path": "path/to/file",
 *     "additional_param": "value"
 *   },
 *   "reasoning": "Brief explanation",
 *   "confidence": 0.95
 * }
 */
public class JSONSchemaParser implements ParsingStrategy {
    
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    // Fenced code block opener, e.g. "```json" or "```". Used to locate a fenced payload; the JSON
    // itself is then extracted by the brace-balanced scanner rather than by a regex.
    private static final Pattern CODE_FENCE_PATTERN = Pattern.compile(
        "```[ \\t]*[a-zA-Z0-9_+-]*[ \\t]*\\r?\\n",
        Pattern.CASE_INSENSITIVE
    );

    @Override
    public ParsedResponse parse(String response, ParsingContext context) {
        long startTime = System.currentTimeMillis();
        
        if (response == null || response.trim().isEmpty()) {
            return new ParsedResponse.Builder(ParsedResponse.ParseResult.EMPTY_RESPONSE, 
                                            ParsedResponse.ParsingStrategy.JSON_SCHEMA, response)
                .addError("Empty or null response")
                .setProcessingTime(System.currentTimeMillis() - startTime)
                .build();
        }
        
        List<String> jsonCandidates = extractJSONCandidates(response);
        
        for (String jsonText : jsonCandidates) {
            try {
                JsonNode rootNode = objectMapper.readTree(jsonText);
                ParsedResponse result = parseJSONNode(rootNode, response, context, startTime);
                if (result.isSuccessful()) {
                    return result;
                }
            } catch (JsonProcessingException e) {
                // Continue to next candidate
                continue;
            }
        }
        
        return new ParsedResponse.Builder(ParsedResponse.ParseResult.FORMAT_ERROR, 
                                        ParsedResponse.ParsingStrategy.JSON_SCHEMA, response)
            .addError("No valid JSON structure found in response")
            .addError("Expected format: {\"action\": \"command\", \"parameters\": {...}, \"reasoning\": \"...\", \"confidence\": 0.95}")
            .setProcessingTime(System.currentTimeMillis() - startTime)
            .build();
    }
    
    /**
     * Extracts every candidate JSON object from a response, in preference order: objects inside code
     * fences first, then any other top-level object found in the surrounding prose.
     *
     * <p>Extraction is done by a brace-balanced scan that respects string literals and escapes, NOT by
     * a regex. The previous {@code \{[^}]*\}} / {@code \{[^{}]*"action"[^{}]*\}} patterns stopped at the
     * first {@code }}, so the documented shape - which nests a {@code parameters} object - could never
     * be captured; the JSON strategy then "failed" and the response fell through to the fuzzy parser.</p>
     */
    private List<String> extractJSONCandidates(String response) {
        List<String> candidates = new ArrayList<>();

        // First try to find JSON inside code fences (```json ... ```)
        Matcher fenceMatcher = CODE_FENCE_PATTERN.matcher(response);
        while (fenceMatcher.find()) {
            int fenceEnd = response.indexOf("```", fenceMatcher.end());
            String fenced = fenceEnd >= 0
                ? response.substring(fenceMatcher.end(), fenceEnd)
                : response.substring(fenceMatcher.end());
            addBalancedObjects(fenced, candidates);
        }

        // Then any JSON object present in the response as a whole (unfenced, possibly wrapped in prose)
        addBalancedObjects(response, candidates);

        return candidates;
    }

    /**
     * Appends every brace-balanced top-level {@code { ... }} object found in {@code text} to
     * {@code candidates}, skipping duplicates. Braces inside string literals (and escaped quotes) do
     * not affect nesting, so a value such as {@code "content": "a { b }"} cannot truncate the object.
     */
    private void addBalancedObjects(String text, List<String> candidates) {
        if (text == null) {
            return;
        }
        int index = 0;
        int unbalancedAttempts = 0;

        while (index < text.length()) {
            int start = text.indexOf('{', index);
            if (start < 0) {
                return;
            }
            int end = findMatchingBrace(text, start);
            if (end < 0) {
                // An unclosed brace (e.g. one inside prose or a code snippet) must not hide a complete
                // object later in the response, so the scan resumes after it. Bounded, because each
                // retry re-scans the tail.
                if (++unbalancedAttempts > MAX_UNBALANCED_BRACE_RETRIES) {
                    return;
                }
                index = start + 1;
                continue;
            }
            String candidate = text.substring(start, end + 1).trim();
            if (!candidate.isEmpty() && !candidates.contains(candidate)) {
                candidates.add(candidate);
            }
            index = end + 1;
        }
    }

    /** How many unclosed '{' the candidate scan will step over before giving up. */
    private static final int MAX_UNBALANCED_BRACE_RETRIES = 8;

    /**
     * Returns the index of the {@code }} that closes the {@code {} at {@code start}, or {@code -1} if
     * the object is never closed. String literals are honoured: braces and escaped characters inside a
     * double-quoted string are ignored for nesting purposes.
     */
    private int findMatchingBrace(String text, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);

            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }

            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }


    private ParsedResponse parseJSONNode(JsonNode rootNode, String originalResponse, 
                                       ParsingContext context, long startTime) {
        
        ParsedResponse.Builder responseBuilder = new ParsedResponse.Builder(
            ParsedResponse.ParseResult.SUCCESS, 
            ParsedResponse.ParsingStrategy.JSON_SCHEMA, 
            originalResponse
        );
        
        // Determine the action label, preferring 'action' then 'tool' then 'command'. The KEY that
        // supplied it is tracked so that, when a model uses 'command' (or 'tool') as the verb, that same
        // field is not also re-absorbed as a parameter — and conversely a sibling flat 'command' string
        // for a bash action (e.g. {"action":"bash","command":"rm -rf /"}) reaches parameters where the
        // security validator can see it, instead of being mistaken for the verb or silently dropped.
        String actionKey = firstPresentKey(rootNode, "action", "tool", "command");
        if (actionKey == null) {
            return new ParsedResponse.Builder(ParsedResponse.ParseResult.VALIDATION_ERROR,
                                             ParsedResponse.ParsingStrategy.JSON_SCHEMA, originalResponse)
                .addError("Missing required 'action' field")
                .setProcessingTime(System.currentTimeMillis() - startTime)
                .build();
        }

        String command = rootNode.get(actionKey).asText().toLowerCase().trim();
        if (command.isEmpty()) {
            return new ParsedResponse.Builder(ParsedResponse.ParseResult.VALIDATION_ERROR,
                                             ParsedResponse.ParsingStrategy.JSON_SCHEMA, originalResponse)
                .addError("Empty 'action' field")
                .setProcessingTime(System.currentTimeMillis() - startTime)
                .build();
        }

        // Extract parameters from an explicit sub-object if the model nested them...
        Map<String, Object> parameters = new HashMap<>();
        JsonNode paramsNode = firstPresent(rootNode, "parameters", "params");
        if (paramsNode != null && paramsNode.isObject()) {
            parameters = extractParameters(paramsNode);
        }

        // ...then absorb only RECOGNISED parameter fields the model placed flat at the top level (the
        // common real-world shape, e.g. {"action":"read","files":[...]} or {"action":"grep","pattern":
        // "x","path":"src/"}). Restricting to a known key set is deliberate: merging arbitrary keys would
        // let a stray field (a comment, id, note) leak into the positional argument stream and silently
        // corrupt e.g. written file content. A nested sub-object value still wins over a flat duplicate.
        mergeRecognizedTopLevelParameters(rootNode, parameters, actionKey);

        // Collapse the many file aliases a model may use (file, files[], path, paths[]) into the
        // single canonical 'file_path' the downstream command and validator understand, so a flat
        // {"action":"read","files":[...]} validates as a real read instead of INVALID_PARAMETERS.
        normalizeFileAliases(parameters);

        // Extract reasoning
        String reasoning = "";
        if (rootNode.has("reasoning")) {
            reasoning = rootNode.get("reasoning").asText();
        } else if (rootNode.has("reason")) {
            reasoning = rootNode.get("reason").asText();
        }
        
        // Extract confidence
        double confidence = 1.0;
        if (rootNode.has("confidence")) {
            confidence = rootNode.get("confidence").asDouble(1.0);
        }
        
        // Validate command against available commands
        ParsedAction.ValidationResult validation = validateCommand(command, parameters, context);
        String validationMessage = getValidationMessage(validation, command);
        
        // Create parsed action
        ParsedAction action = new ParsedAction.Builder(command)
            .setParameters(parameters)
            .setReasoning(reasoning)
            .setConfidence(confidence)
            .setValidation(validation, validationMessage)
            .addMetadata("parser", "json_schema")
            .addMetadata("json_structure", analyzeJSONStructure(rootNode))
            .build();
        
        // Calculate overall confidence based on structure quality and validation
        double overallConfidence = calculateOverallConfidence(confidence, validation, rootNode);
        
        return responseBuilder
            .addAction(action)
            .setConfidence(overallConfidence)
            .setProcessingTime(System.currentTimeMillis() - startTime)
            .build();
    }
    
    private Map<String, Object> extractParameters(JsonNode paramsNode) {
        Map<String, Object> parameters = new HashMap<>();

        if (paramsNode.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = paramsNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                parameters.put(field.getKey(), convertValue(field.getValue()));
            }
        }

        return parameters;
    }

    /**
     * Converts a JSON value node to the plain Java value it denotes: {@code String}, {@code Integer},
     * {@code Double}, {@code Boolean}, {@code List} for an array, {@code Map} for an object, and
     * {@code null} for JSON null.
     *
     * <p>Structure is preserved rather than flattened. An array used to be reduced with
     * {@code element.asText()}, which Jackson defines as the empty string for a container node -- so
     * the {@code edits} array of a multiedit, an array of objects, arrived as
     * {@code ["", "", ""]} and every edit was lost while the action still reported success. An object
     * fell through to {@code toString()} and became a brace-blob that no consumer could read back.</p>
     *
     * @param valueNode the node to convert
     * @return the converted value, {@code null} for a JSON null node
     */
    private Object convertValue(JsonNode valueNode) {
        if (valueNode == null || valueNode.isNull()) {
            return null;
        }
        if (valueNode.isTextual()) {
            return valueNode.asText();
        }
        if (valueNode.isNumber()) {
            return valueNode.isInt() ? (Object) valueNode.asInt() : (Object) valueNode.asDouble();
        }
        if (valueNode.isBoolean()) {
            return valueNode.asBoolean();
        }
        if (valueNode.isArray()) {
            List<Object> arrayValues = new ArrayList<>();
            for (JsonNode element : valueNode) {
                arrayValues.add(convertValue(element));
            }
            return arrayValues;
        }
        if (valueNode.isObject()) {
            // Insertion-ordered, so a nested object read back out keeps the order the model wrote it
            // in -- which is the order a human reading the recovered action would expect.
            Map<String, Object> objectValues = new java.util.LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = valueNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                objectValues.put(field.getKey(), convertValue(field.getValue()));
            }
            return objectValues;
        }
        return valueNode.asText();
    }

    /**
     * The parameter field names recognised when a model places them flat at the top level (rather than
     * inside a {@code parameters} sub-object). Only these keys are absorbed, so arbitrary/structural
     * fields (action, reasoning, confidence, or a stray comment/id) can never leak into the positional
     * argument stream. Compared lower-case. Covers the parameters used across the command set:
     * file targets, read pagination, grep/glob, write content, bash command, multiedit edits, web.
     */
    private static final java.util.Set<String> RECOGNIZED_PARAM_KEYS = new java.util.HashSet<>(java.util.Arrays.asList(
            "file_path", "filepath", "filename", "file", "files", "path", "paths",
            "limit", "offset", "pattern", "include", "content", "command", "cmd",
            "edits", "query", "url"));

    /** Returns the first present, non-null child node among {@code keys}, or {@code null}. */
    private JsonNode firstPresent(JsonNode node, String... keys) {
        for (String key : keys) {
            if (node.has(key) && !node.get(key).isNull()) {
                return node.get(key);
            }
        }
        return null;
    }

    /** Returns the first key among {@code keys} that is present and non-null on {@code node}, else null. */
    private String firstPresentKey(JsonNode node, String... keys) {
        for (String key : keys) {
            if (node.has(key) && !node.get(key).isNull()) {
                return key;
            }
        }
        return null;
    }

    /**
     * Merges the RECOGNISED top-level parameter fields of {@code rootNode} into {@code parameters}.
     * The field that supplied the action verb ({@code actionKey}) is never absorbed, keys already set
     * from an explicit sub-object are not overwritten, and any field not in {@link #RECOGNIZED_PARAM_KEYS}
     * is ignored — so a flat {@code {"action":"read","files":[...]}} yields a {@code files} parameter
     * while a stray {@code {"action":"write","parameters":{...},"note":"x"}} leaves the params untouched.
     */
    private void mergeRecognizedTopLevelParameters(JsonNode rootNode, Map<String, Object> parameters, String actionKey) {
        Iterator<Map.Entry<String, JsonNode>> fields = rootNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String key = field.getKey();
            if (key.equals(actionKey)
                    || !RECOGNIZED_PARAM_KEYS.contains(key.toLowerCase())
                    || parameters.containsKey(key)) {
                continue;
            }
            parameters.put(key, convertValue(field.getValue()));
        }
    }

    /**
     * Ensures a canonical {@code file_path} parameter exists when the model expressed the target via
     * an alias. Single-value aliases ({@code file}, {@code filename}, {@code filepath}, {@code path})
     * and list aliases ({@code files}, {@code paths} — first non-blank element) are consulted in order.
     * Does nothing when {@code file_path} is already set. A list value is consulted ONLY element-wise:
     * an empty or all-blank list never falls through to a stringified {@code "[]"} path.
     */
    private void normalizeFileAliases(Map<String, Object> parameters) {
        if (hasNonBlankString(parameters.get("file_path"))) {
            return;
        }
        for (String key : new String[] {"file", "filepath", "filename", "path"}) {
            Object value = parameters.get(key);
            if (value instanceof List) {
                String fromList = firstNonBlankElement((List<?>) value);
                if (fromList != null) {
                    parameters.put("file_path", fromList);
                    return;
                }
            } else if (hasNonBlankString(value)) {
                parameters.put("file_path", value.toString().trim());
                return;
            }
        }
        for (String key : new String[] {"files", "paths"}) {
            Object value = parameters.get(key);
            if (value instanceof List) {
                String fromList = firstNonBlankElement((List<?>) value);
                if (fromList != null) {
                    parameters.put("file_path", fromList);
                    return;
                }
            } else if (hasNonBlankString(value)) {
                parameters.put("file_path", value.toString().trim());
                return;
            }
        }
    }

    /** First non-blank element of a list as a trimmed string, or {@code null} if none. */
    private String firstNonBlankElement(List<?> list) {
        for (Object element : list) {
            if (hasNonBlankString(element)) {
                return element.toString().trim();
            }
        }
        return null;
    }

    private boolean hasNonBlankString(Object value) {
        return value != null && !value.toString().trim().isEmpty();
    }

    private ParsedAction.ValidationResult validateCommand(String command, Map<String, Object> parameters, 
                                                        ParsingContext context) {
        // Check if command is available
        if (!context.getAvailableCommands().isEmpty() && !context.isCommandAvailable(command)) {
            return ParsedAction.ValidationResult.INVALID_COMMAND;
        }
        
        // Basic parameter validation
        if (FilePathRule.requiresPath(command) && !FilePathRule.isSatisfiedBy(parameters)) {
            return ParsedAction.ValidationResult.INVALID_PARAMETERS;
        }
        
        // Security checks for potentially dangerous commands
        if (isDangerousCommand(command, parameters)) {
            return ParsedAction.ValidationResult.SECURITY_RISK;
        }
        
        return ParsedAction.ValidationResult.VALID;
    }
    
    /**
     * Whether this action proposes running a verb the tool refuses to run.
     *
     * <p>The denylist is {@link com.eonmux.cadetcoder.security.DangerousCommands}, which is where it
     * has lived since two other classes were found keeping private copies of it. This was a third:
     * four substring tests -- {@code "rm "}, {@code "del "}, {@code "format"}, {@code "fdisk"} --
     * that knew nothing of {@code dd}, {@code mkfs}, {@code chmod}, {@code shutdown} or any of the
     * rest, and that flagged {@code git format-patch} because its name contains {@code format}.
     * Which verbs the shell runner answers to is {@link ParsedAction}'s question for the same
     * reason: this copy knew {@code bash} and {@code execute}, and {@code run} went unscreened.</p>
     */
    private boolean isDangerousCommand(String command, Map<String, Object> parameters) {
        return com.eonmux.cadetcoder.security.DangerousCommands.isReferencedBy(
                ParsedAction.shellCommandLineOf(command, parameters));
    }
    
    private String getValidationMessage(ParsedAction.ValidationResult validation, String command) {
        switch (validation) {
            case INVALID_COMMAND:
                return "Command '" + command + "' is not available";
            case INVALID_PARAMETERS:
                return "Missing or invalid parameters for command '" + command + "'";
            case SECURITY_RISK:
                return "Command '" + command + "' poses potential security risks";
            case PATH_ERROR:
                return "Invalid file path specified";
            default:
                return null;
        }
    }
    
    private String analyzeJSONStructure(JsonNode rootNode) {
        StringBuilder analysis = new StringBuilder();
        analysis.append("Fields present: ");
        
        Iterator<String> fieldNames = rootNode.fieldNames();
        while (fieldNames.hasNext()) {
            analysis.append(fieldNames.next()).append(" ");
        }
        
        return analysis.toString().trim();
    }
    
    private double calculateOverallConfidence(double declaredConfidence, 
                                            ParsedAction.ValidationResult validation, 
                                            JsonNode rootNode) {
        double confidence = declaredConfidence;
        
        // Reduce confidence for validation issues
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
        }
        
        // Boost confidence for complete JSON structure
        if (rootNode.has("action") && rootNode.has("parameters") && 
            rootNode.has("reasoning") && rootNode.has("confidence")) {
            confidence = Math.min(1.0, confidence * 1.1);
        }
        
        return Math.max(0.0, Math.min(1.0, confidence));
    }
    
    @Override
    public String getStrategyName() {
        return "JSON Schema Parser";
    }
    
    @Override
    public int getPriority() {
        return 0; // Highest priority
    }
    
    @Override
    public boolean canHandle(String response) {
        return response != null && 
               (response.contains("\"action\"") || 
                response.contains("```json") ||
                (response.trim().startsWith("{") && response.trim().endsWith("}")));
    }
    
    @Override
    public double getExpectedConfidence() {
        return 0.9; // High confidence for well-structured JSON
    }
    
    @Override
    public String getParsingErrorDetails(String response) {
        return "Failed to parse JSON structure. Expected format:\n" +
               "{\n" +
               "  \"action\": \"command_name\",\n" +
               "  \"parameters\": {\n" +
               "    \"file_path\": \"path/to/file\",\n" +
               "    \"additional_param\": \"value\"\n" +
               "  },\n" +
               "  \"reasoning\": \"Brief explanation\",\n" +
               "  \"confidence\": 0.95\n" +
               "}";
    }
    
    @Override
    public String[] getExampleFormats() {
        return new String[] {
            "{\n  \"action\": \"read\",\n  \"parameters\": {\"file_path\": \"src/Main.java\"},\n  \"reasoning\": \"Read the main class\",\n  \"confidence\": 0.95\n}",
            "{\n  \"action\": \"grep\",\n  \"parameters\": {\"pattern\": \"class.*\", \"path\": \"src/\"},\n  \"reasoning\": \"Find class definitions\",\n  \"confidence\": 0.9\n}",
            "{\n  \"action\": \"write\",\n  \"parameters\": {\"file_path\": \"output.txt\", \"content\": \"Hello World\"},\n  \"reasoning\": \"Create output file\",\n  \"confidence\": 0.95\n}"
        };
    }
}