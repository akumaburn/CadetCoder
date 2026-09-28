package com.eonmux.cadetcoder.ai.parsing;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.logging.DebugLogger;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Comparator;
import java.util.stream.Collectors;

/**
 * Main engine that coordinates multiple parsing strategies to extract actions from AI responses.
 * Tries each registered strategy in priority order and keeps the best structured result.
 */
public class ResponseParsingEngine {
    
    private final List<ParsingStrategy> strategies;
    private final DebugLogger debugLogger;
    private final boolean enableFallbacks;
    private final double confidenceThreshold;
    private final int maxRetryAttempts;
    
    // Resilience components
    private final SecurityValidator securityValidator;
    private final CircuitBreaker circuitBreaker;
    private final ErrorRecoveryManager errorRecoveryManager;
    
    // Statistics tracking
    private int totalParses = 0;
    private int successfulParses = 0;
    private int fallbackUses = 0;
    private int recoveryAttempts = 0;
    
    public ResponseParsingEngine() {
        this.debugLogger = DebugLogger.getInstance();
        // Initialize with default values
        this.enableFallbacks = true;
        this.confidenceThreshold = 0.7;
        this.maxRetryAttempts = 3;
        
        // Initialize resilience components
        this.securityValidator = new SecurityValidator();
        this.circuitBreaker = CircuitBreaker.defaultInstance();
        this.errorRecoveryManager = new ErrorRecoveryManager();
        
        // Initialize parsing strategies in priority order
        this.strategies = initializeStrategies();
        
        debugLogger.debug("ResponseParsingEngine", 
            String.format("Initialized with %d strategies, confidence threshold: %.2f, resilience components enabled", 
                         strategies.size(), confidenceThreshold));
    }
    
    private List<ParsingStrategy> initializeStrategies() {
        List<ParsingStrategy> strategyList = new ArrayList<>();
        
        // Add strategies in priority order
        strategyList.add(new JSONSchemaParser());
        strategyList.add(new ActionBlockParser());
        strategyList.add(new ToolCallParser());
        strategyList.add(new XMLActionParser());
        strategyList.add(new SemanticParser());
        strategyList.add(new FuzzyParser());
        
        // Sort by priority to ensure correct order
        return strategyList.stream()
            .sorted(Comparator.comparingInt(ParsingStrategy::getPriority))
            .collect(Collectors.toList());
    }
    
    /**
     * Main parsing method that attempts to extract actions from an AI response.
     * Uses the multi-tier approach with intelligent fallbacks and resilience features.
     */
    public ParsedResponse parseResponse(String aiResponse, String userRequest) {
        totalParses++;
        long startTime = System.currentTimeMillis();
        
        debugLogger.debug("ResponseParsingEngine", 
            "Starting parse attempt " + totalParses + " for response: " + 
            (aiResponse != null ? aiResponse.substring(0, Math.min(100, aiResponse.length())) + "..." : "null"));
        
        // Security check - validate response safety before parsing
        if (!securityValidator.isResponseSafe(aiResponse)) {
            return recorded(new ParsedResponse.Builder(ParsedResponse.ParseResult.SECURITY_ERROR,
                                            ParsedResponse.ParsingStrategy.JSON_SCHEMA, aiResponse)
                .addError("Response failed security validation")
                .addError("Potential security threat detected in AI response")
                .setProcessingTime(System.currentTimeMillis() - startTime)
                .build());
        }
        
        // Build parsing context
        ParsingContext context = buildParsingContext(userRequest);
        
        // Use circuit breaker to protect against cascading failures
        try {
            return recorded(circuitBreaker.execute(() -> parseWithStrategies(aiResponse, context, startTime)));
        } catch (CircuitBreaker.CircuitBreakerException e) {
            debugLogger.warn("ResponseParsingEngine", "Circuit breaker open - falling back to error recovery");
            return recorded(handleCircuitBreakerFailure(aiResponse, context, startTime, e.getMessage()));
        } catch (RuntimeException e) {
            // The circuit breaker re-throws any failure of the wrapped operation as a RuntimeException.
            // Previously only CircuitBreakerException was caught, so an unchecked failure inside a
            // parser or validator (e.g. a ClassCastException on an array-valued parameter) escaped
            // parseResponse entirely and aborted the caller's turn. Report it as a parse failure -
            // never propagate - so a malformed model response can only ever cost one re-prompt.
            debugLogger.error("ResponseParsingEngine", "Unexpected failure while parsing AI response", e);
            return recorded(handleUnexpectedParsingFailure(aiResponse, startTime, e));
        }
    }

    /**
     * Writes the full diagnostic for an outcome somebody would want to debug.
     *
     * <p>Every exit from {@link #parseResponse} passes through here. A parse that succeeded with
     * confidence is not interesting and is left to the per-strategy debug lines already logged; a
     * failure, or a result the engine itself is unsure of, is exactly the case where the strategy,
     * the confidence, the errors and the metadata are worth having together in one place. Without
     * this the detail was assembled by {@link ParsedResponse#getDiagnosticInfo()} and never read
     * by anything.</p>
     *
     * @param response the outcome about to be returned
     * @return the same response, unchanged
     */
    private ParsedResponse recorded(ParsedResponse response) {
        if (response != null && (!response.isSuccessful() || response.isLowConfidence())) {
            debugLogger.debug("ResponseParsingEngine", response.getDiagnosticInfo());
        }
        return response;
    }

    /**
     * Builds the failure response returned when parsing blew up unexpectedly. Reports a FORMAT error
     * (the turn can be retried with a format correction) and surfaces the underlying exception so the
     * cause is visible in logs and diagnostics rather than being swallowed.
     */
    private ParsedResponse handleUnexpectedParsingFailure(String aiResponse, long startTime, Throwable cause) {
        Throwable root = cause.getCause() != null ? cause.getCause() : cause;
        return new ParsedResponse.Builder(ParsedResponse.ParseResult.FORMAT_ERROR,
                                        ParsedResponse.ParsingStrategy.JSON_SCHEMA, aiResponse)
            .addError("Parsing failed unexpectedly: " + root.getClass().getSimpleName() +
                      (root.getMessage() != null ? ": " + root.getMessage() : ""))
            .addError("Unable to extract actionable content from AI response")
            .setProcessingTime(System.currentTimeMillis() - startTime)
            .build();
    }
    
    /**
     * Parses response using available strategies with circuit breaker protection.
     *
     * <p>The winning strategy is the one with the highest confidence, NOT the last one that happened
     * to succeed. Both natural-language fallbacks ({@link SemanticParser}, {@link FuzzyParser}) accept
     * any non-empty string, so unconditionally overwriting the stored result meant a perfectly good
     * structured parse was routinely replaced by a guess - most dangerously, an ACTION block missing
     * only its {@code REASON:} line was re-read by the semantic parser as a {@code bash} execution of a
     * shell string synthesised from the model's prose. Two rules prevent that: a stored result is only
     * replaced by a STRICTLY better one, and once a structured parser has produced actions the
     * fallbacks are not consulted at all.</p>
     */
    private ParsedResponse parseWithStrategies(String aiResponse, ParsingContext context, long startTime) {
        // Best-so-far across all strategies, in priority order (ties resolved toward the earlier,
        // higher-priority strategy).
        ParsedResponse result = null;
        ParsingStrategy usedStrategy = null;
        boolean structuredActionsFound = false;

        for (ParsingStrategy strategy : strategies) {
            // HARD RULE: a structured format that parsed into actions is authoritative. Never let a
            // natural-language fallback reinterpret it.
            if (structuredActionsFound && strategy.isFallbackStrategy()) {
                debugLogger.debug("ResponseParsingEngine",
                    "Skipping fallback " + strategy.getStrategyName() +
                    " - a structured strategy already produced actions");
                continue;
            }

            if (!strategy.canHandle(aiResponse)) {
                debugLogger.debug("ResponseParsingEngine",
                    strategy.getStrategyName() + " cannot handle this response");
                continue;
            }

            try {
                debugLogger.debug("ResponseParsingEngine",
                    "Attempting parse with " + strategy.getStrategyName());

                ParsedResponse strategyResult = strategy.parse(aiResponse, context);

                if (strategyResult.isSuccessful()) {
                    if (isStructuredResult(strategyResult) && strategyResult.hasActions()) {
                        structuredActionsFound = true;
                    }

                    if (isBetterResult(strategyResult, strategy, result, usedStrategy)) {
                        result = strategyResult;
                        usedStrategy = strategy;

                        debugLogger.debug("ResponseParsingEngine",
                            String.format("Parse successful with %s (confidence: %.2f) - new best",
                                         strategy.getStrategyName(), strategyResult.getConfidence()));
                    } else {
                        debugLogger.debug("ResponseParsingEngine",
                            String.format("Parse successful with %s (confidence: %.2f) - keeping %s",
                                         strategy.getStrategyName(), strategyResult.getConfidence(),
                                         usedStrategy.getStrategyName()));
                    }

                    // Check if confidence meets threshold
                    if (result.getConfidence() >= confidenceThreshold) {
                        break; // Use this result
                    } else if (!enableFallbacks) {
                        break; // Use this result even with low confidence if fallbacks disabled
                    }
                    // Otherwise continue to try other strategies for better confidence
                }
            } catch (Exception e) {
                debugLogger.error("ResponseParsingEngine",
                    "Strategy " + strategy.getStrategyName() + " threw exception", e);
                // Continue to next strategy
            }
        }

        // Whether the winner is only an inference over prose (no structured block was found at all).
        // Captured before post-processing, which rebuilds the response.
        boolean fallbackOnly = result != null && !isStructuredResult(result);

        // Post-process and validate the result
        if (result != null && result.isSuccessful()) {
            result = postProcessResult(result, context, usedStrategy);

            // Security validation of parsed actions
            result = validateAndEnforceSecurityPolicies(result, context);
        }

        // A reply the screen refused was read correctly, so there is nothing for recovery to repair,
        // and whatever it made of the reply would be refused for the same reason. Recovering anyway
        // replaced the refusal with "no structured action format found": the reason was lost, the
        // model was told its correct block was malformed, and the run ended on its format. Only a
        // structured action counts: a refused GUESS over prose is not something the model asked for,
        // and telling it that its action was refused would describe an action it never proposed.
        if (!fallbackOnly && refusedActions(result)) {
            return result;
        }

        // If all strategies failed, attempt error recovery
        if (result == null || !result.isSuccessful()) {
            String originalError = result != null ? String.join("; ", result.getErrors()) : "All parsing strategies failed";
            result = attemptErrorRecovery(aiResponse, context, startTime, originalError);
        } else if (fallbackOnly) {
            // No structured action format was present at all: the model answered in prose (or emitted
            // something unparseable). Report that as a FORMAT error so the caller can re-prompt for the
            // documented format instead of silently executing an invented command.
            result = markFallbackOnly(result);
        }

        // Counted from what is RETURNED, not from what survived the security check.
        //
        // The increments used to sit above, before the fallback-only demotion rewrote the result as
        // a format error. A model answering in prose was counted as a successful parse and then
        // reported to the caller as unparseable, so ten prose answers in a row left the engine
        // claiming a 100% success rate having parsed nothing -- permanently, since the counter is
        // never put back.
        if (result != null && result.isSuccessful()) {
            successfulParses++;
            if (usedStrategy != null && usedStrategy.isFallbackStrategy()) {
                fallbackUses++;
            }
        }

        // Log parsing statistics
        debugLogger.debug("ResponseParsingEngine", 
            String.format("Parse completed in %dms. Success rate: %.2f%% (%d/%d), Fallback usage: %.2f%%", 
                         System.currentTimeMillis() - startTime,
                         (double) successfulParses / totalParses * 100,
                         successfulParses, totalParses,
                         (double) fallbackUses / totalParses * 100));
        
        return result;
    }
    
    /**
     * Strategies that read an explicitly-formatted action out of the response rather than inferring
     * one from prose. Only these count as a genuine tool request.
     */
    private static final java.util.EnumSet<ParsedResponse.ParsingStrategy> STRUCTURED_STRATEGIES =
        java.util.EnumSet.of(ParsedResponse.ParsingStrategy.JSON_SCHEMA,
                             ParsedResponse.ParsingStrategy.ACTION_BLOCK,
                             ParsedResponse.ParsingStrategy.TOOL_CALL,
                             ParsedResponse.ParsingStrategy.XML_ACTION);

    /**
     * Metadata key set on a result that ONLY a natural-language fallback could produce. Such a result
     * is returned with {@link ParsedResponse.ParseResult#FORMAT_ERROR} and its (guessed) actions still
     * attached for diagnostics; callers must treat it as "the model did not emit an action", never as
     * an executable request.
     */
    public static final String FALLBACK_ONLY_METADATA = "fallback_only";

    /**
     * Metadata key set on a {@link ParsedResponse.ParseResult#SECURITY_ERROR} whose actions were
     * read and then refused one by one. A reply refused as a whole -- one that looked like prompt
     * injection, or was too long to parse -- has no action to refuse and does not carry it, so a
     * caller can tell "the model asked for something the screen will not run" apart from "the
     * reply itself was refused".
     */
    public static final String REFUSED_ACTIONS_METADATA = "refused_actions";

    /**
     * Whether {@code response} holds actions the screen refused (see
     * {@link #REFUSED_ACTIONS_METADATA}).
     *
     * @param response the parsed response to inspect (may be {@code null})
     * @return true when the reply asked for actions and every one of them was refused
     */
    public static boolean refusedActions(ParsedResponse response) {
        return response != null
            && response.getResult() == ParsedResponse.ParseResult.SECURITY_ERROR
            && Boolean.TRUE.equals(response.getMetadata().get(REFUSED_ACTIONS_METADATA));
    }

    /**
     * Whether {@code response} is a fallback-only parse (see {@link #FALLBACK_ONLY_METADATA}).
     *
     * @param response the parsed response to inspect (may be {@code null})
     * @return true when no structured action format was found in the AI response
     */
    public static boolean isFallbackOnly(ParsedResponse response) {
        return response != null
            && Boolean.TRUE.equals(response.getMetadata().get(FALLBACK_ONLY_METADATA));
    }

    /** Whether the result came from a structured (non-inferring) parsing strategy. */
    private boolean isStructuredResult(ParsedResponse response) {
        return response != null && STRUCTURED_STRATEGIES.contains(response.getStrategyUsed());
    }

    /**
     * Whether {@code candidate} should replace the stored best result. A candidate wins only by
     * STRICTLY exceeding the incumbent's confidence; on an exact tie the higher-priority strategy
     * (lower {@link ParsingStrategy#getPriority()}) wins, so a later fallback can never displace an
     * equally-confident structured parse.
     */
    private boolean isBetterResult(ParsedResponse candidate, ParsingStrategy candidateStrategy,
                                   ParsedResponse incumbent, ParsingStrategy incumbentStrategy) {
        if (incumbent == null || incumbentStrategy == null) {
            return true;
        }
        if (candidate.getConfidence() > incumbent.getConfidence()) {
            return true;
        }
        if (candidate.getConfidence() < incumbent.getConfidence()) {
            return false;
        }
        return candidateStrategy.getPriority() < incumbentStrategy.getPriority();
    }

    /**
     * Re-labels a successful fallback-only parse as a {@link ParsedResponse.ParseResult#FORMAT_ERROR}
     * carrying {@link #FALLBACK_ONLY_METADATA}. Actions, confidence, warnings and metadata are kept so
     * callers can still report what was guessed and why the turn needs a format correction.
     */
    private ParsedResponse markFallbackOnly(ParsedResponse result) {
        return markFallbackOnly(result,
            "Only the " + result.getStrategyUsed() + " fallback matched, which infers actions from prose");
    }

    private ParsedResponse markFallbackOnly(ParsedResponse result, String reason) {
        ParsedResponse.Builder builder = new ParsedResponse.Builder(
            ParsedResponse.ParseResult.FORMAT_ERROR, result.getStrategyUsed(), result.getOriginalResponse());

        for (ParsedAction action : result.getActions()) {
            builder.addAction(action);
        }
        for (String error : result.getErrors()) {
            builder.addError(error);
        }
        for (String warning : result.getWarnings()) {
            builder.addWarning(warning);
        }
        for (Map.Entry<String, Object> entry : result.getMetadata().entrySet()) {
            builder.addMetadata(entry.getKey(), entry.getValue());
        }

        return builder
            .addError("No structured action format (JSON / ACTION block / XML) found in the response")
            .addError(reason)
            .addMetadata(FALLBACK_ONLY_METADATA, true)
            .setConfidence(result.getConfidence())
            .setProcessingTime(result.getProcessingTimeMs())
            .build();
    }

    private ParsingContext buildParsingContext(String userRequest) {
        ParsingContext.Builder contextBuilder = new ParsingContext.Builder(userRequest)
            .workingDirectory(System.getProperty("user.dir"));

        // No session information is added. What used to be here fed two keys nothing reads:
        // "current_files" came from SessionState.openFiles, which nothing ever wrote, and the
        // recent-command list was a stub returning nothing while it waited to be "integrated with
        // session management".

        // Add available commands from registry
        try {
            CommandRegistry registry = new CommandRegistry();
            contextBuilder.setAvailableCommands(registry.getCommands().keySet());
        } catch (Exception e) {
            debugLogger.warn("ResponseParsingEngine", 
                "Could not load command registry for context: " + e.getMessage(), e);
        }
        
        return contextBuilder.build();
    }
    
    private ParsedResponse postProcessResult(ParsedResponse result, ParsingContext context, 
                                           ParsingStrategy usedStrategy) {
        
        // Enhance actions with additional validation
        List<ParsedAction> enhancedActions = new ArrayList<>();
        
        for (ParsedAction action : result.getActions()) {
            ParsedAction enhanced = enhanceAction(action, context);
            enhancedActions.add(enhanced);
        }
        
        // Create enhanced result
        ParsedResponse.Builder enhancedBuilder = new ParsedResponse.Builder(
            result.getResult(), 
            result.getStrategyUsed(), 
            result.getOriginalResponse()
        );
        
        for (ParsedAction action : enhancedActions) {
            enhancedBuilder.addAction(action);
        }
        
        enhancedBuilder
            .setConfidence(result.getConfidence())
            .setProcessingTime(result.getProcessingTimeMs())
            .addMetadata("post_processed", true)
            .addMetadata("strategy_used", usedStrategy.getStrategyName())
            .addMetadata("enhancement_applied", true);
        
        // Copy errors and warnings
        for (String error : result.getErrors()) {
            enhancedBuilder.addError(error);
        }
        for (String warning : result.getWarnings()) {
            enhancedBuilder.addWarning(warning);
        }
        
        return enhancedBuilder.build();
    }
    
    private ParsedAction enhanceAction(ParsedAction action, ParsingContext context) {
        // Apply parameter inference if needed
        if (action.getValidation() == ParsedAction.ValidationResult.REQUIRES_INFERENCE) {
            return applyParameterInference(action, context);
        }
        
        // Apply path resolution if needed
        if (action.requiresFileAccess() && action.getFilePath() != null) {
            String resolvedPath = resolveFilePath(action.getFilePath(), context);
            if (resolvedPath != null && !resolvedPath.equals(action.getFilePath())) {
                return action.withParameter("file_path", resolvedPath);
            }
        }
        
        return action;
    }
    
    private ParsedAction applyParameterInference(ParsedAction action, ParsingContext context) {
        Map<String, Object> inferredParams = new java.util.HashMap<>(action.getParameters());
        boolean enhanced = false;
        
        // Infer file path if missing
        if (action.requiresFileAccess() && action.getFilePath() == null) {
            List<String> potentialPaths = context.extractPotentialFilePaths();
            if (!potentialPaths.isEmpty()) {
                inferredParams.put("file_path", potentialPaths.get(0));
                enhanced = true;
            }
        }
        
        // Infer search pattern for grep commands
        if (action.getCommand().equals("grep") && !action.hasParameter("pattern")) {
            String inferredPattern = inferSearchPattern(context.getUserRequest());
            if (inferredPattern != null) {
                inferredParams.put("pattern", inferredPattern);
                enhanced = true;
            }
        }
        
        if (enhanced) {
            return new ParsedAction.Builder(action.getCommand())
                .setParameters(inferredParams)
                .setReasoning(action.getReasoning() + " [Parameters inferred]")
                .setConfidence(Math.max(0.5, action.getConfidence() * 0.9)) // Slight confidence penalty
                .setValidation(ParsedAction.ValidationResult.VALID, "Parameters inferred successfully")
                .addMetadata("inference_applied", true)
                .build();
        }
        
        return action;
    }
    
    private String resolveFilePath(String filePath, ParsingContext context) {
        // Only explicitly relative paths are rewritten. A bare name is left alone: resolving it
        // against the project would mean guessing which file the model meant, and a wrong guess
        // here silently retargets the command at a file the caller never named.
        if (filePath.startsWith("./") || filePath.startsWith("../")) {
            // Resolve relative paths
            return java.nio.file.Paths.get(context.getWorkingDirectory(), filePath)
                .normalize().toString();
        }
        
        return filePath;
    }
    
    private String inferSearchPattern(String userRequest) {
        if (userRequest == null) return null;
        
        String lowerRequest = userRequest.toLowerCase();
        
        // Look for common search terms
        if (lowerRequest.contains("class")) return "class.*";
        if (lowerRequest.contains("function") || lowerRequest.contains("method")) return "function.*|def .*";
        if (lowerRequest.contains("variable")) return "var .*|let .*|const .*";
        if (lowerRequest.contains("import")) return "import.*";
        if (lowerRequest.contains("todo")) return "TODO|FIXME";
        if (lowerRequest.contains("error")) return "error|Error|ERROR";
        
        return null;
    }
    
    private ParsedResponse createFailureResponse(String aiResponse, ParsingContext context, long startTime) {
        ParsedResponse.Builder failureBuilder = new ParsedResponse.Builder(
            ParsedResponse.ParseResult.FORMAT_ERROR,
            ParsedResponse.ParsingStrategy.JSON_SCHEMA, // Default strategy for error reporting
            aiResponse
        );
        
        failureBuilder
            .addError("All parsing strategies failed")
            .addError("Unable to extract actionable content from AI response")
            .setProcessingTime(System.currentTimeMillis() - startTime);
        
        // Add strategy-specific error details
        for (ParsingStrategy strategy : strategies) {
            if (strategy.canHandle(aiResponse)) {
                failureBuilder.addError(strategy.getStrategyName() + ": " + 
                                      strategy.getParsingErrorDetails(aiResponse));
            }
        }
        
        // Add suggestions based on context
        String suggestion = generateFormatSuggestion(context);
        if (suggestion != null) {
            failureBuilder.addWarning("Suggestion: " + suggestion);
        }
        
        return failureBuilder.build();
    }
    
    private String generateFormatSuggestion(ParsingContext context) {
        String intent = context.inferIntent();
        
        switch (intent) {
            case "read":
                return "Try: {\"action\": \"read\", \"parameters\": {\"file_path\": \"your/file/path\"}, \"reasoning\": \"Read the file\", \"confidence\": 0.9}";
            case "write":
                return "Try: {\"action\": \"write\", \"parameters\": {\"file_path\": \"output.txt\", \"content\": \"Hello World\"}, \"reasoning\": \"Create file\", \"confidence\": 0.9}";
            case "search":
                return "Try: {\"action\": \"grep\", \"parameters\": {\"pattern\": \"search_term\", \"path\": \"src/\"}, \"reasoning\": \"Search for pattern\", \"confidence\": 0.9}";
            default:
                return "Use JSON format: {\"action\": \"command\", \"parameters\": {...}, \"reasoning\": \"explanation\", \"confidence\": 0.9}";
        }
    }
    
    /**
     * Validates parsed actions against security policies and blocks dangerous operations.
     *
     * <p>Outer guard: no failure of the security layer may propagate out of a parse. If enforcement
     * itself breaks, the response is failed CLOSED as a security error rather than letting the
     * exception escape (which previously killed the parse and tripped the circuit breaker).</p>
     */
    private ParsedResponse validateAndEnforceSecurityPolicies(ParsedResponse response, ParsingContext context) {
        try {
            return applySecurityPolicies(response, context);
        } catch (RuntimeException e) {
            debugLogger.error("SecurityValidator", "Security policy enforcement failed - blocking response", e);
            return new ParsedResponse.Builder(ParsedResponse.ParseResult.SECURITY_ERROR,
                                            response.getStrategyUsed(), response.getOriginalResponse())
                .addError("Security policy enforcement failed: " + e.getClass().getSimpleName()
                          + (e.getMessage() != null ? " - " + e.getMessage() : ""))
                .setProcessingTime(response.getProcessingTimeMs())
                .build();
        }
    }

    /** Performs the actual per-action security validation; see {@link #validateAndEnforceSecurityPolicies}. */
    private ParsedResponse applySecurityPolicies(ParsedResponse response, ParsingContext context) {
        if (!response.isSuccessful() || response.getActions().isEmpty()) {
            return response;
        }
        
        List<ParsedAction> validatedActions = new ArrayList<>();
        List<String> securityViolations = new ArrayList<>();
        List<String> securityWarnings = new ArrayList<>();
        
        for (ParsedAction action : response.getActions()) {
            SecurityValidator.ValidationResult validation;
            try {
                validation = securityValidator.validateAction(action, context);
            } catch (RuntimeException e) {
                // A validator must never be able to abort the whole parse (it used to: an array-valued
                // 'command' parameter threw a ClassCastException that escaped through the circuit
                // breaker). Fail CLOSED - the action's safety could not be established, so it is
                // dropped and reported - but keep parsing the remaining actions.
                debugLogger.error("SecurityValidator",
                    "Security validation threw for action '" + action.getCommand() + "' - blocking it", e);
                securityViolations.add("Security validation failed for '" + action.getCommand() + "': "
                    + e.getClass().getSimpleName()
                    + (e.getMessage() != null ? " - " + e.getMessage() : ""));
                continue;
            }

            if (validation.isValid()) {
                validatedActions.add(action);
            } else {
                securityViolations.addAll(validation.getViolations());
                debugLogger.warn("SecurityValidator",
                    "Action blocked: " + action.getCommand() + " - " + validation.getSummary());
            }

            securityWarnings.addAll(validation.getWarnings());
        }

        // If all actions were blocked, return security error
        if (validatedActions.isEmpty() && !response.getActions().isEmpty()) {
            return new ParsedResponse.Builder(ParsedResponse.ParseResult.SECURITY_ERROR,
                                            response.getStrategyUsed(), response.getOriginalResponse())
                .addError("All actions blocked by security policies")
                .addError(String.join("; ", securityViolations))
                .addMetadata(REFUSED_ACTIONS_METADATA, true)
                .setProcessingTime(response.getProcessingTimeMs())
                .build();
        }
        
        // Build response with validated actions
        ParsedResponse.Builder validatedBuilder = new ParsedResponse.Builder(
            response.getResult(), response.getStrategyUsed(), response.getOriginalResponse());
        
        for (ParsedAction action : validatedActions) {
            validatedBuilder.addAction(action);
        }
        
        // Add security warnings
        for (String warning : securityWarnings) {
            validatedBuilder.addWarning("Security: " + warning);
        }
        
        return validatedBuilder
            .setConfidence(response.getConfidence())
            .setProcessingTime(response.getProcessingTimeMs())
            .build();
    }
    
    /**
     * Attempts error recovery when all parsing strategies fail.
     */
    private ParsedResponse attemptErrorRecovery(String aiResponse, ParsingContext context, 
                                              long startTime, String originalError) {
        recoveryAttempts++;
        debugLogger.debug("ErrorRecoveryManager", "Attempting error recovery #" + recoveryAttempts);
        
        ErrorRecoveryManager.RecoveryResult recovery = errorRecoveryManager.attemptRecovery(
            aiResponse, context, originalError);
        
        if (recovery.isSuccess()) {
            debugLogger.info("ErrorRecoveryManager", "Recovery successful: " + recovery.getStrategy());

            // Try parsing the recovered response
            try {
                ParsedResponse recovered = parseWithStrategies(recovery.getRecoveredResponse(), context, startTime);

                // A recovered parse is a REPAIR of the model's output, not the model's own action, so
                // it is only trusted while it still clears the acceptance threshold. The last-resort
                // recovery strategy always "succeeds" by emitting a parameterless {"action":"read"}:
                // accepting that turned every unparseable answer into a fabricated read that the
                // harness then executed and reported as a failed step. Below the threshold, report a
                // format failure so the turn is re-prompted instead.
                ParsedResponse kept = guardRecoveredConfidence(recovered, recovery.getStrategy());
                // Recorded HERE, where the recovery is accepted, rather than where it was produced.
                if (kept != null && kept.isSuccessful()) {
                    errorRecoveryManager.recordAccepted(originalError, recovery.getStrategy());
                }
                return kept;
            } catch (Exception e) {
                debugLogger.warn("ErrorRecoveryManager", "Failed to parse recovered response", e);
            }
        }
        
        // Recovery failed - return original failure
        return createFailureResponse(aiResponse, context, startTime);
    }
    
    /**
     * Refuses a recovered action that is only a guess.
     *
     * <p>Recovery's last-resort strategy always succeeds, by emitting a parameterless
     * {@code {"action":"read"}} at confidence 0.1. Accepting that turns every unparseable answer
     * into a fabricated read the harness executes and then reports as a failed step, so below the
     * threshold the turn is re-prompted for format instead.</p>
     *
     * <p>Shared by both recovery paths on purpose. It lived inline on the ordinary one and was
     * simply absent from the circuit-breaker one, which is reached only after five consecutive
     * failures -- precisely when the model is least likely to be producing something worth running.</p>
     *
     * @param recovered what recovery produced
     * @param strategy  the strategy that produced it, for the log line and the caller's message
     * @return {@code recovered} unchanged, or a fallback-only re-labelling of it
     */
    private ParsedResponse guardRecoveredConfidence(ParsedResponse recovered, String strategy) {
        if (recovered != null && recovered.isSuccessful()
                && recovered.getConfidence() < confidenceThreshold) {
            debugLogger.warn("ErrorRecoveryManager", String.format(
                "Discarding low-confidence recovered action (%.2f < %.2f) from strategy '%s'",
                recovered.getConfidence(), confidenceThreshold, strategy));
            return markFallbackOnly(recovered,
                "Recovered action from '" + strategy + "' was below the confidence threshold");
        }
        return recovered;
    }

    /**
     * Handles circuit breaker failures with graceful degradation.
     */
    private ParsedResponse handleCircuitBreakerFailure(String aiResponse, ParsingContext context, 
                                                     long startTime, String errorMessage) {
        debugLogger.warn("CircuitBreaker", "Handling circuit breaker failure: " + errorMessage);
        
        // Try error recovery as last resort
        ErrorRecoveryManager.RecoveryResult recovery = errorRecoveryManager.attemptRecovery(
            aiResponse, context, "Circuit breaker open");
        
        if (recovery.isSuccess()) {
            // Create a simple response without going through strategies again
            try {
                JSONSchemaParser simpleParser = new JSONSchemaParser();
                ParsedResponse result = simpleParser.parse(recovery.getRecoveredResponse(), context);
                if (result.isSuccessful()) {
                    return guardRecoveredConfidence(result, recovery.getStrategy());
                }
            } catch (Exception e) {
                debugLogger.debug("CircuitBreaker", "Recovery parsing failed", e);
            }
        }
        
        // Create failure response indicating circuit breaker state
        return new ParsedResponse.Builder(ParsedResponse.ParseResult.FORMAT_ERROR,
                                        ParsedResponse.ParsingStrategy.JSON_SCHEMA, aiResponse)
            .addError("Parsing service temporarily unavailable (circuit breaker open)")
            .addError("Too many consecutive parsing failures detected")
            .addWarning("Service will retry automatically after cooldown period")
            .setProcessingTime(System.currentTimeMillis() - startTime)
            .build();
    }
    
}