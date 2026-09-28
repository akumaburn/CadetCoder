package com.eonmux.cadetcoder.logging;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.ui.UnifiedOutput;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enhanced observability logger that provides detailed logging for command execution,
 * AI interactions, file operations, and system events with TUI-friendly formatting.
 */
public class ObservabilityLogger {
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final Map<String, ObservabilityLogger> loggers = new ConcurrentHashMap<>();
    
    private final String componentName;
    private final DebugLogger debugLogger;
    private final CadetLogger cadetLogger;
    private ObservabilityLogger(String componentName) {
        this.componentName = componentName;
        this.debugLogger = DebugLogger.getInstance();
        this.cadetLogger = CadetLogger.getLogger("Observability." + componentName);
    }

    /**
     * Whether verbose tracing is on, read at the moment of use.
     *
     * <p>This used to be captured in the constructor, and loggers are cached per component in a
     * static map, so the answer was fixed at whatever the configuration said the first time that
     * component logged anything. Commands are constructed as picocli subcommands before the command
     * line has been parsed, so {@code --verbose} could not switch tracing on for them however early
     * it was applied.</p>
     */
    private boolean isVerbose() {
        try {
            Configuration config = ConfigManager.getInstance().getConfig();
            return config.getUi().getVerbosityLevel()
                   >= Configuration.UiConfig.VERBOSITY.VERBOSE.ordinal();
        } catch (RuntimeException e) {
            // A missing or half-initialised configuration must not decide to start tracing.
            return false;
        }
    }
    
    /**
     * Get or create an observability logger for a specific component
     */
    public static ObservabilityLogger forComponent(String componentName) {
        return loggers.computeIfAbsent(componentName, ObservabilityLogger::new);
    }
    
    /**
     * Get observability logger for a command class
     */
    public static ObservabilityLogger forCommand(Class<?> commandClass) {
        String componentName = commandClass.getSimpleName().replace("Command", "");
        return forComponent(componentName);
    }
    
    /**
     * Log the start of a command execution with parameters
     */
    public void commandStart(String commandName, String[] args, Map<String, Object> context) {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        String argsStr = args != null && args.length > 0 ? String.join(" ", args) : "<no args>";
        
        // Truncate args if too long for display
        if (argsStr.length() > 100) {
            argsStr = argsStr.substring(0, 97) + "...";
        }
        
        String message = String.format("[%s] %s %s", timestamp, commandName, argsStr);
        
        debugLogger.info(componentName, "COMMAND_START: " + commandName + " with args: " + argsStr);
        
        if (isVerbose()) {
            logLine(message, "info");
        }
        
        // Log context if present
        if (context != null && !context.isEmpty()) {
            debugLogger.debug(componentName, "Command context: " + context.toString());
        }
    }
    
    /**
     * Log command completion with result
     */
    public void commandComplete(String commandName, int exitCode, long durationMs) {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        String statusText = exitCode == 0 ? "SUCCESS" : "FAILED";
        
        String message = String.format("[%s] %s (%dms)", timestamp, commandName, durationMs);
        
        debugLogger.info(componentName, 
            String.format("COMMAND_COMPLETE: %s - %s (exit: %d, duration: %dms)", 
                commandName, statusText, exitCode, durationMs));
        
        if (isVerbose()) {
            String level = exitCode == 0 ? "success" : "error";
            logLine(message, level);
        }
    }
    
    /**
     * Log a command step or operation
     */
    public void commandStep(String step, String details) {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        String message = String.format("[%s] %s", timestamp, step);
        
        debugLogger.debug(componentName, "STEP: " + step + " - " + details);
        
        if (isVerbose()) {
            logLine(message, "info");
            if (details != null && !details.isEmpty()) {
                // Show details on next line with indentation
                logDetail(details);
            }
        }
    }
    
    /**
     * Log AI interaction start
     */
    public void aiRequestStart(String model, String prompt, Map<String, Object> parameters) {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        String truncatedPrompt = truncateForDisplay(prompt, 80);
        double temperature = temperatureOf(parameters);

        String message = String.format("[%s] AI request to %s (temp: %s)", timestamp, model, temperature);

        debugLogger.logAIRequest(prompt, model, temperature);

        if (isVerbose()) {
            logLine(message, "info");
            logDetail("Prompt: " + truncatedPrompt);
        }
    }

    /** What a request is logged as having been made at when it named no temperature. */
    private static final double DEFAULT_TEMPERATURE = 0.7;

    /** The parameter a request states its temperature under. */
    private static final String TEMPERATURE_PARAMETER = "temperature";

    /**
     * The temperature a request was made at, as a number.
     *
     * <h2>Why a value this cannot read is not a failure</h2>
     *
     * <p>The map is whatever the caller assembled, so its {@code temperature} can be a
     * {@link Number}, the text of one, or absent -- and it can be present with a {@code null}
     * value, which {@link Map#getOrDefault} hands back rather than substituting the default for.
     * Reading it with {@code toString()} and {@code Double.parseDouble} therefore threw a
     * {@link NullPointerException} or a {@link NumberFormatException} straight out of
     * {@code aiRequestStart}, which killed the AI request this method was only there to describe.
     * A logger must never be the reason the work it is observing does not happen.</p>
     *
     * <p>A value that cannot be read is reported as the default rather than guessed at: the number
     * is a note in a log, and the request carries its own temperature to the provider whatever this
     * says about it.</p>
     *
     * @param parameters the request parameters, possibly {@code null}
     * @return the temperature to record
     */
    private static double temperatureOf(Map<String, Object> parameters) {
        Object stated = parameters == null ? null : parameters.get(TEMPERATURE_PARAMETER);
        if (stated instanceof Number number) {
            return number.doubleValue();
        }
        if (stated != null) {
            try {
                return Double.parseDouble(stated.toString().trim());
            } catch (NumberFormatException notANumber) {
                return DEFAULT_TEMPERATURE;
            }
        }
        return DEFAULT_TEMPERATURE;
    }
    
    /**
     * Log AI interaction completion
     */
    public void aiRequestComplete(String model, String response, long durationMs) {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        String truncatedResponse = truncateForDisplay(response, 80);
        
        String message = String.format("[%s] AI response from %s (%dms)", timestamp, model, durationMs);
        
        // Observability-level logging only - backends handle their own debug logging
        debugLogger.debug(componentName, "AI_RESPONSE: " + model + " responded in " + durationMs + "ms, length=" + 
                         (response != null ? response.length() : 0) + " chars");
        
        if (isVerbose()) {
            logLine(message, "success");
            logDetail("Response: " + truncatedResponse);
        }
    }
    
    /**
     * Log file operation
     */
    public void fileOperation(String operation, String filePath, boolean success, String details) {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        String shortPath = shortenPath(filePath);
        
        String message = String.format("[%s] %s %s", timestamp, operation, shortPath);
        
        debugLogger.logFileOperation(operation, filePath, success);
        
        if (isVerbose()) {
            String level = success ? "success" : "error";
            logLine(message, level);
            if (details != null && !details.isEmpty()) {
                logDetail(details);
            }
        }
    }
    
    /**
     * Log network/protocol operation
     */
    public void protocolOperation(String protocol, String endpoint, String operation, boolean success, long durationMs) {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        String message = String.format("[%s] %s %s %s (%dms)",
            timestamp, protocol, operation, endpoint, durationMs);
        
        debugLogger.debug(componentName, 
            String.format("PROTOCOL: %s %s to %s - %s (%dms)", 
                protocol, operation, endpoint, success ? "SUCCESS" : "FAILED", durationMs));
        
        if (isVerbose()) {
            String level = success ? "success" : "error";
            logLine(message, level);
        }
    }
    
    /**
     * Log security event
     */
    public void securityEvent(String event, String details, boolean allowed) {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        String statusText = allowed ? "ALLOWED" : "BLOCKED";
        
        String message = String.format("[%s] Security: %s - %s", timestamp, event, statusText);
        
        debugLogger.logSecurityEvent(event, details, allowed);
        
        if (isVerbose() || !allowed) {
            String level = allowed ? "info" : "warning";
            logLine(message, level);
            if (details != null && !details.isEmpty()) {
                logDetail(details);
            }
        }
    }
    
    /**
     * Log performance metric
     */
    public void performance(String operation, long durationMs, Map<String, Object> metrics) {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        String message = String.format("[%s] %s (%dms)", timestamp, operation, durationMs);
        
        StringBuilder details = new StringBuilder();
        if (metrics != null && !metrics.isEmpty()) {
            metrics.forEach((key, value) -> {
                if (details.length() > 0) details.append(", ");
                details.append(key).append(": ").append(value);
            });
        }
        
        debugLogger.logPerformance(operation, durationMs, details.toString());
        
        if (isVerbose() && durationMs > 1000) { // Only log slow operations to console
            logLine(message, "warning");
            if (details.length() > 0) {
                logDetail(details.toString());
            }
        }
    }
    
    /**
     * Log an error with context
     */
    /**
     * Log an error with context.
     *
     * <p>The console copy is {@code cadetLogger.error}'s, and only that one. This method used to add
     * a third rendering of the same failure -- {@code ❌ [17:23:21.999] ✗ Error in execute: ...} --
     * on top of SLF4J's {@code [ERROR] Grep - ...} and CadetLogger's {@code ✗ ...}, so a single bad
     * command-line flag was reported three times, in three formats, around two stack traces. The
     * timestamp and the component name belong in the log file, which still receives both.</p>
     */
    public void error(String operation, String message, Exception exception) {
        // The operation name is for the LOG. It used to be prefixed onto the console line too, so a
        // failed command told the user "analyze_request: Error analyzing request: ..." -- naming an
        // internal step they cannot act on, in front of the sentence that actually says what broke.
        debugLogger.error(componentName, operation + " failed: " + message, exception);
        cadetLogger.errorToFile(operation + ": " + message
                                + (exception == null ? "" : ": " + exception));
        if (exception == null) {
            com.eonmux.cadetcoder.ui.ThemedOutputFormatter.printErrorLine(message);
        } else {
            com.eonmux.cadetcoder.ui.ThemedOutputFormatter.printErrorLine(
                    message + ": " + exception.getMessage()
                    + " (" + exception.getClass().getSimpleName() + ")");
        }
    }

    /**
     * Records an error without printing anything.
     *
     * <p>For a failure the command reports in its own words. A mistyped command-line flag, for
     * instance, is best answered with picocli's own sentence and the usage text; routing it through
     * {@link #error} as well produced a second, developer-shaped line above it naming the internal
     * operation and the Java exception class.</p>
     *
     * @param operation the internal operation name, for the log
     * @param message   what went wrong, for the log
     * @param exception the cause, or {@code null}
     */
    public void errorQuietly(String operation, String message, Exception exception) {
        debugLogger.error(componentName, operation + " failed: " + message, exception);
        cadetLogger.errorToFile(operation + ": " + message
                                + (exception == null ? "" : ": " + exception));
    }
    
    /**
     * Log a warning
     */
    public void warning(String operation, String message) {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        String logMessage = String.format("[%s] %s: %s", timestamp, operation, message);
        
        debugLogger.warn(componentName, operation + ": " + message);
        
        if (isVerbose()) {
            logLine(logMessage, "warning");
        }
    }
    
    /**
     * Log debug information
     */
    public void debug(String operation, String message) {
        debugLogger.debug(componentName, operation + ": " + message);
        
        // Debug messages only go to debug log, not console
    }
    
    /**
     * Log user interaction
     */
    public void userInteraction(String interaction, String details) {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        String message = String.format("[%s] %s", timestamp, interaction);
        
        debugLogger.debug(componentName, "USER_INTERACTION: " + interaction + " - " + details);
        
        if (isVerbose()) {
            logLine(message, "info");
            if (details != null && !details.isEmpty()) {
                logDetail(details);
            }
        }
    }
    
    /**
     * Log data processing operation
     */
    public void dataProcessing(String operation, String dataType, int itemCount, long durationMs) {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        // No literal " items": every caller already passes a plural noun phrase for dataType
        // ("excluded directories", "files with matches"), so the suffix produced lines like
        // "0 directories skipped items".
        String message = String.format("[%s] %s: %d %s (%dms)",
            timestamp, operation, itemCount, dataType, durationMs);

        debugLogger.debug(componentName,
            String.format("DATA_PROCESSING: %s processed %d %s in %dms",
                operation, itemCount, dataType, durationMs));
        
        if (isVerbose()) {
            logLine(message, "info");
        }
    }
    
    /**
     * Create a trace for the full lifecycle of an operation
     */
    public OperationTrace startTrace(String operationName) {
        return new OperationTrace(this, operationName);
    }
    
    /**
     * Helper method to log to console with proper formatting
     */
    /**
     * Writes one verbose-trace line to the console.
     *
     * <p>The two branches now render identically. They used to differ: under the TUI a warning got
     * an extra {@code "! "} and a success an extra {@code "+ "} that the plain console never added,
     * so the same event read differently depending on where it was displayed. And whichever branch
     * ran, the marker was prepended to a message that already began with one, producing lines like
     * {@code "→ [17:47:02.617] → Grep configuration"}.</p>
     *
     * <p>The markers come from the shared glyph set, so a verbose trace line is coloured by
     * {@code OutputLineStyler} like every other line rather than falling through as body text.</p>
     *
     * @param message the text, which must NOT carry a marker of its own
     * @param level   {@code error}, {@code warning}, {@code success} or anything else (information)
     */
    /** The shared marker vocabulary, so a trace line looks like every other line. */
    private static final com.eonmux.cadetcoder.ui.Glyphs GLYPHS =
            com.eonmux.cadetcoder.ui.Glyphs.system();

    /**
     * How far a line that carries no marker of its own is set in.
     *
     * <p>Two columns, so a detail reads as a continuation of the entry above it whichever form
     * that entry took. A marked entry's own text starts further right on an ASCII terminal, where
     * the markers are bracketed words; lining up with that exactly would set details three to six
     * columns in and make the trace look like a hierarchy it does not have.</p>
     */
    private static final String DETAIL_INDENT = "  ";

    private void logLine(String message, String level) {
        String marker;
        switch (level) {
            case "error":
                marker = GLYPHS.errorMarker();
                break;
            case "warning":
                marker = GLYPHS.warningMarker();
                break;
            case "success":
                marker = GLYPHS.successMarker();
                break;
            case "dim":
                marker = "";
                break;
            default:
                // Unmarked, like every other notice the program prints: "this is information" is
                // the one thing a trace line's own text always says already. What is left marked
                // here is what a reader scanning the trace is looking for.
                marker = "";
                break;
        }
        String line = marker.isEmpty() ? DETAIL_INDENT + message : marker + " " + message;
        if ("error".equals(level)) {
            UnifiedOutput.printlnErr(line);
        } else {
            UnifiedOutput.println(line);
        }
    }

    /**
     * Writes a continuation line belonging to the trace line above it.
     *
     * <p>Indented rather than marked, so it reads as part of the entry it follows instead of as a
     * new event.</p>
     *
     * @param message the detail text
     */
    private void logDetail(String message) {
        UnifiedOutput.println(DETAIL_INDENT + message);
    }
    
    /**
     * Truncate text for display purposes
     */
    private String truncateForDisplay(String text, int maxLength) {
        if (text == null) return "null";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength - 3) + "...";
    }
    
    /**
     * Shorten file paths for display
     */
    private String shortenPath(String path) {
        if (path == null) return "null";
        
        // Replace home directory with ~
        String home = System.getProperty("user.home");
        if (path.startsWith(home)) {
            path = "~" + path.substring(home.length());
        }
        
        // If still too long, show just the last few components
        if (path.length() > 50) {
            String[] parts = path.split("/");
            if (parts.length > 3) {
                return ".../" + parts[parts.length - 2] + "/" + parts[parts.length - 1];
            }
        }
        
        return path;
    }
    
    /**
     * Check if OutputRouter is active
     */
    private boolean isOutputRouterActive() {
        try {
            return com.eonmux.cadetcoder.ui.OutputRouter.getInstance().isRouting();
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Helper class for tracing complete operation lifecycles
     */
    public static class OperationTrace {
        private final ObservabilityLogger logger;
        private final String operationName;
        private final long startTime;
        private final Map<String, Object> context;
        
        private OperationTrace(ObservabilityLogger logger, String operationName) {
            this.logger = logger;
            this.operationName = operationName;
            this.startTime = System.currentTimeMillis();
            this.context = new ConcurrentHashMap<>();
            
            logger.commandStep(operationName, "Started");
        }
        
        /**
         * Add context to the trace
         */
        public OperationTrace withContext(String key, Object value) {
            context.put(key, value);
            return this;
        }
        
        /**
         * Log a step in the operation
         */
        public OperationTrace step(String stepName, String details) {
            logger.commandStep(operationName + " → " + stepName, details);
            return this;
        }
        
        /**
         * Complete the operation successfully
         */
        public void success(String message) {
            long duration = System.currentTimeMillis() - startTime;
            logger.commandStep(operationName + " [OK]", message + " (" + duration + "ms)");
            logger.performance(operationName, duration, context);
        }
        
        /**
         * Complete the operation with failure
         */
        public void failure(String message, Exception exception) {
            long duration = System.currentTimeMillis() - startTime;
            logger.error(operationName, message + " (after " + duration + "ms)", exception);
        }
    }
}