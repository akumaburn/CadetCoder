package com.eonmux.cadetcoder.agents;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Starting workers, watching them, and stopping them. */
public class WorkerRunLifecycleTest {

    private final CountDownLatch release = new CountDownLatch(1);

    @After
    public void tearDown() {
        release.countDown();
    }

    private static List<WorkerTask> tasks(int count) {
        List<WorkerTask> tasks = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            tasks.add(new WorkerTask(i, "task " + i, "briefing"));
        }
        return tasks;
    }

    /** A worker that runs until the test lets it go. */
    private WorkerPool.WorkerRunner blocking() {
        return (task, steps) -> {
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return 0;
        };
    }

    @Test
    public void startingReturnsBeforeTheWorkersFinish() {
        WorkerRun run = WorkerPool.start(tasks(2), 0, null, blocking());

        // The whole point of the background form: the caller gets control back and can do its own
        // work, or ask about the run, instead of being blocked until the last worker is done.
        assertThat(run.isDone()).isFalse();
        assertThat(run.total()).isEqualTo(2);
        release.countDown();
        assertThat(run.await(10_000)).isTrue();
    }

    @Test
    public void progressIsVisibleWhileTheRunIsStillGoing() {
        CountDownLatch first = new CountDownLatch(1);
        WorkerRun run = WorkerPool.start(tasks(3), 0, null, (task, steps) -> {
            if (task.index() > 1) {
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            } else {
                first.countDown();
            }
            return 0;
        });

        com.eonmux.cadetcoder.testing.Await.until("worker 1 to finish",
                                                  () -> run.finishedCount() >= 1);

        // Progress means nothing if it is only readable once everything is over.
        assertThat(run.finishedCount()).isEqualTo(1);
        assertThat(run.isDone()).isFalse();
        assertThat(run.stillRunning()).contains(2, 3);
        assertThat(run.resultFor(1)).isPresent();
        assertThat(run.resultFor(2)).isEmpty();

        release.countDown();
        run.await(10_000);
    }

    @Test
    public void stoppingEndsTheRunAndSaysHowManyWereStillGoing() {
        WorkerRun run = WorkerPool.start(tasks(3), 0, null, blocking());
        com.eonmux.cadetcoder.testing.Await.until("the run to be under way", () -> !run.isDone());

        int stopped = run.cancel();

        assertThat(stopped).isGreaterThan(0);
        assertThat(run.isCancelled()).isTrue();
        assertThat(run.isDone()).as("a cancelled run is finished, not left hanging").isTrue();
    }

    @Test
    public void whatFinishedBeforeAStopIsKept() {
        CountDownLatch firstDone = new CountDownLatch(1);
        WorkerRun run = WorkerPool.start(tasks(3), 0, null, (task, steps) -> {
            if (task.index() == 1) {
                firstDone.countDown();
                return 0;
            }
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return 0;
        });
        com.eonmux.cadetcoder.testing.Await.until("worker 1 to finish",
                                                  () -> run.finishedCount() >= 1);

        run.cancel();

        // A stopped run's partial results are usually the reason it was stopped.
        assertThat(run.results()).isNotEmpty();
        assertThat(run.resultFor(1)).isPresent();
    }

    @Test
    public void awaitingWithATimeoutReportsThatItIsStillGoingRatherThanBlocking() {
        WorkerRun run = WorkerPool.start(tasks(2), 0, null, blocking());

        long start = System.currentTimeMillis();
        boolean finished = run.await(200);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(finished).isFalse();
        assertThat(elapsed).isLessThan(5_000L);
        release.countDown();
        run.await(10_000);
    }

    @Test
    public void resultsComeBackInWorkerOrderNotCompletionOrder() {
        WorkerRun run = WorkerPool.start(tasks(3), 0, null, (task, steps) -> {
            try {
                Thread.sleep((4 - task.index()) * 40L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return 0;
        });
        run.await(10_000);

        // "Worker 2" has to mean the same worker in the summary, in `show 2`, and in the transcript.
        List<WorkerResult> results = run.results();
        assertThat(results).hasSize(3);
        assertThat(results.get(0).task().index()).isEqualTo(1);
        assertThat(results.get(1).task().index()).isEqualTo(2);
        assertThat(results.get(2).task().index()).isEqualTo(3);
    }

    @Test
    public void aFinishedRunIsNoLongerTheActiveOne() {
        WorkerRun run = WorkerPool.start(tasks(1), 0, null, (task, steps) -> 0);
        run.await(10_000);
        WorkerRegistry.setActive(run);

        // Otherwise a completed run would block every later start with "workers are already running".
        assertThat(WorkerRegistry.active()).isEmpty();
        WorkerRegistry.clear();
    }
}
