package com.eonmux.cadetcoder.ai.parsing;

/**
 * Interface for different AI response parsing strategies.
 * Each strategy implements a specific approach to extracting actions from AI responses.
 */
public interface ParsingStrategy {
    
    /**
     * Attempts to parse the AI response using this strategy.
     * 
     * @param response The raw AI response to parse
     * @param context Additional context that may help with parsing
     * @return ParsedResponse containing the results of the parsing attempt
     */
    ParsedResponse parse(String response, ParsingContext context);
    
    /**
     * Returns the name of this parsing strategy for logging and debugging.
     * 
     * @return A descriptive name for this strategy
     */
    String getStrategyName();
    
    /**
     * Returns the priority level of this strategy.
     * Lower numbers indicate higher priority (should be tried first).
     * 
     * @return Priority level (0 = highest priority)
     */
    int getPriority();
    
    /**
     * Determines if this strategy can handle the given response.
     * This is a quick check before attempting full parsing.
     * 
     * @param response The AI response to evaluate
     * @return true if this strategy might be able to parse the response
     */
    boolean canHandle(String response);
    
    /**
     * Returns the expected confidence level for responses this strategy can handle.
     * Used for strategy selection when multiple strategies might work.
     * 
     * @return Expected confidence level (0.0 to 1.0)
     */
    double getExpectedConfidence();
    
    /**
     * Validates that a parsed action makes sense for this strategy.
     * 
     * @param action The parsed action to validate
     * @return true if the action is valid for this strategy
     */
    default boolean validateAction(ParsedAction action) {
        return action != null && 
               action.getCommand() != null && 
               !action.getCommand().trim().isEmpty();
    }
    
    /**
     * Provides strategy-specific error information when parsing fails.
     * 
     * @param response The response that failed to parse
     * @return Descriptive error message explaining why parsing failed
     */
    default String getParsingErrorDetails(String response) {
        return "Failed to parse response using " + getStrategyName() + " strategy";
    }
    
    /**
     * Indicates whether this strategy should be used as a fallback.
     * Fallback strategies are tried when preferred strategies fail.
     * 
     * @return true if this is a fallback strategy
     */
    default boolean isFallbackStrategy() {
        return getPriority() > 2;
    }
    
    /**
     * Returns example formats that this strategy can parse.
     * Used for generating format hints to the AI when parsing fails.
     * 
     * @return Array of example format strings
     */
    default String[] getExampleFormats() {
        return new String[0];
    }
}