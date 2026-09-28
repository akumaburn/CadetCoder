package com.eonmux.cadetcoder.commands;

/**
 * What running one action came to.
 *
 * <h2>Why the failure is classified and not just reported</h2>
 *
 * <p>The text of a failure is for the model to read; the kind of failure is for the loop to act on.
 * A file that is not where the model thought can be looked for; a refused path cannot. Keeping the
 * kind as a named vocabulary rather than a substring test on the message means a reworded message
 * cannot quietly turn a recoverable failure into an unrecoverable one.</p>
 */
final class ActionOutcome {

    /** The action's arguments did not meet the command's requirements. */
    static final String VALIDATION = "validation";
    /** A path or command the security policy refuses. */
    static final String SECURITY = "security";
    /** The command ran and reported failure. */
    static final String EXECUTION = "execution";
    /** The named file is not there. */
    static final String FILE_NOT_FOUND = "file_not_found";
    /** The file is there but cannot be read or written. */
    static final String PERMISSION = "permission";
    /** The command could not be understood as written. */
    static final String SYNTAX = "syntax";
    /** Something went wrong that nothing here can name. */
    static final String UNKNOWN = "unknown";

    final boolean success;
    final String  output;
    final String  errorType;
    final String  errorDetails;

    /**
     * An outcome with no classification of its own; a failure is {@link #UNKNOWN}.
     *
     * @param success whether the action did what it was asked
     * @param output  what it produced
     */
    ActionOutcome(boolean success, String output) {
        this(success, output, success ? null : UNKNOWN, null);
    }

    /**
     * @param success      whether the action did what it was asked
     * @param output       what it produced
     * @param errorType    which kind of failure, from the vocabulary above
     * @param errorDetails what specifically went wrong, for recovery to read
     */
    ActionOutcome(boolean success, String output, String errorType, String errorDetails) {
        this.success      = success;
        this.output       = output != null ? output : "";
        this.errorType    = errorType;
        this.errorDetails = errorDetails;
    }
}
