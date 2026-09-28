package com.eonmux.cadetcoder.error;

import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.logging.CadetLogger;

/**
 * Reports an exception that escaped a command's own error handling.
 *
 * <h2>Why there is no automatic recovery here</h2>
 *
 * <p>This used to answer an exception by guessing at a repair from its Java type: an
 * {@code IllegalStateException} reset the AI client, an {@code IOException} reloaded the
 * configuration from disk, and anything else printed "Unhandled exception type". None of it could
 * help, because none of it had anything to do with the operation that had just failed -- the type
 * of a Java exception does not say which subsystem is unwell. A missing file reported by
 * {@code read} reloaded the configuration; "ContextEngine has been closed" reset the AI client.</p>
 *
 * <p>Reloading the configuration was worse than useless. Runtime flags -- {@code --read-only},
 * {@code --no-git}, {@code --model}, {@code --no-color} and the rest -- are applied to the
 * in-memory configuration and are not on disk, so re-reading the file discarded them. In a session
 * that runs more than one command (the interactive shell, or {@code --script}), an unrelated
 * {@code IOException} silently turned {@code --read-only} back off, and the next command was free
 * to write. A safety guarantee the user asked for must not be revoked by an error handler.</p>
 *
 * <p>So this reports the failure, once, and records it. Repair belongs where the fault is known:
 * {@code login} and the connector code reset the AI client themselves when the credential actually
 * changes.</p>
 */
public class ErrorHandler {
    private static ErrorHandler instance;
    private static CadetLogger  logger = null;

    private ErrorHandler() {
    }

    /**
     * Retrieves the singleton instance of ErrorHandler.
     *
     * @return the ErrorHandler instance
     */
    public static synchronized ErrorHandler getInstance() {
        if (instance == null) {
            instance = new ErrorHandler();
        }
        return instance;
    }

    /**
     * Reports an exception to the user and records it in the log.
     *
     * @param e the exception to handle
     */
    public void handleException(Exception e) {
        // printError is the user-facing copy; errorToFile records it without printing a second one.
        OutputFormatter.printError(describe(e));
        getLogger().errorToFile("Exception handled: " + e);
        if (!isDebugEnabled()) {
            // Said only when it would tell the user something they do not already have: with
            // --debug already on, the detail is being written and repeating the offer is noise.
            OutputFormatter.printInfo("Run again with --debug to record the full detail.");
        }
    }

    /**
     * Renders an exception as a line aimed at the person who ran the command.
     *
     * <p>An exception's message is usually the whole story ("Failed to push: ..."), and wrapping it
     * in a fixed lead-in only pushes the sentence to the right. Two kinds of message are not the
     * whole story, and for those the type is what supplies the missing noun: a message that is
     * absent, and a message that is a single bare token -- Lucene and the file-system exceptions
     * report a path and nothing else, which arrived as {@code An error occurred: /home/.../write.lock}.</p>
     *
     * @param e the exception
     * @return one line of text, never {@code null}
     */
    static String describe(Exception e) {
        if (e == null) {
            return "The command failed for an unknown reason.";
        }
        String type    = e.getClass().getSimpleName();
        String message = e.getMessage() == null ? "" : e.getMessage().trim();
        if (message.isEmpty()) {
            return "The command failed with " + type + ".";
        }
        if (message.indexOf(' ') < 0) {
            return type + ": " + message;
        }
        return message;
    }

    /** Whether the detail this would otherwise offer to record is already being recorded. */
    private static boolean isDebugEnabled() {
        try {
            return ConfigManager.getInstance().getConfig().getLogging().isDebugEnabled();
        } catch (Exception ignored) {
            // Configuration is unavailable during early start-up; offering the flag is harmless.
            return false;
        }
    }

    private static CadetLogger getLogger() {
        if (logger == null) {
            logger = CadetLogger.getLogger(ErrorHandler.class);
        }
        return logger;
    }
}
