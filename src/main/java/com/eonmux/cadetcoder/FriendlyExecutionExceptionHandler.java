package com.eonmux.cadetcoder;

import com.eonmux.cadetcoder.logging.DebugLogger;

import picocli.CommandLine;
import picocli.CommandLine.ParseResult;

/**
 * Turns any exception that escapes a command into one clear error line and a non-zero exit code.
 *
 * <p>Without it picocli prints its own stack trace, which is how a misconfigured subcommand
 * surfaced as an unreadable {@code CommandLine$ExecutionException} dump. The full trace is still
 * kept, in the debug log, where it is useful to whoever is fixing it and invisible to everyone
 * else.</p>
 */
class FriendlyExecutionExceptionHandler implements CommandLine.IExecutionExceptionHandler {

    @Override
    public int handleExecutionException(Exception ex, CommandLine commandLine,
                                        ParseResult parseResult) {
        String detail = (ex.getMessage() != null && !ex.getMessage().trim().isEmpty())
                        ? ex.getMessage()
                        : ex.getClass().getSimpleName();
        String commandName = commandLine.getCommandSpec() != null
                             ? commandLine.getCommandSpec().name()
                             : "CadetCoder";
        OutputFormatter.printError("Command '" + commandName + "' failed: " + detail);

        try {
            DebugLogger.getInstance().error("Main", "Unhandled command execution failure", ex);
        } catch (Exception loggingFailure) {
            // Diagnostics must never mask the original failure.
            System.err.println("Failed to log command execution failure: "
                               + loggingFailure.getMessage());
        }

        int exitCode = commandLine.getCommandSpec() != null
                       ? commandLine.getCommandSpec().exitCodeOnExecutionException()
                       : 1;
        return exitCode != 0 ? exitCode : 1;
    }
}
