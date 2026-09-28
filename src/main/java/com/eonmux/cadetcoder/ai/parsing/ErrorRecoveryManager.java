package com.eonmux.cadetcoder.ai.parsing;

import com.eonmux.cadetcoder.logging.DebugLogger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * Manages error recovery strategies for failed parsing attempts.
 * Implements learning and adaptation to improve parsing success rates over time.
 */
public class ErrorRecoveryManager {
    
    /**
     * Renders every recovered action, so that no recovery can be lost to a quote in the text it
     * recovered.
     *
     * <p>These strategies used to interpolate the model's own words into a JSON string with
     * {@code String.format}. An ARGS line of {@code grep "TODO" --include="*.java"} closed the JSON
     * string four characters in and the recovered response failed to parse -- the parse that was
     * supposed to rescue the failed one. A Windows path did it silently, {@code \s} not being a JSON
     * escape at all.</p>
     *
     * <p>{@code ObjectMapper} is safe to share between threads once configured.</p>
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DebugLogger debugLogger;
    private final Map<String, Integer> errorPatterns = new ConcurrentHashMap<>();
    private final Map<String, String> successfulRecoveries = new ConcurrentHashMap<>();
    private final List<RecoveryStrategy> recoveryStrategies;
    
    public ErrorRecoveryManager() {
        this.debugLogger = DebugLogger.getInstance();
        this.recoveryStrategies = initializeRecoveryStrategies();
        
        debugLogger.debug("ErrorRecoveryManager", 
            "Initialized with " + recoveryStrategies.size() + " recovery strategies");
    }
    
    private List<RecoveryStrategy> initializeRecoveryStrategies() {
        List<RecoveryStrategy> strategies = new ArrayList<>();
        
        // Add recovery strategies in order of preference
        strategies.add(new JsonCleanupStrategy());
        strategies.add(new ActionBlockExtractionStrategy());
        strategies.add(new StructureInferenceStrategy());
        strategies.add(new FallbackPatternStrategy());
        
        return strategies;
    }
    
    /**
     * Attempts to recover from a parsing failure using various strategies.
     * 
     * @param originalResponse The response that failed to parse
     * @param context The parsing context
     * @param originalError The original parsing error
     * @return RecoveryResult with success status and potentially recovered content
     */
    public RecoveryResult attemptRecovery(String originalResponse, ParsingContext context, 
                                        String originalError) {
        debugLogger.debug("ErrorRecoveryManager", 
            "Attempting recovery for parsing failure: " + originalError);
        
        // Track this error pattern for learning
        trackErrorPattern(originalError);
        
        // Try each recovery strategy
        for (RecoveryStrategy strategy : recoveryStrategies) {
            try {
                RecoveryResult result = strategy.recover(originalResponse, context, originalError);
                
                if (result.isSuccess()) {
                    debugLogger.info("ErrorRecoveryManager", 
                        "Recovery produced a candidate using: " + strategy.getStrategyName());
                    // NOT learned from here. A strategy "succeeding" means it produced text; whether
                    // that text parses into something worth running is decided by the caller, after
                    // this returns. Recorded here, a strategy whose output was then discarded as too
                    // low-confidence was remembered as the cure for that error -- and the
                    // last-resort strategy, which always produces something and whose confidence of
                    // 0.1 can never be accepted, was recorded for every error that reached it. The
                    // statistics could report a 100% recovery rate having recovered nothing.
                    return result;
                }
            } catch (Exception e) {
                debugLogger.warn("ErrorRecoveryManager", 
                    "Recovery strategy failed: " + strategy.getStrategyName() + " - " + e.getMessage());
            }
        }
        
        debugLogger.warn("ErrorRecoveryManager", "All recovery strategies failed");
        return RecoveryResult.failure("All recovery strategies failed");
    }
    
    private void trackErrorPattern(String error) {
        if (error == null) return;
        
        // Normalize error message to extract patterns
        String pattern = normalizeErrorMessage(error);
        errorPatterns.merge(pattern, 1, Integer::sum);
        
        debugLogger.debug("ErrorRecoveryManager", 
            "Tracked error pattern: " + pattern + " (count: " + errorPatterns.get(pattern) + ")");
    }
    
    /**
     * Records a recovery whose result was actually used.
     *
     * <p>Called by whoever accepted it, because that is where acceptance happens: this class can
     * only say what a strategy produced, not whether it turned out to be usable.</p>
     *
     * @param originalError the failure that prompted the recovery
     * @param strategyName  the strategy whose output was accepted
     */
    public void recordAccepted(String originalError, String strategyName) {
        String pattern = normalizeErrorMessage(originalError);
        successfulRecoveries.put(pattern, strategyName);

        debugLogger.debug("ErrorRecoveryManager",
            "Learned successful recovery: " + pattern + " -> " + strategyName);
    }
    
    private String normalizeErrorMessage(String error) {
        if (error == null) return "unknown";
        
        // Extract key patterns from error messages
        String normalized = error.toLowerCase()
            .replaceAll("\\d+", "N")  // Replace numbers with N
            .replaceAll("'[^']*'", "'X'")  // Replace quoted strings with 'X'
            .replaceAll("\"[^\"]*\"", "\"X\"")  // Replace quoted strings with "X"
            .replaceAll("\\s+", " ")  // Normalize whitespace
            .trim();
        
        return normalized;
    }
    
    /**
     * Gets suggestions for improving AI responses based on common error patterns.
     */
    public List<String> getSuggestions() {
        List<String> suggestions = new ArrayList<>();
        
        // Analyze common error patterns and provide suggestions
        for (Map.Entry<String, Integer> entry : errorPatterns.entrySet()) {
            String pattern = entry.getKey();
            int count = entry.getValue();
            
            if (count >= 3) {  // Only suggest for frequently occurring errors
                String suggestion = generateSuggestion(pattern, count);
                if (suggestion != null) {
                    suggestions.add(suggestion);
                }
            }
        }
        
        return suggestions;
    }
    
    private String generateSuggestion(String errorPattern, int count) {
        if (errorPattern.contains("json") && errorPattern.contains("parse")) {
            return String.format("JSON parsing failed %d times. Consider using structured JSON format: {\"action\": \"command\", \"parameters\": {...}}", count);
        }
        
        if (errorPattern.contains("action") && errorPattern.contains("missing")) {
            return String.format("Missing action field failed %d times. Always include 'action' field in responses", count);
        }
        
        if (errorPattern.contains("format") && errorPattern.contains("error")) {
            return String.format("Format errors occurred %d times. Use ACTION_START/ACTION_END blocks or valid JSON", count);
        }
        
        return null;
    }
    
    /**
     * Gets recovery statistics for monitoring and debugging.
     */
    public RecoveryStatistics getStatistics() {
        Map<String, Integer> errorCounts = new HashMap<>(errorPatterns);
        Map<String, String> recoveries = new HashMap<>(successfulRecoveries);
        
        int totalErrors = errorCounts.values().stream().mapToInt(Integer::intValue).sum();
        int recoveredErrors = recoveries.size();
        
        return new RecoveryStatistics(errorCounts, recoveries, totalErrors, recoveredErrors);
    }
    
    /**
     * Renders a recovered action in the structured form the JSON parser reads back.
     *
     * @param command    the command verb
     * @param parameters the parameters, entries with a null value omitted
     * @param reasoning  why the action was recovered
     * @param confidence how much the recovering strategy trusts it
     * @return valid JSON, whatever characters the recovered text happens to contain
     */
    private static String actionJson(String command, Map<String, ?> parameters,
                                     String reasoning, double confidence) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("action", command);

        ObjectNode parameterNode = root.putObject("parameters");
        for (Map.Entry<String, ?> parameter : parameters.entrySet()) {
            if (parameter.getValue() != null) {
                parameterNode.set(parameter.getKey(), MAPPER.valueToTree(parameter.getValue()));
            }
        }

        root.put("reasoning", reasoning);
        root.put("confidence", confidence);
        return root.toString();
    }

    // Recovery Strategies
    
    private interface RecoveryStrategy {
        RecoveryResult recover(String response, ParsingContext context, String error);
        String getStrategyName();
    }
    
    private static class JsonCleanupStrategy implements RecoveryStrategy {
        @Override
        public RecoveryResult recover(String response, ParsingContext context, String error) {
            if (!error.toLowerCase().contains("json")) {
                return RecoveryResult.failure("Not a JSON error");
            }
            
            // Try to clean up common JSON issues
            String cleaned = response;
            
            // Remove markdown code blocks
            cleaned = cleaned.replaceAll("```json\\s*", "").replaceAll("```\\s*$", "");
            
            // Fix common JSON issues
            cleaned = cleaned.replaceAll(",\\s*}", "}");  // Remove trailing commas
            cleaned = cleaned.replaceAll(",\\s*]", "]");   // Remove trailing commas in arrays
            
            // Extract first valid JSON object
            Pattern jsonPattern = Pattern.compile("\\{[^{}]*(?:\\{[^{}]*\\}[^{}]*)*\\}");
            Matcher matcher = jsonPattern.matcher(cleaned);
            
            if (matcher.find()) {
                String extractedJson = matcher.group();
                return RecoveryResult.success(extractedJson, "Extracted and cleaned JSON");
            }
            
            return RecoveryResult.failure("Could not extract valid JSON");
        }
        
        @Override
        public String getStrategyName() {
            return "JSON Cleanup";
        }
    }
    
    private static class ActionBlockExtractionStrategy implements RecoveryStrategy {

        /** The verb field, in the spellings the ACTION-block format accepts. */
        private static final Pattern COMMAND_FIELD =
            Pattern.compile("(?:COMMAND|CMD):\\s*([^\\n]+)", Pattern.CASE_INSENSITIVE);

        /** The rationale field, in the same spellings. */
        private static final Pattern REASON_FIELD =
            Pattern.compile("(?:REASON|REASONING|WHY):\\s*([^\\n]+)", Pattern.CASE_INSENSITIVE);

        /** How far a block recovered from a failed parse is trusted. */
        private static final double RECOVERED_BLOCK_CONFIDENCE = 0.6;

        @Override
        public RecoveryResult recover(String response, ParsingContext context, String error) {
            // Look for ACTION_START/ACTION_END blocks
            Pattern actionPattern = Pattern.compile(
                "ACTION_START\\s*(.*?)\\s*ACTION_END", 
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE
            );
            
            Matcher matcher = actionPattern.matcher(response);
            if (matcher.find()) {
                String actionContent = matcher.group(1).trim();
                
                // Convert to structured format
                String structuredAction = convertToStructuredFormat(actionContent, context);
                return RecoveryResult.success(structuredAction, "Extracted ACTION block");
            }
            
            return RecoveryResult.failure("No ACTION blocks found");
        }
        
        /**
         * Rewrites a recovered ACTION block as the JSON the parser reads back.
         *
         * <h2>Why the block's own reader is asked for the arguments</h2>
         *
         * <p>This method used to walk the block a line at a time and keep the first line after
         * {@code ARGS:} as the whole argument list. A payload that spans lines -- the content of a
         * file being written, a diff, the blocks of a multi-edit -- lost everything below its first
         * line, and an {@code ARGS_BEGIN}/{@code ARGS_END} payload was lost entirely because
         * neither marker is a field. The recovered action then ran, so the recovery itself wrote
         * the truncated file. {@link ActionBlockParser#argumentsIn} applies the same payload rules
         * the ordinary parse applies, and names the parameters the same way, so what is recovered
         * here is what a block of that shape means anywhere else.</p>
         *
         * @param actionContent the text between {@code ACTION_START} and {@code ACTION_END}
         * @param context       the parsing context (unused here; the block says what it wants)
         * @return the action as JSON, or the block unchanged when it names no command
         */
        private String convertToStructuredFormat(String actionContent, ParsingContext context) {
            Matcher command = COMMAND_FIELD.matcher(actionContent);
            if (!command.find()) {
                return actionContent;  // Return as-is if we can't parse it
            }
            String verb   = command.group(1).trim();
            Matcher why   = REASON_FIELD.matcher(actionContent);
            String reason = why.find() ? why.group(1).trim() : "Recovered from ACTION block";

            return actionJson(verb, ActionBlockParser.argumentsIn(actionContent, verb), reason,
                              RECOVERED_BLOCK_CONFIDENCE);
        }
        
        @Override
        public String getStrategyName() {
            return "ACTION Block Extraction";
        }
    }
    
    private static class StructureInferenceStrategy implements RecoveryStrategy {
        @Override
        public RecoveryResult recover(String response, ParsingContext context, String error) {
            // Try to infer structure from natural language
            String intent = context.inferIntent();
            List<String> filePaths = context.extractPotentialFilePaths();
            
            if (intent != null && !intent.equals("unknown")) {
                String command = mapIntentToCommand(intent);
                String filePath = filePaths.isEmpty() ? null : filePaths.get(0);
                
                return RecoveryResult.success(
                        actionJson(command,
                                   Collections.singletonMap("file_path", filePath),
                                   "Inferred from user request",
                                   0.4),
                        "Inferred structure from context");
            }
            
            return RecoveryResult.failure("Could not infer structure");
        }
        
        private String mapIntentToCommand(String intent) {
            switch (intent) {
                case "read": return "read";
                case "write": return "write";
                case "edit": return "edit";
                case "search": return "grep";
                case "list": return "ls";
                case "execute": return "bash";
                case "analyze": return "analyze";
                default: return "read";  // Safe default
            }
        }
        
        @Override
        public String getStrategyName() {
            return "Structure Inference";
        }
    }
    
    private static class FallbackPatternStrategy implements RecoveryStrategy {
        @Override
        public RecoveryResult recover(String response, ParsingContext context, String error) {
            // Last resort - create a minimal valid action
            String suggestedCommand = context.suggestCommand();
            if (suggestedCommand == null) {
                suggestedCommand = "read";  // Safest default
            }
            
            String fallbackJson = actionJson(suggestedCommand,
                                             Collections.emptyMap(),
                                             "Fallback action due to parsing failure",
                                             0.1);

            return RecoveryResult.success(fallbackJson, "Generated fallback action");
        }
        
        @Override
        public String getStrategyName() {
            return "Fallback Pattern";
        }
    }
    
    // Result and Statistics classes
    
    public static class RecoveryResult {
        private final boolean success;
        private final String recoveredResponse;
        private final String strategy;
        private final String message;
        
        private RecoveryResult(boolean success, String recoveredResponse, String strategy, String message) {
            this.success = success;
            this.recoveredResponse = recoveredResponse;
            this.strategy = strategy;
            this.message = message;
        }
        
        public static RecoveryResult success(String recoveredResponse, String strategy) {
            return new RecoveryResult(true, recoveredResponse, strategy, "Recovery successful");
        }
        
        public static RecoveryResult failure(String message) {
            return new RecoveryResult(false, null, null, message);
        }
        
        public boolean isSuccess() { return success; }
        public String getRecoveredResponse() { return recoveredResponse; }
        public String getStrategy() { return strategy; }
        public String getMessage() { return message; }
    }
    
    public static class RecoveryStatistics {
        private final Map<String, Integer> errorPatterns;
        private final Map<String, String> successfulRecoveries;
        private final int totalErrors;
        private final int recoveredErrors;
        
        public RecoveryStatistics(Map<String, Integer> errorPatterns, Map<String, String> successfulRecoveries,
                                int totalErrors, int recoveredErrors) {
            this.errorPatterns = errorPatterns;
            this.successfulRecoveries = successfulRecoveries;
            this.totalErrors = totalErrors;
            this.recoveredErrors = recoveredErrors;
        }
        
        public Map<String, Integer> getErrorPatterns() { return errorPatterns; }
        public Map<String, String> getSuccessfulRecoveries() { return successfulRecoveries; }
        public int getTotalErrors() { return totalErrors; }
        public int getRecoveredErrors() { return recoveredErrors; }
        
        public double getRecoveryRate() {
            return totalErrors > 0 ? (double) recoveredErrors / totalErrors : 0.0;
        }
        
        @Override
        public String toString() {
            return String.format("RecoveryStats{totalErrors=%d, recovered=%d, rate=%.1f%%}",
                               totalErrors, recoveredErrors, getRecoveryRate() * 100);
        }
    }
}