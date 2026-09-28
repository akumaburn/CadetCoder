package com.eonmux.cadetcoder.logging;

import java.nio.charset.StandardCharsets;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.security.SecretRedactor;
import com.eonmux.cadetcoder.config.Configuration;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Enhanced debug logger with full debugging capabilities
 */
public class DebugLogger {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /** How long shutdown lets the writer finish what is already queued. */
    private static final long SHUTDOWN_DRAIN_MS = 5_000L;

    /** How often the queue is looked at while it drains. */
    private static final long DRAIN_POLL_MS = 10L;

    /** How long shutdown waits for the writer to notice it has been stopped. */
    private static final long JOIN_TIMEOUT_MS = 5_000L;
    private static       DebugLogger       instance;

    private final BlockingQueue<LogEntry> debugQueue            = new LinkedBlockingQueue<>(15000);
    private final AtomicBoolean           running               = new AtomicBoolean(true);
    private       PrintWriter             debugWriter;
    private       Thread                  debugThread;
    private       Path                    debugLogFile;
    private       long                    currentDebugFileSize  = 0;
    private       int                     currentDebugFileIndex = 0;
    private       boolean                 debugEnabled;

    /**
     * The size and file-count limits this log is held to.
     *
     * <p>Read once, on the thread that initialises the logger, and never from the writer thread --
     * the same rule {@link LogTarget} follows and for the same reason: the writer arrives at an
     * arbitrary moment, and asking it to build a configuration would create one in whatever
     * directory the defaults pointed at just then.</p>
     */
    private       long                    maxDebugFileBytes;
    private       int                     maxDebugFiles;

    /** The rotation slots this run has already written into, so a reused one is replaced. */
    private final java.util.Set<Integer>  usedSlots = new java.util.HashSet<>();

    /** Matches {@code Configuration.LoggingConfig}'s own defaults, for when no config is readable. */
    private static final int DEFAULT_MAX_LOG_SIZE_MB = 10;
    private static final int DEFAULT_MAX_LOG_FILES   = 5;

    private DebugLogger() {
        try {
            Configuration               config        = ConfigManager.getInstance().getConfig();
            Configuration.LoggingConfig loggingConfig = config.getLogging();
            if (loggingConfig != null) {
                this.debugEnabled = loggingConfig.isDebugEnabled();
            } else {
                this.debugEnabled = false;
            }
        } catch (Exception e) {
            // Default to disabled if configuration is not available
            this.debugEnabled = false;
        }

        if (debugEnabled) {
            initializeDebugLogging();
        }
    }

    private void initializeDebugLogging() {
        try {
            Configuration config   = ConfigManager.getInstance().getConfig();
            String        baseDir  = config.getBaseDir();
            Path          debugDir = Paths.get(baseDir, "logs", "debug");

            if (!Files.exists(debugDir)) {
                Files.createDirectories(debugDir);
            }

            readLimits(config.getLogging());

            // What earlier runs left behind, before this one adds to it. Each run opens a log of its
            // own, so without this the directory grew by one set per run for the life of the install
            // -- rotation bounds one run's log and nothing bounded the collection of them.
            pruneEarlierRuns(debugDir);

            // Create debug log file with timestamp
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            debugLogFile = debugDir.resolve("debug_" + timestamp + ".log");

            openDebugFile();

            // Start debug logger thread
            debugThread = new Thread(this::runDebugThread, "DebugLogger");
            debugThread.setDaemon(true);
            debugThread.start();

            // Add shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));

            // Log startup
            debug("DebugLogger", "Debug logging initialized. File: " + debugLogFile);

        } catch (IOException e) {
            // Use UnifiedOutput to respect OutputRouter if active
            if (isOutputRouterActive()) {
                com.eonmux.cadetcoder.ui.UnifiedOutput.printlnErr("Failed to initialize debug logging: " + e.getMessage());
            } else {
                System.err.println("Failed to initialize debug logging: " + e.getMessage());
            }
        }
    }

    /**
     * Reads the size and file-count limits this log is held to.
     *
     * <p>They are {@code logging.maxLogSize} and {@code logging.maxLogFiles} -- the same two every
     * other log in this tool obeys. This one obeyed neither: it rotated at a hardcoded ten
     * megabytes and counted slots upwards for ever, so the log written only when a user has asked
     * for detail was the only one that could fill a disk.</p>
     *
     * @param logging the logging settings, possibly {@code null}
     */
    private void readLimits(Configuration.LoggingConfig logging) {
        int sizeMb = logging == null || logging.getMaxLogSize() <= 0
                     ? DEFAULT_MAX_LOG_SIZE_MB
                     : logging.getMaxLogSize();
        int files  = logging == null || logging.getMaxLogFiles() <= 0
                     ? DEFAULT_MAX_LOG_FILES
                     : logging.getMaxLogFiles();
        this.maxDebugFileBytes = sizeMb * 1024L * 1024L;
        this.maxDebugFiles     = files;
    }

    /**
     * Deletes the debug logs of earlier runs, newest kept, so the directory stays bounded.
     *
     * <p>Every failure here is reported and skipped: a debug log that cannot be tidied is no reason
     * to start without one.</p>
     *
     * @param debugDir where the debug logs live
     */
    private void pruneEarlierRuns(Path debugDir) {
        List<Path> existing = new ArrayList<>();
        try (java.nio.file.DirectoryStream<Path> entries =
                     Files.newDirectoryStream(debugDir, "debug_*.log")) {
            for (Path entry : entries) {
                if (Files.isRegularFile(entry)) {
                    existing.add(entry);
                }
            }
        } catch (IOException cannotList) {
            reportProblem("Failed to list debug logs for pruning: " + cannotList.getMessage());
            return;
        }

        // One slot short of the limit, because this run is about to open one of its own.
        int keep = Math.max(0, maxDebugFiles - 1);
        if (existing.size() <= keep) {
            return;
        }
        // Newest first; the name carries the run's start time, which keeps the order deterministic
        // when modification times collide.
        existing.sort(java.util.Comparator.comparingLong(DebugLogger::lastModifiedOrZero).reversed()
                              .thenComparing(path -> path.getFileName().toString(),
                                             java.util.Comparator.reverseOrder()));
        for (Path stale : existing.subList(keep, existing.size())) {
            try {
                Files.deleteIfExists(stale);
            } catch (IOException cannotDelete) {
                reportProblem("Failed to delete old debug log " + stale + ": "
                              + cannotDelete.getMessage());
            }
        }
    }

    /** @return the file's modification time, or {@code 0} when it cannot be read */
    private static long lastModifiedOrZero(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException | SecurityException cannotRead) {
            return 0L;
        }
    }

    private void openDebugFile() throws IOException {
        if (debugWriter != null) {
            debugWriter.close();
        }

        Path logPath;
        if (currentDebugFileIndex == 0) {
            logPath = debugLogFile;
        } else {
            String fileName  = debugLogFile.getFileName().toString();
            String baseName  = fileName.substring(0, fileName.lastIndexOf('.'));
            String extension = fileName.substring(fileName.lastIndexOf('.'));
            logPath = debugLogFile.getParent().resolve(baseName + "." + currentDebugFileIndex + extension);
        }

        // Appended to, except when rotation has come back round to a slot this run already filled:
        // reusing a slot has to replace it, or wrapping would simply go on adding to an old full
        // file and the file count would bound nothing.
        boolean reused = usedSlots.contains(currentDebugFileIndex);
        usedSlots.add(currentDebugFileIndex);
        debugWriter          = new PrintWriter(
                new FileWriter(logPath.toFile(), StandardCharsets.UTF_8, !reused), true);
        currentDebugFileSize = reused || !Files.exists(logPath) ? 0 : Files.size(logPath);

        // Write header
        debugWriter.println("=".repeat(80));
        debugWriter.println("CadetCoder Debug Log - Started: " + LocalDateTime.now());
        debugWriter.println("=".repeat(80));
    }

    private void runDebugThread() {
        while (running.get() || !debugQueue.isEmpty()) {
            try {
                LogEntry entry = debugQueue.take();
                if (debugWriter != null) {
                    String logLine = formatLogEntry(entry);
                    debugWriter.println(logLine);

                    // A stack trace is written through the same masking as the message, and its
                    // bytes are counted: printing it straight to the writer left it out of
                    // currentDebugFileSize, so a log dominated by traces grew past the cap that
                    // rotation exists to enforce.
                    String trace = entry.throwable != null ? stackTraceOf(entry.throwable) : "";
                    if (!trace.isEmpty()) {
                        debugWriter.print(trace);
                    }

                    debugWriter.flush();
                    currentDebugFileSize += logLine.getBytes(StandardCharsets.UTF_8).length
                                            + System.lineSeparator().length()
                                            + trace.getBytes(StandardCharsets.UTF_8).length;

                    if (currentDebugFileSize > maxDebugFileBytes) {
                        rotateDebugFile();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * Stops the writer, having first let it finish what is already queued.
     *
     * <h2>Why it is not simply interrupted</h2>
     *
     * <p>The writer spends its life blocked in {@code take()} waiting for the next entry, so an
     * interrupt is how it is woken. Sent as the first thing shutdown does, it is also how
     * everything still waiting is thrown away: the loop breaks on the interrupt instead of
     * draining, and the entries lost are the newest ones -- which, in a shutdown that follows a
     * failure, are the only ones anybody wants to read. Draining is what the loop already knows how
     * to do once {@code running} is false; the interrupt is what happens when it has had its
     * moment and is still not finished.</p>
     */
    private void shutdown() {
        running.set(false);
        long deadline = System.currentTimeMillis() + SHUTDOWN_DRAIN_MS;
        while (!debugQueue.isEmpty() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(DRAIN_POLL_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (debugThread != null) {
            debugThread.interrupt();
            try {
                debugThread.join(JOIN_TIMEOUT_MS);
            } catch (InterruptedException ignored) {
                // Thread interrupt is expected during shutdown
                Thread.currentThread().interrupt();
            }
        }
        if (debugWriter != null) {
            debugWriter.println("=".repeat(80));
            debugWriter.println("Debug Log Closed: " + LocalDateTime.now());
            debugWriter.println("=".repeat(80));
            debugWriter.close();
        }
    }

    public void debug(String source, String message) {
        log(Level.DEBUG, source, message, null);
    }

    /**
     * Renders a throwable as it may be recorded: the full trace, with credentials masked.
     *
     * @param throwable the exception to render; never {@code null}
     * @return the stack trace text, ending in a newline
     */
    private static String stackTraceOf(Throwable throwable) {
        StringWriter rendered = new StringWriter();
        throwable.printStackTrace(new PrintWriter(rendered));
        return SecretRedactor.redact(rendered.toString());
    }

    private String formatLogEntry(LogEntry entry) {
        return String.format("%s [%-5s] [%s:%d] [%s] [%s] %s",
                entry.timestamp.format(DATE_FORMAT),
                entry.level,
                entry.threadName,
                entry.threadId,
                entry.source,
                entry.invokingClass,
                entry.message);
    }

    /**
     * Moves to the next slot, wrapping so the file count bounds how much is kept.
     *
     * <p>It used to increment the slot number and never come back: {@code logging.maxLogFiles} was
     * not consulted, so a long run wrote {@code debug_….1.log}, {@code .2}, {@code .3} with no end
     * and the cap bounded one file rather than the log.</p>
     */
    private void rotateDebugFile() {
        try {
            currentDebugFileIndex = (currentDebugFileIndex + 1) % Math.max(1, maxDebugFiles);
            openDebugFile();
        } catch (IOException e) {
            reportProblem("Failed to rotate debug file: " + e.getMessage());
        }
    }

    /** Says what went wrong with the log itself, through whichever console is in charge. */
    private void reportProblem(String message) {
        if (isOutputRouterActive()) {
            com.eonmux.cadetcoder.ui.UnifiedOutput.printlnErr(message);
        } else {
            System.err.println(message);
        }
    }

    private void log(Level level, String source, String message, Throwable throwable) {
        if (!debugEnabled) {
            return;
        }

        try {
            // Masked HERE, at the one point every writer passes through, rather than at each of
            // them. CommandRegistry already masks a command's arguments before recording them; the
            // eighteen commands that also log those arguments through ObservabilityLogger did not,
            // and wrote them to this same file. A guarantee that depends on which method a caller
            // reached for is not a guarantee.
            LogEntry entry = new LogEntry(level, source, SecretRedactor.redact(message), throwable);
            debugQueue.put(entry);

            // Also log to console in interactive mode if console logging is enabled
            try {
                Configuration               config        = ConfigManager.getInstance().getConfig();
                Configuration.LoggingConfig loggingConfig = config.getLogging();
                Configuration.UiConfig      uiConfig      = config.getUi();
                if (loggingConfig != null && uiConfig != null &&
                    loggingConfig.isConsoleLoggingEnabled() &&
                    uiConfig.getVerbosityLevel() >= Configuration.UiConfig.VERBOSITY.VERBOSE.ordinal()) {
                    // Use UnifiedOutput to respect OutputRouter if active
                    if (isOutputRouterActive()) {
                        com.eonmux.cadetcoder.ui.UnifiedOutput.printlnErr(formatLogEntry(entry));
                        if (throwable != null) {
                            com.eonmux.cadetcoder.ui.UnifiedOutput.printlnErr(stackTraceOf(throwable));
                        }
                    } else {
                        System.err.println(formatLogEntry(entry));
                        if (throwable != null) {
                            System.err.print(stackTraceOf(throwable));
                        }
                    }
                }
            } catch (Exception e) {
                // Ignore errors in console logging
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static synchronized DebugLogger getInstance() {
        if (instance == null) {
            instance = new DebugLogger();
        }
        return instance;
    }

    // Logging methods
    public void trace(String source, String message) {
        log(Level.TRACE, source, message, null);
    }

    public void debug(String source, String message, Throwable throwable) {
        log(Level.DEBUG, source, message, throwable);
    }

    public void info(String source, String message) {
        log(Level.INFO, source, message, null);
    }

    public void warn(String source, String message, Throwable throwable) {
        log(Level.WARN, source, message, throwable);
    }

    public void error(String source, String message) {
        log(Level.ERROR, source, message, null);
    }

    public void error(String source, String message, Throwable throwable) {
        log(Level.ERROR, source, message, throwable);
    }

    // Command/response logging
    public void logCommand(String command, String[] args) {
        if (!debugEnabled) {
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("COMMAND: ").append(command);
        if (args != null && args.length > 0) {
            sb.append(" ARGS: [");
            for (int i = 0; i < args.length; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append("'").append(args[i]).append("'");
            }
            sb.append("]");
        }
        debug("CommandExecution", sb.toString());
    }

    public void logResponse(String command, int exitCode, long duration) {
        if (!debugEnabled) {
            return;
        }

        String status = exitCode == 0 ? "SUCCESS" : "FAILED";
        debug("CommandExecution", String.format("RESPONSE: %s - %s (exit: %d, duration: %dms)",
                command, status, exitCode, duration));
    }

    // AI interaction logging
    public void logAIRequest(String prompt, String model, double temperature) {
        if (!debugEnabled) {
            return;
        }
        
        // Enhanced LLM request logging with more context
        String truncatedPrompt = truncate(prompt, 500);
        int promptLength = prompt != null ? prompt.length() : 0;
        int promptLines = prompt != null ? prompt.split("\n").length : 0;
        
        debug("AI", String.format("REQUEST to %s (temp: %.2f): %s",
                model, temperature, truncatedPrompt));
        debug("AI", String.format("REQUEST_STATS: length=%d chars, lines=%d, model=%s",
                promptLength, promptLines, model));
    }
    
    /**
     * Log full AI request for detailed debugging (no truncation)
     */
    public void logAIRequestTrace(String prompt, String model, double temperature, String fullRequestBody) {
        if (!debugEnabled) {
            return;
        }
        
        trace("AI-TRACE", String.format("FULL REQUEST to %s (temp: %.2f)", model, temperature));
        trace("AI-TRACE", "--- PROMPT START ---");
        trace("AI-TRACE", prompt != null ? prompt : "<null>");
        trace("AI-TRACE", "--- PROMPT END ---");
        if (fullRequestBody != null) {
            trace("AI-TRACE", "--- FULL REQUEST BODY START ---");
            trace("AI-TRACE", fullRequestBody);
            trace("AI-TRACE", "--- FULL REQUEST BODY END ---");
        }
    }

    // Helper methods
    private String truncate(String str, int maxLength) {
        if (str == null) {
            return "null";
        }
        if (str.length() <= maxLength) {
            return str;
        }
        return str.substring(0, maxLength) + "... (truncated)";
    }

    public void logAIResponse(String response, long duration) {
        if (!debugEnabled) {
            return;
        }
        
        // Enhanced LLM response logging with more context
        String truncatedResponse = truncate(response, 500);
        int totalLength = response != null ? response.length() : 0;
        int lineCount = response != null ? response.split("\n").length : 0;
        
        debug("AI", String.format("RESPONSE (duration: %dms): %s", 
                duration, truncatedResponse));
        debug("AI", String.format("RESPONSE_STATS: length=%d chars, lines=%d, rate=%.1f chars/sec", 
                totalLength, lineCount, totalLength / Math.max(duration / 1000.0, 0.001)));
        
        // Log if response contains common patterns
        if (response != null) {
            if (response.contains("<think>")) {
                debug("AI", "RESPONSE_PATTERN: Contains <think> blocks");
            }
            if (response.contains("ACTION_START")) {
                debug("AI", "RESPONSE_PATTERN: Contains ACTION blocks");
            }
            if (response.contains("```")) {
                debug("AI", "RESPONSE_PATTERN: Contains code blocks");
            }
        }
    }
    
    /**
     * Log full AI response for detailed debugging (no truncation)
     */
    public void logAIResponseTrace(String response, long duration, String fullResponseBody) {
        if (!debugEnabled) {
            return;
        }
        
        trace("AI-TRACE", String.format("FULL RESPONSE (duration: %dms)", duration));
        if (fullResponseBody != null) {
            trace("AI-TRACE", "--- FULL RESPONSE BODY START ---");
            trace("AI-TRACE", fullResponseBody);
            trace("AI-TRACE", "--- FULL RESPONSE BODY END ---");
        }
        trace("AI-TRACE", "--- PARSED CONTENT START ---");
        trace("AI-TRACE", response != null ? response : "<null>");
        trace("AI-TRACE", "--- PARSED CONTENT END ---");
    }

    // File operation logging
    public void logFileOperation(String operation, String path, boolean success) {
        if (!debugEnabled) {
            return;
        }

        String status = success ? "SUCCESS" : "FAILED";
        debug("FileOperation", String.format("%s: %s - %s", operation, path, status));
    }

    // Security logging
    public void logSecurityEvent(String event, String details, boolean allowed) {
        if (!debugEnabled) {
            return;
        }

        String status = allowed ? "ALLOWED" : "BLOCKED";
        warn("Security", String.format("%s - %s: %s", event, status, details));
    }

    public void warn(String source, String message) {
        log(Level.WARN, source, message, null);
    }

    // Performance logging
    public void logPerformance(String operation, long duration, String details) {
        if (!debugEnabled) {
            return;
        }

        debug("Performance", String.format("%s took %dms: %s", operation, duration, details));
    }
    
    // Network/Protocol logging
    public void logHTTPRequest(String method, String url, Map<String, String> headers, String body) {
        if (!debugEnabled) {
            return;
        }
        
        trace("HTTP-TRACE", String.format("HTTP %s %s", method, url));
        if (headers != null && !headers.isEmpty()) {
            trace("HTTP-TRACE", "--- HEADERS START ---");
            headers.forEach((key, value) -> {
                // Mask sensitive headers
                String maskedValue = key.toLowerCase().contains("auth") || key.toLowerCase().contains("key") 
                    ? "[MASKED]" : value;
                trace("HTTP-TRACE", key + ": " + maskedValue);
            });
            trace("HTTP-TRACE", "--- HEADERS END ---");
        }
        if (body != null && !body.isEmpty()) {
            trace("HTTP-TRACE", "--- REQUEST BODY START ---");
            trace("HTTP-TRACE", body);
            trace("HTTP-TRACE", "--- REQUEST BODY END ---");
        }
    }
    
    public void logHTTPResponse(int statusCode, String body, Map<String, String> headers) {
        if (!debugEnabled) {
            return;
        }
        
        trace("HTTP-TRACE", String.format("HTTP Response: %d", statusCode));
        if (headers != null && !headers.isEmpty()) {
            trace("HTTP-TRACE", "--- RESPONSE HEADERS START ---");
            headers.forEach((key, value) -> trace("HTTP-TRACE", key + ": " + value));
            trace("HTTP-TRACE", "--- RESPONSE HEADERS END ---");
        }
        if (body != null && !body.isEmpty()) {
            trace("HTTP-TRACE", "--- RESPONSE BODY START ---");
            trace("HTTP-TRACE", body);
            trace("HTTP-TRACE", "--- RESPONSE BODY END ---");
        }
    }

    public boolean isDebugEnabled() {
        return debugEnabled;
    }

    public void setDebugEnabled(boolean enabled) {
        this.debugEnabled = enabled;
        if (enabled && debugWriter == null) {
            initializeDebugLogging();
        }
    }

    public enum Level {
        TRACE, DEBUG, INFO, WARN, ERROR
    }

    private static class LogEntry {
        final LocalDateTime timestamp;
        final Level         level;
        final String        source;
        final String        message;
        final Throwable     throwable;
        final String        threadName;
        final long          threadId;
        final String        invokingClass;

        LogEntry(Level level, String source, String message, Throwable throwable) {
            this.timestamp  = LocalDateTime.now();
            this.level      = level;
            this.source     = source;
            this.message    = message;
            this.throwable  = throwable;
            Thread currentThread = Thread.currentThread();
            this.threadName = currentThread.getName();
            this.threadId   = currentThread.getId();
            this.invokingClass = getInvokingClassName();
        }
        
        private String getInvokingClassName() {
            try {
                StackTraceElement[] stack = Thread.currentThread().getStackTrace();
                // Skip: getStackTrace(), getInvokingClassName(), LogEntry constructor, log(), calling method
                for (int i = 5; i < stack.length; i++) {
                    String className = stack[i].getClassName();
                    // Skip logging infrastructure classes
                    if (!className.startsWith("com.eonmux.cadetcoder.logging.") &&
                        !className.equals("java.lang.Thread") &&
                        !className.startsWith("java.util.concurrent.")) {
                        return className + "." + stack[i].getMethodName() + ":" + stack[i].getLineNumber();
                    }
                }
                return "Unknown";
            } catch (Exception e) {
                return "Unknown";
            }
        }
    }

    /**
     * Helper method to check if OutputRouter is active to avoid interfering with interactive shell
     */
    private boolean isOutputRouterActive() {
        try {
            return com.eonmux.cadetcoder.ui.OutputRouter.getInstance().isRouting();
        } catch (Exception e) {
            // If we can't determine routing status, assume not active
            return false;
        }
    }
}