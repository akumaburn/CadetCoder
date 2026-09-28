package com.eonmux.cadetcoder.logging;

import com.eonmux.cadetcoder.security.SecretRedactor;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * One thing that happened in a session, as it will be written down.
 *
 * <h2>Why everything is settled here</h2>
 *
 * <p>An entry is made on the thread that logged it and written much later by another one. What it
 * holds therefore has to be a value rather than a view of something still moving: the timestamp is
 * the moment of the event and not the moment the queue drained, and the exception is rendered while
 * the thread that threw it is still where it threw it.</p>
 *
 * <p>Masking happens here for the same reason it happens once rather than at each writer. The
 * session log is on by default and readable by anyone who can read the home directory, and two
 * writers -- the readable line and the JSON record -- would each have to remember the rule. A rule
 * that every writer must remember is the arrangement that let a key through in the first place.</p>
 */
final class SessionLogEntry {

    final long                sequence;
    final LocalDateTime       timestamp;
    final LogEntryType        type;
    final String              source;
    final String              message;
    final Map<String, String> metadata;
    final LoggedFailure       failure;

    private SessionLogEntry(long sequence, LogEntryType type, String source, String message,
                            Map<String, String> metadata, Throwable exception) {
        this.sequence  = sequence;
        this.timestamp = LocalDateTime.now();
        this.type      = type;
        this.source    = source;
        this.message   = SecretRedactor.redact(message);
        this.metadata  = redacted(metadata);
        this.failure   = LoggedFailure.of(exception);
    }

    /**
     * Records an event, masked and timestamped as of now.
     *
     * @param sequence  its place in the session, counted by the logger
     * @param type      what kind of event it was
     * @param source    the part of the tool it came from
     * @param message   what to say about it, in the caller's own words
     * @param metadata  anything else worth keeping; {@code null} is nothing
     * @param exception the failure it reports, if it reports one; {@code null} if it does not
     * @return the entry to be written
     */
    static SessionLogEntry of(long sequence, LogEntryType type, String source, String message,
                              Map<String, String> metadata, Throwable exception) {
        return new SessionLogEntry(sequence, type, source, message, metadata, exception);
    }

    /**
     * Copies a metadata map with every value masked.
     *
     * <p>A copy, and one nobody can change afterwards: the caller keeps its own map and goes on
     * using it, and the writer reads this one on another thread long after.</p>
     *
     * @param metadata the caller's map; {@code null} is an empty map
     * @return a map of the same keys with masked values
     */
    private static Map<String, String> redacted(Map<String, String> metadata) {
        Map<String, String> copy = new HashMap<>();
        if (metadata != null) {
            metadata.forEach((key, value) -> copy.put(key, SecretRedactor.redact(value)));
        }
        return Collections.unmodifiableMap(copy);
    }

    /**
     * A failure as it may be recorded: rendered and masked once, when the entry was made.
     *
     * <p>Held instead of the live {@link Throwable} because the writer reads it long after the
     * throwing thread moved on, and because what it renders should be a value rather than an object
     * someone else still holds.</p>
     */
    static final class LoggedFailure {
        final String type;
        final String message;
        final String stackTrace;

        private LoggedFailure(Throwable exception) {
            this.type    = exception.getClass().getSimpleName();
            this.message = SecretRedactor.redact(exception.getMessage());

            StringWriter rendered = new StringWriter();
            exception.printStackTrace(new PrintWriter(rendered));
            this.stackTrace = SecretRedactor.redact(rendered.toString());
        }

        /**
         * @param exception the failure to record; {@code null} means there was none
         * @return the recordable form, or {@code null}
         */
        static LoggedFailure of(Throwable exception) {
            return exception == null ? null : new LoggedFailure(exception);
        }
    }
}
