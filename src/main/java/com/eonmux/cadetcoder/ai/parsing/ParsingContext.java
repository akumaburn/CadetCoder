package com.eonmux.cadetcoder.ai.parsing;

import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.HashSet;

/**
 * Context information provided to parsing strategies to help them make better decisions.
 * This includes user request context, session history, and parsing preferences.
 */
public class ParsingContext {
    
    private final String userRequest;
    private final String workingDirectory;
    private final Map<String, Object> sessionState;
    private final List<String> recentCommands;
    private final Set<String> availableCommands;
    private final Map<String, String> environmentVariables;
    private final boolean strictMode;
    private final int maxRetries;
    private final double confidenceThreshold;
    
    private static final boolean DEFAULT_STRICT_MODE          = false;
    private static final int     DEFAULT_MAX_RETRIES          = 3;
    private static final double  DEFAULT_CONFIDENCE_THRESHOLD = 0.7;

    public ParsingContext(String userRequest, String workingDirectory) {
        this(userRequest, workingDirectory,
             DEFAULT_STRICT_MODE, DEFAULT_MAX_RETRIES, DEFAULT_CONFIDENCE_THRESHOLD);
    }

    private ParsingContext(String userRequest, String workingDirectory,
                           boolean strictMode, int maxRetries, double confidenceThreshold) {
        this.userRequest = userRequest;
        this.workingDirectory = workingDirectory;
        this.sessionState = new HashMap<>();
        this.recentCommands = new ArrayList<>();
        this.availableCommands = new HashSet<>();
        this.environmentVariables = new HashMap<>();
        this.strictMode = strictMode;
        this.maxRetries = maxRetries;
        this.confidenceThreshold = confidenceThreshold;
    }
    
    // Builder pattern for flexible construction
    public static class Builder {
        private String userRequest;
        private String workingDirectory;
        private Map<String, Object> sessionState = new HashMap<>();
        private List<String> recentCommands = new ArrayList<>();
        private Set<String> availableCommands = new HashSet<>();
        private Map<String, String> environmentVariables = new HashMap<>();
        private boolean strictMode = DEFAULT_STRICT_MODE;
        private int maxRetries = DEFAULT_MAX_RETRIES;
        private double confidenceThreshold = DEFAULT_CONFIDENCE_THRESHOLD;
        
        public Builder(String userRequest) {
            this.userRequest = userRequest;
            this.workingDirectory = System.getProperty("user.dir");
        }
        
        public Builder workingDirectory(String workingDirectory) {
            this.workingDirectory = workingDirectory;
            return this;
        }
        
        public Builder addSessionState(String key, Object value) {
            this.sessionState.put(key, value);
            return this;
        }
        
        public Builder addRecentCommand(String command) {
            this.recentCommands.add(command);
            return this;
        }
        
        public Builder setRecentCommands(List<String> commands) {
            this.recentCommands = new ArrayList<>(commands);
            return this;
        }
        
        public Builder addAvailableCommand(String command) {
            this.availableCommands.add(command);
            return this;
        }
        
        public Builder setAvailableCommands(Set<String> commands) {
            this.availableCommands = new HashSet<>(commands);
            return this;
        }
        
        public Builder addEnvironmentVariable(String key, String value) {
            this.environmentVariables.put(key, value);
            return this;
        }
        
        public Builder strictMode(boolean strictMode) {
            this.strictMode = strictMode;
            return this;
        }
        
        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }
        
        public Builder confidenceThreshold(double threshold) {
            this.confidenceThreshold = threshold;
            return this;
        }
        
        public ParsingContext build() {
            ParsingContext context = new ParsingContext(userRequest, workingDirectory,
                                                        strictMode, maxRetries, confidenceThreshold);
            context.sessionState.putAll(this.sessionState);
            context.recentCommands.addAll(this.recentCommands);
            context.availableCommands.addAll(this.availableCommands);
            context.environmentVariables.putAll(this.environmentVariables);
            return context;
        }
    }
    
    // Getters
    public String getUserRequest() {
        return userRequest;
    }
    
    public String getWorkingDirectory() {
        return workingDirectory;
    }
    
    public Map<String, Object> getSessionState() {
        return new HashMap<>(sessionState);
    }
    
    public Object getSessionValue(String key) {
        return sessionState.get(key);
    }
    
    public String getSessionString(String key) {
        Object value = sessionState.get(key);
        return value != null ? value.toString() : null;
    }
    
    public List<String> getRecentCommands() {
        return new ArrayList<>(recentCommands);
    }
    
    public Set<String> getAvailableCommands() {
        return new HashSet<>(availableCommands);
    }
    
    public Map<String, String> getEnvironmentVariables() {
        return new HashMap<>(environmentVariables);
    }
    
    public String getEnvironmentVariable(String key) {
        return environmentVariables.get(key);
    }
    
    public boolean isStrictMode() {
        return strictMode;
    }
    
    public int getMaxRetries() {
        return maxRetries;
    }
    
    public double getConfidenceThreshold() {
        return confidenceThreshold;
    }
    
    // Utility methods
    public boolean hasSessionValue(String key) {
        return sessionState.containsKey(key);
    }
    
    /**
     * Whether a verb a model wrote names a command that can run.
     *
     * <p>Asked of the verb the dispatcher will use, not the verb as typed. Every parser screens an
     * action with this before marking it INVALID_COMMAND, and the dispatcher resolves synonyms and
     * strips a leading slash through
     * {@link com.eonmux.cadetcoder.commands.CommandAliases#canonicalize(String)}. Comparing the raw
     * spelling against the registry's keys asked a different question from the one that decides
     * what runs: {@code show}, {@code create}, {@code list}, {@code execute} and {@code /read} were
     * all refused here and would all have dispatched correctly, so a well-formed action was dropped
     * and the turn spent on a "could not be parsed" correction.</p>
     *
     * @param command the verb as the model wrote it
     * @return whether it resolves to a registered command
     */
    public boolean isCommandAvailable(String command) {
        return availableCommands.contains(
                com.eonmux.cadetcoder.commands.CommandAliases.canonicalize(command));
    }
    
    public boolean wasCommandRecentlyUsed(String command) {
        return recentCommands.contains(command);
    }
    
    public String getMostRecentCommand() {
        return recentCommands.isEmpty() ? null : recentCommands.get(recentCommands.size() - 1);
    }
    
    public List<String> getRecentCommands(int count) {
        if (recentCommands.size() <= count) {
            return new ArrayList<>(recentCommands);
        }
        return new ArrayList<>(recentCommands.subList(recentCommands.size() - count, recentCommands.size()));
    }
    
    /**
     * Infers the likely intent from the user request
     */
    public String inferIntent() {
        if (userRequest == null) return "unknown";
        
        String lower = userRequest.toLowerCase();
        
        if (lower.contains("read") || lower.contains("show") || lower.contains("display") || lower.contains("view")) {
            return "read";
        }
        if (lower.contains("write") || lower.contains("create") || lower.contains("save")) {
            return "write";
        }
        if (lower.contains("edit") || lower.contains("modify") || lower.contains("change") || lower.contains("update")) {
            return "edit";
        }
        if (lower.contains("search") || lower.contains("find") || lower.contains("grep")) {
            return "search";
        }
        if (lower.contains("list") || lower.contains("ls") || lower.contains("dir")) {
            return "list";
        }
        if (lower.contains("run") || lower.contains("execute") || lower.contains("bash")) {
            return "execute";
        }
        if (lower.contains("analyze") || lower.contains("explain")) {
            return "analyze";
        }
        
        return "unknown";
    }
    
    /**
     * Suggests the most likely command based on the user request and context
     */
    public String suggestCommand() {
        String intent = inferIntent();
        
        // Map intents to preferred commands
        switch (intent) {
            case "read": return "read";
            case "write": return "write"; 
            case "edit": return "edit";
            case "search": return "grep";
            case "list": return "ls";
            case "execute": return "bash";
            case "analyze": return "analyze";
            default:
                // Fallback to most recent command if available
                return getMostRecentCommand();
        }
    }
    
    /**
     * Determines if the user request suggests multiple actions
     */
    public boolean isMultiStepRequest() {
        if (userRequest == null) return false;
        
        String lower = userRequest.toLowerCase();
        
        // Look for conjunctions and sequence indicators
        return lower.contains(" and ") || 
               lower.contains(" then ") || 
               lower.contains(" after ") ||
               lower.contains(" first ") ||
               lower.contains(" next ") ||
               lower.contains(" finally ");
    }
    
    /**
     * Extracts potential file paths from the user request.
     *
     * @return file-looking words in the request, in order
     */
    public List<String> extractPotentialFilePaths() {
        return extractPotentialFilePaths(userRequest);
    }

    /**
     * Extracts potential file paths from any text.
     *
     * <p>Static because a parser needs this for the text the MODEL produced, not only for the
     * request. Bound to the request alone, a parser that wanted the path out of an action had no
     * way to ask, and quietly used the request's path instead -- so an action naming one file could
     * be dispatched against a different one the user had merely mentioned.</p>
     *
     * @param text any text; {@code null} yields an empty list
     * @return file-looking words in the text, in order
     */
    public static List<String> extractPotentialFilePaths(String text) {
        List<String> paths = new ArrayList<>();
        if (text == null) return paths;
        
        // Simple regex to find file-like patterns
        String[] words = text.split("\\s+");
        for (String word : words) {
            // Look for file extensions or path separators
            if (word.contains(".") && (word.contains("/") || word.contains("\\") || word.matches(".*\\.[a-zA-Z0-9]{1,4}$"))) {
                paths.add(word);
            }
        }
        
        return paths;
    }
    
    /**
     * Creates a context summary for debugging and logging
     */
    public String getContextSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("ParsingContext Summary:\n");
        sb.append("  User Request: ").append(userRequest).append("\n");
        sb.append("  Working Directory: ").append(workingDirectory).append("\n");
        sb.append("  Inferred Intent: ").append(inferIntent()).append("\n");
        sb.append("  Suggested Command: ").append(suggestCommand()).append("\n");
        sb.append("  Recent Commands: ").append(recentCommands).append("\n");
        sb.append("  Available Commands: ").append(availableCommands.size()).append(" commands\n");
        sb.append("  Multi-step Request: ").append(isMultiStepRequest()).append("\n");
        sb.append("  Strict Mode: ").append(strictMode).append("\n");
        sb.append("  Confidence Threshold: ").append(confidenceThreshold).append("\n");
        
        List<String> potentialPaths = extractPotentialFilePaths();
        if (!potentialPaths.isEmpty()) {
            sb.append("  Potential File Paths: ").append(potentialPaths).append("\n");
        }
        
        return sb.toString();
    }
    
    @Override
    public String toString() {
        return String.format("ParsingContext{userRequest='%s', workingDirectory='%s', recentCommands=%d, availableCommands=%d}", 
                           userRequest, workingDirectory, recentCommands.size(), availableCommands.size());
    }
}