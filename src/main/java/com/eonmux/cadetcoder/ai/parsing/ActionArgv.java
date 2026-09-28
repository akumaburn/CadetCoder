package com.eonmux.cadetcoder.ai.parsing;

import java.util.List;

/**
 * One action rendered as the command it will actually run and the argument list it will run with.
 *
 * <h2>Why the command travels with the arguments</h2>
 *
 * <p>Rendering can change which command runs. An {@code edit} that names both the text to find and
 * the text to put in its place is a deterministic replacement, and {@code multiedit} is the command
 * that performs one -- so that arm produces {@code multiedit} arguments and has to be able to say
 * so. Returning the arguments alone left the caller dispatching {@code edit} with an argument list
 * shaped for a different command, which reached {@code EditCommand} as prose and was sent back to
 * the model to be worked out again.</p>
 */
record ActionArgv(String command, List<String> args) {

    /**
     * @param command the command this argument list is shaped for
     * @param args    the arguments, in dispatch order
     */
    ActionArgv {
        args = List.copyOf(args);
    }
}
