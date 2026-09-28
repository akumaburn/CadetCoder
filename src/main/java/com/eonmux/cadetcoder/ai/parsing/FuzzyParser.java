package com.eonmux.cadetcoder.ai.parsing;

import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * Last resort parsing strategy that uses fuzzy pattern matching and heuristics.
 * This parser attempts to extract any actionable content from malformed or unclear responses.
 */
public class FuzzyParser implements ParsingStrategy {
    
    // Command similarity mapping for typo correction
    private static final Map<String, String> COMMAND_CORRECTIONS = new HashMap<>();
    
    static {
        // Common typos and variations
        COMMAND_CORRECTIONS.put("raed", "read");
        COMMAND_CORRECTIONS.put("reda", "read");
        COMMAND_CORRECTIONS.put("readd", "read");
        COMMAND_CORRECTIONS.put("wriet", "write");
        COMMAND_CORRECTIONS.put("wrtie", "write");
        COMMAND_CORRECTIONS.put("wirte", "write");
        COMMAND_CORRECTIONS.put("edti", "edit");
        COMMAND_CORRECTIONS.put("eidt", "edit");
        COMMAND_CORRECTIONS.put("editr", "edit");
        COMMAND_CORRECTIONS.put("serach", "grep");
        COMMAND_CORRECTIONS.put("saerch", "grep");
        COMMAND_CORRECTIONS.put("finde", "grep");
        COMMAND_CORRECTIONS.put("liste", "ls");
        COMMAND_CORRECTIONS.put("lsit", "ls");
        COMMAND_CORRECTIONS.put("hsab", "bash");
        COMMAND_CORRECTIONS.put("shel", "bash");
        COMMAND_CORRECTIONS.put("runn", "bash");
        COMMAND_CORRECTIONS.put("exec", "bash");
        COMMAND_CORRECTIONS.put("analize", "analyze");
        COMMAND_CORRECTIONS.put("analayze", "analyze");
        COMMAND_CORRECTIONS.put("explian", "explain");
        COMMAND_CORRECTIONS.put("expalin", "explain");
    }
    
    // Patterns for finding anything that looks like a command
    private static final Pattern COMMAND_LIKE_PATTERN = Pattern.compile(
        "\\b(read|write|edit|grep|search|bash|execute|analyze|explain|ls|list|find|show|view|create|modify|run|cat|echo|pwd|mkdir|touch)\\b",
        Pattern.CASE_INSENSITIVE
    );
    
    // Patterns for extracting file-like strings
    private static final Pattern FILE_LIKE_PATTERN = Pattern.compile(
        "(?:['\"]([^'\"]+)['\"]|\\b([a-zA-Z]:)?[/\\\\]?(?:[\\w.-]+[/\\\\])*[\\w.-]+\\b)"
    );
    
    // Patterns for extracting meaningful text segments
    private static final Pattern MEANINGFUL_SEGMENT_PATTERN = Pattern.compile(
        "[a-zA-Z][a-zA-Z0-9._/-]{2,}"
    );
    
    @Override
    public ParsedResponse parse(String response, ParsingContext context) {
        long startTime = System.currentTimeMillis();
        
        if (response == null || response.trim().isEmpty()) {
            return new ParsedResponse.Builder(ParsedResponse.ParseResult.EMPTY_RESPONSE, 
                                            ParsedResponse.ParsingStrategy.FUZZY_PARSING, response)
                .addError("Empty or null response")
                .setProcessingTime(System.currentTimeMillis() - startTime)
                .build();
        }
        
        // Clean the response
        String cleanResponse = cleanAndNormalizeResponse(response);
        
        // Try multiple fuzzy parsing approaches
        ParsedAction action = null;
        
        // Approach 1: Look for command-like words
        action = attemptCommandExtraction(cleanResponse, context);
        
        // Approach 2: If no command found, try pattern-based inference
        if (action == null) {
            action = attemptPatternInference(cleanResponse, context);
        }
        
        // Approach 3: If still nothing, generate best guess from context
        if (action == null) {
            action = generateContextualGuess(cleanResponse, context);
        }
        
        if (action == null) {
            return new ParsedResponse.Builder(ParsedResponse.ParseResult.FORMAT_ERROR, 
                                            ParsedResponse.ParsingStrategy.FUZZY_PARSING, response)
                .addError("Unable to extract any actionable content")
                .addError("Response appears to be non-actionable text")
                .addWarning("Consider providing more explicit command instructions")
                .setProcessingTime(System.currentTimeMillis() - startTime)
                .build();
        }
        
        return new ParsedResponse.Builder(ParsedResponse.ParseResult.PARTIAL_SUCCESS, 
                                        ParsedResponse.ParsingStrategy.FUZZY_PARSING, response)
            .addAction(action)
            .setConfidence(action.getConfidence())
            .addWarning("Action extracted using fuzzy parsing - please verify before execution")
            .addWarning("Consider using more structured command format for better accuracy")
            .setProcessingTime(System.currentTimeMillis() - startTime)
            .build();
    }
    
    private String cleanAndNormalizeResponse(String response) {
        // Remove extra whitespace and normalize
        String cleaned = response.trim().replaceAll("\\s+", " ");
        
        // Remove common filler words that don't help with parsing
        String[] fillerWords = {
            "I think", "I believe", "maybe", "perhaps", "probably", "likely",
            "I would", "I could", "I should", "let me", "how about"
        };
        
        for (String filler : fillerWords) {
            cleaned = cleaned.replaceAll("(?i)\\b" + Pattern.quote(filler) + "\\b", " ");
        }
        
        return cleaned.trim();
    }
    
    private ParsedAction attemptCommandExtraction(String response, ParsingContext context) {
        String lowerResponse = response.toLowerCase();
        
        // Look for explicit command words
        Matcher commandMatcher = COMMAND_LIKE_PATTERN.matcher(lowerResponse);
        List<String> foundCommands = new ArrayList<>();
        
        while (commandMatcher.find()) {
            String command = commandMatcher.group().toLowerCase();
            if (!foundCommands.contains(command)) {
                foundCommands.add(command);
            }
        }
        
        // If multiple commands found, prefer the first one
        if (!foundCommands.isEmpty()) {
            String command = normalizeCommand(foundCommands.get(0));
            Map<String, Object> parameters = extractParametersForCommand(response, command, context);
            
            return new ParsedAction.Builder(command)
                .setParameters(parameters)
                .setReasoning("Extracted from fuzzy command detection: " + response)
                .setConfidence(0.4)
                .setValidation(ParsedAction.ValidationResult.REQUIRES_INFERENCE, 
                              "Parameters may need inference")
                .addMetadata("parser", "fuzzy_command_extraction")
                .addMetadata("found_commands", foundCommands.toString())
                .build();
        }
        
        // Check for typos and corrections
        for (String word : response.split("\\s+")) {
            String corrected = COMMAND_CORRECTIONS.get(word.toLowerCase());
            if (corrected != null) {
                Map<String, Object> parameters = extractParametersForCommand(response, corrected, context);
                
                return new ParsedAction.Builder(corrected)
                    .setParameters(parameters)
                    .setReasoning("Corrected typo '" + word + "' to '" + corrected + "': " + response)
                    .setConfidence(0.3)
                    .setValidation(ParsedAction.ValidationResult.REQUIRES_INFERENCE, 
                                  "Command corrected from typo")
                    .addMetadata("parser", "fuzzy_typo_correction")
                    .addMetadata("original_typo", word)
                    .addMetadata("corrected_to", corrected)
                    .build();
            }
        }
        
        return null;
    }
    
    private ParsedAction attemptPatternInference(String response, ParsingContext context) {
        String lowerResponse = response.toLowerCase();
        
        // Pattern-based command inference
        if (containsFileReference(response)) {
            if (lowerResponse.contains("content") || lowerResponse.contains("show") || 
                lowerResponse.contains("display") || lowerResponse.contains("see")) {
                return createInferredAction("read", response, context);
            }
            if (lowerResponse.contains("change") || lowerResponse.contains("modify") || 
                lowerResponse.contains("update") || lowerResponse.contains("fix")) {
                return createInferredAction("edit", response, context);
            }
            if (lowerResponse.contains("create") || lowerResponse.contains("make") || 
                lowerResponse.contains("generate")) {
                return createInferredAction("write", response, context);
            }
        }
        
        // Search-related patterns
        if (lowerResponse.contains("find") || lowerResponse.contains("search") || 
            lowerResponse.contains("look for") || lowerResponse.contains("locate")) {
            return createInferredAction("grep", response, context);
        }
        
        // Execution patterns
        if (lowerResponse.contains("run") || lowerResponse.contains("execute") || 
            lowerResponse.contains("command") || lowerResponse.contains("shell")) {
            return createInferredAction("bash", response, context);
        }
        
        // Analysis patterns
        if (lowerResponse.contains("analyze") || lowerResponse.contains("review") || 
            lowerResponse.contains("examine") || lowerResponse.contains("check")) {
            return createInferredAction("analyze", response, context);
        }
        
        // Listing patterns
        if (lowerResponse.contains("list") || lowerResponse.contains("files") || 
            lowerResponse.contains("directory") || lowerResponse.contains("contents")) {
            return createInferredAction("ls", response, context);
        }
        
        return null;
    }
    
    private ParsedAction generateContextualGuess(String response, ParsingContext context) {
        // Check if response indicates uncertainty or inability to help
        if (isNonActionableResponse(response)) {
            return null; // Don't generate actions for non-actionable responses
        }
        
        // Use context to make educated guesses only if there's some actionable intent
        String suggestedCommand = context.suggestCommand();
        if (suggestedCommand == null) {
            suggestedCommand = "read"; // Default to read as safest option
        }
        
        Map<String, Object> parameters = extractParametersForCommand(response, suggestedCommand, context);
        
        return new ParsedAction.Builder(suggestedCommand)
            .setParameters(parameters)
            .setReasoning("Best guess based on context: " + response)
            .setConfidence(0.2)
            .setValidation(ParsedAction.ValidationResult.REQUIRES_INFERENCE, 
                          "Action generated from contextual guess")
            .addMetadata("parser", "fuzzy_contextual_guess")
            .addMetadata("context_suggestion", suggestedCommand)
            .build();
    }
    
    private boolean isNonActionableResponse(String response) {
        String lowerResponse = response.toLowerCase();
        
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
    
    /**
     * Maps a verb spotted in prose to the command it means.
     *
     * <p>The synonym table lives in {@link com.eonmux.cadetcoder.commands.CommandAliases}, shared
     * with the dispatchers, so a verb cannot mean one command here and another when the action is
     * run. This copy of it also mapped {@code search} to {@code grep}, which shadowed a registered
     * command: {@code search} finds code by meaning across the project index and {@code grep} finds
     * literal text, so a model asking for one was given the other.</p>
     *
     * <p>{@code find} and {@code locate} are resolved here rather than in the shared table because
     * they are ambiguous -- file contents ({@code grep}) or file names ({@code glob}). This strategy
     * only reaches them from prose, where a search pattern is what it can extract, so contents is
     * the reading it commits to; the shared table stays out of the guess.</p>
     */
    private String normalizeCommand(String command) {
        switch (command.toLowerCase()) {
            case "find":
            case "locate":
                return "grep";
            default:
                return com.eonmux.cadetcoder.commands.CommandAliases.canonicalize(command);
        }
    }
    
    private boolean containsFileReference(String response) {
        // Check if response contains file-like references
        Matcher fileMatcher = FILE_LIKE_PATTERN.matcher(response);
        return fileMatcher.find() || 
               response.contains(".java") || response.contains(".js") || 
               response.contains(".py") || response.contains(".txt") ||
               response.contains(".json") || response.contains(".xml");
    }
    
    private ParsedAction createInferredAction(String command, String response, ParsingContext context) {
        Map<String, Object> parameters = extractParametersForCommand(response, command, context);
        
        return new ParsedAction.Builder(command)
            .setParameters(parameters)
            .setReasoning("Inferred from pattern analysis: " + response)
            .setConfidence(0.35)
            .setValidation(ParsedAction.ValidationResult.REQUIRES_INFERENCE, 
                          "Parameters inferred from patterns")
            .addMetadata("parser", "fuzzy_pattern_inference")
            .addMetadata("inferred_command", command)
            .build();
    }
    
    private Map<String, Object> extractParametersForCommand(String response, String command, 
                                                          ParsingContext context) {
        Map<String, Object> parameters = new HashMap<>();
        
        // Extract file-like strings
        List<String> fileStrings = extractFileStrings(response);
        if (!fileStrings.isEmpty()) {
            parameters.put("file_path", fileStrings.get(0));
        }
        
        // Extract quoted strings
        List<String> quotedStrings = extractQuotedStrings(response);
        
        // Extract meaningful segments
        List<String> meaningfulSegments = extractMeaningfulSegments(response);
        
        // Command-specific parameter extraction
        switch (command) {
            case "grep":
            case "search":
                if (!quotedStrings.isEmpty()) {
                    parameters.put("pattern", quotedStrings.get(0));
                } else if (!meaningfulSegments.isEmpty()) {
                    // Use first meaningful segment as search pattern
                    String pattern = meaningfulSegments.get(0);
                    if (pattern.length() > 2) {
                        parameters.put("pattern", pattern);
                    }
                }
                break;
                
            case "bash":
                // Try to extract command-like strings
                String bashCommand = extractBashCommand(response);
                if (bashCommand != null) {
                    parameters.put("command", bashCommand);
                }
                break;
                
            case "write":
                if (quotedStrings.size() > 1) {
                    parameters.put("content", quotedStrings.get(1));
                }
                break;
        }
        
        // Use potential file paths from context if none found in response
        if (!parameters.containsKey("file_path")) {
            List<String> contextPaths = context.extractPotentialFilePaths();
            if (!contextPaths.isEmpty()) {
                parameters.put("file_path", contextPaths.get(0));
            }
        }
        
        return parameters;
    }
    
    private List<String> extractFileStrings(String response) {
        List<String> files = new ArrayList<>();
        Matcher matcher = FILE_LIKE_PATTERN.matcher(response);
        
        while (matcher.find()) {
            String match = matcher.group(1) != null ? matcher.group(1) : matcher.group();
            if (match != null && couldBeFilePath(match)) {
                files.add(match);
            }
        }
        
        return files;
    }
    
    private List<String> extractQuotedStrings(String response) {
        List<String> quoted = new ArrayList<>();
        Pattern quotedPattern = Pattern.compile("['\"]([^'\"]+)['\"]");
        Matcher matcher = quotedPattern.matcher(response);
        
        while (matcher.find()) {
            quoted.add(matcher.group(1));
        }
        
        return quoted;
    }
    
    private List<String> extractMeaningfulSegments(String response) {
        List<String> segments = new ArrayList<>();
        Matcher matcher = MEANINGFUL_SEGMENT_PATTERN.matcher(response);
        
        while (matcher.find()) {
            String segment = matcher.group();
            if (segment.length() > 3 && !isCommonWord(segment)) {
                segments.add(segment);
            }
        }
        
        return segments;
    }
    
    private String extractBashCommand(String response) {
        // Look for command-like patterns
        String[] commandKeywords = {"run", "execute", "command"};
        
        for (String keyword : commandKeywords) {
            int index = response.toLowerCase().indexOf(keyword);
            if (index >= 0) {
                String remainder = response.substring(index + keyword.length()).trim();
                if (remainder.length() > 0) {
                    // Clean up the remainder
                    remainder = remainder.replaceAll("^[^a-zA-Z]*", ""); // Remove leading non-letters
                    if (remainder.length() > 0) {
                        return remainder;
                    }
                }
            }
        }
        
        return null;
    }
    
    private boolean couldBeFilePath(String str) {
        // Simple heuristics to determine if a string could be a file path
        return str.contains(".") && 
               (str.contains("/") || str.contains("\\") || str.matches(".*\\.[a-zA-Z]{1,4}$")) &&
               str.length() > 3 &&
               !str.contains(" ");
    }
    
    private boolean isCommonWord(String word) {
        Set<String> commonWords = Set.of(
            "the", "and", "for", "are", "but", "not", "you", "all", "can", "had", 
            "her", "was", "one", "our", "out", "day", "get", "has", "him", "his",
            "how", "its", "may", "new", "now", "old", "see", "two", "way", "who",
            "boy", "did", "man", "men", "put", "say", "she", "too", "use"
        );
        return commonWords.contains(word.toLowerCase());
    }
    
    @Override
    public String getStrategyName() {
        return "Fuzzy Parser";
    }
    
    @Override
    public int getPriority() {
        return 5; // Lowest priority - last resort
    }
    
    @Override
    public boolean canHandle(String response) {
        // Can always attempt fuzzy parsing as last resort
        return response != null && !response.trim().isEmpty();
    }
    
    @Override
    public double getExpectedConfidence() {
        return 0.3; // Low confidence for fuzzy parsing
    }
    
    @Override
    public boolean isFallbackStrategy() {
        return true;
    }
    
    @Override
    public String getParsingErrorDetails(String response) {
        return "Fuzzy parsing failed - response may not contain actionable content. " +
               "Try using explicit command words like 'read', 'write', 'search', etc.";
    }
    
    @Override
    public String[] getExampleFormats() {
        return new String[] {
            "Look at the main file",
            "Need to check what's in config.json",
            "Find where the error occurs",
            "Change the title in index.html", 
            "Run a directory listing"
        };
    }
}