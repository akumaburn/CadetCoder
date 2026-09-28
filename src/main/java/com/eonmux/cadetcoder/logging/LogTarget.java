package com.eonmux.cadetcoder.logging;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

/**
 * Where the log goes and how large it may grow: the whole of the logging destination, as one value.
 *
 * <p>Resolved on the thread that has the configuration in hand, never on the writer thread. The
 * rotation policy used to be fetched from {@link ConfigManager} for every log line from the
 * background writer -- a thread that arrives at an arbitrary moment, so if the singleton happened
 * to be unset when it got there, the LOGGER built the configuration, against whatever the default
 * base directory pointed at just then. Whoever asked for the configuration next got the logger's
 * copy, and a process that only meant to write one line had created a configuration tree in the
 * user's home.</p>
 */
final class LogTarget {

    /** Matches {@code Configuration.LoggingConfig}'s own defaults, used when no config is readable. */
    private static final int DEFAULT_MAX_LOG_SIZE_MB = 10;
    private static final int DEFAULT_MAX_LOG_FILES   = 5;

    /** What {@code logging.level} means when it says nothing this class recognises. */
    private static final LogLevel DEFAULT_LEVEL = LogLevel.INFO;

    private final Path     file;
    private final long     maxSizeBytes;
    private final int      maxFiles;
    private final LogLevel threshold;

    private LogTarget(Path file, long maxSizeBytes, int maxFiles, LogLevel threshold) {
        this.file         = file;
        this.maxSizeBytes = maxSizeBytes;
        this.maxFiles     = maxFiles;
        this.threshold    = threshold;
    }

    /**
     * Whether a line of this severity is worth writing.
     *
     * <p>{@code logging.level} is read here, with the destination and the rotation policy, because
     * it is resolved the same way and changes at the same moments -- and because the alternative was
     * what it had: the setting existed, was documented, was settable, and nothing read it, so a user
     * who quietened their log was told it was done and got the same log as before.</p>
     *
     * @param entry the severity of the line being logged
     * @return whether it belongs in this log
     */
    boolean records(LogLevel entry) {
        return threshold.records(entry);
    }

    /**
     * Reads the current logging destination from the configuration.
     *
     * @return the target, or {@code null} when the configuration names no log file -- in which case
     *         there is nothing to write to and file logging stays off. A blank setting counts as
     *         naming none: {@code logging.logFile=""} resolves to the base directory itself, so
     *         treating it as a path would report the log as broken rather than as switched off.
     */
    static LogTarget fromConfiguration() {
        try {
            Configuration               config  = ConfigManager.getInstance().getConfig();
            Configuration.LoggingConfig logging = config.getLogging();
            if (logging == null || logging.getLogFile() == null || logging.getLogFile().trim().isEmpty()) {
                return null;
            }

            Path declared = Paths.get(logging.getLogFile().trim());
            Path resolved = declared.isAbsolute()
                            ? declared
                            : Paths.get(config.getBaseDir()).resolve(declared);

            return new LogTarget(resolved.normalize(),
                                 Math.max(1L, sizeMegabytes(logging)) * 1024L * 1024L,
                                 Math.max(1, filesOrDefault(logging)),
                                 thresholdFor(logging));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The severity below which nothing is written.
     *
     * <p>{@code logging.level}, except that {@code logging.debugEnabled} lowers it to DEBUG: asking
     * for debugging and then not being shown the debug lines is not an outcome anyone wants, and
     * {@code --debug} is how that is asked for. It only ever lowers the threshold, so a user who
     * has set TRACE keeps TRACE.</p>
     *
     * @param logging the logging settings
     * @return the threshold to hold the log to
     */
    private static LogLevel thresholdFor(Configuration.LoggingConfig logging) {
        LogLevel configured = LogLevel.of(logging.getLevel(), DEFAULT_LEVEL);
        if (logging.isDebugEnabled() && !configured.records(LogLevel.DEBUG)) {
            return LogLevel.DEBUG;
        }
        return configured;
    }

    private static long sizeMegabytes(Configuration.LoggingConfig logging) {
        int configured = logging.getMaxLogSize();
        return configured > 0 ? configured : DEFAULT_MAX_LOG_SIZE_MB;
    }

    private static int filesOrDefault(Configuration.LoggingConfig logging) {
        int configured = logging.getMaxLogFiles();
        return configured > 0 ? configured : DEFAULT_MAX_LOG_FILES;
    }

    Path file() {
        return file;
    }

    long maxSizeBytes() {
        return maxSizeBytes;
    }

    /** The file backing rotation slot {@code index}: the configured path, then {@code name.N.ext}. */
    Path slot(int index) {
        if (index == 0) {
            return file;
        }
        String fileName  = file.getFileName().toString();
        int    dot       = fileName.lastIndexOf('.');
        String baseName  = dot < 0 ? fileName : fileName.substring(0, dot);
        String extension = dot < 0 ? "" : fileName.substring(dot);
        Path   parent    = file.getParent();
        String rotated   = baseName + "." + index + extension;
        return parent != null ? parent.resolve(rotated) : Paths.get(rotated);
    }

    /** The slot after {@code index}, wrapping so the configured number of files bounds growth. */
    int nextSlot(int index) {
        return (index + 1) % maxFiles;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof LogTarget)) {
            return false;
        }
        LogTarget that = (LogTarget) other;
        return maxSizeBytes == that.maxSizeBytes
               && maxFiles == that.maxFiles
               && threshold == that.threshold
               && Objects.equals(file, that.file);
    }

    @Override
    public int hashCode() {
        return Objects.hash(file, maxSizeBytes, maxFiles, threshold);
    }

    @Override
    public String toString() {
        return file + " (max " + maxSizeBytes + " bytes across " + maxFiles + " files, from "
               + threshold + ")";
    }
}
