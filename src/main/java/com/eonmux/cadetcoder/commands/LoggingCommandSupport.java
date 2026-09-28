package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.logging.ObservabilityLogger;
import com.eonmux.cadetcoder.logging.SessionLogger;

import java.util.HashMap;
import java.util.Map;

/**
 * Base support class for commands that provides observability logging capabilities.
 * Commands can extend this class or use it as a mixin to get comprehensive logging.
 */
public abstract class LoggingCommandSupport {
    protected final ObservabilityLogger logger;
    protected final SessionLogger sessionLogger;
    protected final Map<String, Object> executionContext;
    private String currentCommandName;
    private long commandStartTime;
    
    protected LoggingCommandSupport() {
        this.logger = ObservabilityLogger.forCommand(this.getClass());
        this.sessionLogger = SessionLogger.getInstance();
        this.executionContext = new HashMap<>();
    }
    
    /**
     * Start logging for command execution
     */
    protected void startCommandLogging(String commandName, String[] args) {
        this.currentCommandName = commandName;
        this.commandStartTime = System.currentTimeMillis();
        this.executionContext.clear();
        
        logger.commandStart(commandName, args, executionContext);
    }
    
    /**
     * Complete command logging
     */
    protected void completeCommandLogging(int exitCode) {
        if (currentCommandName != null) {
            long duration = System.currentTimeMillis() - commandStartTime;
            logger.commandComplete(currentCommandName, exitCode, duration);
            // Reset per-invocation state so a later completeCommandLogging() without a matching
            // startCommandLogging() (re-entrant/concurrent use on the reused singleton) cannot log
            // a stale command name and a duration carried over from a previous run.
            currentCommandName = null;
            commandStartTime = 0L;
        }
    }
    
    /**
     * Log a step in command execution
     */
    protected void logStep(String step, String details) {
        logger.commandStep(step, details);
    }
    
    /**
     * Log an operation step with context
     */
    protected void logStep(String step) {
        logStep(step, null);
    }
    
    /**
     * Add context to the execution
     */
    protected void addContext(String key, Object value) {
        executionContext.put(key, value);
    }
    
    /**
     * Log file operation
     */
    protected void logFileOperation(String operation, String filePath, boolean success, String details) {
        logger.fileOperation(operation, filePath, success, details);
        sessionLogger.logFileOperation(operation, filePath, success);
        addContext("lastFileOperation", operation + ":" + (filePath != null ? filePath : "<null>"));
    }
    
    /**
     * Log file operation without details
     */
    protected void logFileOperation(String operation, String filePath, boolean success) {
        logFileOperation(operation, filePath, success, null);
    }
    
    /**
     * Log AI interaction start
     */
    protected void logAIStart(String model, String prompt, Map<String, Object> parameters) {
        logger.aiRequestStart(model, prompt, parameters);
        addContext("aiModel", model);
        addContext("aiPromptLength", prompt != null ? prompt.length() : 0);
    }
    
    /**
     * Log AI interaction completion
     */
    protected void logAIComplete(String model, String response, long durationMs) {
        logger.aiRequestComplete(model, response, durationMs);
        addContext("aiResponseLength", response != null ? response.length() : 0);
        addContext("aiDuration", durationMs);
    }
    
    /**
     * Log user interaction
     */
    protected void logUserInteraction(String interaction, String details) {
        logger.userInteraction(interaction, details);
        addContext("lastUserInteraction", interaction);
    }
    
    /**
     * Log security event
     */
    protected void logSecurityEvent(String event, String details, boolean allowed) {
        logger.securityEvent(event, details, allowed);
        sessionLogger.logSecurityEvent(event, details, allowed);
        addContext("lastSecurityEvent", event + ":" + allowed);
    }
    
    /**
     * Log network/protocol operation
     */
    protected void logProtocolOperation(String protocol, String endpoint, String operation, boolean success, long durationMs) {
        logger.protocolOperation(protocol, endpoint, operation, success, durationMs);
        addContext("lastProtocolOp", protocol + ":" + operation);
    }
    
    /**
     * Log performance metric
     */
    protected void logPerformance(String operation, long durationMs, Map<String, Object> metrics) {
        logger.performance(operation, durationMs, metrics);
        sessionLogger.logPerformance(operation, durationMs, metrics != null ? metrics.toString() : null);
    }
    
    /**
     * Log performance metric with simple context
     */
    protected void logPerformance(String operation, long durationMs) {
        logPerformance(operation, durationMs, executionContext);
    }
    
    /**
     * Record an error in the logs AND print it to the console.
     *
     * <p>For a failure the command does not report in its own words. When the command prints its
     * own message -- which is the usual case, because a message aimed at whoever typed the command
     * is better than one aimed at whoever wrote it -- use {@link #logErrorQuietly}: using this
     * method as well is what makes one failure appear twice.</p>
     */
    protected void logError(String operation, String message, Exception exception) {
        logger.error(operation, message, exception);
        sessionLogger.logError(this.getClass().getSimpleName(), operation + ": " + message, exception);
        addContext("lastError", operation + ":" + message);
    }
    
    /**
     * Record an error in the logs without printing it.
     *
     * <p>For failures the command reports itself, in words aimed at the person who typed the
     * command rather than at whoever wrote it.</p>
     */
    protected void logErrorQuietly(String operation, String message, Exception exception) {
        logger.errorQuietly(operation, message, exception);
        sessionLogger.logError(this.getClass().getSimpleName(), operation + ": " + message, exception);
        addContext("lastError", operation + ":" + message);
    }

    /**
     * Record an error in the logs without printing it, for a failure with no exception behind it.
     *
     * @param operation the internal operation name, for the log
     * @param message   what went wrong, for the log
     */
    protected void logErrorQuietly(String operation, String message) {
        logErrorQuietly(operation, message, null);
    }

    /**
     * Log an error without an exception, and print it. See {@link #logError(String, String, Exception)}
     * for when to prefer the quiet variant.
     */
    protected void logError(String operation, String message) {
        logger.error(operation, message, (Exception) null);
        sessionLogger.logError(this.getClass().getSimpleName(), operation + ": " + message, null);
        addContext("lastError", operation + ":" + message);
    }
    
    /**
     * Log warning
     */
    protected void logWarning(String operation, String message) {
        logger.warning(operation, message);
        addContext("lastWarning", operation + ":" + message);
    }
    
    /**
     * Log debug information
     */
    protected void logDebug(String operation, String message) {
        logger.debug(operation, message);
    }
    
    /**
     * Log data processing operation
     */
    protected void logDataProcessing(String operation, String dataType, int itemCount, long durationMs) {
        logger.dataProcessing(operation, dataType, itemCount, durationMs);
        addContext("lastDataProcessing", operation + ":" + itemCount + " " + dataType);
    }
    
    /**
     * Start a complete operation trace
     */
    protected ObservabilityLogger.OperationTrace startTrace(String operationName) {
        return logger.startTrace(operationName);
    }
    
    /**
     * Helper method to execute code with automatic error logging
     */
    protected <T> T executeWithLogging(String operation, SupplierWithException<T> supplier) throws Exception {
        long startTime = System.currentTimeMillis();
        try {
            logStep("Starting " + operation);
            T result = supplier.get();
            long duration = System.currentTimeMillis() - startTime;
            logStep("Completed " + operation, "Duration: " + duration + "ms");
            logPerformance(operation, duration);
            return result;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            logError(operation, "Failed after " + duration + "ms", e);
            throw e;
        }
    }
    
    /**
     * Helper method to execute void operations with automatic error logging
     */
    protected void executeWithLogging(String operation, RunnableWithException runnable) throws Exception {
        executeWithLogging(operation, () -> {
            runnable.run();
            return null;
        });
    }
    
    @FunctionalInterface
    protected interface SupplierWithException<T> {
        T get() throws Exception;
    }
    
    @FunctionalInterface
    protected interface RunnableWithException {
        void run() throws Exception;
    }
}