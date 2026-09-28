package com.eonmux.cadetcoder.agents;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * How far along the current worker run is, for the shell's live status line.
 *
 * <h2>Why the shell needs telling at all</h2>
 *
 * <p>Every other activity reports itself by printing: the header bar reads the transcript's active
 * section, so whatever a command last said IS the status. Workers deliberately break that, because
 * their output is collected per worker rather than streamed — which is what keeps concurrent runs
 * attributable, but also means the transcript says nothing for as long as they run.</p>
 *
 * <p>Without this the header would sit on the launch line, unchanged, for minutes: the one shape of
 * status that is indistinguishable from a hang. A count of finished workers is small, but it is the
 * difference between "working" and "stuck".</p>
 */
public final class WorkerActivity {

    private static final AtomicInteger TOTAL    = new AtomicInteger();
    private static final AtomicInteger FINISHED = new AtomicInteger();

    private WorkerActivity() {
    }

    /** Marks the start of a run of {@code total} workers. */
    static void begin(int total) {
        TOTAL.set(Math.max(0, total));
        FINISHED.set(0);
    }

    /** Marks one worker as finished, however it finished. */
    static void finished() {
        FINISHED.incrementAndGet();
    }

    /** Marks the run as over, so the status line stops mentioning workers. */
    static void end() {
        TOTAL.set(0);
        FINISHED.set(0);
    }

    /** @return whether any workers are running now */
    public static boolean active() {
        return TOTAL.get() > 0;
    }

    /**
     * @return a short progress phrase such as {@code 2/5 workers done}, or empty when none are
     *         running
     */
    public static Optional<String> describe() {
        int total = TOTAL.get();
        if (total <= 0) {
            return Optional.empty();
        }
        return Optional.of(FINISHED.get() + "/" + total + " workers done");
    }
}
