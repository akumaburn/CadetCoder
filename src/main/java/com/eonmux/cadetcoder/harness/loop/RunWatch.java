package com.eonmux.cadetcoder.harness.loop;

import java.util.List;

/**
 * Whoever is watching a run happen.
 *
 * <h2>Why the driver announces rather than prints</h2>
 *
 * <p>A run is minutes or hours of work somebody is waiting on, and what they need to see differs
 * with where they are watching from: a terminal wants a line per call, a log wants everything, a test
 * wants a list it can assert on. Printing inside the loop picks one of those for all of them, and
 * makes the loop untestable in the bargain.</p>
 *
 * <h2>Why every method does nothing by default</h2>
 *
 * <p>Watching is optional and partial. A caller that only cares when a run ends should say so by
 * writing that one method, not by writing five empty ones -- and adding a sixth event later should
 * not break every watcher that never asked about it.</p>
 */
public interface RunWatch {

    /** A watcher that is not interested in anything. */
    static RunWatch silent() {
        return new RunWatch() {
        };
    }

    /**
     * The agent said something.
     *
     * @param reasoner  which reasoner said it
     * @param text      what it said, in full
     * @param requested the calls that were read out of it
     */
    default void thought(String reasoner, String text, List<ToolRequest> requested) {
    }

    /**
     * A tool ran.
     *
     * @param request what was asked for
     * @param answer  what it answered, which may be a refusal
     */
    default void called(ToolRequest request, String answer) {
    }

    /**
     * Something in a reply could not be read as a call.
     *
     * @param complaint what the agent is being told, phrased as something it can fix
     */
    default void complained(String complaint) {
    }

    /**
     * A stronger reasoner took over.
     *
     * @param reasoner who has it now
     * @param reason   the plateau that handed it over
     */
    default void escalated(String reasoner, String reason) {
    }

    /**
     * The run stopped getting anywhere and there is nobody stronger to hand it to.
     *
     * <p>Said once per run. The plateau is measured over a window, so it stays true on every
     * deliberation that follows; repeating it would bury the run's work under one sentence. It is
     * not an error and does not stop anything -- it is the one moment where the person who started
     * the run can do something the run cannot, which is name a model to hand to.</p>
     *
     * @param reason what the plateau was
     */
    default void stalled(String reason) {
    }

    /**
     * The transcript was cut down.
     *
     * @param squashed how many answers have now been given up in total
     */
    default void compacted(int squashed) {
    }

    /**
     * The run stopped.
     *
     * @param result what it came to
     */
    default void ended(RunResult result) {
    }
}
