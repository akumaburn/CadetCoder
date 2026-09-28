package com.eonmux.cadetcoder.harness.model;

/**
 * A model could not say what state an observation puts the world in.
 *
 * <p>Kept apart from every other model error because it is the one that must never be tolerated
 * quietly. A {@code parse} that answers with nothing when it does not understand an observation
 * leaves the agent predicting about a world it is not in, and everything downstream -- the ledger,
 * the certificate, the plan -- goes on looking healthy while it does.</p>
 */
public class GroundingException extends ModelException {

    private static final long serialVersionUID = 1L;

    public GroundingException(String message) {
        super(message);
    }

    public GroundingException(String message, Throwable cause) {
        super(message, cause);
    }
}
