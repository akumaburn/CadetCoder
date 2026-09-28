package com.eonmux.cadetcoder.harness.model.lang;

/**
 * A model cannot be read as a program.
 *
 * <p>Raised before anything runs -- for a malformed statement, but also for a name the program never
 * defines. Catching a typo at parse time matters more here than in most languages: a model that
 * refers to something that is not there would otherwise fail in the middle of a replay, where the
 * damage looks like evidence against the theory rather than a slip in writing it down.</p>
 */
public class ModelSyntaxException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ModelSyntaxException(String message, int line, int column) {
        super("line " + line + ", column " + column + ": " + message);
    }

    public ModelSyntaxException(String message) {
        super(message);
    }
}
