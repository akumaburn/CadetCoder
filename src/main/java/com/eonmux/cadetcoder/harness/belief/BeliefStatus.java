package com.eonmux.cadetcoder.harness.belief;

import java.util.Locale;

/**
 * What has become of one of the agent's durable claims.
 *
 * <p>The five states are what a free-form notes file spells with markers -- "(old)", "(STALE)",
 * "!! WRONG" -- and then leaves the reader to interpret. Made explicit they can be asked about:
 * {@link #ACTIVE} is what the agent may act on, {@link #OPEN} is what it still owes itself an
 * answer to, and the two dead states are kept rather than deleted because knowing which theory
 * failed, and on what evidence, is most of what stops it being tried again.</p>
 */
public enum BeliefStatus {

    /** A claim the agent is still acting on. */
    ACTIVE,

    /** A question nothing has answered yet. */
    OPEN,

    /** A question something answered. */
    RESOLVED,

    /** A claim the ledger contradicted. */
    REFUTED,

    /** A claim a better one replaced. */
    SUPERSEDED;

    /** How it is written in a record. */
    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Reads a status back from a record.
     *
     * @param label how it was written
     * @return the status
     * @throws IllegalArgumentException if nothing goes by that name
     */
    public static BeliefStatus of(String label) {
        for (BeliefStatus status : values()) {
            if (status.label().equals(label)) {
                return status;
            }
        }
        throw new IllegalArgumentException("there is no belief status called " + label);
    }

    /** Whether a belief in this state is one the agent is still carrying. */
    public boolean live() {
        return this == ACTIVE || this == OPEN;
    }
}
