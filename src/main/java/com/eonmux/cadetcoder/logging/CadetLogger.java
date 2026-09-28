package com.eonmux.cadetcoder.logging;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.security.SecretRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Custom logger implementation that sends errors to console and everything else to file.
 * This wraps SLF4J logger and adds file logging capability.
 */
/**
 * Custom logger implementation that sends errors to console and everything else to file.
 * This wraps SLF4J logger and adds file logging capability.
 *
 * <p>The file itself belongs to {@link LogFileWriter}, which there is exactly one of: a logger is a
 * named front end onto the shared log, not an owner of it.</p>
 */
public class CadetLogger {

    private static final ConcurrentHashMap<String, CadetLogger> loggerCache = new ConcurrentHashMap<>();

    private final Logger logger;

    private CadetLogger(Class<?> clazz) {
        this.logger = LoggerFactory.getLogger(clazz);
    }

    private CadetLogger(String name) {
        this.logger = LoggerFactory.getLogger(name);
    }

    /**
     * Points file logging at whatever the configuration now names.
     *
     * <p>Called after a configuration change: {@code logging.logFile}, {@code logging.maxLogSize}
     * and {@code logging.maxLogFiles} are all settable at runtime, and the shared writer resolves
     * its destination once.</p>
     */
    public static void reopenLogFile() {
        LogFileWriter.reopen();
    }

    public static CadetLogger getLogger(Class<?> clazz) {
        String key = clazz.getName();
        return loggerCache.computeIfAbsent(key, k -> new CadetLogger(clazz));
    }

    public static CadetLogger getLogger(String name) {
        return loggerCache.computeIfAbsent(name, k -> new CadetLogger(name));
    }

    /**
     * Reports an error: one line on the console, and the full record in the log file.
     *
     * <p>The SLF4J call this used to make first is gone. SimpleLogger has no file appender here, so
     * its only effect was a second console line in a different format ({@code [ERROR] Grep - msg}),
     * printed immediately before this method printed its own. The file copy below is this logger's
     * own, and the console copy goes through the shared formatter so it is themed and classified
     * like every other error the user sees.</p>
     *
     * @param message the error message
     */
    public void error(String message) {
        // Errors belong in the log file too; without this the rotating log would
        // contain every level except the one worth diagnosing a failure with.
        logToFile("ERROR", message);
        com.eonmux.cadetcoder.ui.ThemedOutputFormatter.printErrorLine(message);
    }

    public void error(String message, Throwable throwable) {
        // A null throwable is a valid call (e.g. logging an error condition that
        // is not backed by an exception). Delegate to the message-only variant so
        // we never dereference a null throwable below.
        if (throwable == null) {
            error(message);
            return;
        }
        // The stack trace goes to the log file, and ONLY to the log file. Dumping it to the console
        // turned a mistyped flag into forty lines of Java frames -- twice over, since SLF4J printed
        // its own copy first -- above the one line that actually said what was wrong. The exception
        // type stays on the console line, because that is the part of a trace a user can act on.
        logToFile("ERROR", message + ": " + throwable.getMessage() +
                           System.lineSeparator() + stackTraceToString(throwable));
        com.eonmux.cadetcoder.ui.ThemedOutputFormatter.printErrorLine(
                message + ": " + throwable.getMessage()
                + " (" + throwable.getClass().getSimpleName() + ")");
    }

    /**
     * Renders a throwable's stack trace as a string.
     *
     * @param throwable the throwable to render; must not be null
     * @return the stack trace exactly as {@link Throwable#printStackTrace()} would print it
     */
    private static String stackTraceToString(Throwable throwable) {
        StringWriter stringWriter = new StringWriter();
        throwable.printStackTrace(new PrintWriter(stringWriter));
        return stringWriter.toString();
    }

    /**
     * Reports a warning: one line on the console, and the record in the log file.
     *
     * <p>The SLF4J call is gone for the same reason it is gone from {@link #error(String)}: with no
     * file appender configured, its only effect was a second console line in a different shape
     * ({@code [WARN] ConnectorAIClient - msg}, naming the Java class) beside the one the shared
     * formatter renders. Callers that want the log entry alone use {@link #warnToFile}.</p>
     *
     * @param message the warning
     */
    public void warn(String message) {
        logToFile("WARN", message);
        com.eonmux.cadetcoder.ui.ThemedOutputFormatter.printWarningLine(message);
    }

    /**
     * Logs an error to the rotating log file only, without any console output.
     * <p>
     * Intended for callers that have already rendered the user-facing line themselves
     * (e.g. {@code ThemedOutputFormatter}). Those callers must not use {@link #error(String)},
     * which would re-echo the same text via SLF4J's console appender and the "✗" line.
     */
    public void errorToFile(String message) {
        logToFile("ERROR", message);
    }

    /**
     * Logs a warning to the rotating log file only, without any console output.
     *
     * @see #errorToFile(String)
     */
    public void warnToFile(String message) {
        logToFile("WARN", message);
    }

    /**
     * Logs an informational message to the rotating log file only, without any console output.
     *
     * @see #errorToFile(String)
     */
    public void infoToFile(String message) {
        logToFile("INFO", message);
    }

    /**
     * Records one line, masked, in the rotating log file.
     *
     * <p>Masked HERE, at the one point every one of this class's entry points reaches, rather than
     * beside each of them. {@code ThemedOutputFormatter} copies every error, warning and success
     * line the user is shown into this file, and {@code ObservabilityLogger} copies every failure
     * into it beside the debug log -- so it receives the same text the debug and session logs were
     * taught to mask, and {@code logging.logFile} is set by default, so it receives it on every
     * run.</p>
     *
     * @param level   the severity to record the line under
     * @param message the line, as the caller composed it
     */
    private void logToFile(String level, String message) {
        String recorded = SecretRedactor.redact(message);
        LogFileWriter.Acceptance acceptance = LogFileWriter.getInstance()
                .enqueue(new LogEntry(LocalDateTime.now(), level, logger.getName(), recorded));
        if (acceptance == LogFileWriter.Acceptance.QUEUE_FULL) {
            // This line will not reach the log file that the configuration asked for, so say so
            // rather than lose it silently. Only a full queue is a loss: when no log file is
            // configured there is nothing to lose, and echoing here would print a second copy of
            // every message the program logs -- beside the one its caller already rendered.
            if (isOutputRouterActive()) {
                com.eonmux.cadetcoder.ui.UnifiedOutput.printlnErr("[LOG QUEUE FULL] " + level + ": " + recorded);
            } else {
                System.err.println("[LOG QUEUE FULL] " + level + ": " + recorded);
            }
        }
    }

    /**
     * Records an informational message in the log file.
     *
     * <p>File only. The SLF4J call this used to make first had no file appender behind it, so its
     * sole effect was to print {@code [INFO] OpenAIBackend - Sending request to ...} on the console
     * -- on every request, in the retired bracketed vocabulary, naming the Java class. Everything
     * here is diagnostic; information meant for the user goes through
     * {@code OutputFormatter.printInfo}, which is themed, classified and verbosity-gated.</p>
     *
     * @param message the message
     */
    public void info(String message) {
        logToFile("INFO", message);
    }

    public void debug(String message) {
        logger.debug(message);
        logToFile("DEBUG", message);

        // Also log to console if debug is enabled and verbose mode is on. Masked like the file
        // copy: one message rendered twice must not be safe in one rendering and not the other.
        String recorded = SecretRedactor.redact(message);
        try {
            Configuration               config        = ConfigManager.getInstance().getConfig();
            Configuration.LoggingConfig loggingConfig = config.getLogging();
            Configuration.UiConfig      uiConfig      = config.getUi();
            if (loggingConfig != null && uiConfig != null &&
                loggingConfig.isDebugEnabled() &&
                loggingConfig.isConsoleLoggingEnabled() &&
                uiConfig.getVerbosityLevel() >= Configuration.UiConfig.VERBOSITY.VERBOSE.ordinal()) {
                // Use UnifiedOutput to respect OutputRouter if active
                if (isOutputRouterActive()) {
                    com.eonmux.cadetcoder.ui.UnifiedOutput.printlnErr("[DEBUG] " + logger.getName() + " - " + recorded);
                } else {
                    System.err.println("[DEBUG] " + logger.getName() + " - " + recorded);
                }
            }
        } catch (Exception e) {
            // Log to standard error to avoid recursive logging
            // Use UnifiedOutput to respect OutputRouter if active
            if (isOutputRouterActive()) {
                com.eonmux.cadetcoder.ui.UnifiedOutput.printlnErr("[ERROR] Failed to log to console: " + e.getMessage());
            } else {
                System.err.println("[ERROR] Failed to log to console: " + e.getMessage());
            }
        }
    }

    public void trace(String message) {
        logger.trace(message);
        logToFile("TRACE", message);
    }

    public boolean isDebugEnabled() {
        return logger.isDebugEnabled();
    }

    public boolean isTraceEnabled() {
        return logger.isTraceEnabled();
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