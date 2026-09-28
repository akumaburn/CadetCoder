package com.eonmux.cadetcoder.harness.commit;

/**
 * Somebody outside the loop, asked before an irreversible action.
 *
 * <p>The context is a sentence rather than a structure on purpose: whoever answers needs to know how
 * much the agent actually knows -- which model, whether it is certified, whether this step is an
 * experiment -- and a sentence is what a prompt, a log line and a dialog can all carry unchanged.</p>
 */
@FunctionalInterface
public interface Approval {

    /**
     * Whether this action may be taken.
     *
     * @param action  what is about to happen
     * @param context what the gate knows about the step it belongs to
     * @return whether to allow it
     */
    boolean approves(Object action, String context);
}
