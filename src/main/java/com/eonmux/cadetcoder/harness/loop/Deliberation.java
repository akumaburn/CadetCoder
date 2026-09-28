package com.eonmux.cadetcoder.harness.loop;

/**
 * How one round of thinking finished.
 *
 * <h2>Why a deliberation reports rather than decides</h2>
 *
 * <p>Ending a deliberation and ending a run are different events: the common case is a deliberation
 * that ends because something happened to the world, after which the next one starts from the new
 * observation. Answering with the reason lets the one loop that owns the run make that call in one
 * place, rather than each exit deciding for itself whether it was also the end of everything.</p>
 *
 * @param status what ended the run, or {@link RunStatus#RUNNING} when the run goes on
 * @param text   what was said as it ended, which is what a result carries
 */
public record Deliberation(RunStatus status, String text) {

    /** A deliberation that ended because the world moved, leaving the run going. */
    public static final Deliberation CONTINUES = new Deliberation(RunStatus.RUNNING, "");

    public Deliberation {
        if (status == null) {
            throw new IllegalArgumentException("a deliberation ends for a reason");
        }
        text = text == null ? "" : text;
    }

    /** Whether this ended the run and not just the round. */
    public boolean over() {
        return status.over();
    }
}
