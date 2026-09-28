package com.eonmux.cadetcoder.harness.tools;

/**
 * Something the toolbox was asked to do and could not.
 *
 * <p>A tool call comes from a language model, so most of what goes wrong is the call itself and is
 * reported back as an answer the agent can learn from. This is for the rest: the workspace that
 * cannot be written to, the notes file that cannot be read. It is a failure of the harness rather
 * than of the agent, and it says which.</p>
 */
public class ToolException extends RuntimeException {

    /** @param message what could not be done */
    public ToolException(String message) {
        super(message);
    }

    /**
     * @param message what could not be done
     * @param cause   what stopped it
     */
    public ToolException(String message, Throwable cause) {
        super(message, cause);
    }
}
