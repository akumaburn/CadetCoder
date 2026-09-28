package com.eonmux.cadetcoder.agents;

import com.eonmux.cadetcoder.commands.WorkersCommand;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The user- and model-facing surface of a worker run. */
public class WorkersCommandTest {

    private final ByteArrayOutputStream captured    = new ByteArrayOutputStream();
    private final PrintStream           originalOut = System.out;
    private final PrintStream           originalErr = System.err;
    private       WorkersCommand        command;

    @Before
    public void setUp() {
        command = new WorkersCommand();
        WorkerRegistry.clear();
        PrintStream sink = new PrintStream(captured);
        System.setOut(sink);
        System.setErr(sink);
    }

    @After
    public void tearDown() {
        System.setOut(originalOut);
        System.setErr(originalErr);
        WorkerRegistry.clear();
    }

    private String output() {
        System.out.flush();
        return captured.toString();
    }

    @Test
    public void withNothingRunYetItSaysSoAndShowsHowToStart() {
        assertThat(command.execute(new String[0])).isZero();

        assertThat(output()).contains("No workers have run yet").contains("workers");
    }

    @Test
    public void aRunWithNoTasksIsRefusedWithAnExample() {
        // "workers" alone lists; "workers -m 5" asks for workers without saying what they should do.
        assertThat(command.execute(new String[]{"-m", "5"})).isEqualTo(1);

        assertThat(output()).contains("Give each worker a task");
    }

    @Test
    public void moreWorkersThanTheCapIsRefusedBeforeAnythingStarts() {
        String[] tooMany = new String[WorkerPool.MAX_WORKERS + 1];
        for (int i = 0; i < tooMany.length; i++) {
            tooMany[i] = "task " + i;
        }

        assertThat(command.execute(tooMany)).isEqualTo(1);
        assertThat(output()).contains("At most");
    }

    @Test
    public void showWithoutANumberSaysWhatItNeeds() {
        assertThat(command.execute(new String[]{"show"})).isEqualTo(1);
        assertThat(output()).contains("Which worker");
    }

    @Test
    public void showRejectsSomethingThatIsNotAWorkerNumber() {
        assertThat(command.execute(new String[]{"show", "second"})).isEqualTo(1);
        assertThat(output()).contains("Not a worker number");
    }

    @Test
    public void aRecordedRunCanBeListedAndReprinted() {
        WorkerRegistry.setActive(WorkerRun.finished(List.of(
                new WorkerResult(new WorkerTask(1, "review retries", ""),
                                 WorkerResult.Status.COMPLETED, List.of("found three"), 1200, null),
                new WorkerResult(new WorkerTask(2, "review the UI", ""),
                                 WorkerResult.Status.FAILED, List.of(), 800, "ran out of steps"))));

        assertThat(command.execute(new String[]{"list"})).isZero();
        assertThat(output()).contains("Worker 1").contains("Worker 2").contains("review retries");

        captured.reset();
        assertThat(command.execute(new String[]{"show", "1"})).isZero();
        // `show` reprints the worker's own output, which the list deliberately does not.
        assertThat(output()).contains("found three");
    }

    @Test
    public void askingForAWorkerTheRunDidNotHaveIsAnError() {
        WorkerRegistry.setActive(WorkerRun.finished(List.of(
                new WorkerResult(new WorkerTask(1, "only task", ""),
                                 WorkerResult.Status.COMPLETED, List.of(), 100, null))));

        assertThat(command.execute(new String[]{"show", "2"})).isEqualTo(1);
        assertThat(output()).contains("No worker 2");
    }

    @Test
    public void aWorkerIsAddressedByItsOwnNumberNotItsPositionInTheResults() {
        // A stopped run has gaps: worker 2 never reported, so the results are [1, 3]. Addressing
        // them by position made `show 2` print worker 3 under worker 2's name and `show 3` claim
        // there was no such worker -- both wrong, and neither of them visibly so.
        WorkerRegistry.setActive(WorkerRun.finished(List.of(
                new WorkerResult(new WorkerTask(1, "review retries", ""),
                                 WorkerResult.Status.COMPLETED, List.of("first said this"), 100, null),
                new WorkerResult(new WorkerTask(3, "review the UI", ""),
                                 WorkerResult.Status.COMPLETED, List.of("third said this"), 300, null))));

        assertThat(command.execute(new String[]{"show", "3"})).isZero();
        assertThat(output()).contains("third said this").doesNotContain("first said this");

        captured.reset();
        assertThat(command.execute(new String[]{"show", "2"})).isEqualTo(1);
        assertThat(output()).contains("No worker 2");
    }

    @Test
    public void theUsageNamesEveryPartOfTheLifecycle() {
        // The usage is what a stuck model re-reads, so it has to cover the whole lifecycle, not
        // just how to start.
        assertThat(command.getUsage())
                .contains("-b").contains("start").contains("status")
                .contains("wait").contains("stop").contains("list").contains("show");
    }

    @Test
    public void statusWithNothingRunningSaysSoRatherThanFailing() {
        assertThat(command.execute(new String[]{"status"})).isZero();
        assertThat(output()).contains("No workers are running");
    }

    @Test
    public void stoppingWhenNothingIsRunningIsNotAnError() {
        // A model that stops twice, or stops after they finished, has done nothing wrong.
        assertThat(command.execute(new String[]{"stop"})).isZero();
        assertThat(output()).contains("No workers are running");
    }

    @Test
    public void waitingWhenNothingIsRunningReturnsImmediately() {
        long start = System.currentTimeMillis();

        assertThat(command.execute(new String[]{"wait"})).isZero();

        assertThat(System.currentTimeMillis() - start).isLessThan(2_000L);
        assertThat(output()).contains("No workers are running");
    }

    @Test
    public void waitRejectsSomethingThatIsNotANumberOfSeconds() {
        WorkerRegistry.setActive(WorkerPool.start(
                java.util.List.of(new WorkerTask(1, "t", "")), 0, null,
                (task, steps) -> {
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return 0;
                }));
        try {
            assertThat(command.execute(new String[]{"wait", "soon"})).isEqualTo(1);
            assertThat(output()).contains("Not a number of seconds");
        } finally {
            WorkerRegistry.active().ifPresent(com.eonmux.cadetcoder.agents.WorkerRun::cancel);
            WorkerRegistry.clear();
        }
    }

    @Test
    public void asecondRunIsRefusedWhileTheFirstIsStillGoing() {
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        WorkerRegistry.setActive(WorkerPool.start(
                java.util.List.of(new WorkerTask(1, "t", "")), 0, null,
                (task, steps) -> {
                    try {
                        release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return 0;
                }));
        try {
            // Queued silently, a second run would double the agents pointed at one provider and make
            // every later question about "the workers" ambiguous.
            assertThat(command.execute(new String[]{"another task"})).isEqualTo(1);
            assertThat(output()).contains("already running").contains("workers stop");
        } finally {
            release.countDown();
            WorkerRegistry.active().ifPresent(com.eonmux.cadetcoder.agents.WorkerRun::cancel);
            WorkerRegistry.clear();
        }
    }
}
