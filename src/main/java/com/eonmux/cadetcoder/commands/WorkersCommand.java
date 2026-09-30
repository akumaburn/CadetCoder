package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ExitCode;
import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.agents.WorkerPool;
import com.eonmux.cadetcoder.agents.WorkerRegistry;
import com.eonmux.cadetcoder.agents.WorkerResult;
import com.eonmux.cadetcoder.agents.WorkerTask;
import com.eonmux.cadetcoder.resume.ResumeBriefing;
import com.eonmux.cadetcoder.resume.ResumeScope;
import com.eonmux.cadetcoder.session.ResumePoint;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Runs several agents at once over one shared briefing, each on a task of its own.
 *
 * <h2>What this is for</h2>
 *
 * <p>Some questions are wide rather than deep: review this change for correctness, for security, and
 * for test coverage; or search four subsystems for the same defect. One agent answers those in
 * sequence, carrying every earlier answer in its context and getting slower and less focused as it
 * goes. Several agents answer them independently, each with the whole briefing and none of the
 * others' output.</p>
 *
 * <h2>Why every worker gets the same briefing</h2>
 *
 * <p>Because the alternative is worse in both directions. Give workers different context and their
 * findings cannot be compared — a disagreement might be a real conflict or might be two agents
 * looking at different things. Give them each other's output and they converge on whatever the first
 * one said, which is the failure this is meant to avoid.</p>
 */
@picocli.CommandLine.Command (name = "workers", description = "Run several agents at once, each on its own task")
public class WorkersCommand implements CommandRegistry.InterruptibleCommand {

    /**
     * Set by the shell so F2 and Ctrl+C reach a run.
     *
     * <p>Interruptible on purpose: a worker run is the longest thing this tool starts — several
     * agentic loops at once — and a command the shell cannot interrupt has to be waited out or the
     * session killed. The pool's threads are daemons, so an abandoned run would not block exit, but
     * it would keep spending the user's tokens after they asked it to stop.</p>
     */
    private volatile CommandRegistry.InterruptionContext interruptionContext;

    @Override
    public void setInterruptionContext(CommandRegistry.InterruptionContext context) {
        this.interruptionContext = context;
    }

    /** @return whether the user has asked for the run to stop */
    private boolean interrupted() {
        return (interruptionContext != null && interruptionContext.isInterrupted())
               || Thread.currentThread().isInterrupted();
    }

    /** Per-worker step budget when none is given; keeps one worker from consuming the whole run. */
    private static final int DEFAULT_WORKER_STEPS = 12;

    /** What each worker runs its task with. */
    private final WorkerPool.WorkerRunner runner;

    public WorkersCommand() {
        this(WorkerPool.agentRunner());
    }

    /** @param runner what each worker runs its task with */
    WorkersCommand(WorkerPool.WorkerRunner runner) {
        this.runner = runner;
    }

    @Override
    public int execute(String[] args) {
        if (args == null || args.length == 0) {
            return list();
        }
        String first = args[0].toLowerCase(java.util.Locale.ROOT);
        switch (first) {
            case "-h":
            case "--help":
                // Asked before anything is started. Every unrecognised argument here is a task, so
                // without this the first thing anyone types at an unfamiliar command started an
                // agent whose task was the word "--help" and billed a model call for it.
                OutputFormatter.println(CommandUsage.render(getUsage()));
                return 0;
            case "list":
                return list();
            case "show":
                return show(args);
            case "status":
                return status();
            case "wait":
                return waitFor(args);
            case "stop":
                return stop(args);
            case "start":
                return spawn(java.util.Arrays.copyOfRange(args, 1, args.length), false);
            default:
                List<String> meant = verbsResembling(args[0]);
                return meant.isEmpty() ? spawn(args, true) : refuseMistypedVerb(args[0], meant);
        }
    }

    /** The words `workers` answers to. A first argument that is one of these is never a task. */
    private static final java.util.Set<String> VERBS =
            java.util.Set.of("list", "show", "status", "wait", "stop", "start");

    /** How many alternatives a mistyped verb is offered. */
    private static final int SUGGESTIONS_SHOWN = 3;

    /**
     * The verbs a first argument looks like a misspelling of.
     *
     * <h2>Why a typo here cannot be allowed through</h2>
     *
     * <p>Every unrecognised first argument is a task, so {@code workers staus} did not report a
     * mistyped {@code status}: it started an agent whose task was the word "staus", against the
     * user's provider, at the user's expense, and answered a question about what the workers were
     * doing by doing something else entirely. The registry refuses {@code /raed pom.xml} for exactly
     * this reason -- a typo must not become a billed round trip -- and this is the same mistake one
     * level down.</p>
     *
     * <h2>Why only a single bare word</h2>
     *
     * <p>A task is a sentence, and a sentence contains a space; {@code workers "stop the leak"} is
     * a task that begins with a verb's name and must still run. One word is not a task anybody
     * means -- an agent cannot act on it -- so it is the only shape where a suggestion is worth
     * more than obedience. A one-word task can still be insisted upon: {@code workers start <word>}
     * names the verb explicitly and never reaches this test.</p>
     *
     * @param firstArgument the first argument given to {@code workers}
     * @return the verbs it resembles, closest first; empty when it is a task rather than a typo
     */
    static List<String> verbsResembling(String firstArgument) {
        if (firstArgument == null) {
            return java.util.List.of();
        }
        String word = firstArgument.trim().toLowerCase(java.util.Locale.ROOT);
        if (word.isEmpty() || word.chars().anyMatch(Character::isWhitespace) || VERBS.contains(word)) {
            return java.util.List.of();
        }
        return InputRouter.suggest(word, VERBS, SUGGESTIONS_SHOWN);
    }

    /** Reports a first argument that reads as a mistyped verb, having started nothing. */
    private int refuseMistypedVerb(String asked, List<String> meant) {
        OutputFormatter.printError("There is no `" + CommandUsage.prefix() + "workers " + asked + "`.");
        OutputFormatter.printInfo("Did you mean: " + String.join(", ", meant) + "?");
        OutputFormatter.printInfo("A task is a sentence, quoted: "
                                  + CommandUsage.prefix() + "workers \"review the retry logic\"");
        OutputFormatter.printInfo("To run that one word as a task anyway: "
                                  + CommandUsage.prefix() + "workers start \"" + asked + "\"");
        return 1;
    }

    /** Reports what the workers are doing right now, without waiting for them. */
    private int status() {
        java.util.Optional<com.eonmux.cadetcoder.agents.WorkerRun> running = WorkerRegistry.active();
        if (running.isEmpty()) {
            List<WorkerResult> last = WorkerRegistry.lastRun();
            OutputFormatter.printInfo(last.isEmpty()
                    ? "No workers are running and none have run yet."
                    : "No workers are running. The last run finished; `workers list` shows it.");
            return 0;
        }
        com.eonmux.cadetcoder.agents.WorkerRun run = running.get();
        OutputFormatter.printSubheader("Workers");
        OutputFormatter.printInfo(run.finishedCount() + " of " + run.total() + " finished after "
                                  + String.format("%.1fs", run.elapsedMillis() / 1000.0) + ".");
        for (com.eonmux.cadetcoder.agents.WorkerTask task : run.tasks()) {
            String state = run.resultFor(task.index())
                    .map(r -> mark(r).trim())
                    .orElse("running");
            OutputFormatter.printInfo(String.format("  %-8s %-10s %s",
                                                    state, task.name(), task.label()));
        }
        OutputFormatter.printInfo("`workers wait` blocks until they finish; `workers stop` ends them.");
        return 0;
    }

    /** Blocks until the active run finishes, or until the given number of seconds has passed. */
    private int waitFor(String[] args) {
        java.util.Optional<com.eonmux.cadetcoder.agents.WorkerRun> running = WorkerRegistry.active();
        if (running.isEmpty()) {
            OutputFormatter.printInfo("No workers are running.");
            return 0;
        }
        long timeoutMillis = 0;
        if (args.length >= 2) {
            try {
                timeoutMillis = Math.max(0, Long.parseLong(args[1].trim())) * 1000L;
            } catch (NumberFormatException e) {
                OutputFormatter.printError("Not a number of seconds: " + args[1]);
                return 1;
            }
        }
        com.eonmux.cadetcoder.agents.WorkerRun run = running.get();
        boolean finished = run.await(timeoutMillis);
        if (!finished) {
            OutputFormatter.printWarning("Still running: " + run.finishedCount() + " of "
                                         + run.total() + " finished. `workers wait` again, or"
                                         + " `workers stop`.");
            return 1;
        }
        return summarise(run.results(), run.total(), run.elapsedMillis());
    }

    /** Ends the active run, or one worker in it. */
    private int stop(String[] args) {
        java.util.Optional<com.eonmux.cadetcoder.agents.WorkerRun> running = WorkerRegistry.active();
        if (running.isEmpty()) {
            OutputFormatter.printInfo("No workers are running.");
            return 0;
        }
        com.eonmux.cadetcoder.agents.WorkerRun run = running.get();
        int stopped = run.cancel();

        // What finished is kept: a stopped run's partial results are usually why it was stopped.
        OutputFormatter.printWarning("Stopped " + stopped + " worker" + (stopped == 1 ? "" : "s")
                                     + ". " + run.finishedCount() + " of " + run.total()
                                     + " had finished; their output is still available"
                                     + " with `workers show <n>`.");
        return 0;
    }

    /**
     * Starts one worker per task, and reports each as it finishes.
     *
     * @param wait whether to block until every worker has finished
     */
    private int spawn(String[] args, boolean wait) {
        if (WorkerPool.insideWorker()) {
            OutputFormatter.printError("A worker cannot start more workers.");
            return 1;
        }
        java.util.Optional<com.eonmux.cadetcoder.agents.WorkerRun> already = WorkerRegistry.active();
        if (already.isPresent()) {
            // Refused rather than queued. A second run would double the agents pointed at one
            // provider, and would make every later question about "the workers" ambiguous.
            com.eonmux.cadetcoder.agents.WorkerRun run = already.get();
            OutputFormatter.printError("Workers are already running: " + run.finishedCount()
                                       + " of " + run.total() + " finished.");
            OutputFormatter.printInfo("Use `workers wait` to let them finish, or `workers stop` to"
                                      + " end them, then start again.");
            return 1;
        }
        Parsed parsed = Parsed.of(args);
        if (!parsed.refused.isEmpty()) {
            // Reported before anything starts: every one of these would otherwise have been a task,
            // and a worker run is the most expensive thing this tool begins.
            for (String refusal : parsed.refused) {
                OutputFormatter.printError(refusal);
            }
            OutputFormatter.printInfo("Usage: " + CommandUsage.render(getUsage()));
            return 1;
        }
        if (parsed.tasks.isEmpty()) {
            OutputFormatter.printError("Give each worker a task, quoted: "
                                       + "workers \"review retries\" \"review the UI\"");
            return 1;
        }
        if (parsed.tasks.size() > WorkerPool.MAX_WORKERS) {
            OutputFormatter.printError("At most " + WorkerPool.MAX_WORKERS
                                       + " workers per run; " + parsed.tasks.size() + " were asked for.");
            return 1;
        }

        List<WorkerTask> tasks = new ArrayList<>(parsed.tasks.size());
        for (int i = 0; i < parsed.tasks.size(); i++) {
            tasks.add(new WorkerTask(i + 1, parsed.tasks.get(i), parsed.briefing));
        }

        OutputFormatter.printHeader(tasks.size() + " workers");
        OutputFormatter.printInfo("Running " + WorkerPool.concurrency() + " at a time"
                                  + (parsed.briefing.isEmpty() ? "" : ", over a shared briefing")
                                  + ". Each worker reports below as it finishes.");
        for (WorkerTask task : tasks) {
            OutputFormatter.printInfo("  " + task.name() + "  " + task.label());
        }

        try (ResumeScope scope = ResumeScope.open()) {
            com.eonmux.cadetcoder.agents.WorkerRun run = WorkerPool.start(
                    tasks, parsed.maxSteps, wait ? WorkersCommand::report : null, runner);
            WorkerRegistry.setActive(run);
            ResumeScope.workersStarted(run);

            if (!wait) {
                OutputFormatter.printInfo("Started. They run in the background: `workers status` to"
                                          + " see how far along they are, `workers wait` to block"
                                          + " until they finish, `workers stop` to end them.");
                return 0;
            }
            return awaitAll(run, List.of(), args, scope);
        }
    }

    /**
     * Waits for a run's workers, and stops them when the user interrupts.
     *
     * <p>The workers are stopped with the command. Left running, they spend model requests on a
     * run the user stopped, and nothing reports what they did. Which of them
     * finished is saved as the session's resume point, so {@code resume} runs only the others.</p>
     *
     * @param run       the run
     * @param kept      what finished in an earlier, interrupted run of the same tasks
     * @param arguments the command's arguments, for the resume point
     * @param scope     the run's scope
     * @return the exit code
     */
    private int awaitAll(com.eonmux.cadetcoder.agents.WorkerRun run, List<WorkerResult> kept,
                         String[] arguments, ResumeScope scope) {
        run.await(0);
        List<WorkerResult> results = run.results();

        if (interrupted()) {
            run.cancel();
            List<ResumePoint.Worker> finished = new ArrayList<>();
            for (WorkerResult result : kept) {
                finished.add(new ResumePoint.Worker(result.task().index(), result.task().task(),
                                                    result.status().name(), result.output()));
            }
            // Counted against what was STARTED, and counting every worker that reached an ending
            // however it ended. `results` holds only the workers that finished -- the coordinator
            // stops recording once the run is cancelled -- so "N of results.size()" compared the
            // finished against themselves.
            OutputFormatter.printWarning("Worker run stopped; " + (kept.size() + results.size())
                                         + " of " + (kept.size() + run.total())
                                         + " had finished.");
            scope.stopped(ResumePoint.workers(java.util.Arrays.asList(arguments), finished));
            return ExitCode.INTERRUPTED;
        }
        List<WorkerResult> all = new ArrayList<>(kept);
        all.addAll(results);
        all.sort(java.util.Comparator.comparingInt(result -> result.task().index()));
        if (!kept.isEmpty()) {
            // One record of the whole run, so `workers show <n>` reaches the kept workers too.
            WorkerRegistry.setActive(com.eonmux.cadetcoder.agents.WorkerRun.finished(all));
        }
        return summarise(all, kept.size() + run.total(), run.elapsedMillis());
    }

    /**
     * Runs again the tasks of an interrupted workers run that did not finish.
     *
     * <p>A worker that finished keeps its result, and is reported with the new ones. A worker that
     * was stopped part-way starts its task again, told what it printed before it was stopped. The
     * caller checks first that no other workers run; see {@link ResumeCommand}.</p>
     *
     * @param workers   how far each worker got
     * @param arguments the command's arguments as it was started with them
     * @param briefing  what else each worker is told about the interrupt, or empty
     * @return the exit code
     */
    public int resume(List<ResumePoint.Worker> workers, List<String> arguments, String briefing) {
        Parsed parsed = Parsed.of(arguments.toArray(new String[0]));
        List<WorkerResult> kept  = new ArrayList<>();
        List<WorkerTask>   tasks = new ArrayList<>();
        for (ResumePoint.Worker worker : workers) {
            if (worker.finished()) {
                kept.add(new WorkerResult(new WorkerTask(worker.index(), worker.task(),
                                                         parsed.briefing),
                                          WorkerResult.Status.valueOf(worker.status()),
                                          worker.output(), 0, null));
            } else {
                tasks.add(new WorkerTask(worker.index(), worker.task(),
                                         againBriefing(parsed.briefing, worker, briefing)));
            }
        }
        OutputFormatter.printHeader("Resuming " + tasks.size() + " of " + workers.size()
                                    + " workers");
        for (WorkerResult result : kept) {
            OutputFormatter.printInfo("  kept     " + result.task().name() + "  "
                                      + result.task().label());
        }
        for (WorkerTask task : tasks) {
            OutputFormatter.printInfo("  again    " + task.name() + "  " + task.label());
        }
        for (WorkerResult result : kept) {
            report(result);
        }
        if (tasks.isEmpty()) {
            return summarise(kept, kept.size(), 0);
        }
        String[] args = arguments.toArray(new String[0]);
        try (ResumeScope scope = ResumeScope.open()) {
            com.eonmux.cadetcoder.agents.WorkerRun run = WorkerPool.start(
                    tasks, parsed.maxSteps, WorkersCommand::report, runner);
            WorkerRegistry.setActive(run);
            ResumeScope.workersStarted(run);
            return awaitAll(run, kept, args, scope);
        }
    }

    /**
     * The briefing a worker gets when its task is run again.
     *
     * @param shared   the run's shared briefing
     * @param worker   how far the worker got before
     * @param briefing what else it is told about the interrupt, or empty
     * @return the briefing
     */
    private static String againBriefing(String shared, ResumePoint.Worker worker, String briefing) {
        StringBuilder text = new StringBuilder(shared);
        text.append(text.length() == 0 ? "" : "\n\n")
            .append("The user interrupted an earlier run of this task, and has now resumed it.");
        if (briefing != null && !briefing.isBlank()) {
            text.append('\n').append(briefing.strip());
        }
        if (!worker.output().isEmpty()) {
            text.append("\nWhat the earlier worker printed before it was stopped:\n")
                .append(String.join("\n", worker.output()));
        }
        return text.append('\n').append(ResumeBriefing.STOPPED_IS_NOT_REFUSED)
                   .append('\n').append(ResumeBriefing.CARRY_ON).toString();
    }

    /**
     * Prints one finished worker as its own section.
     *
     * <p>A sub-header, so the shell opens a navigable region for it: a worker's output is a whole
     * agentic run, and several of them concatenated into one flat stream would be unreadable.</p>
     */
    private static void report(WorkerResult result) {
        WorkerTask task = result.task();
        OutputFormatter.printSubheader(task.name() + "  " + task.label());
        for (String line : result.output()) {
            // Verbatim: the worker's lines already carry their own markers, and re-marking them
            // here would stamp a second glyph onto every line of a nested run.
            OutputFormatter.println(line);
        }
        String timing = String.format("%.1fs", result.durationMillis() / 1000.0);
        switch (result.status()) {
            case COMPLETED -> OutputFormatter.printSuccess(task.name() + " finished in " + timing);
            case INTERRUPTED -> OutputFormatter.printWarning(task.name() + " was stopped after " + timing);
            default -> OutputFormatter.printError(task.name() + " failed after " + timing
                                                  + (result.failure() == null ? ""
                                                                              : ": " + result.failure()));
        }
    }

    /**
     * Reports the run and answers for it.
     *
     * <h2>Why the denominator is the run's total</h2>
     *
     * <p>{@code results} holds the workers that finished, so measuring success against its own size
     * asks whether the workers that finished, finished. A run stopped after two of four succeeded
     * satisfied {@code done == results.size()}, reported "2 of 2", and exited 0 -- overall success
     * for a run that lost half its workers.</p>
     *
     * @param results  what each finished worker came to
     * @param started  how many workers the run was given
     * @return 0 when every worker that was started succeeded, 1 when any did not
     */
    private static int summarise(List<WorkerResult> results, int started, long elapsedMillis) {
        long   done   = results.stream().filter(WorkerResult::succeeded).count();
        String timing = String.format("%.1fs", elapsedMillis / 1000.0);

        OutputFormatter.printSubheader("Workers");
        OutputFormatter.printInfo(done + " of " + started + " succeeded in " + timing
                                  + ".  `workers show <n>` reprints one in full.");
        for (WorkerResult result : results) {
            OutputFormatter.printInfo("  " + mark(result) + " " + result.task().name()
                                      + "  " + result.task().label());
        }
        return done == started ? 0 : 1;
    }

    private static String mark(WorkerResult result) {
        return switch (result.status()) {
            case COMPLETED -> "ok  ";
            case INTERRUPTED -> "stop";
            default -> "fail";
        };
    }

    /** Lists the most recent run without re-running anything. */
    private int list() {
        boolean            running = WorkerRegistry.active().isPresent();
        List<WorkerResult> run     = WorkerRegistry.lastRun();
        if (run.isEmpty()) {
            // A run in flight that has not yet had a worker report is not "nothing has run": telling
            // the model to start some would have it refused for overlapping with the run it was just
            // told did not exist.
            OutputFormatter.printInfo(running
                    ? "Workers are running and none have reported yet. `workers status` shows how far"
                      + " along they are."
                    : "No workers have run yet. Start some with: " + CommandUsage.render(getUsage()));
            return 0;
        }
        OutputFormatter.printHeader(running ? "Workers (run in progress)" : "Workers (last run)");
        for (WorkerResult result : run) {
            OutputFormatter.printInfo(String.format("  %s %-10s %6.1fs  %s",
                                                    mark(result), result.task().name(),
                                                    result.durationMillis() / 1000.0,
                                                    result.task().label()));
        }
        return 0;
    }

    /** Reprints one worker's output in full. */
    private int show(String[] args) {
        if (args.length < 2) {
            OutputFormatter.printError("Which worker? e.g. workers show 2");
            return 1;
        }
        int index;
        try {
            index = Integer.parseInt(args[1].trim());
        } catch (NumberFormatException e) {
            OutputFormatter.printError("Not a worker number: " + args[1]);
            return 1;
        }
        Optional<WorkerResult> found = WorkerRegistry.worker(index);
        if (found.isEmpty()) {
            OutputFormatter.printError("No worker " + index + " in the last run.");
            return 1;
        }
        report(found.get());
        return 0;
    }

    /** The parsed form of an invocation: a briefing, a step budget, and one task per worker. */
    /** Options that mean nothing without the word after them. */
    private static final java.util.Set<String> TAKES_A_VALUE =
            java.util.Set.of("-b", "--briefing", "-m", "--max-steps");

    private static final class Parsed {
        private final List<String> tasks = new ArrayList<>();
        /** Arguments that are not tasks and are not options either, each already a sentence. */
        private final List<String> refused = new ArrayList<>();
        private       String       briefing = "";
        private       int          maxSteps = DEFAULT_WORKER_STEPS;

        static Parsed of(String[] args) {
            Parsed parsed = new Parsed();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i] == null ? "" : args[i].trim();
                if (arg.isEmpty()) {
                    continue;
                }
                if (("-b".equals(arg) || "--briefing".equals(arg)) && i + 1 < args.length) {
                    parsed.briefing = args[++i];
                } else if (("-m".equals(arg) || "--max-steps".equals(arg)) && i + 1 < args.length) {
                    try {
                        int value = Integer.parseInt(args[++i].trim());
                        if (value > 0) {
                            parsed.maxSteps = value;
                        }
                    } catch (NumberFormatException ignored) {
                        // keep the default rather than failing a run over a malformed budget
                    }
                } else if (TAKES_A_VALUE.contains(arg)) {
                    parsed.refused.add(arg + " needs a value after it.");
                } else if (arg.startsWith("-")) {
                    // Anything unrecognised used to become a task, options included: a mistyped
                    // --brief started one agent on the typo and another on the briefing text that
                    // followed it, both at the provider's price.
                    parsed.refused.add("There is no " + arg + " option for workers.");
                } else {
                    parsed.tasks.add(arg);
                }
            }
            return parsed;
        }
    }


    @Override
    public String getUsage() {
        return "workers \"<task>\" \"<task>\" [...] [-b <briefing>] [-m <steps>]\n"
               + "  Start them and wait; every result comes back together.\n"
               + "workers start \"<task>\" [...]    start them and return immediately\n"
               + "workers status                  how far along the running workers are\n"
               + "workers wait [<seconds>]        block until they finish\n"
               + "workers stop                    end them, keeping what finished\n"
               + "workers list                    summary of the last run\n"
               + "workers show <n>                one worker's output in full";
    }
}
