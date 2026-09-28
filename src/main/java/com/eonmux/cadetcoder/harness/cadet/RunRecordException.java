package com.eonmux.cadetcoder.harness.cadet;

/**
 * A run's record cannot be read or cannot be written.
 *
 * <p>Thrown when the summary a record keeps about itself is present but is not a summary, or when
 * the record's directory refuses a write. Both mean the same thing: the one file that says what the
 * run was and how it ended is not saying it, and treating that as a run which recorded nothing would
 * hide a damaged record among the ordinary ones.</p>
 */
public class RunRecordException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RunRecordException(String message) {
        super(message);
    }

    public RunRecordException(String message, Throwable cause) {
        super(message, cause);
    }
}
