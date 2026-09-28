package com.eonmux.cadetcoder.agents;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The worker numbers of the most recent run.
 *
 * <h2>Why this is here</h2>
 *
 * <p>Three places in the shell asked the same question -- which workers exist, in what order -- and
 * each derived it from the run itself. Tab cycles through these, the focused heading counts
 * positions within them, and the status bar says which of them is showing, so the three agreeing is
 * the difference between "worker 2 of 3" naming the worker on screen and naming a different
 * one.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>That the answer is the workers' own numbers rather than their positions, so a run missing one
 * does not renumber the rest; that the order is the order they were launched in; and that a process
 * where nothing has run answers with an empty list rather than failing, since the shell asks on
 * every frame whether or not anything has been started.</p>
 */
public class TheWorkerNumbersOfARunTest {

    @After
    public void tearDown() {
        WorkerRegistry.clear();
    }

    private static List<WorkerTask> tasks(int... numbers) {
        List<WorkerTask> tasks = new ArrayList<>();
        for (int number : numbers) {
            tasks.add(new WorkerTask(number, "task " + number, "briefing"));
        }
        return tasks;
    }

    private static WorkerRun runOf(int... numbers) {
        WorkerRun run = WorkerPool.start(tasks(numbers), 0, null, (task, steps) -> 0);
        WorkerRegistry.setActive(run);
        run.await(10_000);
        return run;
    }

    @Test
    public void withNothingHavingRunThereAreNoNumbers() {
        WorkerRegistry.clear();

        assertThat(WorkerRegistry.numbers()).isEmpty();
    }

    @Test
    public void theyAreTheWorkersOwnNumbersInTheOrderTheyWereLaunched() {
        runOf(1, 2, 3);

        assertThat(WorkerRegistry.numbers()).containsExactly(1, 2, 3);
    }

    @Test
    public void aRunMissingOneDoesNotRenumberTheRest() {
        // Positions would say the third worker is number 3; it is number 4, and that is the number
        // printed beside it in the transcript and accepted by `workers show`.
        runOf(1, 2, 4);

        assertThat(WorkerRegistry.numbers()).containsExactly(1, 2, 4);
    }

    @Test
    public void asecondRunReplacesTheFirstsNumbers() {
        runOf(1, 2, 3);
        runOf(1, 2);

        assertThat(WorkerRegistry.numbers()).containsExactly(1, 2);
    }

    @Test
    public void theNumbersOutliveTheRunFinishing() {
        // They are what `workers show 2` is addressed by after everything has scrolled past.
        WorkerRun run = runOf(1, 2);

        assertThat(run.isDone()).isTrue();
        assertThat(WorkerRegistry.numbers()).containsExactly(1, 2);
    }
}
