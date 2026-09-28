package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.agents.WorkerRegistry;
import com.eonmux.cadetcoder.agents.WorkerRun;
import com.eonmux.cadetcoder.agents.WorkerTask;
import com.eonmux.cadetcoder.jobs.BackgroundJob;
import com.eonmux.cadetcoder.jobs.JobRegistry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What background work there is to look at, and how it is doing.
 *
 * <h2>Why workers and jobs are listed together</h2>
 *
 * <p>They are the same thing to the person watching: work that is going on while the prompt stays
 * free. They were reachable by different routes and reported in different places -- a worker only
 * through {@code Tab}, a job only by typing {@code job list} -- so the one question anybody
 * actually asks, "what is running", had no single answer. Here they are one list in one order:
 * every worker, then every job, each as the registry lists it.</p>
 *
 * <p>This is where the registries are read, so the console's own classes hold no knowledge of
 * either. They are given panes and rows, and draw them.</p>
 */
final class BackgroundPanes {

    /** How far a job's command is allowed to run before it is cut short in a list. */
    private static final int MOST_COMMAND_CHARACTERS = 60;

    /** How a piece of background work is doing. */
    enum Liveness {

        /** Still going. */
        RUNNING,

        /** Finished, and did what it was asked. */
        DONE,

        /** Finished, and did not. */
        FAILED,

        /** Ended because it was told to. */
        STOPPED
    }

    /**
     * One line of the overview.
     *
     * @param pane     what opening this line shows
     * @param liveness how it is doing
     * @param name     what it is called, such as {@code worker 2} or {@code j1}
     * @param state    how it is doing, in words
     * @param detail   what it was asked to do
     */
    record Row(BackgroundPane pane, Liveness liveness, String name, String state, String detail) {
    }

    private BackgroundPanes() {
    }

    /**
     * Every pane there is to walk through.
     *
     * <p>The overview is not among them. It is opened by its own key and is a place to look from
     * rather than a place in the walk, so cycling past the last job returns to the first worker
     * instead of landing somewhere that is not a piece of work.</p>
     *
     * @return every worker then every job, in registry order; empty when neither has any
     */
    static List<BackgroundPane> all() {
        List<BackgroundPane> panes = new ArrayList<>();
        for (int number : WorkerRegistry.numbers()) {
            panes.add(BackgroundPane.worker(number));
        }
        for (BackgroundJob job : JobRegistry.all()) {
            panes.add(BackgroundPane.job(job.id()));
        }
        return List.copyOf(panes);
    }

    /**
     * The overview, one line per piece of work.
     *
     * @return the rows, in the same order as {@link #all()}
     */
    static List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        Optional<WorkerRun> run = WorkerRegistry.mostRecent();
        for (int number : WorkerRegistry.numbers()) {
            rows.add(workerRow(run, number));
        }
        for (BackgroundJob job : JobRegistry.all()) {
            rows.add(jobRow(job));
        }
        return List.copyOf(rows);
    }

    /**
     * @param run    the run the worker belongs to, when there is one
     * @param number the worker's number
     * @return its line of the overview
     */
    private static Row workerRow(Optional<WorkerRun> run, int number) {
        Optional<WorkerTask> task = run.flatMap(r -> r.taskFor(number));
        String label = task.map(WorkerTask::label).orElse("");
        return run.flatMap(r -> r.resultFor(number))
                  .map(result -> switch (result.status()) {
                      case COMPLETED -> new Row(BackgroundPane.worker(number), Liveness.DONE,
                                                "worker " + number, "done", label);
                      case INTERRUPTED -> new Row(BackgroundPane.worker(number), Liveness.STOPPED,
                                                  "worker " + number, "stopped", label);
                      default -> new Row(BackgroundPane.worker(number), Liveness.FAILED,
                                         "worker " + number, "failed", label);
                  })
                  .orElseGet(() -> new Row(BackgroundPane.worker(number), Liveness.RUNNING,
                                           "worker " + number, "running", label));
    }

    /**
     * @param job the job
     * @return its line of the overview
     */
    private static Row jobRow(BackgroundJob job) {
        String detail = shortened(job.command());
        if (job.description() != null && !job.description().isBlank()) {
            detail = job.description() + "  " + detail;
        }
        return new Row(BackgroundPane.job(job.id()), liveness(job), job.id(),
                       state(job) + "  " + spoken(job.runtime()), detail);
    }

    /**
     * @param job a background job
     * @return how it is doing
     */
    static Liveness liveness(BackgroundJob job) {
        if (!job.isDone()) {
            return Liveness.RUNNING;
        }
        return switch (job.state()) {
            case STOPPED -> Liveness.STOPPED;
            default -> job.succeeded() ? Liveness.DONE : Liveness.FAILED;
        };
    }

    /**
     * @param job a background job
     * @return how it is doing, in the words {@code job list} uses for the same thing
     */
    static String state(BackgroundJob job) {
        if (!job.isDone()) {
            return "running";
        }
        if (job.state() == BackgroundJob.State.STOPPED) {
            return "stopped";
        }
        int code = job.exitCode().orElse(0);
        return code == 0 ? "ok" : "exit " + code;
    }

    /**
     * @param runtime how long something has been going
     * @return it as {@code 2m14s}, or {@code 41s} under a minute
     */
    static String spoken(Duration runtime) {
        long seconds = Math.max(0, runtime.getSeconds());
        return seconds < 60 ? seconds + "s" : (seconds / 60) + "m" + (seconds % 60) + "s";
    }

    /**
     * @param command what a job was asked to run
     * @return it, cut short when it would otherwise fill the line
     */
    private static String shortened(String command) {
        if (command == null) {
            return "";
        }
        String oneLine = command.replace('\n', ' ').strip();
        return oneLine.length() <= MOST_COMMAND_CHARACTERS
               ? oneLine
               : oneLine.substring(0, MOST_COMMAND_CHARACTERS - 1) + "...";
    }
}
