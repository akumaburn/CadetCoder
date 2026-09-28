package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ExitCode;
import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.security.SecretRedactor;
import com.eonmux.cadetcoder.ui.UnifiedOutput;
import picocli.CommandLine.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

@Command (name = "bash", description = "Run one shell command")
public class BashCommand extends LoggingCommandSupport implements CommandRegistry.Command, CommandRegistry.InterruptibleCommand, Callable<Integer> {

    private static final int DEFAULT_TIMEOUT  = 120_000; // 2 minutes
    private static final int MAX_TIMEOUT      = 600_000;     // 10 minutes
    private static final int MAX_OUTPUT_CHARS = 30_000;

    /**
     * How long the output reader is given to reach end-of-file once the process has exited.
     *
     * <p>Normally it needs none of this: the pipe closes with the process and whatever is still
     * buffered is read at once. The grace is for the case where it does not close, because a
     * child the command left running inherited the write end -- there the reader will never
     * finish, and what it has captured by now is all there is going to be.</p>
     */
    private static final int OUTPUT_DRAIN_GRACE_MS = 500;
    @Parameters (index = "0..*", description = "The bash command to execute")
    private String[] commandParts;
    @Option (names = {"-t", "--timeout"}, description = "Timeout in milliseconds (max 600000)")
    private Integer timeout;
    @Option (names = {"-d", "--description"}, description = "Description of what this command does")
    private String description;
    @Option (names = {"-f", "--force"}, description = "Force execution without confirmation")
    private boolean force;
    
    private CommandRegistry.InterruptionContext interruptionContext;

    /** The commands of this tool, to recognise one given to the shell as a program. */
    private CommandRegistry registry;

    @Override
    public int execute(String[] args) {
        try {
            startCommandLogging("bash", args);
            
            if (args.length == 0) {
                logErrorQuietly("execute", "No command provided");
                OutputFormatter.printError("No command provided");
                completeCommandLogging(1);
                return 1;
            }

            logStep("Parsing command arguments");
            Invocation asked = read(args);
            if (asked.error() != null) {
                logErrorQuietly("argument parsing", asked.error());
                OutputFormatter.printError(asked.error());
                completeCommandLogging(1);
                return 1;
            }
            String  command   = asked.command();
            String  desc      = asked.description();
            int     timeoutMs = asked.timeoutMs();
            boolean forceExec = asked.force();

            if (timeoutMs != DEFAULT_TIMEOUT) {
                logStep("Setting timeout", timeoutMs + "ms");
            }
            if (desc != null) {
                logStep("Setting description", desc);
            }
            if (forceExec) {
                logStep("Enabling force execution");
            }
            addContext("command", command);
            addContext("timeout", timeoutMs);
            addContext("forced", forceExec);
            
            logStep("Executing bash command", command);
            int result = executeBashCommand(command, desc, timeoutMs, forceExec);
            
            completeCommandLogging(result);
            return result;
        } catch (Exception e) {
            logErrorQuietly("execute", "Error executing command", e);
            OutputFormatter.printError("Error executing command: " + e.getMessage());
            completeCommandLogging(1);
            return 1;
        }
    }

    /**
     * What one {@code bash} invocation asked for.
     *
     * @param command     the shell command line, joined as it will be run
     * @param description what the caller said it is for, or {@code null}
     * @param timeoutMs   how long it may take
     * @param force       whether the confirmation was answered in advance
     * @param error       what is wrong with the arguments, or {@code null} when they are usable
     */
    record Invocation(String command, String description, int timeoutMs, boolean force,
                      String error) {
    }

    /**
     * Reads this command's own options off the front of its arguments.
     *
     * <h2>Why the rule is here rather than inline</h2>
     *
     * <p>Two callers need the same answer. This command screens and runs the line it finds, and
     * {@code ActionRun} screens the same invocation before dispatching it -- and screened the whole
     * argv instead, so a model that wrote {@code bash -f awk '...'} had {@code -f} read as the
     * program it was about to run, and the refusal named it. One rule, asked twice.</p>
     *
     * <p>The options are recognised ONLY before the shell command begins, and an explicit
     * {@code --} ends them early. Every token used to be scanned, so flags belonging to the command
     * being RUN were stolen:</p>
     *
     * <pre>
     *   bash rm -f build/   -&gt; "-f" became force-execute (skipping the confirmation gate!)
     *                          and the command actually run was "rm build/"
     *   bash ls -d /tmp     -&gt; "-d" ate "/tmp" as a description; bare "ls" ran in the CWD
     *   bash find . -t f    -&gt; "-t" ate "f" as a timeout -&gt; "Invalid timeout value: f"
     * </pre>
     *
     * <p>Stopping at the first non-option token matches every prefix-runner convention
     * ({@code timeout}, {@code env}, {@code nohup}) and is what the usage string already implied.</p>
     *
     * @param args the arguments as given
     * @return what was asked for, or an {@link Invocation#error()} saying what is wrong with it
     */
    static Invocation read(String[] args) {
        List<String> cmdParts     = new ArrayList<>();
        String       desc         = null;
        int          timeoutMs    = DEFAULT_TIMEOUT;
        boolean      forceExec    = false;
        boolean      optionsEnded = false;

        for (int i = 0; args != null && i < args.length; i++) {
            String token = args[i];

            if (optionsEnded) {
                cmdParts.add(token);
                continue;
            }
            if ("--".equals(token)) {
                optionsEnded = true;
                continue;
            }

            switch (token) {
                case "-t":
                case "--timeout":
                    if (++i >= args.length) {
                        return new Invocation("", null, 0, false, "Missing value for " + token);
                    }
                    try {
                        timeoutMs = Integer.parseInt(args[i]);
                    } catch (NumberFormatException e) {
                        return new Invocation("", null, 0, false,
                                              "Invalid timeout value: " + args[i]);
                    }
                    break;
                case "-d":
                case "--description":
                    if (++i >= args.length) {
                        return new Invocation("", null, 0, false, "Missing value for " + token);
                    }
                    desc = args[i];
                    break;
                case "-f":
                case "--force":
                    forceExec = true;
                    break;
                default:
                    // First token that is not one of this command's options starts the shell
                    // command; everything from here on belongs to it, flags included.
                    optionsEnded = true;
                    cmdParts.add(token);
                    break;
            }
        }
        return new Invocation(String.join(" ", cmdParts), desc, timeoutMs, forceExec, null);
    }

    private int executeBashCommand(String command, String description, int timeoutMs, boolean forceExec) {
        logStep("Starting security validation", command);

        // Screened and confirmed by the same gate `job start` uses, so the way of running a command
        // that nobody waits for cannot end up screened differently from this one. See CommandGate.
        CommandGate.Verdict screened = CommandGate.screen(this, command, description, forceExec);
        if (!screened.allowed()) {
            return screened.exitCode();
        }

        if (timeoutMs < 0) {
            OutputFormatter.printError("Timeout cannot be negative");
            return 1;
        }
        if (timeoutMs > MAX_TIMEOUT) {
            OutputFormatter.printWarning("Timeout exceeds maximum (" + MAX_TIMEOUT + "ms), using maximum");
            timeoutMs = MAX_TIMEOUT;
        }

        OutputFormatter.printHeader("Command: " + command);
        if (description != null) {
            try {
                OutputFormatter.printInfo("Description: " + description);
            } catch (NullPointerException e) {
                OutputFormatter.printWarning("UI configuration missing for description verbosity");
            }
        }

        CommandGate.Verdict confirmed =
                CommandGate.confirm(this, command, description, forceExec, screened.alreadyApproved());
        if (!confirmed.allowed()) {
            return confirmed.exitCode();
        }

        // Asked before the process is started, not after. Asked only afterwards, a command that had
        // already been taken back was run anyway and then killed -- and whatever it managed to do in
        // between, it did. The check after the start stays: an interruption can also arrive while
        // the process is being set up, and that one has a process to kill.
        if (shouldInterrupt()) {
            logDebug("Command execution", String.format("COMMAND: %s", command));
            logDebug("Command execution", "STATUS: INTERRUPTED BEFORE STARTING");
            OutputFormatter.printWarning("Command interrupted by user request");
            return ExitCode.INTERRUPTED;
        }

        try {
            logStep("Creating process", String.format("bash -c \"%s\"", command));
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
            pb.redirectErrorStream(true);
            pb.directory(new java.io.File(System.getProperty("user.dir")));

            long processStartTime = System.currentTimeMillis();
            Process       process = pb.start();

            // StringBuffer, not StringBuilder: the reader task appends on the executor's thread
            // while THIS thread reads output.toString() on the timeout and interrupt paths, which
            // is exactly when a partial transcript is wanted. An unsynchronised builder read
            // mid-append can return torn text or throw out of the copy, and it would do so only on
            // the failure paths -- the ones with the least chance of being noticed.
            StringBuffer output = new StringBuffer();
            
            logStep("Process started", "PID available, starting output capture");

            ExecutorService executor = Executors.newSingleThreadExecutor(BashCommand::outputReaderThread);
            // A Callable, not a Runnable: reading can fail, and a Runnable would have to swallow
            // the failure on the reader's own thread instead of reporting it where the transcript
            // is assembled.
            Future<?> capture = executor.submit(() -> {
                captureOutput(process, output);
                return null;
            });

            try {
                // Check for interruption before waiting for process completion
                if (shouldInterrupt()) {
                    long processDuration = System.currentTimeMillis() - processStartTime;
                    // Log interrupted command details
                    String partialOutput = output.toString();
                    logDebug("Command execution", String.format("COMMAND: %s", command));
                    logDebug("Command execution", "STATUS: INTERRUPTED");
                    logDebug("Command execution", String.format("DURATION: %dms", processDuration));
                    if (partialOutput.length() > 0) {
                        logDebug("Command execution", String.format("PARTIAL_OUTPUT_LENGTH: %d characters", partialOutput.length()));
                        logDebug("Command execution", "=== PARTIAL OUTPUT START ===");
                        logDebug("Command execution", partialOutput);
                        logDebug("Command execution", "=== PARTIAL OUTPUT END ===");
                    } else {
                        logDebug("Command execution", "PARTIAL_OUTPUT: <no output captured before interruption>");
                    }
                    
                    capture.cancel(true);
                    process.destroyForcibly();
                    OutputFormatter.printWarning("Command interrupted by user request");
                    return ExitCode.INTERRUPTED;
                }

                // Waited on the PROCESS, not on its output. The two do not end together: a child
                // the command leaves running inherits its stdout and holds the write end of that
                // pipe open, so the pipe reaches end-of-file only when that child stops. Waiting
                // on the pipe therefore waited on the child -- `bash ./start-dev`, where the script
                // starts a server and returns, was reported as a timeout with its output thrown
                // away, two minutes after it had in fact succeeded.
                if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                    return reportTimeout(command, capture, process, processStartTime, timeoutMs, output);
                }
                awaitCapturedOutput(capture);

                int  exitCode        = process.exitValue();
                long processDuration = System.currentTimeMillis() - processStartTime;
                
                logPerformance("Process execution", processDuration);
                logDataProcessing("Command output", "lines", output.toString().split("\n").length, processDuration);
                
                // Masked here rather than where it is printed, because this text has two
                // destinations and only one of them redacts. An agent's run goes through
                // CapturedRun, which masks the whole capture; the interactive route goes to the
                // terminal and from there into the saved transcript, so `bash printenv` wrote every
                // exported credential into session.json while the same key typed at the prompt was
                // masked. The debug log below is written from the same string for the same reason.
                String fullOutput = SecretRedactor.redact(output.toString());
                if (fullOutput.length() > 0) {
                    // Log command execution details to debug log
                    logDebug("Command execution", String.format("COMMAND: %s", command));
                    logDebug("Command execution", String.format("EXIT_CODE: %d", exitCode));
                    logDebug("Command execution", String.format("DURATION: %dms", processDuration));
                    logDebug("Command execution", String.format("OUTPUT_LENGTH: %d characters", fullOutput.length()));
                    logDebug("Command execution", "=== COMMAND OUTPUT START ===");
                    logDebug("Command execution", fullOutput);
                    logDebug("Command execution", "=== COMMAND OUTPUT END ===");
                    
                    UnifiedOutput.print(fullOutput);
                } else {
                    logDebug("Command execution", String.format("COMMAND: %s", command));
                    logDebug("Command execution", String.format("EXIT_CODE: %d", exitCode));
                    logDebug("Command execution", String.format("DURATION: %dms", processDuration));
                    logDebug("Command execution", "OUTPUT: <no output>");
                }
                
                if (exitCode == 0) {
                    logStep("Command completed successfully", String.format("Exit code: %d, Duration: %dms", exitCode, processDuration));
                    OutputFormatter.printSuccess("Command completed successfully");
                } else {
                    logWarning("Command execution", String.format("Command failed with exit code: %d after %dms", exitCode, processDuration));
                    OutputFormatter.printError("Command failed with exit code: " + exitCode);
                    pointAtTheAction(command, exitCode);
                }
                return exitCode;
            } catch (InterruptedException e) {
                return handleInterruption(command, capture, process, processStartTime);
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
                // shutdownNow rather than shutdown: the reader may be parked on a pipe that an
                // orphaned child still holds, and shutdown() leaves the executor waiting for a task
                // that will never finish.
                executor.shutdownNow();
            }
        } catch (IOException e) {
            logErrorQuietly("Process creation", "Failed to execute command", e);
            OutputFormatter.printError("Failed to execute command: " + e.getMessage());
            return 1;
        } catch (Exception e) {
            logErrorQuietly("Command execution", "Unexpected error during command execution", e);
            OutputFormatter.printError("Unexpected error: " + e.getMessage());
            return 1;
        }
    }

    /**
     * Makes the thread that reads one command's output, and lets the program leave it behind.
     *
     * <p>A daemon, because it may never return. It reads a pipe that a child the command left
     * running can hold open for as long as it likes, and a blocked read on such a pipe cannot be
     * taken back: killing the process does not end it, closing the stream does not end it, and
     * interrupting the thread does not end it. The default factory makes non-daemon threads, and
     * one of those in this position kept the whole session from exiting until the background child
     * it was waiting on happened to stop.</p>
     *
     * @param task the reading to do
     * @return the thread to do it on
     */
    private static Thread outputReaderThread(Runnable task) {
        Thread thread = new Thread(task, "cadet-bash-output");
        thread.setDaemon(true);
        return thread;
    }

    /**
     * Copies a process's output into {@code output} until the pipe reaches end-of-file.
     *
     * <p>The output only. The exit code is the caller's to collect, because the two do not become
     * available at the same moment -- see {@link #outputReaderThread}.</p>
     *
     * @param process the running process
     * @param output  where to accumulate what it prints
     * @throws IOException if the output cannot be read
     */
    private static void captureOutput(Process process, StringBuffer output) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            int    charCount = 0;
            // What is METERED has to be what is KEPT. The buffer receives the line and a separator;
            // the meter used to advance by the line alone, so a cap of 30,000 delivered 38,000 on
            // ordinary output and 60,000 on one-character lines -- and on output that is all empty
            // lines the meter never advanced at all, so `bash "yes ''"` grew the buffer without
            // bound for the whole timeout, which is the one input the cap exists for.
            int    perLine   = System.lineSeparator().length();
            while ((line = reader.readLine()) != null) {
                if (charCount + line.length() + perLine > MAX_OUTPUT_CHARS) {
                    output.append(System.lineSeparator()).append("... (output truncated)");
                    // Keep draining and discarding remaining output so the child
                    // process does not block on a full stdout pipe and can exit.
                    while (reader.readLine() != null) {
                        // discard
                    }
                    break;
                }
                output.append(line).append(System.lineSeparator());
                charCount += line.length() + perLine;
            }
        }
    }

    /**
     * Waits, briefly, for everything the process wrote to have been captured.
     *
     * <p>Normally instant: the pipe closes with the process and what is still buffered is read at
     * once. The wait is bounded for the case where it does not close, which is the same case
     * {@link #outputReaderThread} exists for.</p>
     *
     * @param capture the output reader's task
     */
    private void awaitCapturedOutput(Future<?> capture) {
        try {
            capture.get(OUTPUT_DRAIN_GRACE_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            logStep("Output capture", "the process exited with its output pipe still held open");
        } catch (java.util.concurrent.ExecutionException e) {
            // Said out loud rather than dropped: the transcript below is short by whatever the read
            // failed to deliver, and a transcript that is quietly incomplete cannot be told apart
            // from a command that printed less.
            Throwable cause = e.getCause() == null ? e : e.getCause();
            logErrorQuietly("Output capture", "Failed to read all command output", e);
            OutputFormatter.printWarning("Some command output could not be read: " + cause.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Reports a command that was still running when its timeout expired.
     *
     * @param command          the command line, for the log
     * @param capture          the output reader's task
     * @param process          the process to stop
     * @param processStartTime when it was started
     * @param timeoutMs        the limit it passed
     * @param output           what it had printed by then
     * @return {@code 1}
     */
    private int reportTimeout(String command, Future<?> capture, Process process,
                              long processStartTime, int timeoutMs, StringBuffer output) {
        long processDuration = System.currentTimeMillis() - processStartTime;
        logError("Process execution",
                String.format("Command timed out after %dms (limit: %dms)", processDuration, timeoutMs));

        String partialOutput = output.toString();
        logDebug("Command execution", String.format("COMMAND: %s", command));
        logDebug("Command execution", "STATUS: TIMEOUT");
        logDebug("Command execution", String.format("DURATION: %dms (limit: %dms)", processDuration, timeoutMs));
        if (partialOutput.length() > 0) {
            logDebug("Command execution", String.format("PARTIAL_OUTPUT_LENGTH: %d characters", partialOutput.length()));
            logDebug("Command execution", "=== PARTIAL OUTPUT START ===");
            logDebug("Command execution", partialOutput);
            logDebug("Command execution", "=== PARTIAL OUTPUT END ===");
        } else {
            logDebug("Command execution", "PARTIAL_OUTPUT: <no output captured before timeout>");
        }

        capture.cancel(true);
        process.destroyForcibly();
        OutputFormatter.printError("Command timed out after " + timeoutMs + "ms");
        return 1;
    }

    /**
     * Handles a command interruption that surfaces while waiting on the
     * output-capture future: cancels the future, force-destroys the child
     * process, re-asserts the thread's interrupt flag, and reports {@link ExitCode#INTERRUPTED}.
     */
    private int handleInterruption(String command, Future<?> capture, Process process, long processStartTime) {
        long processDuration = System.currentTimeMillis() - processStartTime;
        logDebug("Command execution", String.format("COMMAND: %s", command));
        logDebug("Command execution", "STATUS: INTERRUPTED");
        logDebug("Command execution", String.format("DURATION: %dms", processDuration));

        capture.cancel(true);
        process.destroyForcibly();
        // Re-assert the interrupt flag so the registry monitor / outer loop observes it.
        Thread.currentThread().interrupt();
        OutputFormatter.printWarning("Command interrupted by user request");
        return ExitCode.INTERRUPTED;
    }

    @Override
    public String getUsage() {
        return "bash [-t|--timeout <ms>] [-d|--description <desc>] [-f|--force] [--] <command...>\n"
             + "  Options must come BEFORE the command; everything from the first non-option\n"
             + "  token on is the shell command, so its own flags are passed through untouched.\n"
             + "  Use -- when the command itself starts with a dash:\n"
             + "    bash -- -x";
    }

    /**
     * Receives the registry this command is registered in.
     *
     * @param registry the registry
     */
    public void setCommandRegistry(CommandRegistry registry) {
        this.registry = registry;
    }

    /**
     * Says so when the program the shell could not find is one of this tool's commands.
     *
     * <p>A model ran {@code bash job list}, read "job: command not found", and spent its next
     * request on working out that {@code job} is a command of this tool, run as an action of its
     * own.</p>
     *
     * @param command  the command line that was run
     * @param exitCode what it exited with; 127 is the shell's "command not found"
     */
    private void pointAtTheAction(String command, int exitCode) {
        if (exitCode != 127 || registry == null || command == null || command.isBlank()) {
            return;
        }
        String program = command.strip().split("\\s+", 2)[0];
        if (!program.equals("bash") && registry.getCommand(program) != null) {
            OutputFormatter.printInfo("'" + program + "' is a CadetCoder command, not a program the "
                                      + "shell can run: give it as an action of its own, with "
                                      + "COMMAND: " + program + ".");
        }
    }

    @Override
    public void setInterruptionContext(CommandRegistry.InterruptionContext context) {
        this.interruptionContext = context;
    }

    @Override
    public Integer call() {
        if (commandParts == null || commandParts.length == 0) {
            logErrorQuietly("call", "No command provided");
            OutputFormatter.printError("No command provided");
            return 1;
        }
        String command = String.join(" ", commandParts);
        return executeBashCommand(command, description, timeout != null ? timeout : DEFAULT_TIMEOUT, force);
    }
}
