package com.eonmux.cadetcoder.logging;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * One line destined for the rotating log file, captured on the thread that logged it.
 *
 * <p>Everything here is recorded at construction rather than at write time, because the writer
 * thread has none of it: the timestamp would be the moment the queue drained, the thread name would
 * always be the writer's own, and the stack would lead back to the queue instead of to the call.</p>
 */
final class LogEntry {

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /** Frames that describe the logging machinery rather than the code that used it. */
    private static final String LOGGING_PACKAGE = "com.eonmux.cadetcoder.logging.";

    private final LocalDateTime timestamp;
    private final String        level;
    private final LogLevel      severity;
    private final String        loggerName;
    private final String        message;
    private final String        threadName;
    private final long          threadId;
    private final String        invokingClass;

    LogEntry(LocalDateTime timestamp, String level, String loggerName, String message) {
        this.timestamp  = timestamp;
        this.level      = level;
        this.severity   = LogLevel.of(level, LogLevel.INFO);
        this.loggerName = loggerName;
        this.message    = message;

        Thread currentThread = Thread.currentThread();
        this.threadName    = currentThread.getName();
        this.threadId      = currentThread.getId();
        this.invokingClass = invokingClassName();
    }

    /** @return how severe this line is, for the configured threshold to compare against */
    LogLevel severity() {
        return severity;
    }

    /** Renders the entry as it appears in the log file. */
    String format() {
        return String.format("%s [%s:%d] %s - %s - [%s] %s",
                timestamp.format(DATE_FORMAT), threadName, threadId, level, loggerName,
                invokingClass, message);
    }

    /**
     * The first stack frame outside the logging machinery: the code that actually logged.
     *
     * <p>Found by filtering rather than by counting frames. A fixed start index encodes the exact
     * call depth of one call site, so any refactoring of the logger silently starts attributing
     * lines to the wrong class -- or to {@code Unknown}, which is worse, because a log line whose
     * origin is wrong is read as fact.</p>
     */
    private static String invokingClassName() {
        try {
            for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
                String className = frame.getClassName();
                if (!className.startsWith(LOGGING_PACKAGE)
                        && !className.equals("java.lang.Thread")
                        && !className.startsWith("java.util.concurrent.")) {
                    return className + "." + frame.getMethodName() + ":" + frame.getLineNumber();
                }
            }
            return "Unknown";
        } catch (Exception e) {
            return "Unknown";
        }
    }
}
