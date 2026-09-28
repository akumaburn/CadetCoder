package com.eonmux.cadetcoder.harness.loop;

/**
 * Whether whoever started a run still wants it.
 *
 * <h2>Why being called off is not a budget</h2>
 *
 * <p>An allowance is something the run agreed to before it began, and running out of one is a fact
 * about the run. A person changing their mind is a fact about the person, and it arrives at a moment
 * no allowance predicted. Folding the two together would report a run somebody stopped as a run that
 * spent what it was given, which is the one thing whoever stopped it already knows is untrue.</p>
 *
 * <h2>Why the driver asks rather than being told</h2>
 *
 * <p>The loop spends most of its time inside a request to a backend that cannot be taken back. There
 * is no moment at which something outside could hand the driver a stop; there is only the next moment
 * at which the driver is free to look. Asking is what makes those moments the driver's own, so the
 * run always ends between turns -- with the ledger written, the transcript whole, and a result to
 * hand back -- rather than part way through one.</p>
 */
@FunctionalInterface
public interface RunStop {

    /** A run nobody can call off, which is what a run with no operator behind it is. */
    static RunStop never() {
        return () -> false;
    }

    /**
     * A run that is called off when the thread driving it is interrupted.
     *
     * <p>Interruption is how this codebase's command dispatcher takes a long command back, and it is
     * how the platform says the same thing everywhere else. The flag is read rather than cleared, so
     * whatever else on the thread is also waiting to be told still finds it set.</p>
     *
     * @return the stop
     */
    static RunStop whenThreadInterrupted() {
        return () -> Thread.currentThread().isInterrupted();
    }

    /** @return whether the run should stop at the next turn it is free to */
    boolean called();
}
