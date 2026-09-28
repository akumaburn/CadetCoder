package com.eonmux.cadetcoder.harness.belief;

/**
 * The agent's own record of what it believes cannot be read or written.
 *
 * <p>Thrown when the belief log on disk is damaged, names an event nothing knows how to apply, or
 * cannot be appended to. Carrying on with the beliefs that happened to load would leave the agent
 * acting on half its notes without knowing which half, so the run stops instead.</p>
 */
public class BeliefStoreException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public BeliefStoreException(String message) {
        super(message);
    }

    public BeliefStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
