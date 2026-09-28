package com.eonmux.cadetcoder.agents;

import java.util.List;
import java.util.Optional;

/**
 * The most recent worker run, kept so it can be inspected after it has scrolled away.
 *
 * <h2>Why only the most recent</h2>
 *
 * <p>A worker's output is the full transcript of an agentic run and can be thousands of lines, so
 * keeping every run for the life of the process would grow without bound in exchange for a history
 * nobody asked for. The last run is what "show me what worker 2 found" means in practice; anything
 * older is already in the console scrollback and in the session log.</p>
 *
 * <h2>Why the run itself, and not a copy of its results</h2>
 *
 * <p>This used to hold both: the live {@link WorkerRun} and a separate list of results published when
 * a command happened to finish waiting on it. Two records of one run is one too many, and they
 * disagreed in both the ways that were available to them. A run left to finish in the background
 * published nothing, so {@code workers show} answered from the run BEFORE it. And the copied list was
 * addressed by position, so after a run where worker 2 was stopped and worker 3 finished, asking for
 * worker 2 printed worker 3's output and asking for worker 3 said there was no such worker.</p>
 *
 * <p>Holding the run and deriving everything from it removes both: there is nothing to publish and
 * nothing to fall behind, and a worker is addressed by its own number.</p>
 */
public final class WorkerRegistry {

    private static volatile WorkerRun current;

    private WorkerRegistry() {
    }

    /**
     * The run currently in flight, if any.
     *
     * <p>One at a time, deliberately. Several concurrent runs would multiply into more agents than
     * the provider will answer, and — worse — would make "the workers" ambiguous in every question
     * asked about them afterwards. A second start is refused with a message saying how to wait for
     * or stop the first, which is a better answer than silently queueing behind it.</p>
     *
     * @return the active run, or empty when nothing is running
     */
    public static Optional<WorkerRun> active() {
        WorkerRun run = current;
        if (run == null || run.isDone()) {
            return Optional.empty();
        }
        return Optional.of(run);
    }

    /** @return the most recent run, whether or not it has finished */
    public static Optional<WorkerRun> mostRecent() {
        return Optional.ofNullable(current);
    }

    /** Registers a newly started run, replacing whatever came before. */
    public static void setActive(WorkerRun run) {
        current = run;
    }

    /**
     * The results of the most recent run, as far as it has got.
     *
     * @return the finished results in worker order, empty when nothing has run yet
     */
    public static List<WorkerResult> lastRun() {
        WorkerRun run = current;
        return run == null ? List.of() : run.results();
    }

    /**
     * @param index 1-based worker number as shown in the transcript
     * @return that worker's result, when it has one
     */
    public static Optional<WorkerResult> worker(int index) {
        WorkerRun run = current;
        return run == null ? Optional.empty() : run.resultFor(index);
    }

    /**
     * The worker numbers of the most recent run, in the order they were launched.
     *
     * <p>Numbers rather than positions: a run reports its workers by the number printed beside them,
     * and a list indexed by position says something different the moment one of them is missing.</p>
     *
     * @return the numbers, empty when nothing has run yet
     */
    public static List<Integer> numbers() {
        WorkerRun run = current;
        return run == null ? List.of() : run.tasks().stream().map(WorkerTask::index).toList();
    }

    /** Forgets everything; used by tests so one run does not leak into the next. */
    static void clear() {
        current = null;
    }
}
