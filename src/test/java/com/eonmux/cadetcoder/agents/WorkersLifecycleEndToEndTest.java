package com.eonmux.cadetcoder.agents;

import com.eonmux.cadetcoder.commands.WorkersCommand;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sequence the model is told to use: start, check progress, read a result, terminate.
 *
 * <p>Driven through {@link WorkersCommand} rather than the pool, because that is the surface the
 * model actually reaches, and because a lifecycle that works on the pool but not through the command
 * is a lifecycle the model cannot use.</p>
 */
public class WorkersLifecycleEndToEndTest {

    private final ByteArrayOutputStream captured    = new ByteArrayOutputStream();
    private final PrintStream           originalOut = System.out;
    private final PrintStream           originalErr = System.err;
    private final CountDownLatch        release     = new CountDownLatch(1);

    private WorkersCommand command;

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
        release.countDown();
        WorkerRegistry.active().ifPresent(WorkerRun::cancel);
        WorkerRegistry.clear();
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    private String output() {
        System.out.flush();
        return captured.toString();
    }

    /** Starts a run whose workers block until the test releases them. */
    private WorkerRun startBlockingRun(int count) {
        java.util.List<WorkerTask> tasks = new java.util.ArrayList<>();
        for (int i = 1; i <= count; i++) {
            tasks.add(new WorkerTask(i, "task " + i, "briefing"));
        }
        WorkerRun run = WorkerPool.start(tasks, 0, null, (task, steps) -> {
            try {
                release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return 0;
        });
        WorkerRegistry.setActive(run);
        return run;
    }

    @Test
    public void statusReportsWhoIsStillGoingAndNamesTheNextSteps() {
        startBlockingRun(3);

        assertThat(command.execute(new String[]{"status"})).isZero();

        String shown = output();
        assertThat(shown).contains("Worker 1").contains("Worker 3").contains("running");
        // A model that can see progress but not what to do about it is no better off.
        assertThat(shown).contains("workers wait").contains("workers stop");
    }

    @Test
    public void waitingWithATimeoutSaysItIsStillGoingRatherThanBlockingForever() {
        startBlockingRun(2);

        // Return code 1 so the model can tell "not finished" from "finished".
        assertThat(command.execute(new String[]{"wait", "1"})).isEqualTo(1);
        assertThat(output()).contains("Still running");
    }

    @Test
    public void stoppingEndsTheRunAndSaysWhatSurvived() {
        startBlockingRun(3);

        assertThat(command.execute(new String[]{"stop"})).isZero();

        String shown = output();
        assertThat(shown).contains("Stopped");
        // The model has to know the partial results are still reachable, or it will not look.
        assertThat(shown).contains("workers show");
        assertThat(WorkerRegistry.active()).isEmpty();
    }

    @Test
    public void afterStoppingAnotherRunCanBeStarted() {
        startBlockingRun(2);
        command.execute(new String[]{"stop"});
        captured.reset();

        // The refusal is only meant to prevent OVERLAPPING runs; a stopped one must not block the
        // next one forever.
        WorkerRun second = startBlockingRun(1);
        assertThat(second).isNotNull();
        assertThat(WorkerRegistry.active()).isPresent();
    }

    @Test
    public void aBackgroundRunIsReadableAsSoonAsItFinishes() {
        // The gap this closes: results used to be published only by whichever command finished
        // waiting on the run. A run started with `workers start` and left alone published nothing,
        // so `workers show` answered from the run BEFORE it -- or said there had been none.
        release.countDown();
        WorkerRun run = startBlockingRun(2);
        run.await(10_000);
        captured.reset();

        assertThat(command.execute(new String[]{"show", "1"})).isZero();
        assertThat(output()).contains("Worker 1");
    }

    @Test
    public void aFinishedRunIsReadableThroughListAndShow() {
        release.countDown(); // let the workers finish immediately
        WorkerRun run = startBlockingRun(2);
        run.await(10_000);
        captured.reset();

        assertThat(command.execute(new String[]{"list"})).isZero();
        assertThat(output()).contains("Worker 1").contains("Worker 2");

        captured.reset();
        assertThat(command.execute(new String[]{"show", "2"})).isZero();
        assertThat(output()).contains("Worker 2");
    }

    @Test
    public void theWholeSequenceTheCatalogDescribesWorksInOrder() {
        // start -> status -> stop -> list, as the model is told to use it.
        startBlockingRun(2);
        assertThat(command.execute(new String[]{"status"})).isZero();
        assertThat(command.execute(new String[]{"stop"})).isZero();
        assertThat(command.execute(new String[]{"list"})).isZero();

        assertThat(output()).contains("Worker 1");
    }
}
