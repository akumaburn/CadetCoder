package com.eonmux.cadetcoder.harness.budget;

/**
 * Thrown when a run has spent an allowance it was given.
 *
 * <p>This is not a failure of the work; it is the end of the run's licence to keep doing it. The
 * driver catches it, stops, and reports what has been achieved so far -- the ledger, the model and
 * the certificate all remain valid, because everything that was charged actually happened.</p>
 */
public class BudgetExceededException extends RuntimeException {

    public BudgetExceededException(String message) {
        super(message);
    }
}
