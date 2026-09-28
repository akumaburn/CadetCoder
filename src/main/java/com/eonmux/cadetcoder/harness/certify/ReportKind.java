package com.eonmux.cadetcoder.harness.certify;

/**
 * Why a transition is in a certificate's reports.
 *
 * <p>The distinction that matters is between a model that said something and was wrong -- the first
 * two -- and a model that could not say anything at all. An agent revises those two situations
 * differently: the first is a rule to correct, the second is a representation that has stopped
 * working, and treating them alike is how a broken {@code parse} gets patched around in
 * {@code step}.</p>
 */
public enum ReportKind {

    /** The model predicted a whole observation and reality produced a different one. */
    EXACT,

    /** The model predicted named beliefs and reality contradicted at least one. */
    CONSTRAINT,

    /** The model could not say what state an observation puts the world in. */
    GROUNDING_FAILURE,

    /** The model failed while being asked a question it is supposed to answer. */
    MODEL_FAILURE,

    /** {@code step} changed the state it was handed instead of answering with a new one. */
    IMPURE_STEP;

    /** How it is written down. */
    public String label() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
