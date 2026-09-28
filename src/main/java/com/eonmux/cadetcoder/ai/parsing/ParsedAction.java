package com.eonmux.cadetcoder.ai.parsing;


import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.Arrays;
import java.util.Objects;

/**
 * Represents a single parsed action from an AI response.
 * This class encapsulates command, parameters, reasoning, and validation results.
 */
public class ParsedAction {
    
    public enum ValidationResult {
        VALID,              // Action is valid and ready for execution
        INVALID_COMMAND,    // Command is not recognized
        INVALID_PARAMETERS, // Parameters are missing or malformed
        SECURITY_RISK,      // Action poses security concerns
        PATH_ERROR,         // File path issues detected
        REQUIRES_INFERENCE  // Parameters need intelligent inference
    }
    
    private final String command;
    private final Map<String, Object> parameters;
    private final String reasoning;
    private final double confidence;
    private final ValidationResult validation;
    private final String validationMessage;
    private final Map<String, Object> metadata;
    
    public ParsedAction(String command, Map<String, Object> parameters, String reasoning) {
        this(command, parameters, reasoning, 1.0, ValidationResult.VALID, null);
    }
    
    public ParsedAction(String command, Map<String, Object> parameters, String reasoning, 
                       double confidence, ValidationResult validation, String validationMessage) {
        this.command = command != null ? command.toLowerCase().trim() : "";
        this.parameters = new HashMap<>(parameters != null ? parameters : new HashMap<>());
        this.reasoning = reasoning != null ? reasoning.trim() : "";
        this.confidence = Math.max(0.0, Math.min(1.0, confidence));
        this.validation = validation != null ? validation : ValidationResult.VALID;
        this.validationMessage = validationMessage;
        this.metadata = new HashMap<>();
    }
    
    // Builder pattern for complex construction
    public static class Builder {
        private String command;
        private Map<String, Object> parameters = new HashMap<>();
        private String reasoning;
        private double confidence = 1.0;
        private ValidationResult validation = ValidationResult.VALID;
        private String validationMessage;
        private Map<String, Object> metadata = new HashMap<>();
        
        public Builder(String command) {
            this.command = command;
        }
        
        public Builder addParameter(String key, Object value) {
            this.parameters.put(key, value);
            return this;
        }
        
        public Builder setParameters(Map<String, Object> parameters) {
            this.parameters = new HashMap<>(parameters);
            return this;
        }
        
        public Builder setReasoning(String reasoning) {
            this.reasoning = reasoning;
            return this;
        }
        
        public Builder setConfidence(double confidence) {
            this.confidence = confidence;
            return this;
        }
        
        public Builder setValidation(ValidationResult validation, String message) {
            this.validation = validation;
            this.validationMessage = message;
            return this;
        }
        
        public Builder addMetadata(String key, Object value) {
            this.metadata.put(key, value);
            return this;
        }
        
        public ParsedAction build() {
            ParsedAction action = new ParsedAction(command, parameters, reasoning, confidence, validation, validationMessage);
            action.metadata.putAll(this.metadata);
            return action;
        }
    }
    
    // Getters
    public String getCommand() {
        return command;
    }
    
    public Map<String, Object> getParameters() {
        return new HashMap<>(parameters);
    }
    
    public Object getParameter(String key) {
        return parameters.get(key);
    }
    
    public String getStringParameter(String key) {
        Object value = parameters.get(key);
        return value != null ? value.toString() : null;
    }
    
    public String getStringParameter(String key, String defaultValue) {
        Object value = parameters.get(key);
        return value != null ? value.toString() : defaultValue;
    }
    
    public Integer getIntegerParameter(String key) {
        Object value = parameters.get(key);
        if (value instanceof Integer) {
            return (Integer) value;
        } else if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
    
    public Boolean getBooleanParameter(String key) {
        Object value = parameters.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        } else if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        }
        return null;
    }
    
    public String getReasoning() {
        return reasoning;
    }
    
    public double getConfidence() {
        return confidence;
    }
    
    public ValidationResult getValidation() {
        return validation;
    }
    
    public String getValidationMessage() {
        return validationMessage;
    }
    
    public Map<String, Object> getMetadata() {
        return new HashMap<>(metadata);
    }
    
    // Utility methods
    public boolean isValid() {
        return validation == ValidationResult.VALID;
    }
    
    public boolean hasParameter(String key) {
        return parameters.containsKey(key);
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
    
    public boolean requiresFileAccess() {
        Map<String, Object> byNormalizedKey = normalizedKeys(parameters);
        for (String key : FILE_PATH_KEYS) {
            if (byNormalizedKey.get(key) != null) {
                return true;
            }
        }
        return false;
    }

    public String getFilePath() {
        return filePathIn(parameters);
    }

    /**
     * The file a set of parameters names, whatever the model called the parameter.
     *
     * <p>Static so that a parser can ask the question before it has an action to ask it of. It used
     * to be answerable only from a built {@link ParsedAction}, which is why every parser validating
     * a set of parameters kept a shortened copy of this list instead -- see {@link FilePathRule}.</p>
     *
     * @param parameters the parameters as the model wrote them
     * @return the path the dispatcher would use, or {@code null} if there is none to use
     */
    static String filePathIn(Map<String, Object> parameters) {
        // Matched on the normalised spelling, so file_path, filePath, FilePath and file-path are one
        // key: which of them a model writes is a house style, not a different parameter.
        Map<String, Object> byNormalizedKey = normalizedKeys(parameters);
        // Scalar aliases first, in priority order.
        for (String key : new String[] {"filepath", "path", "filename", "file"}) {
            String path = firstPathString(byNormalizedKey.get(key));
            if (path != null) {
                return path;
            }
        }
        // List-valued aliases (e.g. {"files": ["a","b"]}) — read the first path; the harness runs one
        // file per iteration and the model requests the remainder on the next turn.
        for (String key : new String[] {"files", "paths"}) {
            String path = firstPathString(byNormalizedKey.get(key));
            if (path != null) {
                return path;
            }
        }
        return null;
    }

    /**
     * Extracts a usable, trimmed path string from a parameter value that may be a plain String or a
     * {@code List} (in which case the first non-blank element is used). Returns {@code null} when no
     * non-blank path is available.
     */
    static String firstPathString(Object value) {
        if (value instanceof java.util.Collection) {
            for (Object element : (java.util.Collection<?>) value) {
                String text = scalarText(element);
                if (text != null && !text.trim().isEmpty()) {
                    return text.trim();
                }
            }
            return null;
        }
        String text = scalarText(value);
        if (text != null && !text.trim().isEmpty()) {
            return text.trim();
        }
        return null;
    }

    /**
     * The text of a SCALAR parameter value, or {@code null} when the value is structured.
     *
     * <p>{@code JSONSchemaParser} preserves a JSON object as a {@code Map} and an array as a
     * {@code List}, so a parameter can hold a structure that a scalar-shaped command arm has no way
     * to use. Calling {@code toString()} on it would put {@code {old_string=a, new_string=b}} on the
     * command line as one argument. An arm that does not understand a structure is better off
     * treating it as absent -- it then falls back on whatever it does with a missing parameter,
     * which is at worst a clear error rather than a plausible-looking wrong one.</p>
     *
     * @param value the raw parameter value (may be {@code null})
     * @return the scalar text, or {@code null} for {@code null}, a collection or a map
     */
    static String scalarText(Object value) {
        if (value == null || value instanceof java.util.Collection || value instanceof Map) {
            return null;
        }
        return value.toString();
    }

    /**
     * Coerces a parameter value to a single argument string without a hard {@code (String)} cast. A
     * {@code List} (which the model may emit for a field expected to be scalar, e.g. a {@code pattern}
     * array) yields its first non-blank element rather than throwing {@link ClassCastException} or
     * emitting a {@code "[a, b]"} blob; any other non-null value is its {@code toString()}. Returns
     * {@code null} when there is no usable value.
     */
    /**
     * Coerces a parameter into a list of strings.
     *
     * <p>A model may supply a batch of paths as a JSON array, as a single whitespace- or
     * comma-separated string, or as one plain value; all three mean the same thing, so all three are
     * accepted rather than silently yielding an empty batch.</p>
     *
     * @param value the raw parameter value (may be {@code null})
     * @return the coerced list, never {@code null}
     */
    static java.util.List<String> asStringList(Object value) {
        java.util.List<String> result = new java.util.ArrayList<>();
        if (value == null) {
            return result;
        }
        if (value instanceof java.util.Collection) {
            for (Object element : (java.util.Collection<?>) value) {
                String text = asString(element);
                if (text != null && !text.trim().isEmpty()) {
                    result.add(text.trim());
                }
            }
            return result;
        }
        String text = asString(value);
        if (text == null || text.trim().isEmpty()) {
            return result;
        }
        for (String part : text.trim().split("[,\\s]+")) {
            if (!part.isEmpty()) {
                result.add(part);
            }
        }
        return result;
    }

    static String asString(Object value) {
        if (value instanceof java.util.Collection) {
            return firstPathString(value);
        }
        return scalarText(value);
    }

    /**
     * Coerces a parameter value to a single shell command line. Unlike {@link #asString(Object)}, a
     * {@code List} is JOINED with spaces rather than reduced to its first element: for a shell command
     * the list is the argv of ONE command ({@code ["ls","-la"]}), so keeping only {@code "ls"} would
     * execute a different command than the model requested. Returns {@code null} when there is no
     * usable value.
     */
    static String asCommandLine(Object value) {
        if (value instanceof java.util.Collection) {
            StringBuilder joined = new StringBuilder();
            for (Object element : (java.util.Collection<?>) value) {
                String text = scalarText(element);
                if (text == null || text.trim().isEmpty()) {
                    continue;
                }
                if (joined.length() > 0) {
                    joined.append(' ');
                }
                joined.append(text.trim());
            }
            return joined.length() > 0 ? joined.toString() : null;
        }
        return scalarText(value);
    }
    
    /**
     * The command this action will actually be dispatched to, whatever verb the model wrote.
     *
     * <p>{@link com.eonmux.cadetcoder.commands.CommandAliases} is where the synonyms live, and
     * asking it is how a classification here stays the same classification the dispatcher makes.
     * Written out separately, the two drifted immediately: {@code view} and {@code display} were
     * reads to the dispatcher and not to {@link #isReadOperation}, {@code change} was an edit to one
     * and not the other, and {@code exec} ran a shell command that {@link #isExecutionOperation}
     * said was not one.</p>
     *
     * @return the canonical command name, or {@code null} when this action names no command
     */
    public String dispatchedCommand() {
        return com.eonmux.cadetcoder.commands.CommandAliases.canonicalize(command);
    }

    public boolean isReadOperation() {
        return "read".equals(dispatchedCommand());
    }
    
    public boolean isWriteOperation() {
        return "write".equals(dispatchedCommand());
    }
    
    public boolean isModifyOperation() {
        String dispatched = dispatchedCommand();
        return "edit".equals(dispatched) || "multiedit".equals(dispatched);
    }
    
    public boolean isExecutionOperation() {
        return SHELL_RUNNER_COMMAND.equals(dispatchedCommand());
    }

    /**
     * The shell command line this action runs, or {@code null} when it runs none.
     *
     * <p>Every screen that wants to know what a proposed action will do to the machine has to ask
     * this question, and each one that answered it privately answered it differently from the
     * dispatcher.</p>
     *
     * <p>{@code ai.parsing.SecurityValidator} asked whether the verb was literally one of
     * {@code bash}, {@code shell} or {@code execute}. But {@code CommandAliases} also maps
     * {@code run} and {@code exec} onto the shell runner -- the very reason that class exists, as
     * its own javadoc says -- so a model that wrote {@code COMMAND: run} had its command line
     * dispatched to {@code bash} without the denylist, the network rule or the injection check ever
     * looking at it. {@code JSONSchemaParser} asked the same question about {@code bash} and
     * {@code execute} only.</p>
     *
     * <p>The second half is where the line lives. Those screens read the {@code command} parameter,
     * which is the shape the JSON and XML front ends produce; an {@code ACTION_START} block writes
     * {@code ARGS: <line>} into {@code args} instead, and {@link #appendRawArgumentLine} hands that
     * to the shell runner whole. So the screens saw nothing at all for the front end the agentic
     * loop actually uses. Both keys are read here, joined the way {@code BashCommand} joins the
     * argv it is given, so what is screened is the line that runs.</p>
     *
     * @return the command line, or {@code null} when this action is not a shell invocation or
     *         carries no command line
     */
    public String shellCommandLine() {
        return shellCommandLineOf(command, parameters);
    }

    /**
     * The shell command line an action would run, answered before a {@link ParsedAction} exists.
     *
     * <p>For the strategies that validate a command name and a parameter map on their way to
     * building one.</p>
     *
     * @param command    the verb as the model wrote it
     * @param parameters the action's parameters
     * @return the command line, or {@code null} when this is not a shell invocation
     */
    public static String shellCommandLineOf(String command, Map<String, Object> parameters) {
        if (parameters == null
            || !SHELL_RUNNER_COMMAND.equals(
                    com.eonmux.cadetcoder.commands.CommandAliases.canonicalize(command))) {
            return null;
        }
        java.util.List<String> parts = new java.util.ArrayList<>();
        String structured = asCommandLine(parameters.get("command"));
        if (structured != null && !structured.trim().isEmpty()) {
            parts.add(structured.trim());
        }
        String rawArgs = asString(parameters.get(RAW_ARGS_KEY));
        if (rawArgs != null && !rawArgs.trim().isEmpty()) {
            parts.add(rawArgs.trim());
        }
        return parts.isEmpty() ? null : String.join(" ", parts);
    }
    
    public boolean isSearchOperation() {
        return "grep".equals(command) || "search".equals(command) || "find".equals(command) || "glob".equals(command);
    }
    

    /**
     * This action as the command it dispatches under and the argv it dispatches with.
     *
     * <p>The rendering itself lives in {@link ActionArguments}, because the shape of every
     * command's argument list is a different question from what one parsed action is. This class
     * answers what the model asked for; that one answers how each command wants to be asked.</p>
     *
     * @return the dispatchable action
     */
    public com.eonmux.cadetcoder.commands.ChatCommand.AIAction toLegacyAction() {
        return ActionArguments.legacyActionOf(this);
    }

    /**
     * The parameters as the model wrote them, for the same-package renderer that only reads them.
     *
     * <p>{@link #getParameters()} copies, which is right for a caller that may keep or change what
     * it is handed; the renderer reads every parameter of every action and would copy the map once
     * per arm for nothing.</p>
     *
     * @return the parameters, unmodifiable
     */
    Map<String, Object> parameters() {
        return java.util.Collections.unmodifiableMap(parameters);
    }

    /** The one command that is handed a whole command line rather than an argument list. */
    static final String SHELL_RUNNER_COMMAND = "bash";

    /**
     * The key under which a recovered action carries its entire unparsed argument line. Consumed by
     * the tokenising tail of {@link ActionArguments}, so it must never also be emitted as a bare
     * positional -- that would repeat the whole line as one unsplit token.
     */
    static final String RAW_ARGS_KEY = "args";

    /**
     * The parameter keys, NORMALISED (lower-cased, {@code _} and {@code -} removed), that name a
     * file the action operates on. Normalised because a model's choice between {@code file_path},
     * {@code filePath} and {@code file-path} is a spelling, not a different parameter -- and a
     * spelling this list did not carry was both missed by {@link #getFilePath()} and re-emitted as
     * a bare positional in alphabetical order.
     *
     * <p>Includes the alias keys ({@code file}, {@code files}, {@code paths}) a model may use so
     * that, once the canonical path is taken, the aliases are not appended again -- a {@code files}
     * list does not duplicate the path it produced.</p>
     */
    static final Set<String> FILE_PATH_KEYS =
            new LinkedHashSet<>(Arrays.asList(
                    "filepath", "path", "filename", "file", "files", "paths"));

    /**
     * Re-keys a map so that {@code old_string}, {@code oldString}, {@code OLD-STRING} and
     * {@code oldstring} are all the same key. Spelling is the model's choice, not the schema's.
     */
    static Map<String, Object> normalizedKeys(Map<?, ?> source) {
        Map<String, Object> normalized = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            normalized.putIfAbsent(normalizeKey(entry.getKey().toString()), entry.getValue());
        }
        return normalized;
    }

    /** The one spelling rule: lower case, with {@code _} and {@code -} removed. */
    static String normalizeKey(String key) {
        return key.toLowerCase().replace("_", "").replace("-", "");
    }

    /**
     * Creates a copy of this action with updated validation
     */
    public ParsedAction withValidation(ValidationResult validation, String message) {
        ParsedAction updated = new ParsedAction(command, parameters, reasoning, confidence, validation, message);
        updated.metadata.putAll(this.metadata);
        return updated;
    }
    
    /**
     * Creates a copy of this action with updated confidence
     */
    public ParsedAction withConfidence(double newConfidence) {
        ParsedAction updated = new ParsedAction(command, parameters, reasoning, newConfidence, validation, validationMessage);
        updated.metadata.putAll(this.metadata);
        return updated;
    }
    
    /**
     * Creates a copy of this action with additional parameters
     */
    public ParsedAction withParameter(String key, Object value) {
        Map<String, Object> newParams = new HashMap<>(parameters);
        newParams.put(key, value);
        ParsedAction updated = new ParsedAction(command, newParams, reasoning, confidence, validation, validationMessage);
        updated.metadata.putAll(this.metadata);
        return updated;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ParsedAction that = (ParsedAction) o;
        return Double.compare(that.confidence, confidence) == 0 &&
               Objects.equals(command, that.command) &&
               Objects.equals(parameters, that.parameters) &&
               Objects.equals(reasoning, that.reasoning) &&
               validation == that.validation;
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(command, parameters, reasoning, confidence, validation);
    }
    
    @Override
    public String toString() {
        return String.format("ParsedAction{command='%s', parameters=%s, reasoning='%s', confidence=%.2f, validation=%s}", 
                           command, parameters, reasoning, confidence, validation);
    }
}