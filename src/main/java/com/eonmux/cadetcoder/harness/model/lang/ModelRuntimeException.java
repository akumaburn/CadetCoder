package com.eonmux.cadetcoder.harness.model.lang;

/**
 * A model failed while it was running.
 *
 * <p>Every one of these is a fact about the model rather than about the world, which is why
 * certification records it as a model error and never as evidence that reality misbehaved. Reading
 * a field that is not there, dividing by zero, running past the step limit and editing a frozen
 * observation all arrive here, each naming the line it happened on.</p>
 */
public class ModelRuntimeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int line;

    public ModelRuntimeException(String message, int line, int column) {
        super("line " + line + ", column " + column + ": " + message);
        this.line = line;
    }

    public ModelRuntimeException(String message) {
        super(message);
        this.line = 0;
    }

    /** Which line of the model failed, or {@code 0} when the failure is not tied to one. */
    public int line() {
        return line;
    }
}
