package com.eonmux.cadetcoder.harness.commit;

/**
 * What an irreversible action needs before it is allowed through.
 *
 * <p>This is the single setting that makes one loop safe on a shell as well as in a puzzle. In a
 * world where everything can be undone it costs nothing to leave it at {@link #ALLOW}; in a working
 * directory it is the difference between an experiment and a deletion.</p>
 */
public enum IrreversibleRule {

    /** A green certificate covering the current ledger, and no untested rule on that step. */
    CERTIFIED,

    /** Somebody is asked, every time. */
    APPROVAL,

    /** Never. */
    DENY,

    /** Always; for worlds where nothing is really irreversible. */
    ALLOW
}
