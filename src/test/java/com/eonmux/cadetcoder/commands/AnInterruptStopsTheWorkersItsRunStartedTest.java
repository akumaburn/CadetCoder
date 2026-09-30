package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.ExitCode;
import com.eonmux.cadetcoder.InterruptSignal;
import com.eonmux.cadetcoder.agents.WorkerPool;
import com.eonmux.cadetcoder.agents.WorkerRegistry;
import com.eonmux.cadetcoder.agents.WorkerRun;
import com.eonmux.cadetcoder.resume.ResumeScope;
import com.eonmux.cadetcoder.session.ResumePoint;
import com.eonmux.cadetcoder.session.SessionManager;
import com.eonmux.cadetcoder.test.TestOutputCapture;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An interrupt stops the workers the interrupted run started, and {@code resume} runs again only
 * the tasks that did not finish.
 *
 * <p>The workers used to keep running after the command that started them was interrupted, with
 * nothing left to report what they did. A worker whose task finished keeps its result: running it
 * again would spend a model run on work that is already done.</p>
 */
public class AnInterruptStopsTheWorkersItsRunStartedTest {

    private static final long WAIT_SECONDS = 20;

    private TestOutputCapture output;

    @Before
    public void setUp() {
        SessionManager.getInstance().clearResumePoint();
        output = new TestOutputCapture();
        output.startCapture();
    }

    @After
    public void tearDown() {
        output.stopCapture();
        InterruptSignal.clear();
        WorkerRegistry.active().ifPresent(WorkerRun::cancel);
        WorkerRegistry.setActive(null);
        SessionManager.getInstance().clearResumePoint();
    }

    /**
     * Finishes "quick" at once, and holds any other task until it is stopped.
     *
     * <p>Each task opens a run of its own, as the agent a real worker runs does.</p>
     */
    private static WorkerPool.WorkerRunner quickOrHeld() {
        return (task, maxSteps) -> {
            try (ResumeScope own = ResumeScope.open()) {
                if (task.task().equals("quick")) {
                    return 0;
                }
                new CountDownLatch(1).await();
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            }
            return ExitCode.INTERRUPTED;
        };
    }

    private static void awaitFinished(int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (WorkerRegistry.active().map(WorkerRun::finishedCount).orElse(0) < count) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the workers never got that far");
            }
            Thread.sleep(10);
        }
    }

    @Test
    public void anInterruptedWorkersRunStopsItsWorkersAndSavesWhichFinished() throws Exception {
        WorkersCommand workers = new WorkersCommand(quickOrHeld());
        CommandRegistry.InterruptionContext stop = new CommandRegistry.InterruptionContext();
        workers.setInterruptionContext(stop);
        int[] exit = new int[1];

        Thread running = new Thread(() -> exit[0] = workers.execute(
                new String[] {"quick", "held", "-b", "the shared briefing", "-m", "7"}));
        running.start();
        awaitFinished(1);
        WorkerRun run = WorkerRegistry.active().orElseThrow();
        stop.setInterrupted(true);
        running.interrupt();
        running.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));

        assertThat(exit[0]).isEqualTo(ExitCode.INTERRUPTED);
        assertThat(run.isCancelled()).as("the held worker was stopped").isTrue();

        ResumePoint point = SessionManager.getInstance().getResumePoint().orElseThrow();
        assertThat(point.kind()).isEqualTo(ResumePoint.WORKERS);
        // The held worker is saved as UNFINISHED, or as INTERRUPTED when its own ending was
        // recorded before the stop. Either way it is not finished, so a resume runs it again.
        assertThat(point.workers()).extracting(ResumePoint.Worker::task, ResumePoint.Worker::finished)
                                   .containsExactly(
                                           org.assertj.core.groups.Tuple.tuple("quick", true),
                                           org.assertj.core.groups.Tuple.tuple("held", false));
    }

    @Test
    public void aResumedWorkersRunRunsOnlyTheUnfinishedTasks() {
        SessionManager.getInstance().setResumePoint(ResumePoint.workers(
                List.of("quick", "held", "-b", "the shared briefing", "-m", "7"),
                List.of(new ResumePoint.Worker(1, "quick", "COMPLETED", List.of("quick is done")),
                        new ResumePoint.Worker(2, "held", "UNFINISHED",
                                               List.of("read the retry code")))));
        List<String> ran      = new CopyOnWriteArrayList<>();
        List<Integer> budgets = new CopyOnWriteArrayList<>();
        WorkerPool.WorkerRunner recording = (task, maxSteps) -> {
            ran.add(task.prompt());
            budgets.add(maxSteps);
            return 0;
        };

        int exit = new ResumeCommand(ChatCommand::new, AgentCommand::new,
                                     () -> new WorkersCommand(recording))
                .execute(new String[0]);

        assertThat(exit).isEqualTo(ExitCode.OK);
        assertThat(ran).hasSize(1);
        assertThat(ran.get(0)).contains("held")
                              .contains("the shared briefing")
                              .contains("read the retry code");
        assertThat(budgets).containsExactly(7);
        assertThat(output.getOutput()).contains("quick is done").contains("2 of 2 succeeded");
    }

    @Test
    public void aResumeRefusedWhileWorkersRunKeepsThePoint() throws Exception {
        assertThat(new WorkersCommand(quickOrHeld()).execute(new String[] {"start", "held"}))
                .isEqualTo(ExitCode.OK);
        SessionManager.getInstance().setResumePoint(ResumePoint.workers(
                List.of("quick", "held"),
                List.of(new ResumePoint.Worker(1, "quick", "COMPLETED", List.of()),
                        new ResumePoint.Worker(2, "held", "UNFINISHED", List.of()))));

        int exit = new ResumeCommand(ChatCommand::new, AgentCommand::new,
                                     () -> new WorkersCommand(quickOrHeld()))
                .execute(new String[0]);

        assertThat(exit).isEqualTo(1);
        assertThat(output.getAllOutput()).contains("already running");
        assertThat(SessionManager.getInstance().getResumePoint())
                .as("the run can still be resumed once the other workers end")
                .isPresent();
    }

    @Test
    public void workersStartedInTheBackgroundAreStoppedWithTheRunThatStartedThem() throws Exception {
        WorkerRun run;
        try (ResumeScope scope = ResumeScope.open()) {
            assertThat(new WorkersCommand(quickOrHeld()).execute(new String[] {"start", "quick", "held"}))
                    .isEqualTo(ExitCode.OK);
            awaitFinished(1);
            run = WorkerRegistry.active().orElseThrow();

            scope.stopped(ResumePoint.chat(List.of("review it"),
                    new ResumePoint.Chat("review it", List.of(), 0)));
        }

        assertThat(run.isCancelled()).isTrue();
        ResumePoint point = SessionManager.getInstance().getResumePoint().orElseThrow();
        assertThat(point.workers()).extracting(ResumePoint.Worker::finished)
                                   .containsExactly(true, false);
    }
}
