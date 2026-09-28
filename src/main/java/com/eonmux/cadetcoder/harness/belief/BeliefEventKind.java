package com.eonmux.cadetcoder.harness.belief;

import java.util.Locale;

/**
 * The five things that can happen to a belief, which are the only five lines a belief log holds.
 *
 * <p>Keeping the log to events rather than to snapshots is what makes a claim's history readable
 * after the fact: an agent that wants to know why it stopped believing something reads the refute
 * event and the indices on it, rather than diffing two versions of a notes file.</p>
 */
public enum BeliefEventKind {

    /** A claim or a question was written down. */
    ADD,

    /** More of the ledger was named behind an existing claim. */
    EVIDENCE,

    /** A claim was contradicted by what really happened. */
    REFUTE,

    /** A question was answered. */
    RESOLVE,

    /** A claim was retired in favour of a better one. */
    SUPERSEDE;

    /** How it is written in the log. */
    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Reads a kind back from the log.
     *
     * @param label how it was written
     * @return the kind
     * @throws IllegalArgumentException if nothing goes by that name
     */
    public static BeliefEventKind of(String label) {
        for (BeliefEventKind kind : values()) {
            if (kind.label().equals(label)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("there is no belief event called " + label);
    }
}
