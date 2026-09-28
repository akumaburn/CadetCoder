package com.eonmux.cadetcoder.logging;

import java.util.Locale;

/**
 * The severities a log line can carry, in the order they are ranked.
 *
 * <p>Declared least severe first, so the enum's own ordering is the comparison
 * {@link #records(LogLevel)} makes. The levels are the ones {@code CadetLogger} actually writes and
 * the ones {@code logging.level} is documented to accept.</p>
 */
enum LogLevel {
    TRACE,
    DEBUG,
    INFO,
    WARN,
    ERROR;

    /**
     * Reads a configured or recorded level.
     *
     * @param name     what the configuration or the entry called it; may be {@code null}
     * @param fallback what to use when it is not one of these
     * @return the level
     */
    static LogLevel of(String name, LogLevel fallback) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        try {
            return valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException notALevel) {
            // A setting nobody can read is no reason to stop logging: an unrecognised level leaves
            // the log as it would have been, rather than silently dropping every line.
            return fallback;
        }
    }

    /**
     * @param entry the severity of a line being logged
     * @return whether a log at this threshold records it
     */
    boolean records(LogLevel entry) {
        return entry.ordinal() >= ordinal();
    }
}
