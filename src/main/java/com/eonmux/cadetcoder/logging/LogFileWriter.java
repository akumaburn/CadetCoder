package com.eonmux.cadetcoder.logging;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The single writer of the rotating log file.
 *
 * <h2>Why there is exactly one</h2>
 *
 * <p>{@link CadetLogger} caches an instance per logger NAME, and twenty-one names are constructed
 * across the codebase. When each of those instances opened its own writer on the one configured
 * path, it also kept its own byte counter and its own rotation index -- so the size limit bounded
 * nothing (the file grew to roughly the cap multiplied by the number of loggers), and rotation was
 * destructive: the first writer to cross its own threshold truncated slot 1 while the other twenty
 * kept appending through handles onto files that had been rotated underneath them. Lines from a
 * single session ended up interleaved across slots and partially overwritten, which is precisely the
 * situation a log exists to survive.</p>
 *
 * <h2>Where the destination comes from</h2>
 *
 * <p>The destination is a {@link LogTarget}, always read from the configuration on the thread that
 * is logging -- never on the writer thread, which arrives at an arbitrary moment and would build a
 * configuration tree of its own if the singleton happened to be unset when it got there.</p>
 *
 * <p>It is resolved lazily, on the first entry rather than in the constructor: reading the
 * configuration can itself log, and a logger that logs from inside this class's initializer would
 * find {@code INSTANCE} still null. Resolution is retried on every entry for as long as the
 * configuration names no destination, so file logging that could not start during early startup
 * begins as soon as the configuration is readable -- but not after a destination was named and
 * failed to open, which is a standing condition that retrying once per line would only turn into a
 * flood of failed opens.</p>
 */
final class LogFileWriter {

    /** Bounds the memory a logging burst can take; beyond it, entries are reported as lost. */
    private static final int QUEUE_CAPACITY = 10_000;

    private static final String WRITER_THREAD_NAME = "CadetLogger-Thread";

    /** How long shutdown waits for the queue to drain before letting the JVM go. */
    private static final long SHUTDOWN_TIMEOUT_MS = 5_000;

    private static final LogFileWriter INSTANCE = new LogFileWriter();

    /**
     * What became of an entry that was offered to the writer.
     *
     * <p>The distinction matters to the caller: only {@link #QUEUE_FULL} is a loss. A log line with
     * nowhere to go is what a configuration that names no log file asks for, and echoing it to the
     * console instead would print every message the program logs a second time.</p>
     */
    enum Acceptance {
        /** Handed to the writer thread. */
        QUEUED,
        /** There is no log file to write to, so the entry was deliberately discarded. */
        NO_DESTINATION,
        /** There is a log file, but the queue is full and the entry was lost. */
        QUEUE_FULL,
        /** The entry is less severe than {@code logging.level} asks to be recorded. */
        BELOW_LEVEL
    }

    private final BlockingQueue<LogEntry> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);

    /** True while a file is open to write to; read by logging threads, written under the monitor. */
    private final AtomicBoolean hasDestination = new AtomicBoolean(false);

    /** True once a named destination failed to open, so the failure is neither retried nor re-reported. */
    private final AtomicBoolean openFailed = new AtomicBoolean(false);

    /** Guards against a configuration read that logs re-entering resolution on the same thread. */
    private final ThreadLocal<Boolean> resolving = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private final AtomicBoolean writerThreadStarted = new AtomicBoolean(false);
    private final AtomicBoolean draining            = new AtomicBoolean(true);

    /** Guarded by this. */
    private PrintWriter writer;
    private LogTarget   target;
    private long        currentFileSize;
    private int         currentSlot;

    private LogFileWriter() {
        // Deliberately empty. See "Where the destination comes from" above: resolving it here would
        // run while this class is still initializing.
    }

    static LogFileWriter getInstance() {
        return INSTANCE;
    }

    /**
     * Points file logging at whatever the configuration now names.
     *
     * <p>Called when the configuration changes, so that {@code config logging.logFile <path>} takes
     * effect on the next line rather than on the next launch. Without it the destination would be
     * frozen at whichever configuration happened to be loaded when the first line was logged, and a
     * user who redirected their log would be told the setting was saved while every subsequent line
     * went to the old file.</p>
     *
     * <p>Also the way back from a destination that failed to open: an explicit reopen is a fresh
     * instruction, so the earlier failure no longer suppresses the attempt.</p>
     */
    static void reopen() {
        INSTANCE.openFailed.set(false);
        INSTANCE.retarget(LogTarget.fromConfiguration());
    }

    /**
     * Offers an entry to the writer.
     *
     * @param entry the entry to write
     * @return what became of it; see {@link Acceptance}
     */
    Acceptance enqueue(LogEntry entry) {
        if (!hasDestination.get()) {
            resolveDestination();
        }
        if (!hasDestination.get()) {
            return Acceptance.NO_DESTINATION;
        }
        // Filtered before the queue rather than before the write: a line nobody asked to keep
        // should not take a place in a queue whose only failure mode is being full of them.
        if (!recordsSeverityOf(entry)) {
            return Acceptance.BELOW_LEVEL;
        }
        return queue.offer(entry) ? Acceptance.QUEUED : Acceptance.QUEUE_FULL;
    }

    /** @return whether the destination's configured level keeps a line of this severity */
    private synchronized boolean recordsSeverityOf(LogEntry entry) {
        return target == null || target.records(entry.severity());
    }

    /**
     * Reads the configuration, on this thread, and adopts whatever destination it names.
     *
     * <p>Skipped while a named destination is known to be unopenable, and skipped when reading the
     * configuration has itself reached this method on this thread -- that entry has nowhere to go
     * yet, and recursing to find out would not give it one.</p>
     */
    private void resolveDestination() {
        if (openFailed.get() || resolving.get()) {
            return;
        }
        resolving.set(Boolean.TRUE);
        try {
            retarget(LogTarget.fromConfiguration());
        } finally {
            resolving.set(Boolean.FALSE);
        }
    }

    /**
     * Adopts a destination, or gives one up.
     *
     * <p>The configuration read that produced {@code newTarget} must already have happened: this
     * method holds the writer's monitor, and the writer thread takes the same monitor for every
     * line, so anything done here stalls logging.</p>
     *
     * @param newTarget where the log now goes, or {@code null} when the configuration names no log
     *                  file -- in which case file logging stops rather than carrying on into a file
     *                  the configuration no longer mentions
     */
    private synchronized void retarget(LogTarget newTarget) {
        if (newTarget == null) {
            closeDestination();
            return;
        }

        LogTarget previousTarget = target;
        int       previousSlot   = currentSlot;
        target      = newTarget;
        // A new destination starts at its first slot; rotation counts from there.
        currentSlot = 0;
        try {
            // Appends, so lines are preserved across launches.
            openSlot(false);
        } catch (IOException e) {
            // Keep writing where we were: a log that goes to the old file is worth more than one
            // that stops.
            target      = previousTarget;
            currentSlot = previousSlot;
            openFailed.set(!hasDestination.get());
            reportToConsole("Failed to open log file " + newTarget.file() + ": " + e.getMessage());
            return;
        }

        hasDestination.set(true);
        startWriterThread();
    }

    /** Stops file logging, releasing the open file. Monitor held. */
    private void closeDestination() {
        hasDestination.set(false);
        if (writer != null) {
            writer.flush();
            writer.close();
            writer = null;
        }
        target          = null;
        currentSlot     = 0;
        currentFileSize = 0;
    }

    /**
     * Starts the writer thread, once for the life of the process.
     *
     * <p>Once, because file logging can be switched off and on again, and a thread per switch would
     * leave several draining the same queue onto the same file -- the interleaving this class exists
     * to prevent.</p>
     */
    private void startWriterThread() {
        if (!writerThreadStarted.compareAndSet(false, true)) {
            return;
        }
        Thread writerThread = new Thread(this::drainQueue, WRITER_THREAD_NAME);
        writerThread.setDaemon(true);
        writerThread.start();

        Runtime.getRuntime().addShutdownHook(
                new Thread(this::shutdown, WRITER_THREAD_NAME + "-Shutdown"));
    }

    private void drainQueue() {
        while (draining.get() || !queue.isEmpty()) {
            try {
                write(queue.take());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /** Writes one entry. Synchronized so a destination change cannot land mid-line. */
    private synchronized void write(LogEntry entry) {
        if (writer == null) {
            // The destination was given up while this entry was queued.
            return;
        }
        String line = entry.format();
        writer.println(line);
        writer.flush();
        currentFileSize += line.getBytes(StandardCharsets.UTF_8).length
                           + System.lineSeparator().length();
        rotateIfFull();
    }

    /**
     * Opens the current rotation slot for writing. Monitor held.
     *
     * <h2>Why the slot is brought into existence restricted</h2>
     *
     * <p>{@code config.json} and {@code session.json} are both made owner-only because of what is
     * in them, and the same material ends up here: this file carries prompts, file contents and
     * command output. Opened through a bare {@link FileWriter} it was created at whatever the umask
     * allowed, which on a great many machines means every local account can read it. Asking for the
     * permissions at the moment of creation leaves no window at all, since a umask can only take
     * permissions away; a slot that already exists is narrowed instead, which is what a log written
     * before this rule existed needs.</p>
     *
     * @param truncate when true the slot file is overwritten and the size counter reset (used when
     *                 rotating into a reused slot); when false the file is appended to and the
     *                 counter seeded from its existing size
     * @throws IOException when the slot cannot be opened; the previous writer is kept
     */
    private void openSlot(boolean truncate) throws IOException {
        Path slot   = target.slot(currentSlot);
        Path logDir = slot.getParent();
        if (logDir != null && !Files.exists(logDir)) {
            Files.createDirectories(logDir);
        }

        String unprotected = com.eonmux.cadetcoder.security.OwnerOnlyFile.createOwnerOnly(slot);
        if (unprotected != null) {
            reportToConsole("Failed to create " + slot + " restricted to this account - it holds "
                            + "prompts, file contents and command output and may be readable by "
                            + "other users: " + unprotected);
        }

        PrintWriter previous = writer;
        PrintWriter opened   = null;
        try {
            opened          = new PrintWriter(
                    new FileWriter(slot.toFile(), StandardCharsets.UTF_8, !truncate), true);
            currentFileSize = (!truncate && Files.exists(slot)) ? Files.size(slot) : 0;

            // Only swap in the new writer once it exists, so a failure leaves logging working.
            writer = opened;
            if (previous != null) {
                previous.close();
            }
        } catch (IOException e) {
            if (opened != null) {
                opened.close();
            }
            throw e;
        }
    }

    /** Monitor held. */
    private void rotateIfFull() {
        if (currentFileSize <= target.maxSizeBytes()) {
            return;
        }
        try {
            currentSlot = target.nextSlot(currentSlot);
            // Truncate the reused slot so the size and count limits actually bound growth;
            // appending here would re-open an already-full file and rotate forever.
            openSlot(true);
        } catch (IOException e) {
            reportToConsole("Failed to rotate log file: " + e.getMessage());
        }
    }

    private void shutdown() {
        draining.set(false);
        // Give the writer a moment to finish what is already queued, rather than dropping the last
        // lines before a crash -- which are the ones worth having.
        long deadline = System.currentTimeMillis() + SHUTDOWN_TIMEOUT_MS;
        while (!queue.isEmpty() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        synchronized (this) {
            if (writer != null) {
                // Flushed, not closed: every line is already flushed as it is written, and closing
                // here would race the writer thread onto a dead handle for a descriptor the JVM is
                // about to release anyway.
                writer.flush();
            }
        }
    }

    /** Reports a logging failure itself, without recursing back through the logger. */
    private static void reportToConsole(String message) {
        try {
            if (com.eonmux.cadetcoder.ui.OutputRouter.getInstance().isRouting()) {
                com.eonmux.cadetcoder.ui.UnifiedOutput.printlnErr(message);
                return;
            }
        } catch (Exception ignored) {
            // Routing status is unknowable here; fall through to standard error.
        }
        System.err.println(message);
    }
}
