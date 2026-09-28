package com.eonmux.cadetcoder.ai.parsing;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * Represents a parsed AI response with validation results, confidence scores, and extracted actions.
 * This class encapsulates the results of the multi-tier parsing process.
 */
public class ParsedResponse {
    
    public enum ParseResult {
        SUCCESS,           // Successfully parsed with high confidence
        PARTIAL_SUCCESS,   // Parsed but with low confidence or missing elements
        FORMAT_ERROR,      // Unable to parse due to format issues
        VALIDATION_ERROR,  // Parsed but failed validation
        EMPTY_RESPONSE,    // No actionable content found
        SECURITY_ERROR     // Response contains security concerns
    }
    
    /**
     * Which parser read the reply, in the order they are tried.
     *
     * <p>The order is the engine's tolerance, not advice to the model: the system prompts ask for
     * an {@code ACTION_START}/{@code ACTION_END} block and for nothing else, and every other member
     * here exists to salvage a reply that did not follow them.</p>
     */
    public enum ParsingStrategy {
        JSON_SCHEMA,       // A JSON object; tried first, but not a format the model is asked for
        ACTION_BLOCK,      // The ACTION_START/END block the system prompts require
        TOOL_CALL,         // A tool call in the notation the model's own vendor trained it on
        XML_ACTION,        // XML action tag format
        SEMANTIC_PARSING,  // Natural language inference
        FUZZY_PARSING      // Last resort pattern matching
    }
    
    private final ParseResult result;
    private final ParsingStrategy strategyUsed;
    private final List<ParsedAction> actions;
    private final double confidence;
    private final List<String> errors;
    private final List<String> warnings;
    private final Map<String, Object> metadata;
    private final String originalResponse;
    private final long processingTimeMs;
    
    public ParsedResponse(ParseResult result, ParsingStrategy strategyUsed, String originalResponse) {
        this.result = result;
        this.strategyUsed = strategyUsed;
        this.originalResponse = originalResponse;
        this.actions = new ArrayList<>();
        this.errors = new ArrayList<>();
        this.warnings = new ArrayList<>();
        this.metadata = new HashMap<>();
        this.confidence = 0.0;
        this.processingTimeMs = 0;
    }
    
    public ParsedResponse(ParseResult result, ParsingStrategy strategyUsed, List<ParsedAction> actions, 
                         double confidence, String originalResponse, long processingTimeMs) {
        this.result = result;
        this.strategyUsed = strategyUsed;
        this.actions = new ArrayList<>(actions);
        this.confidence = confidence;
        this.originalResponse = originalResponse;
        this.processingTimeMs = processingTimeMs;
        this.errors = new ArrayList<>();
        this.warnings = new ArrayList<>();
        this.metadata = new HashMap<>();
    }
    
    // Builder pattern for complex construction
    public static class Builder {
        private ParseResult result;
        private ParsingStrategy strategyUsed;
        private List<ParsedAction> actions = new ArrayList<>();
        private double confidence = 0.0;
        private List<String> errors = new ArrayList<>();
        private List<String> warnings = new ArrayList<>();
        private Map<String, Object> metadata = new HashMap<>();
        private String originalResponse;
        private long processingTimeMs = 0;
        
        public Builder(ParseResult result, ParsingStrategy strategyUsed, String originalResponse) {
            this.result = result;
            this.strategyUsed = strategyUsed;
            this.originalResponse = originalResponse;
        }
        
        public Builder addAction(ParsedAction action) {
            this.actions.add(action);
            return this;
        }
        
        public Builder setConfidence(double confidence) {
            this.confidence = confidence;
            return this;
        }
        
        public Builder addError(String error) {
            this.errors.add(error);
            return this;
        }
        
        public Builder addWarning(String warning) {
            this.warnings.add(warning);
            return this;
        }
        
        public Builder addMetadata(String key, Object value) {
            this.metadata.put(key, value);
            return this;
        }
        
        public Builder setProcessingTime(long timeMs) {
            this.processingTimeMs = timeMs;
            return this;
        }
        
        public ParsedResponse build() {
            ParsedResponse response = new ParsedResponse(result, strategyUsed, actions, confidence, originalResponse, processingTimeMs);
            response.errors.addAll(this.errors);
            response.warnings.addAll(this.warnings);
            response.metadata.putAll(this.metadata);
            return response;
        }
    }
    
    // Getters
    public ParseResult getResult() {
        return result;
    }
    
    public ParsingStrategy getStrategyUsed() {
        return strategyUsed;
    }
    
    public List<ParsedAction> getActions() {
        return new ArrayList<>(actions);
    }
    
    public double getConfidence() {
        return confidence;
    }
    
    public List<String> getErrors() {
        return new ArrayList<>(errors);
    }
    
    public List<String> getWarnings() {
        return new ArrayList<>(warnings);
    }
    
    public Map<String, Object> getMetadata() {
        return new HashMap<>(metadata);
    }
    
    public String getOriginalResponse() {
        return originalResponse;
    }
    
    public long getProcessingTimeMs() {
        return processingTimeMs;
    }
    
    // Utility methods
    public boolean isSuccessful() {
        return result == ParseResult.SUCCESS || result == ParseResult.PARTIAL_SUCCESS;
    }
    
    public boolean hasErrors() {
        return !errors.isEmpty();
    }
    
    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }
    
    public boolean hasActions() {
        return !actions.isEmpty();
    }
    
    public ParsedAction getFirstAction() {
        return actions.isEmpty() ? null : actions.get(0);
    }
    
    public boolean isHighConfidence() {
        return confidence >= 0.8;
    }
    
    public boolean isMediumConfidence() {
        return confidence >= 0.5 && confidence < 0.8;
    }
    
    public boolean isLowConfidence() {
        return confidence < 0.5;
    }
    
    /**
     * Returns a detailed diagnostic string for debugging parsing issues
     */
    public String getDiagnosticInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("ParsedResponse Diagnostics:\n");
        sb.append("  Result: ").append(result).append("\n");
        sb.append("  Strategy: ").append(strategyUsed).append("\n");
        sb.append("  Confidence: ").append(String.format("%.2f", confidence)).append("\n");
        sb.append("  Actions found: ").append(actions.size()).append("\n");
        sb.append("  Processing time: ").append(processingTimeMs).append("ms\n");
        
        if (hasErrors()) {
            sb.append("  Errors:\n");
            for (String error : errors) {
                sb.append("    - ").append(error).append("\n");
            }
        }
        
        if (hasWarnings()) {
            sb.append("  Warnings:\n");
            for (String warning : warnings) {
                sb.append("    - ").append(warning).append("\n");
            }
        }
        
        if (!metadata.isEmpty()) {
            sb.append("  Metadata:\n");
            for (Map.Entry<String, Object> entry : metadata.entrySet()) {
                sb.append("    ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            }
        }
        
        return sb.toString();
    }
    
    @Override
    public String toString() {
        return String.format("ParsedResponse{result=%s, strategy=%s, actions=%d, confidence=%.2f}", 
                           result, strategyUsed, actions.size(), confidence);
    }
}