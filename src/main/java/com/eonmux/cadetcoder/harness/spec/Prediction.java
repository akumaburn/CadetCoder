package com.eonmux.cadetcoder.harness.spec;

import com.eonmux.cadetcoder.harness.Json;

import java.util.List;

/**
 * The one place a prediction is checked, whatever shape the model chose to state it in.
 *
 * <p>A model may name the next observation exactly or state an {@link Expect} about it. Both are
 * predictions, both halt a plan when they fail, and both are written into the ledger beside the
 * transition they were made about -- so both have to be checked by the same call, or the commit
 * gate would need to know which kind it was holding and would eventually get it wrong.</p>
 */
public final class Prediction {

    /** How much of a prediction is written into a record before it is cut short. */
    public static final int DESCRIPTION_LENGTH = 200;

    private static final String ELLIPSIS = "...";

    private Prediction() {
    }

    /**
     * Checks a prediction against what actually happened.
     *
     * @param prediction an {@link Expect}, or the exact observation the model named
     * @param actual     the observation that arrived
     * @return what the check found
     */
    public static PredictionOutcome check(Object prediction, Object actual) {
        if (prediction instanceof Expect expectation) {
            return new PredictionOutcome(PredictionKind.CONSTRAINT, expectation.check(actual));
        }
        Difference difference = Difference.between(prediction, actual);
        return new PredictionOutcome(PredictionKind.EXACT,
                                     difference.isEmpty()
                                     ? List.of()
                                     : List.of(new Violation("exact match", difference.render())));
    }

    /**
     * How a prediction reads once it is short enough to keep.
     *
     * @param prediction an {@link Expect}, or an exact observation
     * @return its description, cut short if it would otherwise fill the record
     */
    public static String describe(Object prediction) {
        String written = prediction instanceof Expect expectation ? expectation.describe()
                                                                  : Json.canonical(prediction);
        return written.length() <= DESCRIPTION_LENGTH
               ? written
               : written.substring(0, DESCRIPTION_LENGTH) + ELLIPSIS;
    }
}
