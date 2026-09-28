package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.ExitCode;
import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.ai.CommandApproval;
import com.eonmux.cadetcoder.security.SecretRedactor;
import com.eonmux.cadetcoder.security.SecurityValidator;
import com.eonmux.cadetcoder.ui.CapturedRun;
import com.eonmux.cadetcoder.ui.CommandOutputVisibility;
import com.eonmux.cadetcoder.ui.UnifiedOutput;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Running one action the model proposed, and bringing back what it did.
 *
 * <h2>Why the output is captured rather than simply printed</h2>
 *
 * <p>A command's output is the model's evidence for its next step, so it has to be collected
 * whatever the console is showing. Whether any of it is echoed to the terminal is a separate,
 * purely presentational decision, because an agentic turn runs many commands and printing every
 * one's raw output buries the reasoning and the result.</p>
 *
 * <p>It is collected through {@link CapturedRun}, which is per thread, rather than by replacing
 * {@code System.out} for the process. Two things turn on that. A worker pool runs eight of these at
 * once, and a save-and-restore pair on a process-global leaves one dispatch restoring another's
 * buffer. And every "can a person be asked?" check in the application reads
 * {@code OutputCapture.isCapturing()}: with the streams swapped instead, those checks all answered
 * yes while the question was written into the swallowed buffer, so a nested {@code edit} put a bare
 * {@code &gt;&gt;&gt;} on the shell and waited forever for an answer to a question nobody saw.</p>
 *
 * <h2>Why the action is copied first</h2>
 *
 * <p>Path substitution and path sanitisation both rewrite arguments. The parsed action is what the
 * loop guard hashes to recognise repetition, so rewriting it in place would let a recorded signature
 * drift from the action that was actually proposed, and repetition would stop being visible.</p>
 */
final class ActionRun {

    private final LoggingCommandSupport log;
    private final ActionPaths           paths;

    /**
     * @param log   where to record what ran, what it produced, and why anything was refused
     * @param paths what repairs the paths in an action and remembers the ones that worked
     */
    ActionRun(LoggingCommandSupport log, ActionPaths paths) {
        this.log   = log;
        this.paths = paths;
    }

    /**
     * Runs an action and reports what came of it.
     *
     * @param proposed the action as the model proposed it; never modified
     * @param context  the conversation, for the files this action proves exist
     * @param registry what actually dispatches the command
     * @return what it came to
     */
    ActionOutcome run(ChatCommand.AIAction proposed, ChatContext context, CommandRegistry registry) {
        ChatCommand.AIAction action = new ChatCommand.AIAction(
                proposed.command,
                proposed.arguments != null ? proposed.arguments.clone() : null,
                proposed.explanation);
        try {
            ActionOutcome refusal = screened(action, context);
            if (refusal != null) {
                return refusal;
            }
            if ("agent".equals(action.command)) {
                // The agent command takes one task description, not a list of words.
                action.arguments = new String[] {String.join(" ", action.arguments)};
            }
            return dispatch(action, context, registry);
        } catch (Exception e) {
            return failed(action, e);
        }
    }

    /**
     * Everything that can refuse an action before it runs.
     *
     * @return why it was refused, or {@code null} when it may proceed
     */
    private ActionOutcome screened(ChatCommand.AIAction action, ChatContext context) {
        if (action.command == null || action.command.isEmpty()) {
            return refused("Error: Command cannot be empty", "Command Validation",
                           ActionOutcome.VALIDATION, "empty_command");
        }
        if (!argumentsAreUsable(action)) {
            return refused("Error: Invalid or missing arguments for command: " + action.command,
                           "Command Validation", ActionOutcome.VALIDATION,
                           "invalid_arguments_for_" + action.command);
        }

        paths.substituteFoundPaths(action, context);

        ActionOutcome pathRefusal = screenedPath(action);
        if (pathRefusal != null) {
            return pathRefusal;
        }
        return screenedShellCommand(action);
    }

    /**
     * The path argument, if this command has one, checked.
     *
     * <p>Only the argument that actually IS a path is checked, per {@link PathArgument} -- never
     * argv[0] of every "file operation". {@code grep}'s and {@code glob}'s argv[0] is a PATTERN, so
     * the old blanket gate tested a regex for existence ("File not found: SessionManager").</p>
     *
     * <h2>Why the path is not rewritten</h2>
     *
     * <p>A path that passes the access check reaches the command as it was written. It used to be
     * "sanitised" afterwards: every {@code ..} was deleted from the text and the rest normalized.
     * The access check already refuses traversal, so that added no protection. It only changed
     * which file was meant -- {@code a..b.txt} became {@code ab.txt} -- and {@code .} normalized to
     * an empty path, so {@code ls .} reached {@code ls} as {@code ls ""}.</p>
     */
    private ActionOutcome screenedPath(ChatCommand.AIAction action) {
        int index = PathArgument.indexFor(action.command, log);
        if (index < 0 || action.arguments.length <= index) {
            return null;
        }
        String            filePath  = action.arguments[index];
        SecurityValidator validator = new SecurityValidator();

        if (!validator.isFileAccessAllowed(filePath)) {
            return refused("Error: Access to file path not allowed: " + filePath,
                           "Security Validation", ActionOutcome.SECURITY, "file_access_denied");
        }

        if ("read".equals(action.command)) {
            Path path = Paths.get(filePath);
            if (!Files.exists(path)) {
                return refused("Error: File not found: " + filePath, "File Operation",
                               ActionOutcome.FILE_NOT_FOUND, "file_not_found");
            }
            if (!Files.isReadable(path)) {
                return refused("Error: Permission denied reading file: " + filePath,
                               "File Operation", ActionOutcome.PERMISSION, "file_not_readable");
            }
        }
        return null;
    }

    /**
     * A {@code bash} action, checked against the shell policy.
     *
     * <p>The command is NOT silently rewritten: {@code BashCommand} screens it again before running,
     * so executing a sanitised variant -- different from what the model requested -- would only mask
     * intent and run something nobody approved.</p>
     *
     * <p>A refusal the screen is UNSURE about is left to the gate rather than settled here, because
     * that is the case somebody is asked about: the model under
     * {@code security.commandApproval auto}, the person at the terminal otherwise. Settling it in
     * advance would mean neither was ever asked. It is refused here only when nobody can be asked,
     * and what the screen refuses outright is always refused here, where the reason reaches the
     * model one turn earlier and costs no process. A command only the person may allow, such as a
     * force push, is left to the gate only when the person can be asked.</p>
     */
    private ActionOutcome screenedShellCommand(ChatCommand.AIAction action) {
        SecurityValidator validator = new SecurityValidator();
        for (String shellCommand : shellCommandsIn(action)) {
            SecurityValidator.CommandScreening screening = validator.screenCommand(shellCommand);
            if (screening.allowed()) {
                continue;
            }
            if (screening.personOnly() ? CommandApproval.aPersonCanBeAsked()
                                       : screening.reconsiderable()
                                         && (CommandApproval.isAuto() || CommandApproval.aPersonCanBeAsked())) {
                continue;
            }
            return refused("Error: Command execution not allowed: " + screening.reason(),
                           "Security Validation", ActionOutcome.SECURITY,
                           "command_execution_denied");
        }
        return null;
    }

    /**
     * The shell command an action would run, whichever way it means to run it.
     *
     * <p>Both ways are screened, and both are read the way the command that runs them reads them,
     * so this command's own options -- a model that writes {@code bash -f awk ...} or
     * {@code job start -d "the build" mvn test} passes some -- are not mistaken for the program
     * about to run. A background job is if anything the one worth screening most carefully: nobody
     * waits for it and it runs for as long as it likes.</p>
     *
     * <p>One {@code job} action can start several commands, one per line, and every one of them is
     * screened: a screen shown only the first would let the rest through unread.</p>
     *
     * @param action what the model asked for
     * @return the command lines it would run, in order; empty when it runs no shell command
     */
    static List<String> shellCommandsIn(ChatCommand.AIAction action) {
        if (action.arguments == null || action.arguments.length == 0) {
            return List.of();
        }
        String verb = CommandAliases.canonicalize(action.command);
        if ("bash".equals(verb)) {
            String command = BashCommand.read(action.arguments).command();
            return command == null ? List.of() : List.of(command);
        }
        if ("job".equals(verb)) {
            return JobCommand.startedCommands(action.arguments);
        }
        return List.of();
    }

    /** One refusal, said the same way to the log and to the model. */
    private ActionOutcome refused(String message, String operation, String errorType,
                                  String errorDetails) {
        log.logWarning(operation, message);
        return new ActionOutcome(false, message, errorType, errorDetails);
    }

    /**
     * Dispatches the command with this thread's output collected rather than printed.
     *
     * <h2>Why an unknown verb is not forwarded to chat</h2>
     *
     * <p>The registry's fallback exists for the person at the terminal: a line that names no
     * command is something they meant to say, so it goes to the model. An action block is not that.
     * A verb the registry does not know, proposed by a model already inside a loop, was handed to
     * {@code chat} -- a second model loop inside the first, which is exactly what
     * {@link ModelDispatch#STARTS_A_LOOP} refuses when a model asks for it by name. The model is
     * told the command does not exist instead, which is a thing it can act on.</p>
     */
    private ActionOutcome dispatch(ChatCommand.AIAction action, ChatContext context,
                                   CommandRegistry registry) {
        CapturedRun.Result run = CapturedRun.of(() -> ModelDispatch.run(action.command,
                () -> registry.executeCommand(action.command, action.arguments, false)));

        String captured = run.output();

        // A console setting, not a data-flow one: the model is given the captured text either way.
        if (CommandOutputVisibility.isVisible() && !captured.isEmpty()) {
            UnifiedOutput.println(captured.stripTrailing());
        }

        record(action, run.exitCode(), captured);

        if (run.exitCode() == 0) {
            paths.trackFoundFiles(action, context);
            return new ActionOutcome(true, CommandOutputBudget.forPrompt(captured));
        }
        if (run.exitCode() == ExitCode.INTERRUPTED) {
            // Somebody stopped this on purpose. Reported as an ordinary failure, it came back to the
            // model as an action to retry, skip or work around -- so a run the user had just
            // interrupted spent its next step deciding how to get around the interruption.
            return new ActionOutcome(false,
                    CommandOutputBudget.forPrompt(captured)
                    + System.lineSeparator()
                    + "This was stopped by the person running CadetCoder, not by a fault. Do not "
                    + "retry it or work around it.",
                    ActionOutcome.EXECUTION, "interrupted_by_user");
        }
        return new ActionOutcome(false, CommandOutputBudget.forPrompt(captured),
                                 ActionOutcome.EXECUTION,
                                 "non_zero_exit_code_" + run.exitCode());
    }

    /**
     * The full record of what ran, for the debug log.
     *
     * <p>The action's stated reason is NOT printed to the console here: this runs inside the capture
     * window, so it would land in the buffer that has already been snapshotted and reach neither the
     * console nor the model. {@link ChatActions#announce} states it before the command runs, which is
     * where it is useful.</p>
     */
    private void record(ChatCommand.AIAction action, int exitCode, String captured) {
        log.logDebug("Command Output", String.format("COMMAND: %s",
                action.command + " " + String.join(" ", action.arguments)));
        log.logDebug("Command Output", String.format("EXIT_CODE: %d", exitCode));
        log.logDebug("Command Output", String.format("REASON: %s", action.explanation));
        if (captured.isEmpty()) {
            log.logDebug("Command Output", "OUTPUT: <no output>");
            return;
        }
        log.logDebug("Command Output",
                String.format("OUTPUT_LENGTH: %d characters", captured.length()));
        log.logDebug("Command Output", "=== COMMAND OUTPUT START ===");
        log.logDebug("Command Output", captured);
        log.logDebug("Command Output", "=== COMMAND OUTPUT END ===");
    }

    /**
     * The outcome of an action that threw, classified by what was thrown.
     *
     * <p>The kind matters because the loop acts on it: a file that is not there can be looked for, a
     * refused one cannot. Classifying from the exception type rather than its message means a
     * reworded message cannot change what happens next.</p>
     */
    private ActionOutcome failed(ChatCommand.AIAction action, Exception e) {
        String errorType    = ActionOutcome.UNKNOWN;
        String errorDetails = "exception";

        if (e instanceof java.io.FileNotFoundException) {
            errorType    = ActionOutcome.FILE_NOT_FOUND;
            errorDetails = "file_not_found";
        } else if (e instanceof java.nio.file.AccessDeniedException) {
            errorType    = ActionOutcome.PERMISSION;
            errorDetails = "access_denied";
        } else if (e instanceof SecurityException) {
            errorType    = ActionOutcome.SECURITY;
            errorDetails = "security_violation";
        } else if (e instanceof IllegalArgumentException) {
            errorType    = ActionOutcome.VALIDATION;
            errorDetails = "invalid_arguments";
        } else if (e instanceof java.io.IOException) {
            errorType    = ActionOutcome.EXECUTION;
            errorDetails = "io_error";
        } else if (e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null) {
            // Reflection hides the real failure, so the cause is what names this one.
            errorType    = ActionOutcome.EXECUTION;
            errorDetails = "invocation_error_" + e.getCause().getClass().getSimpleName();
        } else if (e instanceof java.util.concurrent.TimeoutException) {
            errorType    = ActionOutcome.EXECUTION;
            errorDetails = "timeout";
        } else if (e instanceof UnsupportedOperationException) {
            errorType    = ActionOutcome.EXECUTION;
            errorDetails = "unsupported_operation";
        } else if (e instanceof java.text.ParseException) {
            errorType    = ActionOutcome.SYNTAX;
            errorDetails = "parse_error";
        }

        log.logDebug("Command Output", String.format("COMMAND: %s",
                action.command + " " + String.join(" ", action.arguments)));
        log.logDebug("Command Output", "STATUS: EXCEPTION");
        log.logDebug("Command Output", String.format("REASON: %s", action.explanation));
        log.logDebug("Command Output", String.format("ERROR_TYPE: %s", errorType));
        log.logDebug("Command Output", String.format("ERROR_DETAILS: %s", errorDetails));
        log.logDebug("Command Output", String.format("ERROR: %s", e.getMessage()));
        log.logDebug("Command Output", "OUTPUT: <exception occurred during execution>");

        OutputFormatter.printError("Failed to execute " + action.command + ": " + e.getMessage());
        // Masked, because this is the one copy of the message that is not. The console copy above
        // goes through the formatter and the debug copy through the logger, both of which redact;
        // this one is written straight into the transcript the model reads and sends back, and a
        // provider's 401 body or a failing command's message is exactly where a key turns up.
        return new ActionOutcome(false, SecretRedactor.redact("Error: " + e.getMessage()),
                                 errorType, errorDetails);
    }

    /**
     * Whether an action carries the arguments its command needs.
     *
     * <p>{@code grep} is deliberately absent from the path checks: its first positional is the search
     * pattern, and its path is an option.</p>
     *
     * @param action the action to judge
     * @return {@code true} when the command can be dispatched as written
     */
    boolean argumentsAreUsable(ChatCommand.AIAction action) {
        if (action == null || action.command == null || action.command.isEmpty()) {
            return false;
        }
        String   command = action.command;
        String[] args    = action.arguments;

        if (needsAnArgument(command) && (args == null || args.length == 0)) {
            return complain(command + " command requires at least one argument");
        }
        // grep takes one positional pattern plus optional --path/--include/--exclude OPTIONS, so a
        // valid grep is exactly the pattern, present and non-empty.
        if ("grep".equals(command)
            && (args == null || args.length < 1 || args[0] == null || args[0].trim().isEmpty())) {
            return complain("grep command requires a non-empty search pattern");
        }
        if ("write".equals(command) && (args == null || args.length < 2)) {
            return complain("write command requires at least two arguments: file and content");
        }
        // An edit is driven by its REQUEST, which is all of argv joined into one sentence. A blank
        // request would send the model nothing to act on -- but it is not a path, and is not
        // described or validated as one.
        if ("edit".equals(command) && args != null && args.length > 0
            && (args[0] == null || args[0].trim().isEmpty())) {
            return complain("edit command requires a non-empty edit request");
        }
        if (("read".equals(command) || "find".equals(command))
            && args != null && args.length > 0
            && (args[0] == null || args[0].trim().isEmpty())) {
            return complain("File path cannot be empty for " + command + " command");
        }
        return true;
    }

    /** The commands that can do nothing at all without an argument. */
    private static boolean needsAnArgument(String command) {
        switch (command) {
            case "read":
            case "write":
            case "edit":
            case "grep":
            case "find":
            case "search":
            case "glob":
                return true;
            default:
                return false;
        }
    }

    /** Says why the arguments will not do, and answers "they will not do". */
    private boolean complain(String why) {
        log.logWarning("Command Validation", why);
        return false;
    }
}
