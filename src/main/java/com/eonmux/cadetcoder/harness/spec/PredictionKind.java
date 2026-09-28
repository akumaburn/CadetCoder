package com.eonmux.cadetcoder.harness.spec;

/**
 * The two ways a model can commit to what happens next.
 *
 * <p>Which one was used is recorded rather than inferred, because it changes what a held prediction
 * is worth: an exact match says the model reproduced the environment, a satisfied expectation says
 * only that the beliefs the model chose to state were not contradicted.</p>
 */
public enum PredictionKind {

    /** The model named the whole next observation. */
    EXACT,

    /** The model named the parts of it it claims to know. */
    CONSTRAINT
}
