package com.eonmux.cadetcoder.agents;

import com.eonmux.cadetcoder.commands.AgentCommand;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.timers.TimerScope;
import com.eonmux.cadetcoder.ui.OutputCapture;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs several workers over one shared briefing, each on a task of its own.
 *
 * <h2>Why the concurrency is small, and configurable</h2>
 *
 * <p>The binding constraint is not the machine, it is the provider. Every worker runs a full agentic
 * loop, so eight workers are eight concurrent streams of completions against one account and one
 * rate limit — and they share a single retry budget, so they do not merely queue, they fail each
 * other. Three at a time keeps a run genuinely parallel while leaving the provider room to answer;
 * anyone who knows their own limits can raise it.</p>
 *
 * <h2>Why workers cannot spawn workers</h2>
 *
 * <p>{@code AgentCommand} guards against recursion with a thread-local depth, which a new thread
 * does not inherit — so without an explicit check here, a worker could start its own pool and each
 * of those another, multiplying without bound and with no single place to notice. The guard is a
 * process-wide flag inherited deliberately: inside a worker, spawning is refused outright.</p>
 */
public final class WorkerPool {

    /** Workers allowed to run at once by default; the shipped value of {@code performance.threads}. */
    public static final int DEFAULT_CONCURRENCY = Configuration.PerformanceConfig.DEFAULT_THREADS;

    /** Ceiling on the concurrency, however it is asked for; above it, workers only starve each other. */
    public static final int MAX_CONCURRENCY = 8;

    /** Most workers one call may start. */
    public static final int MAX_WORKERS = 8;

    /**
     * A one-run override of {@code performance.threads}, for a launcher script or a test.
     *
     * <p>The configuration is the setting a user is shown and told about; this is a way past it for
     * one process without writing to their file. It used to be the ONLY way, which made the
     * documented setting a lie and the working one unfindable.</p>
     */
    public static final String CONCURRENCY_PROPERTY = "cadet.workers.concurrency";

    /** Set while this thread IS a worker, so nested spawning can be refused. */
    private static final ThreadLocal<Boolean> INSIDE_WORKER = ThreadLocal.withInitial(() -> false);

    /**
     * What one worker actually does.
     *
     * <p>A seam, not an extension point. The pool's job is scheduling, isolation and attribution;
     * without this it could only be exercised by starting real agents against a real provider, which
     * would make its behaviour untestable exactly where it matters — ordering, failure isolation, and
     * the nesting guard.</p>
     */
    @FunctionalInterface
    public interface WorkerRunner {
        /**
         * @param task     the worker's assignment
         * @param maxSteps per-worker step budget, or 0 for the agent's own default
         * @return the worker's exit code, 0 for success
         */
        int run(WorkerTask task, int maxSteps);
    }

    /** The real worker: a fresh agent per task, so no two workers share run state. */
    private static final WorkerRunner AGENT_RUNNER =
            (task, maxSteps) -> new AgentCommand().execute(arguments(task, maxSteps));

    /** @return the real worker body: a fresh agent per task */
    public static WorkerRunner agentRunner() {
        return AGENT_RUNNER;
    }

    private WorkerPool() {
    }

    /** @return whether the calling thread is itself a worker */
    public static boolean insideWorker() {
        return INSIDE_WORKER.get();
    }

    /** @return how many workers may run at once */
    public static int concurrency() {
        String override = System.getProperty(CONCURRENCY_PROPERTY);
        if (override != null) {
            try {
                int value = Integer.parseInt(override.trim());
                if (value >= 1 && value <= MAX_CONCURRENCY) {
                    return value;
                }
            } catch (NumberFormatException ignored) {
                // fall through to what the user's configuration says
            }
        }
        return fromConfiguration();
    }

    /**
     * What {@code performance.threads} and {@code performance.parallelProcessing} come to.
     *
     * <p>Switching parallel processing off means one at a time rather than none at all: the work
     * still has to happen, and a pool of zero would simply never run it. A thread count outside the
     * range is brought back into it rather than refused, because this is read on the way to
     * starting a run and the run is what the user asked for -- {@code config} is where an impossible
     * value is rejected, and it is, at the moment it is set.</p>
     *
     * @return a workable concurrency, never below 1 nor above {@link #MAX_CONCURRENCY}
     */
    private static int fromConfiguration() {
        Configuration.PerformanceConfig performance;
        try {
            performance = ConfigManager.getInstance().getConfig().getPerformance();
        } catch (RuntimeException unreadable) {
            // Concurrency is not worth failing a run over; the shipped value is a safe answer.
            return DEFAULT_CONCURRENCY;
        }
        if (performance == null) {
            return DEFAULT_CONCURRENCY;
        }
        if (!performance.isParallelProcessing()) {
            return 1;
        }
        return Math.max(1, Math.min(MAX_CONCURRENCY, performance.getThreads()));
    }

    /**
     * Runs every task, returning once all of them have finished.
     *
     * <p>Results come back in task order regardless of the order they completed in, so a run reads
     * the same way twice and a worker's number always means the same worker.</p>
     *
     * @param tasks    what to run; at most {@link #MAX_WORKERS}
     * @param maxSteps per-worker step budget, or {@code 0} for the agent's own default
     * @param progress notified as each worker finishes, on the calling thread's behalf; may be null
     * @return one result per task, in the order the tasks were given
     */
    public static List<WorkerResult> run(List<WorkerTask> tasks, int maxSteps,
                                         java.util.function.Consumer<WorkerResult> progress) {
        return run(tasks, maxSteps, progress, AGENT_RUNNER);
    }

    /**
     * As {@link #run(List, int, java.util.function.Consumer)}, with the worker body supplied.
     *
     * @param runner what each worker does; see {@link WorkerRunner}
     */
    public static List<WorkerResult> run(List<WorkerTask> tasks, int maxSteps,
                                         java.util.function.Consumer<WorkerResult> progress,
                                         WorkerRunner runner) {
        if (tasks == null || tasks.isEmpty()) {
            return List.of();
        }
        WorkerRun run = start(tasks, maxSteps, progress, runner);
        run.await(0);
        return run.results();
    }

    /**
     * Starts the workers and returns immediately.
     *
     * <p>The caller decides when — or whether — to wait. That is the difference between workers as
     * the next step and workers as something running alongside it, and it is the only way a run can
     * be asked about or stopped while it is still going.</p>
     *
     * @param tasks    what to run; at most {@link #MAX_WORKERS}
     * @param maxSteps per-worker step budget, or {@code 0} for the agent's own default
     * @param progress notified as each worker finishes; may be null
     * @param runner   what each worker does
     * @return a handle on the run
     */
    public static WorkerRun start(List<WorkerTask> tasks, int maxSteps,
                                  java.util.function.Consumer<WorkerResult> progress,
                                  WorkerRunner runner) {
        if (tasks == null || tasks.isEmpty()) {
            throw new IllegalArgumentException("a run needs at least one task");
        }
        if (tasks.size() > MAX_WORKERS) {
            throw new IllegalArgumentException("at most " + MAX_WORKERS + " workers per run");
        }
        if (insideWorker()) {
            throw new IllegalStateException("a worker cannot start more workers");
        }

        ExecutorService pool = Executors.newFixedThreadPool(
                Math.min(concurrency(), tasks.size()) + 1, namedThreads());
        WorkerRun run = new WorkerRun(tasks, pool, System.currentTimeMillis());

        // Workers collect their own output, so the transcript stays silent while they run and the
        // shell has nothing to show. This is what it shows instead.
        WorkerActivity.begin(tasks.size());

        // One coordinating task owns the run, so start() can return without leaving the caller
        // responsible for draining futures it never asked for.
        run.bind(pool.submit(() -> {
            // EVERYTHING the coordinator does is inside this try, submission included. It used to
            // submit outside it, which meant a rejected submission -- the pool having been shut down
            // by a concurrent `workers stop` -- escaped before the finally: the latch was never
            // counted down, so anyone waiting on the run waited forever, the status line kept
            // claiming workers were running, and the exception went nowhere because nothing ever
            // calls get() on this future. A stall with no diagnostic is the worst of the outcomes
            // available here, and it was the reachable one.
            List<Future<WorkerResult>> futures = new ArrayList<>(tasks.size());
            try {
                // Taken in the order the workers finish rather than the order they were started.
                // Waited on by index, worker 2's result is not recorded until worker 1 has been
                // waited for, however long ago 2 actually finished -- so the count the shell shows,
                // the list of which workers are still running, and the moment each progress
                // callback fires all describe the slowest worker so far rather than what has
                // happened. The results are sorted by worker number when they are read back, so
                // nothing downstream depends on the order they arrive in.
                java.util.concurrent.ExecutorCompletionService<WorkerResult> asTheyFinish =
                        new java.util.concurrent.ExecutorCompletionService<>(pool);
                java.util.Map<Future<WorkerResult>, WorkerTask> whose = new java.util.IdentityHashMap<>();
                for (WorkerTask task : tasks) {
                    Future<WorkerResult> started = asTheyFinish.submit(worker(task, maxSteps, runner, run));
                    futures.add(started);
                    whose.put(started, task);
                }
                for (int reported = 0; reported < futures.size(); reported++) {
                    if (run.isCancelled()) {
                        break;
                    }
                    Future<WorkerResult> next   = asTheyFinish.take();
                    WorkerResult         result = await(next, whose.get(next));
                    run.record(result);
                    WorkerActivity.finished();
                    if (progress != null) {
                        progress.accept(result);
                    }
                }
            } catch (InterruptedException stopped) {
                // Waiting for the next worker to finish was interrupted, which is how this run is
                // taken back. The flag is re-raised and the run ends where it stands; whatever has
                // already been recorded stays recorded.
                Thread.currentThread().interrupt();
            } catch (RuntimeException | Error e) {
                // Attributed to the workers that never got to run, so the failure is visible as a
                // result rather than only as a count that does not add up.
                recordUnstarted(run, tasks, futures.size(), e);
            } finally {
                // complete() clears the activity counter; see WorkerRun#complete for why it lives
                // there and not here.
                run.complete();
                pool.shutdown();
            }
        }));
        return run;
    }

    /**
     * Gives every task that was never submitted a result saying why.
     *
     * <p>Only reachable when the coordinator itself fails — a pool shut down mid-submission, or an
     * {@link Error}. Without it those workers would simply be missing, and a run that reported "1 of
     * 4 finished" with no explanation for the other three is indistinguishable from one still going.
     * </p>
     *
     * @param submitted how many tasks were successfully submitted before the failure
     */
    private static void recordUnstarted(WorkerRun run, List<WorkerTask> tasks, int submitted,
                                        Throwable cause) {
        String reason = "the run failed before this worker started: "
                + (cause.getMessage() == null ? cause.toString() : cause.getMessage());
        for (int i = submitted; i < tasks.size(); i++) {
            run.record(new WorkerResult(tasks.get(i), WorkerResult.Status.FAILED, List.of(), 0, reason));
            WorkerActivity.finished();
        }
    }

    /** Waits for one worker, turning any way it can fail into a result rather than an exception. */
    private static WorkerResult await(Future<WorkerResult> future, WorkerTask task) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return new WorkerResult(task, WorkerResult.Status.INTERRUPTED, List.of(), 0,
                                    "stopped before finishing");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return new WorkerResult(task, WorkerResult.Status.FAILED, List.of(), 0,
                                    cause.getMessage() == null ? cause.toString() : cause.getMessage());
        }
    }

    /** One worker: its own agent, its own output, and nothing shared with its siblings but the briefing. */
    private static Callable<WorkerResult> worker(WorkerTask task, int maxSteps, WorkerRunner runner,
                                                 WorkerRun run) {
        return () -> {
            long start = System.currentTimeMillis();
            INSIDE_WORKER.set(true);
            int[] exit = {1};
            try {
                // Collected into the run's own buffer rather than a private list, so the shell can
                // show what this worker is doing while it is still doing it.
                List<String> output = run.transcriptFor(task.index());
                // Its own timer scope, for the same reason it gets its own output buffer: a
                // check-in one worker sets itself is a note about the task only that worker has,
                // and delivered from a shared list it would arrive in whichever agent's prompt was
                // built next. The scope is given up when the task ends because these threads are
                // pooled and reused by the workers that follow.
                OutputCapture.collectInto(output::add,
                        () -> TimerScope.in(task.name(), () -> exit[0] = runner.run(task, maxSteps)));
                output = run.outputSoFar(task.index());
                long elapsed = System.currentTimeMillis() - start;
                if (Thread.currentThread().isInterrupted()) {
                    return new WorkerResult(task, WorkerResult.Status.INTERRUPTED, output, elapsed,
                                            "stopped before finishing");
                }
                return new WorkerResult(task,
                                        exit[0] == 0 ? WorkerResult.Status.COMPLETED
                                                     : WorkerResult.Status.FAILED,
                                        output, elapsed,
                                        exit[0] == 0 ? null : "the agent reported exit code " + exit[0]);
            } catch (RuntimeException e) {
                // What the worker had already said before it failed, not an empty list. The lines
                // are collected into the run as they are produced, so they exist whether or not the
                // worker got to the end -- and a failure is the case where reading them matters
                // most, since they are the only account of what it was doing when it broke.
                return new WorkerResult(task, WorkerResult.Status.FAILED,
                                        run.outputSoFar(task.index()),
                                        System.currentTimeMillis() - start,
                                        e.getMessage() == null ? e.toString() : e.getMessage());
            } finally {
                INSIDE_WORKER.remove();
            }
        };
    }

    /**
     * The argv one worker's agent is started with.
     *
     * <p>{@code -y} states that the user already consented: they asked for these workers, by name
     * and by task. The agent's own "shall I proceed?" is written for someone typing {@code agent} at
     * a shell, and a worker has nobody to ask -- its copy of the question goes into its own
     * collected output, where it is never seen. Left to answer that itself, a worker took the safe
     * default for an unanswerable confirmation, which is "no", and cancelled before doing anything.</p>
     */
    private static String[] arguments(WorkerTask task, int maxSteps) {
        if (maxSteps > 0) {
            return new String[]{task.prompt(), "-y", "-m", Integer.toString(maxSteps)};
        }
        return new String[]{task.prompt(), "-y"};
    }

    /** Named daemon threads, so a stuck worker is identifiable in a stack dump and never blocks exit. */
    private static ThreadFactory namedThreads() {
        AtomicInteger seq = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "cadet-worker-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

}
