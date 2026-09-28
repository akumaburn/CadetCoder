package com.eonmux.cadetcoder;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Whether the user has asked the work in flight to stop.
 *
 * <p>One flag for the whole process, because that is what the user means: F2 stops the command at
 * the prompt, and everything that command has fanned out to. A single {@code /agent} run can have a
 * dozen sub-commands on {@code WorkerPool} threads at once, and stopping only the one whose thread
 * happens to be asked is not stopping.</p>
 *
 * <h2>What this replaces</h2>
 *
 * <p>The signal used to travel as a system property that {@code CommandRegistry} read only after
 * walking the calling thread's stack for a frame whose class name contained
 * {@code InteractiveShell}. That made the interrupt work from the shell's own command thread and
 * nowhere else: a command dispatched by an agent runs on a {@code cadet-worker} thread whose stack
 * starts at {@code Thread.run}, so the check found no such frame, answered "not interrupted", and
 * the user's F2 did nothing at all.</p>
 */
public final class InterruptSignal {

    private static final AtomicBoolean REQUESTED = new AtomicBoolean(false);

    private InterruptSignal() {
    }

    /** Asks everything currently running to stop. */
    public static void request() {
        REQUESTED.set(true);
    }

    /**
     * Withdraws the request, so the next command does not inherit it.
     *
     * <p>Called when a command STARTS rather than when one ends: a command that will not stop
     * outlives the request to stop it, and its interrupt has to stay set for as long as it runs.</p>
     */
    public static void clear() {
        REQUESTED.set(false);
    }

    /**
     * @return whether a stop has been asked for and not yet withdrawn
     */
    public static boolean isRequested() {
        return REQUESTED.get();
    }
}
