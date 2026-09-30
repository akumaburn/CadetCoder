package com.eonmux.cadetcoder.harness.cadet;

/**
 * A run that ended on a failure it could not turn into a result, with the record it left.
 *
 * <p>A model request that fails, or that the user interrupts, ends the run with an exception, and
 * the {@link RunOutcome} that names the record is never made. The record still holds what the run
 * did before then, and a resume needs it, so the failure carries the record with it.</p>
 */
public final class RunCutShort extends RuntimeException {

    private final transient RunRecord record;

    /**
     * @param record  the record the run kept
     * @param failure what ended it
     */
    public RunCutShort(RunRecord record, RuntimeException failure) {
        super(failure.getMessage(), failure);
        this.record = record;
    }

    /** @return the record the run kept */
    public RunRecord record() {
        return record;
    }

    /** @return what ended the run */
    public RuntimeException failure() {
        return (RuntimeException) getCause();
    }
}
