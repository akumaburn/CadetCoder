package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.ExitCode;
import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.jobs.BackgroundJob;
import com.eonmux.cadetcoder.jobs.JobRegistry;
import com.eonmux.cadetcoder.timers.TimerInterval;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Work left running while the conversation goes on.
 *
 * <h2>What this is for</h2>
 *
 * <p>{@code bash} waits for what it starts, which is right for almost everything: a command that
 * takes a second is best answered by its output. It is wrong for the things that take longer than a
 * step. A full build, a test suite, a server that has to be up while something is tried against it
 * -- waiting for those spends the step, then the timeout, and the run is over before the thing it
 * was waiting for finished. The alternative available until now was to not run them at all.</p>
 *
 * <p>A job is the same command, started and then let go. The step ends at once with an id, the
 * output accumulates, and the ending is announced into a later prompt. Between those two the agent
 * gets on with something else, which is the entire point: a build and a code review are not in each
 * other's way, and until now they were.</p>
 *
 * <h2>Why starting is screened like any other command</h2>
 *
 * <p>It is the same shell, and a command nobody waits for is if anything the one worth screening
 * most carefully -- it runs unattended, for as long as it likes. {@link CommandGate} is the screen
 * {@code bash} uses, asked here in the same order.</p>
 */
@picocli.CommandLine.Command (name = "job",
        description = "Run a command in the background and check on it later")
public class JobCommand extends LoggingCommandSupport
        implements CommandRegistry.InterruptibleCommand {

    /** Flags that take a value, so {@code --lines=40} means the same as {@code --lines 40}. */
    private static final Set<String> VALUE_FLAGS =
            Set.of("--description", "-d", "--lines", "-n", "--timeout", "-t");

    /** How many lines {@code job output} shows when nobody says. */
    private static final int DEFAULT_OUTPUT_LINES = 200;

    /** How long a wait lasts when nobody says. */
    static final long DEFAULT_WAIT_SECONDS = 600;

    /**
     * How long one blocking wait may last, however long the job takes.
     *
     * <p>An hour, so a run waiting on a fit that takes forty minutes asks once rather than four
     * times. Every wait that runs out costs a model call to decide to wait again.</p>
     */
    static final long MAX_WAIT_SECONDS = 3600;

    /** How often a wait looks up from sleeping, so an interrupt is noticed promptly. */
    private static final long WAIT_TICK_MILLIS = 200;

    /**
     * The subcommand one action with several commands to start is dispatched as.
     *
     * <p>Not for typing: a line typed at the prompt holds one command. {@link #argvFor} produces
     * it when the text after {@code start} holds a command on each of several lines.</p>
     */
    static final String START_EACH = "start-each";

    private volatile CommandRegistry.InterruptionContext interruptionContext;

    @Override
    public void setInterruptionContext(CommandRegistry.InterruptionContext context) {
        this.interruptionContext = context;
    }

    /** @return whether whoever started this has asked for it to stop */
    private boolean interrupted() {
        return (interruptionContext != null && interruptionContext.isInterrupted())
               || Thread.currentThread().isInterrupted();
    }

    @Override
    public int execute(String[] args) {
        if (args == null || args.length == 0) {
            return list();
        }
        // A command this is about to run is never rewritten on the way in. `job start curl
        // -d='{"a":1}' https://x` expanded as this command's own flags becomes `-d` followed by
        // `{"a":1}`, which `read` then takes for the job's description -- the payload disappears
        // and the job runs a different request. `bash` keeps its line byte-intact for the same
        // reason; `read` understands `-d=value` itself so nothing is lost by not expanding here.
        String[] expanded = starts(args[0]) || START_EACH.equals(args[0])
                            ? args
                            : CommandOptions.expandInlineValues(args, VALUE_FLAGS);
        switch (expanded[0].toLowerCase(Locale.ROOT)) {
            case "start":
            case "run":
                return start(rest(expanded));
            case START_EACH:
                return startEach(rest(expanded));
            case "list":
            case "status":
            case "ls":
                return list();
            case "output":
            case "log":
            case "read":
                return output(rest(expanded));
            case "stop":
            case "kill":
            case "cancel":
                return stop(rest(expanded));
            case "wait":
                return waitFor(rest(expanded));
            default:
                OutputFormatter.printError("Unknown job subcommand: " + expanded[0]);
                OutputFormatter.printInfo(getUsage());
                return 1;
        }
    }

    /** @return {@code args} without its first token */
    private static String[] rest(String[] args) {
        return Arrays.copyOfRange(args, 1, args.length);
    }

    /**
     * Starts a command in the background.
     *
     * <p>The command is every token that is not one of this command's options, joined back
     * together, which is the same rule {@code bash} follows: a model that wrote
     * {@code job start mvn -o test} meant one command of four words, and insisting it quote them
     * would fail the call rather than teach it to. The options are recognised only before the
     * command begins, so a flag belonging to the command is left for the command.</p>
     *
     * @param args everything after {@code start}
     * @return the exit code
     */
    private int start(String[] args) {
        Launch launched = launch(args);
        if (launched.job() != null) {
            OutputFormatter.printInfo(
                    "It runs while you carry on, and you are told when it ends. Other independent"
                    + " work can run as jobs beside it: up to " + JobRegistry.MAX_RUNNING
                    + " at once.");
        }
        return launched.exitCode();
    }

    /**
     * Starts several commands in the background, one after another, without waiting for any.
     *
     * <h2>Why one action starts them all</h2>
     *
     * <p>A run with independent work to do started one job, waited on it, read it, and only then
     * started the next. The loop allows one action per step, so each start cost a step even when
     * the model knew all of them from the start. One action with a command on each line starts
     * them together, and they run at the same time.</p>
     *
     * <p>Each command is screened and started as a {@code job start} of its own. One that is
     * refused, or does not fit under {@link JobRegistry#MAX_RUNNING}, does not stop the others:
     * what started is reported by id, and the exit code says that something did not.</p>
     *
     * @param args an optional {@code -d <description>} for lines that give none, then one command
     *             line per argument, each as the model wrote it
     * @return 0 when every command started, 1 otherwise
     */
    private int startEach(String[] args) {
        String description = null;
        int    first       = 0;
        if (args.length >= 2 && "-d".equals(args[0])) {
            description = args[1];
            first = 2;
        }
        List<String> started = new ArrayList<>();
        int          failed  = 0;
        for (int i = first; i < args.length; i++) {
            if (interrupted()) {
                OutputFormatter.printWarning("Stopped starting jobs after " + started.size() + ".");
                return ExitCode.INTERRUPTED;
            }
            Launch launched = launch(rest(startArgv(args[i], description).toArray(new String[0])));
            if (launched.job() != null) {
                started.add(launched.job().id());
            } else {
                failed++;
            }
        }
        if (started.isEmpty()) {
            OutputFormatter.printError("None of the " + failed + " jobs started.");
            return 1;
        }
        OutputFormatter.printInfo(
                (started.size() == 1 ? "1 job is" : started.size() + " jobs are")
                + " running at the same time: " + String.join(", ", started) + ". You are told as"
                + " each one ends. To wait, name only the jobs whose results you need next:"
                + " `job wait <id>...` returns when the first of them ends, and"
                + " `job wait --all <id>...` when every one has.");
        if (failed > 0) {
            OutputFormatter.printError(failed + (failed == 1 ? " job" : " jobs")
                                       + " did not start; the reasons are above.");
            return 1;
        }
        return 0;
    }

    /**
     * What one attempt to start a job came to.
     *
     * @param job      the job, or {@code null} when it did not start
     * @param exitCode the exit code the attempt ends with
     */
    private record Launch(BackgroundJob job, int exitCode) {
    }

    /**
     * Screens, confirms and starts one command.
     *
     * @param args the arguments of one {@code job start}, without the {@code start}
     * @return what came of it
     */
    private Launch launch(String[] args) {
        Started asked       = read(args);
        String  command     = asked.command();
        String  description = asked.description();
        if (command.isEmpty()) {
            OutputFormatter.printError("Nothing to run. `job start <command>`.");
            return new Launch(null, 1);
        }

        startCommandLogging("job start", args);
        addContext("command", command);

        CommandGate.Verdict screened = CommandGate.screen(this, command, description, false);
        if (!screened.allowed()) {
            completeCommandLogging(screened.exitCode());
            return new Launch(null, screened.exitCode());
        }

        OutputFormatter.printHeader("Background: " + command);
        if (description != null && !description.isBlank()) {
            OutputFormatter.printInfo("Description: " + description);
        }

        CommandGate.Verdict confirmed =
                CommandGate.confirm(this, command, description, false, screened.alreadyApproved());
        if (!confirmed.allowed()) {
            completeCommandLogging(confirmed.exitCode());
            return new Launch(null, confirmed.exitCode());
        }

        try {
            BackgroundJob job = JobRegistry.start(command, description,
                                                  new File(System.getProperty("user.dir")));
            OutputFormatter.printSuccess(
                    "Started " + job.id() + job.pid().map(pid -> " (pid " + pid + ")").orElse("")
                    + ".");
            completeCommandLogging(0);
            return new Launch(job, 0);
        } catch (IllegalStateException tooMany) {
            OutputFormatter.printError(tooMany.getMessage());
            completeCommandLogging(1);
            return new Launch(null, 1);
        } catch (IOException cannotStart) {
            OutputFormatter.printError("Could not start the job: " + cannotStart.getMessage());
            completeCommandLogging(1);
            return new Launch(null, 1);
        }
    }

    /**
     * What one {@code job start} invocation asked for.
     *
     * @param command     the shell command line, joined as it will be run
     * @param description what the caller said it is for, or {@code null}
     */
    record Started(String command, String description) {
    }

    /**
     * Reads this subcommand's own options off the front of its arguments.
     *
     * <h2>Why the rule is here rather than inline</h2>
     *
     * <p>Two callers need the same answer, which is the lesson {@code BashCommand.read} already
     * records. This subcommand screens and runs the line it finds, and {@code ActionRun} screens the
     * same invocation before dispatching it -- and a screen that read the whole argument list would
     * take {@code start} for the program about to run, so the line it refused or allowed would not
     * be the line that ran.</p>
     *
     * <p>The options are recognised ONLY before the shell command begins, and an explicit
     * {@code --} ends them early, so a flag belonging to the command is left for the command.</p>
     *
     * @param args everything after {@code start}
     * @return what was asked for
     */
    static Started read(String[] args) {
        String       description  = null;
        List<String> commandParts = new ArrayList<>();
        boolean      optionsEnded = false;

        for (int i = 0; args != null && i < args.length; i++) {
            String token = args[i];
            if (optionsEnded) {
                commandParts.add(token);
                continue;
            }
            if ("--".equals(token)) {
                optionsEnded = true;
            } else if (("--description".equals(token) || "-d".equals(token)) && i + 1 < args.length) {
                description = args[++i];
            } else if (describedInline(token) != null) {
                // The inline form, read here rather than by a pass over the whole line: expanding
                // every `-d=` in the argument list would also expand the ones inside the command
                // about to run.
                description = describedInline(token);
            } else {
                optionsEnded = true;
                commandParts.add(token);
            }
        }
        return new Started(String.join(" ", commandParts).trim(), description);
    }

    /**
     * The argument list a {@code job} action should be dispatched with.
     *
     * <h2>Why the command is not tokenised</h2>
     *
     * <p>A model writes {@code ARGS: start grep -r "foo bar" .} on one line, and an argument list
     * made by splitting that line and then joined back with single spaces is a different command:
     * {@code grep -r foo bar .}, run without complaint. {@code bash} avoids this by being handed
     * its whole command line as one argument, and this is the same arrangement one subcommand
     * deeper -- the subcommand is a token, and everything it is to run stays as it was written.</p>
     *
     * <p>Only a subcommand that RUNS something needs this. {@code list}, {@code output},
     * {@code stop} and {@code wait} take an id and some flags, which are tokens like any other.</p>
     *
     * <h2>Several commands in one action</h2>
     *
     * <p>When the text after {@code start} holds more than one non-blank line, each line is one
     * job, with its own optional {@code -d}, and the action is dispatched as {@link #START_EACH}.
     * A description the action gives applies to each line that gives none of its own. A line may
     * repeat {@code job start} in front of its command, which is how the form is often written.</p>
     *
     * @param rawLine     the whole argument line, as the model wrote it
     * @param description what the action said the job is for, or {@code null}
     * @return the argv to dispatch, empty when there is nothing to dispatch
     */
    public static List<String> argvFor(String rawLine, String description) {
        String line = rawLine == null ? "" : rawLine.trim();
        if (line.isEmpty()) {
            return List.of();
        }
        String[] split = line.split("\\s+", 2);
        String   head  = split[0];
        String   rest  = split.length < 2 ? "" : split[1].trim();
        if (!starts(head)) {
            return List.of(CommandLineTokenizer.tokenize(line));
        }

        List<String> commands = new ArrayList<>();
        for (String written : rest.split("\\R")) {
            String command = withoutJobStart(written.trim());
            if (!command.isEmpty()) {
                commands.add(command);
            }
        }
        if (commands.size() > 1) {
            List<String> argv = new ArrayList<>();
            argv.add(START_EACH);
            if (description != null && !description.isBlank()) {
                argv.add("-d");
                argv.add(description);
            }
            argv.addAll(commands);
            return List.copyOf(argv);
        }
        return startArgv(commands.isEmpty() ? "" : commands.get(0), description);
    }

    /**
     * One line of a several-job action without a {@code job start} written in front of it.
     *
     * @param written one line, trimmed
     * @return the line from its options or command onwards
     */
    private static String withoutJobStart(String written) {
        String[] words = written.split("\\s+", 3);
        if (words.length == 3 && "job".equals(words[0]) && starts(words[1])) {
            return words[2].trim();
        }
        return written;
    }

    /**
     * The argv of one {@code job start}.
     *
     * @param rest        the text after {@code start}: options, then the command
     * @param description what the action said the job is for, or {@code null}
     * @return {@code start}, the description when there is one, then the command as written
     */
    private static List<String> startArgv(String rest, String description) {
        // The options this subcommand reads itself are taken off the front as tokens; what is left
        // is the command, kept exactly as it was written.
        List<String> argv = new ArrayList<>();
        argv.add("start");
        String        command = rest;
        String        said    = description;
        while (command.startsWith("-d ") || command.startsWith("--description ")) {
            String after = command.substring(command.indexOf(' ') + 1).trim();
            String[] tokens = CommandLineTokenizer.tokenize(after);
            if (tokens.length == 0) {
                break;
            }
            said = tokens[0];
            // Past the value, however it was quoted, without re-splitting what follows it.
            int consumed = after.startsWith("\"") || after.startsWith("'")
                           ? after.indexOf(after.charAt(0), 1) + 1
                           : said.length();
            command = consumed <= 0 ? "" : after.substring(consumed).trim();
        }
        if (said != null && !said.isBlank()) {
            argv.add("-d");
            argv.add(said);
        }
        if (!command.isEmpty()) {
            argv.add(command);
        }
        return List.copyOf(argv);
    }

    /** @return whether this subcommand is one that runs something */
    private static boolean starts(String subcommand) {
        return "start".equalsIgnoreCase(subcommand) || "run".equalsIgnoreCase(subcommand);
    }

    /**
     * The description a token carries, when it is the inline form of this subcommand's own option.
     *
     * @param token one argument, before the shell command begins
     * @return what it says the job is for, or {@code null} when it is not that option
     */
    private static String describedInline(String token) {
        for (String flag : List.of("--description=", "-d=")) {
            if (token.startsWith(flag)) {
                return token.substring(flag.length());
            }
        }
        return null;
    }

    /**
     * The shell command a {@code job} invocation would run, when it starts exactly one.
     *
     * @param args the whole argument list, as it was written
     * @return the command line, or {@code null} when this invocation starts nothing or several
     */
    public static String startedCommand(String[] args) {
        List<String> commands = startedCommands(args);
        return commands.size() == 1 ? commands.get(0) : null;
    }

    /**
     * Every shell command a {@code job} invocation would run.
     *
     * <p>Read the way {@link #execute} reads them, so each line screened here is a line that runs.
     * A screen shown only the first command of several would let the rest through unread.</p>
     *
     * @param args the whole argument list, as it was written
     * @return the command lines in the order they would start; empty when it starts nothing
     */
    public static List<String> startedCommands(String[] args) {
        if (args == null || args.length == 0) {
            return List.of();
        }
        // Unexpanded, exactly as execute() dispatches it, so the line screened here is the line
        // that runs.
        if (starts(args[0])) {
            String command = read(rest(args)).command();
            return command.isEmpty() ? List.of() : List.of(command);
        }
        if (!START_EACH.equals(args[0])) {
            return List.of();
        }
        int          first    = args.length >= 3 && "-d".equals(args[1]) ? 3 : 1;
        List<String> commands = new ArrayList<>();
        for (int i = first; i < args.length; i++) {
            String command = read(rest(startArgv(args[i], null).toArray(new String[0]))).command();
            if (!command.isEmpty()) {
                commands.add(command);
            }
        }
        return List.copyOf(commands);
    }

    /** Shows every job and where it has got to. */
    private int list() {
        List<BackgroundJob> jobs = JobRegistry.all();
        if (jobs.isEmpty()) {
            OutputFormatter.printInfo(
                    "No background jobs. `job start <command>` runs one without waiting for it.");
            return 0;
        }
        OutputFormatter.printSubheader("Background jobs");
        for (BackgroundJob job : jobs) {
            OutputFormatter.printInfo(String.format("  %-4s %-9s %-8s %s",
                                                    job.id(),
                                                    outcome(job),
                                                    TimerInterval.render(job.runtime()),
                                                    job.command()));
            long pending = job.pending();
            if (pending > 0) {
                OutputFormatter.printInfo(String.format("       %d unread %s; `job output %s`",
                                                        pending, pending == 1 ? "line" : "lines",
                                                        job.id()));
            }
        }
        return 0;
    }

    /** How a job is doing, in one word. */
    private static String outcome(BackgroundJob job) {
        switch (job.state()) {
            case RUNNING:
                return "running";
            case STOPPED:
                return "stopped";
            default:
                int code = job.exitCode().orElse(-1);
                return code == 0 ? "ok" : "exit " + code;
        }
    }

    /**
     * Shows what a job has printed.
     *
     * <p>What is NEW by default, because that is the question asked repeatedly: an agent checking
     * on a build wants what has happened since it last looked, and being handed the whole
     * transcript each time would spend the context window on lines it has already read.
     * {@code --all} is there for the first look at a job that has already finished.</p>
     *
     * @param args the job id, then any options
     * @return the exit code
     */
    private int output(String[] args) {
        String  id    = null;
        int     lines = DEFAULT_OUTPUT_LINES;
        boolean all   = false;
        for (int i = 0; i < args.length; i++) {
            String token = args[i];
            if ("--all".equals(token) || "-a".equals(token)) {
                all = true;
            } else if ("--lines".equals(token) || "-n".equals(token)) {
                if (i + 1 >= args.length) {
                    OutputFormatter.printError("Missing value for " + token + ".");
                    return 1;
                }
                try {
                    // Zero or less means every line there is, which is what JobOutput's own limit
                    // means. Clamped to one, `--all` could not show more than a screenful.
                    lines = Integer.parseInt(args[++i].trim());
                } catch (NumberFormatException notANumber) {
                    OutputFormatter.printError("Not a number of lines: " + args[i]);
                    return 1;
                }
            } else if (id == null) {
                id = token;
            }
        }
        if (id == null) {
            OutputFormatter.printError("Which job? `job output <id>`. `job list` shows them.");
            return 1;
        }
        Optional<BackgroundJob> found = JobRegistry.find(id);
        if (found.isEmpty()) {
            OutputFormatter.printError("No job called " + id.trim() + ". `job list` shows them.");
            return 1;
        }

        BackgroundJob job = found.get();
        if (all) {
            job.rewind();
            // `--all` means all of it. Left at the default, it would rewind to the start and then
            // show the same last two hundred lines, having consumed everything before them.
            lines = 0;
        }
        long                missed = job.missed();
        BackgroundJob.Slice slice  = job.readNew(lines);

        OutputFormatter.printSubheader(job.id() + " (" + outcome(job) + ") " + job.command());
        if (missed > 0) {
            OutputFormatter.printWarning(
                    missed + (missed == 1 ? " line was" : " lines were")
                    + " dropped before this: the job has printed more than is kept.");
        }
        if (slice.skipped() > 0) {
            OutputFormatter.printWarning(
                    slice.skipped() + (slice.skipped() == 1 ? " earlier line is" : " earlier lines are")
                    + " not shown, and this read has passed them: ask with `-n 0` next time to see"
                    + " everything a job has printed.");
        }
        if (slice.lines().isEmpty()) {
            // The run time is in the sentence for the reason it is in a wait that runs out: without
            // it, asking twice gives the same answer twice, and that is refused as a loop.
            OutputFormatter.printInfo(job.isDone()
                    ? "Nothing new. The job has finished."
                    : "Nothing new since you last looked. It is still running, "
                      + TimerInterval.render(job.runtime()) + " so far.");
            return 0;
        }
        OutputFormatter.printOutputBlock(String.join(System.lineSeparator(), slice.lines()));
        return 0;
    }

    /**
     * Stops one job, or all of them.
     *
     * @param args the id, or {@code all}
     * @return the exit code
     */
    private int stop(String[] args) {
        if (args.length == 0) {
            OutputFormatter.printError("Which job? `job stop <id>`, or `job stop all`.");
            return 1;
        }
        if ("all".equalsIgnoreCase(args[0].trim())) {
            List<BackgroundJob> stopped = JobRegistry.stopAll();
            OutputFormatter.printSuccess(stopped.isEmpty()
                    ? "There were no jobs to stop."
                    : "Stopped " + stopped.size() + (stopped.size() == 1 ? " job." : " jobs."));
            return 0;
        }
        Optional<BackgroundJob> found = JobRegistry.find(args[0]);
        if (found.isEmpty()) {
            OutputFormatter.printError("No job called " + args[0].trim()
                                       + ". `job list` shows them.");
            return 1;
        }
        if (found.get().stop()) {
            OutputFormatter.printSuccess("Stopped " + found.get().id() + ".");
            return 0;
        }
        OutputFormatter.printInfo(found.get().id() + " had already finished.");
        return 0;
    }

    /**
     * Waits for one job to end, or for all of them.
     *
     * <p>An agent with nothing to do until a build finishes would otherwise spend the wait on model
     * calls, asking what to do next and being told to wait. This spends it in one command instead.
     * The ending is NOT consumed here: it is announced where every other ending is announced, in
     * the next prompt, so waiting and being told are one thing that happens in order rather than
     * two that can disagree.</p>
     *
     * <h2>Why a job already reported is left out</h2>
     *
     * <p>A run often needs only some of its jobs before it can go on. It waits, is told the first
     * one has ended, and waits again on the same jobs for the rest. A job whose ending has already
     * been reported cannot end again, so it is not waited for. Counted, it ended every later wait
     * at once, and waiting for the rest meant working out which ids to leave out.</p>
     *
     * <h2>Why a wait that names no job waits only for the caller's own</h2>
     *
     * <p>Several agents may have jobs running. A worker with nothing to do until its own build ends
     * was woken by a build the session had started, which it knows nothing about.</p>
     *
     * @param args the job ids, or nothing for the caller's own running jobs; {@code --all} to wait
     *             for every one of them; {@code -t <seconds>} to wait no longer than that
     * @return the exit code
     */
    private int waitFor(String[] args) {
        long         cap    = DEFAULT_WAIT_SECONDS;
        boolean      forAll = false;
        List<String> ids    = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String token = args[i];
            if ("--all".equals(token) || "-a".equals(token) || "all".equalsIgnoreCase(token)) {
                forAll = true;
            } else if ("--timeout".equals(token) || "-t".equals(token)) {
                if (i + 1 >= args.length) {
                    OutputFormatter.printError("Missing value for " + token + ".");
                    return 1;
                }
                try {
                    cap = Math.min(MAX_WAIT_SECONDS, Math.max(0, Long.parseLong(args[++i].trim())));
                } catch (NumberFormatException notANumber) {
                    OutputFormatter.printError("Not a number of seconds: " + args[i]);
                    return 1;
                }
            } else {
                ids.add(token);
            }
        }

        List<BackgroundJob> named = new ArrayList<>();
        if (ids.isEmpty()) {
            named.addAll(JobRegistry.runningHere());
            if (named.isEmpty()) {
                OutputFormatter.printInfo(
                        "None of your jobs is running, so there is nothing to wait for.");
                return 0;
            }
        } else {
            for (String id : ids) {
                Optional<BackgroundJob> found = JobRegistry.find(id);
                if (found.isEmpty()) {
                    OutputFormatter.printError("No job called " + id.trim()
                                               + ". `job list` shows them.");
                    return 1;
                }
                if (!named.contains(found.get())) {
                    named.add(found.get());
                }
            }
        }

        List<BackgroundJob> waitingOn = new ArrayList<>();
        List<String>        reported  = new ArrayList<>();
        for (BackgroundJob job : named) {
            if (job.isDone() && JobRegistry.wasAnnounced(job)) {
                reported.add(job.id());
            } else {
                waitingOn.add(job);
            }
        }
        if (!reported.isEmpty()) {
            OutputFormatter.printInfo(String.join(", ", reported)
                    + (reported.size() == 1 ? " has" : " have")
                    + " already ended, and you were told in an earlier step; `job output <id>`"
                    + " shows what a job printed.");
        }
        if (waitingOn.isEmpty()) {
            return 0;
        }

        long deadline = System.currentTimeMillis() + cap * 1000;
        // Checked before the clock: `-t 0` asks whether anything has ended, and a loop that tests
        // the deadline first answers "still running" without having looked.
        do {
            if (interrupted()) {
                OutputFormatter.printWarning("Stopped waiting.");
                return ExitCode.INTERRUPTED;
            }
            long ended = waitingOn.stream().filter(BackgroundJob::isDone).count();
            if (forAll ? ended == waitingOn.size() : ended > 0) {
                report(waitingOn, forAll);
                return 0;
            }
            if (System.currentTimeMillis() >= deadline) {
                break;
            }
            try {
                Thread.sleep(WAIT_TICK_MILLIS);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                OutputFormatter.printWarning("Stopped waiting.");
                return ExitCode.INTERRUPTED;
            }
        } while (true);
        OutputFormatter.printInfo("Waited " + TimerInterval.render(Duration.ofSeconds(cap)) + ".");
        report(waitingOn, forAll);
        return 0;
    }

    /**
     * Says, for each job a wait was on, whether it has ended or how far it has got, and how to go
     * on waiting for the ones still running.
     *
     * @param jobs   the jobs waited on, in the order they were named
     * @param forAll whether the wait was for every one of them
     */
    private static void report(List<BackgroundJob> jobs, boolean forAll) {
        List<String> running = new ArrayList<>();
        for (BackgroundJob job : jobs) {
            if (job.isDone()) {
                OutputFormatter.printInfo(job.id() + " has finished (" + outcome(job)
                                          + "); it is reported with the next step.");
            } else {
                OutputFormatter.printInfo(progress(job));
                running.add(job.id());
            }
        }
        if (!running.isEmpty()) {
            OutputFormatter.printInfo(
                    "To keep waiting for the rest: `job wait " + (forAll ? "--all " : "")
                    + String.join(" ", running) + "`. Until then they keep running, and you are"
                    + " told when each one ends.");
        }
    }

    /**
     * How far a running job has got: how long it has run, how much it has printed, and the latest
     * line.
     *
     * <h2>Why a wait that runs out says this</h2>
     *
     * <p>It used to say "still running" and nothing else, the same sentence every time. The second
     * such wait was, to the guard that stops an agent repeating itself, the same command with the
     * same result, and it was refused as a loop; the agent could no longer wait on its own job and
     * went to {@code ps} and {@code jstack} to learn what this line tells it. The run time differs
     * every time it is asked, and the rest is what the agent was trying to find out.</p>
     *
     * <p>The latest line is read without moving the job's read position, so {@code job output}
     * still shows it as new. It is quoted whole: a line cut short could hide the part that
     * matters.</p>
     *
     * @param job a job that has not finished
     * @return one line about it
     */
    static String progress(BackgroundJob job) {
        String ran     = job.id() + " is still running after " + TimerInterval.render(job.runtime());
        long   printed = job.output().produced();
        if (printed == 0) {
            return ran + " and has printed nothing yet.";
        }
        List<String> latest = job.output().since(printed - 1, 1);
        String       line   = latest.isEmpty() ? "" : latest.get(0).strip();
        return ran + "; it has printed " + printed + (printed == 1 ? " line" : " lines")
               + ", the latest: " + line;
    }

    @Override
    public String getUsage() {
        return "job start [-d <description>] <command>   run it without waiting for it\n"
             + "job list                                 every job, and where it has got to\n"
             + "job output <id> [--all] [-n <lines>]     what it printed; new lines by default\n"
             + "job stop <id>|all                        end one, or all of them\n"
             + "job wait [<id>...] [--all] [-t <seconds>] block until one ends, or all of them\n"
             + "A wait skips jobs whose ending you were already told of.\n"
             + "Up to " + JobRegistry.MAX_RUNNING + " jobs run at the same time.\n"
             + "A job outlives the step that started it. You are told when one ends.";
    }
}
