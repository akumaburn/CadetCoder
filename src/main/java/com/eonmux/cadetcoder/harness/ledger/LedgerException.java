package com.eonmux.cadetcoder.harness.ledger;

/**
 * The record of what happened cannot be trusted.
 *
 * <p>Thrown only when the ledger on disk fails to verify -- a rewritten line, a missing one, a torn
 * write -- or when it cannot be read or appended to. Every one of those means the evidence every
 * certificate rests on is gone, so nothing downstream may carry on with a partial answer.</p>
 */
public class LedgerException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public LedgerException(String message) {
        super(message);
    }

    public LedgerException(String message, Throwable cause) {
        super(message, cause);
    }
}
