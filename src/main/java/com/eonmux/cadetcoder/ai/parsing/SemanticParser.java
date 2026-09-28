package com.eonmux.cadetcoder.ai.parsing;

import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.HashSet;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * Parsing strategy that uses semantic analysis to infer commands from natural language.
 * This is used when structured formats fail and we need to extract intent from prose.
 */
public class SemanticParser implements ParsingStrategy {
    
    // Intent patterns for different command types
    private static final Map<String, Set<String>> INTENT_PATTERNS = new HashMap<>();
    
    static {
        INTENT_PATTERNS.put("read", Set.of(
            "read", "show", "display", "view", "examine", "look at", "see", "check",
            "print", "output", "content", "what is in", "what's in"
        ));
        
        INTENT_PATTERNS.put("write", Set.of(
            "write", "create", "save", "generate", "make", "produce", "output to",
            "put", "store", "build"
        ));
        
        INTENT_PATTERNS.put("edit", Set.of(
            "edit", "modify", "change", "update", "fix", "alter", "revise", "correct",
            "replace", "substitute", "refactor"
        ));
        
        INTENT_PATTERNS.put("grep", Set.of(
            "search", "find", "look for", "grep", "locate", "seek", "hunt for",
            "scan for", "filter", "match", "pattern"
        ));
        
        INTENT_PATTERNS.put("bash", Set.of(
            "run", "execute", "command", "bash", "shell", "terminal", "cmd",
            "launch", "start", "invoke"
        ));
        
        INTENT_PATTERNS.put("analyze", Set.of(
            "analyze", "review", "inspect", "study", "examine", "evaluate",
            "assess", "understand", "investigate"
        ));
        
        INTENT_PATTERNS.put("explain", Set.of(
            "explain", "describe", "tell me about", "what does", "how does",
            "clarify", "detail", "elaborate"
        ));
        
        INTENT_PATTERNS.put("ls", Set.of(
            "list", "ls", "dir", "directory", "files", "contents", "show files",
            "what files", "browse"
        ));
    }
    
    // File path detection patterns
    private static final Pattern FILE_PATH_PATTERN = Pattern.compile(
        "(?:['\"]([^'\"]+\\.[a-zA-Z0-9]{1,4})['\"]|\\b([a-zA-Z]:)?[/\\\\]?(?:[\\w.-]+[/\\\\])*[\\w.-]+\\.[a-zA-Z0-9]{1,4}\\b|\\b[\\w.-]+\\.[a-zA-Z0-9]{1,4}\\b)"
    );
    
    // Quoted content pattern for strings and patterns
    private static final Pattern QUOTED_CONTENT_PATTERN = Pattern.compile(
        "['\"]([^'\"]+)['\"]"
    );
    
    // Number pattern for limits and counts
    private static final Pattern NUMBER_PATTERN = Pattern.compile(
        "\\b(\\d+)\\b"
    );
    
    @Override
    public ParsedResponse parse(String response, ParsingContext context) {
        long startTime = System.currentTimeMillis();
        
        if (response == null || response.trim().isEmpty()) {
            return new ParsedResponse.Builder(ParsedResponse.ParseResult.EMPTY_RESPONSE, 
                                            ParsedResponse.ParsingStrategy.SEMANTIC_PARSING, response)
                .addError("Empty or null response")
                .setProcessingTime(System.currentTimeMillis() - startTime)
                .build();
        }
        
        // Clean and normalize the response
        String cleanResponse = cleanResponse(response);
        
        // Attempt to extract intent and create an action
        ParsedAction action = extractSemanticAction(cleanResponse, context);
        
        if (action == null) {
            return new ParsedResponse.Builder(ParsedResponse.ParseResult.FORMAT_ERROR, 
                                            ParsedResponse.ParsingStrategy.SEMANTIC_PARSING, response)
                .addError("Could not infer valid command from natural language")
                .addWarning("Try using more specific command words like 'read', 'write', 'search', etc.")
                .setProcessingTime(System.currentTimeMillis() - startTime)
                .build();
        }
        
        return new ParsedResponse.Builder(ParsedResponse.ParseResult.PARTIAL_SUCCESS, 
                                        ParsedResponse.ParsingStrategy.SEMANTIC_PARSING, response)
            .addAction(action)
            .setConfidence(action.getConfidence())
            .addWarning("Command inferred from natural language - please verify parameters")
            .setProcessingTime(System.currentTimeMillis() - startTime)
            .build();
    }
    
    private String cleanResponse(String response) {
        // Remove common AI response prefixes
        String cleaned = response.trim();
        String[] prefixes = {
            "I'll", "I will", "Let me", "I can", "I should", "I need to",
            "First,", "Next,", "Then,", "Now,", "To do this,"
        };
        
        for (String prefix : prefixes) {
            if (cleaned.startsWith(prefix)) {
                cleaned = cleaned.substring(prefix.length()).trim();
                break;
            }
        }
        
        // Remove trailing punctuation that might interfere
        cleaned = cleaned.replaceAll("[.!?]+$", "").trim();
        
        return cleaned;
    }
    
    private ParsedAction extractSemanticAction(String response, ParsingContext context) {
        String lowerResponse = response.toLowerCase();
        
        // Determine the most likely command based on intent patterns
        String command = inferCommand(lowerResponse, context);
        if (command == null) {
            return null;
        }
        
        // Extract parameters based on the inferred command
        Map<String, Object> parameters = extractParameters(response, command, context);
        
        // Use the original response as reasoning
        String reasoning = response;
        
        // Calculate confidence based on intent strength and parameter completeness
        double confidence = calculateSemanticConfidence(lowerResponse, command, parameters, context);
        
        // Validate the inferred action
        ParsedAction.ValidationResult validation = validateSemanticAction(command, parameters, context);
        String validationMessage = getValidationMessage(validation, command);
        
        return new ParsedAction.Builder(command)
            .setParameters(parameters)
            .setReasoning(reasoning)
            .setConfidence(confidence)
            .setValidation(validation, validationMessage)
            .addMetadata("parser", "semantic")
            .addMetadata("intent_strength", calculateIntentStrength(lowerResponse, command))
            .addMetadata("parameter_completeness", calculateParameterCompleteness(parameters, command))
            .build();
    }
    
    private String inferCommand(String lowerResponse, ParsingContext context) {
        // Check if response indicates uncertainty or inability to help
        if (isNonActionableResponse(lowerResponse)) {
            return null; // Don't infer commands for non-actionable responses
        }
        
        Map<String, Double> intentScores = new HashMap<>();
        
        // Calculate intent scores for each command
        for (Map.Entry<String, Set<String>> entry : INTENT_PATTERNS.entrySet()) {
            String command = entry.getKey();
            Set<String> patterns = entry.getValue();
            
            double score = 0.0;
            for (String pattern : patterns) {
                if (lowerResponse.contains(pattern)) {
                    score += 1.0;
                    // Boost score if pattern appears at the beginning
                    if (lowerResponse.startsWith(pattern)) {
                        score += 0.5;
                    }
                }
            }
            
            // Normalize by pattern count
            if (score > 0) {
                score = score / patterns.size();
                intentScores.put(command, score);
            }
        }
        
        // If no strong intent found, use context suggestions
        if (intentScores.isEmpty()) {
            String suggested = context.suggestCommand();
            if (suggested != null) {
                return suggested;
            }
        }
        
        // Return the command with the highest score
        return intentScores.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(null);
    }
    
    private boolean isNonActionableResponse(String lowerResponse) {
        // Check for common phrases indicating the AI can't or won't help
        String[] nonActionablePhrases = {
            "i'm not sure",
            "i don't know",
            "i can't",
            "i cannot",
            "i'm unable",
            "not sure what to do",
            "unclear what you want",
            "i don't understand",
            "can you clarify",
            "could you be more specific",
            "i need more information",
            "i'm confused",
            "i don't have enough information",
            "not clear what",
            "unable to help",
            "can't help",
            "don't know how"
        };
        
        for (String phrase : nonActionablePhrases) {
            if (lowerResponse.contains(phrase)) {
                return true;
            }
        }
        
        return false;
    }
    
    private Map<String, Object> extractParameters(String response, String command, ParsingContext context) {
        Map<String, Object> parameters = new HashMap<>();
        
        // Extract file paths
        List<String> filePaths = extractFilePaths(response);
        if (!filePaths.isEmpty()) {
            parameters.put("file_path", filePaths.get(0));
        }
        
        // Extract quoted content
        List<String> quotedStrings = extractQuotedContent(response);
        
        // Extract numbers
        List<Integer> numbers = extractNumbers(response);
        
        // Command-specific parameter extraction
        switch (command) {
            case "read":
            case "write":
            case "edit":
            case "analyze":
            case "explain":
                // File path is the main parameter
                if (filePaths.isEmpty() && !quotedStrings.isEmpty()) {
                    // Maybe the file is in quotes
                    parameters.put("file_path", quotedStrings.get(0));
                }
                if (!numbers.isEmpty()) {
                    parameters.put("limit", numbers.get(0));
                }
                break;
                
            case "grep":
                // Pattern is the main parameter
                if (!quotedStrings.isEmpty()) {
                    parameters.put("pattern", quotedStrings.get(0));
                } else {
                    // Try to infer pattern from keywords
                    String pattern = inferSearchPattern(response);
                    if (pattern != null) {
                        parameters.put("pattern", pattern);
                    }
                }
                if (filePaths.size() > 1) {
                    parameters.put("path", filePaths.get(1));
                } else if (filePaths.isEmpty() && quotedStrings.size() > 1) {
                    parameters.put("path", quotedStrings.get(1));
                }
                break;
                
            case "bash":
                // Command is the main parameter
                String bashCommand = inferBashCommand(response);
                if (bashCommand != null) {
                    parameters.put("command", bashCommand);
                }
                break;
                
            case "ls":
                // Path is optional
                if (!filePaths.isEmpty()) {
                    parameters.put("path", filePaths.get(0));
                } else if (!quotedStrings.isEmpty()) {
                    parameters.put("path", quotedStrings.get(0));
                }
                break;
        }
        
        return parameters;
    }
    
    private List<String> extractFilePaths(String response) {
        List<String> paths = new ArrayList<>();
        Matcher matcher = FILE_PATH_PATTERN.matcher(response);
        
        while (matcher.find()) {
            String path = matcher.group(1) != null ? matcher.group(1) : matcher.group();
            if (path != null && !path.trim().isEmpty()) {
                paths.add(path.trim());
            }
        }
        
        return paths;
    }
    
    private List<String> extractQuotedContent(String response) {
        List<String> quoted = new ArrayList<>();
        Matcher matcher = QUOTED_CONTENT_PATTERN.matcher(response);
        
        while (matcher.find()) {
            quoted.add(matcher.group(1));
        }
        
        return quoted;
    }
    
    private List<Integer> extractNumbers(String response) {
        List<Integer> numbers = new ArrayList<>();
        Matcher matcher = NUMBER_PATTERN.matcher(response);
        
        while (matcher.find()) {
            try {
                numbers.add(Integer.parseInt(matcher.group(1)));
            } catch (NumberFormatException e) {
                // Ignore invalid numbers
            }
        }
        
        return numbers;
    }
    
    private String inferSearchPattern(String response) {
        // Look for common search keywords
        String[] searchKeywords = {"class", "function", "method", "variable", "import", "public", "private"};
        
        for (String keyword : searchKeywords) {
            if (response.toLowerCase().contains(keyword)) {
                return keyword + ".*";
            }
        }
        
        return null;
    }
    
    private String inferBashCommand(String response) {
        String lowerResponse = response.toLowerCase();
        
        // Common bash command patterns
        if (lowerResponse.contains("list") || lowerResponse.contains("directory")) {
            return "ls -la";
        }
        if (lowerResponse.contains("current directory")) {
            return "pwd";
        }
        if (lowerResponse.contains("disk space") || lowerResponse.contains("space")) {
            return "df -h";
        }
        if (lowerResponse.contains("processes") || lowerResponse.contains("running")) {
            return "ps aux";
        }
        
        // Try to extract command after keywords
        String[] commandKeywords = {"run", "execute", "command"};
        for (String keyword : commandKeywords) {
            int index = lowerResponse.indexOf(keyword);
            if (index >= 0) {
                String remainder = response.substring(index + keyword.length()).trim();
                if (remainder.length() > 0) {
                    return remainder;
                }
            }
        }
        
        return null;
    }
    
    private double calculateSemanticConfidence(String response, String command, 
                                             Map<String, Object> parameters, ParsingContext context) {
        double confidence = 0.0;
        
        // Base confidence from intent strength
        double intentStrength = calculateIntentStrength(response, command);
        confidence += intentStrength * 0.5;
        
        // Parameter completeness
        double parameterCompleteness = calculateParameterCompleteness(parameters, command);
        confidence += parameterCompleteness * 0.3;
        
        // Context relevance
        if (context.wasCommandRecentlyUsed(command)) {
            confidence += 0.1;
        }
        
        // Penalty for missing critical parameters
        if (FilePathRule.requiresPath(command) && !FilePathRule.isSatisfiedBy(parameters)) {
            confidence *= 0.5;
        }
        
        return Math.max(0.1, Math.min(0.7, confidence)); // Cap at 0.7 for semantic parsing
    }
    
    private double calculateIntentStrength(String response, String command) {
        Set<String> patterns = INTENT_PATTERNS.get(command);
        if (patterns == null) return 0.0;
        
        int matches = 0;
        for (String pattern : patterns) {
            if (response.contains(pattern)) {
                matches++;
            }
        }
        
        return (double) matches / patterns.size();
    }
    
    private double calculateParameterCompleteness(Map<String, Object> parameters, String command) {
        int required = getRequiredParameterCount(command);
        if (required == 0) return 1.0;
        
        int present = 0;
        if (FilePathRule.isSatisfiedBy(parameters)) present++;
        if (parameters.containsKey("pattern")) present++;
        if (parameters.containsKey("command")) present++;
        
        return (double) present / required;
    }
    
    private int getRequiredParameterCount(String command) {
        switch (command) {
            case "read":
            case "write":
            case "edit":
            case "analyze":
            case "explain":
                return 1; // file_path
            case "grep":
                return 1; // pattern (path is optional)
            case "bash":
                return 1; // command
            default:
                return 0;
        }
    }
    
    private ParsedAction.ValidationResult validateSemanticAction(String command, Map<String, Object> parameters, 
                                                               ParsingContext context) {
        // Check if command is available
        if (!context.getAvailableCommands().isEmpty() && !context.isCommandAvailable(command)) {
            return ParsedAction.ValidationResult.INVALID_COMMAND;
        }
        
        // Since this is semantic parsing, most missing parameters require inference
        if (FilePathRule.requiresPath(command) && !FilePathRule.isSatisfiedBy(parameters)) {
            return ParsedAction.ValidationResult.REQUIRES_INFERENCE;
        }
        
        return ParsedAction.ValidationResult.VALID;
    }
    
    private String getValidationMessage(ParsedAction.ValidationResult validation, String command) {
        switch (validation) {
            case INVALID_COMMAND:
                return "Command '" + command + "' is not available";
            case REQUIRES_INFERENCE:
                return "Command '" + command + "' requires parameter inference from context";
            default:
                return null;
        }
    }
    
    @Override
    public String getStrategyName() {
        return "Semantic Parser";
    }
    
    @Override
    public int getPriority() {
        return 4; // Fifth priority - used when structured formats fail
    }
    
    @Override
    public boolean canHandle(String response) {
        // Can always attempt semantic parsing, but with lower confidence
        return response != null && !response.trim().isEmpty();
    }
    
    @Override
    public double getExpectedConfidence() {
        return 0.4; // Lower confidence for semantic inference
    }
    
    @Override
    public boolean isFallbackStrategy() {
        return true;
    }
    
    @Override
    public String getParsingErrorDetails(String response) {
        return "Semantic parsing failed - could not infer clear command intent from: " + 
               (response.length() > 100 ? response.substring(0, 100) + "..." : response);
    }
    
    @Override
    public String[] getExampleFormats() {
        return new String[] {
            "Please read the main Java file",
            "I need to search for class definitions in the source code",
            "Can you list the files in the current directory?",
            "Show me the contents of config.json",
            "Find all occurrences of 'TODO' in the project"
        };
    }
}