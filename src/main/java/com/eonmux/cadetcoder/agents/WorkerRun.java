package com.eonmux.cadetcoder.agents;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * A live handle on a set of workers: what they are, how far along they are, and how to stop them.
 *
 * <h2>Why a handle rather than just a result</h2>
 *
 * <p>A blocking call that returns everything at the end is the right default, and it is all the
 * model needs when the workers ARE the next step. It is the wrong shape when they are not: a model
 * that wants to start four reviews and read the diff while they run has no way to express that, and
 * — more importantly — a model that has started something long has no way to stop it. Without a
 * handle there is nothing to ask and nothing to cancel.</p>
 *
 * <p>Results are exposed as they arrive rather than only at the end, so progress means something
 * before the run is over.</p>
 */
public final class WorkerRun {

    private final List<WorkerTask>   tasks;
    private final List<WorkerResult> finished = new CopyOnWriteArrayList<>();

    /**
     * Each worker's transcript, appended to as it works.
     *
     * <p>A worker's output is collected rather than streamed, which is what keeps concurrent runs
     * attributable — and it used to mean the lines existed nowhere anything else could reach until
     * the worker finished. For a run measured in minutes that is the whole of it: the only thing to
     * look at was a counter. These are the same lines, readable while they are still arriving.</p>
     *
     * <p>Copy-on-write: one worker thread appends while the render thread walks it.</p>
     */
    private final Map<Integer, List<String>> transcripts = new ConcurrentHashMap<>();
    private final CountDownLatch     done;
    private final ExecutorService    pool;
    private final long               startedAt;

    private volatile Future<?> execution;
    private volatile boolean   cancelled;

    WorkerRun(List<WorkerTask> tasks, ExecutorService pool, long startedAt) {
        this.tasks     = List.copyOf(tasks);
        this.pool      = pool;
        this.done      = new CountDownLatch(1);
        this.startedAt = startedAt;
    }

    /**
     * A run that is already over, described by its results.
     *
     * <p>There is no pool and nothing to cancel: this is a record of work that has happened, which is
     * what a run becomes once every worker has reported. It exists so a completed run is the same
     * kind of thing as a running one — the registry holds one type, and a worker is addressed by its
     * own number whether it is still going or long finished.</p>
     *
     * @param results what the workers produced, in any order
     * @return a finished run over those results
     */
    static WorkerRun finished(List<WorkerResult> results) {
        List<WorkerTask> tasks = new ArrayList<>(results.size());
        for (WorkerResult result : results) {
            tasks.add(result.task());
        }
        WorkerRun run = new WorkerRun(tasks, null, System.currentTimeMillis());
        run.finished.addAll(results);
        run.complete();
        return run;
    }

    void bind(Future<?> execution) {
        this.execution = execution;
    }

    void record(WorkerResult result) {
        finished.add(result);
    }

    /**
     * The line sink for one worker, created on first use.
     *
     * @param index 1-based worker number
     * @return the list that worker's output is appended to
     */
    List<String> transcriptFor(int index) {
        return transcripts.computeIfAbsent(index, key -> new CopyOnWriteArrayList<>());
    }

    /**
     * One worker's output so far, whether or not it has finished.
     *
     * @param index 1-based worker number
     * @return the lines produced up to now, in order; empty when that worker has produced none
     */
    public List<String> outputSoFar(int index) {
        List<String> lines = transcripts.get(index);
        return lines == null ? List.of() : List.copyOf(lines);
    }

    /**
     * @param index 1-based worker number
     * @return that worker's task, or empty when the run has no such worker
     */
    public Optional<WorkerTask> taskFor(int index) {
        for (WorkerTask task : tasks) {
            if (task.index() == index) {
                return Optional.of(task);
            }
        }
        return Optional.empty();
    }

    /**
     * Marks the run over, and stops the status line mentioning workers.
     *
     * <p>The activity counter is cleared HERE rather than by whoever finished the run, because there
     * is more than one way for a run to end and they do not all reach the same code. The coordinator
     * clears it in its own finally, which never runs if the coordinator was cancelled before it was
     * ever scheduled — a cancel immediately after start does exactly that — and the header then
     * claimed workers were running for the rest of the session. Every ending goes through this
     * method, so tying the two together makes them one event rather than two that must be kept in
     * step.</p>
     */
    void complete() {
        WorkerActivity.end();
        done.countDown();
    }

    /** @return every task in this run, in order */
    public List<WorkerTask> tasks() {
        return tasks;
    }

    /** @return how many workers this run started */
    public int total() {
        return tasks.size();
    }

    /** @return how many have finished, however they finished */
    public int finishedCount() {
        return finished.size();
    }

    /** @return whether every worker has finished or the run was cancelled */
    public boolean isDone() {
        return done.getCount() == 0;
    }

    /** @return whether the run was stopped rather than allowed to finish */
    public boolean isCancelled() {
        return cancelled;
    }

    /** @return milliseconds since the run started */
    public long elapsedMillis() {
        return System.currentTimeMillis() - startedAt;
    }

    /**
     * Results so far, in task order.
     *
     * <p>Workers that have not finished are absent rather than represented by a placeholder: a
     * placeholder would have to claim a status, and "not finished" is not one of the outcomes a
     * result describes.</p>
     *
     * @return the finished results, ordered by worker number
     */
    public List<WorkerResult> results() {
        List<WorkerResult> ordered = new ArrayList<>(finished);
        ordered.sort((a, b) -> Integer.compare(a.task().index(), b.task().index()));
        return ordered;
    }

    /**
     * @param index 1-based worker number
     * @return that worker's result, when it has finished
     */
    public Optional<WorkerResult> resultFor(int index) {
        for (WorkerResult result : finished) {
            if (result.task().index() == index) {
                return Optional.of(result);
            }
        }
        return Optional.empty();
    }

    /** @return the numbers of workers still running, in order */
    public List<Integer> stillRunning() {
        List<Integer> running = new ArrayList<>();
        for (WorkerTask task : tasks) {
            if (resultFor(task.index()).isEmpty()) {
                running.add(task.index());
            }
        }
        return running;
    }

    /**
     * Waits for the run to finish.
     *
     * @param timeoutMillis how long to wait; {@code 0} or less waits indefinitely
     * @return whether the run had finished when this returned
     */
    public boolean await(long timeoutMillis) {
        try {
            if (timeoutMillis <= 0) {
                done.await();
                return true;
            }
            return done.await(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return isDone();
        }
    }

    /**
     * Stops every worker that has not finished.
     *
     * <p>Interrupting is the only lever available: a worker is an agent loop, and the thing it is
     * usually blocked on is a provider call. What has already finished is kept — a stopped run's
     * partial results are usually the reason it was stopped.</p>
     *
     * @return how many workers were still running when this was called
     */
    public int cancel() {
        int running = stillRunning().size();
        cancelled = true;
        if (execution != null) {
            execution.cancel(true);
        }
        if (pool != null) {
            pool.shutdownNow();
        }
        complete();
        return running;
    }
}
