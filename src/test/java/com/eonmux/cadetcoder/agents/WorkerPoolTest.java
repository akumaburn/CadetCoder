package com.eonmux.cadetcoder.agents;

import com.eonmux.cadetcoder.OutputFormatter;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** Scheduling, isolation and attribution of a worker run. */
public class WorkerPoolTest {

    @After
    public void tearDown() {
        System.clearProperty(WorkerPool.CONCURRENCY_PROPERTY);
    }

    private static List<WorkerTask> tasks(int count) {
        List<WorkerTask> tasks = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            tasks.add(new WorkerTask(i, "task " + i, "shared briefing"));
        }
        return tasks;
    }

    @Test
    public void resultsComeBackInTaskOrderNotCompletionOrder() {
        // Worker 3 finishes first. If results followed completion order, "Worker 1" would name a
        // different worker on every run and `workers show 2` would be meaningless.
        List<WorkerResult> results = WorkerPool.run(tasks(3), 0, null, (task, steps) -> {
            try {
                Thread.sleep((4 - task.index()) * 60L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return 0;
        });

        assertThat(results).hasSize(3);
        assertThat(results.get(0).task().task()).isEqualTo("task 1");
        assertThat(results.get(1).task().task()).isEqualTo("task 2");
        assertThat(results.get(2).task().task()).isEqualTo("task 3");
    }

    @Test
    public void eachWorkersOutputIsKeptSeparateFromItsSiblings() {
        // The reason output is captured per thread at all. Concurrent writes to one stream cannot be
        // attributed afterwards, so a run of several workers would be one unreadable interleaving.
        List<WorkerResult> results = WorkerPool.run(tasks(3), 0, null, (task, steps) -> {
            for (int i = 0; i < 20; i++) {
                OutputFormatter.println(task.name() + " line " + i);
                Thread.yield();
            }
            return 0;
        });

        for (WorkerResult result : results) {
            assertThat(result.output()).hasSize(20);
            assertThat(result.output())
                    .as("%s must contain only its own lines", result.task().name())
                    .allMatch(line -> line.startsWith(result.task().name() + " "));
        }
    }

    @Test
    public void oneWorkerFailingDoesNotStopTheOthers() {
        List<WorkerResult> results = WorkerPool.run(tasks(3), 0, null, (task, steps) -> {
            if (task.index() == 2) {
                throw new IllegalStateException("worker 2 exploded");
            }
            return 0;
        });

        assertThat(results.get(0).succeeded()).isTrue();
        assertThat(results.get(1).succeeded()).isFalse();
        assertThat(results.get(2).succeeded()).isTrue();
    }

    @Test
    public void aNonZeroExitIsReportedAsFailureRatherThanSuccess() {
        List<WorkerResult> results =
                WorkerPool.run(tasks(1), 0, null, (task, steps) -> 1);

        assertThat(results.get(0).status()).isEqualTo(WorkerResult.Status.FAILED);
        assertThat(results.get(0).failure()).contains("exit code 1");
    }

    @Test
    public void aWorkerCannotStartMoreWorkers() {
        // AgentCommand's recursion guard is thread-local, and a worker runs on a new thread, so it
        // does not inherit one. Without an explicit refusal, workers could multiply without bound.
        List<WorkerResult> results = WorkerPool.run(tasks(1), 0, null, (task, steps) -> {
            Throwable thrown = catchThrowable(() -> WorkerPool.run(tasks(2), 0, null, (t, s) -> 0));
            assertThat(thrown).isInstanceOf(IllegalStateException.class);
            return 0;
        });

        assertThat(results.get(0).succeeded()).isTrue();
    }

    @Test
    public void noMoreThanTheConfiguredNumberRunAtOnce() throws Exception {
        // The provider, not the machine, is the limit: every worker is a full agentic loop against
        // one account and one shared retry budget.
        System.setProperty(WorkerPool.CONCURRENCY_PROPERTY, "2");
        AtomicInteger live = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(1);

        WorkerPool.run(tasks(6), 0, null, (task, steps) -> {
            int now = live.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(40);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            live.decrementAndGet();
            return 0;
        });
        done.countDown();
        assertThat(done.await(1, TimeUnit.SECONDS)).isTrue();

        assertThat(peak.get()).isLessThanOrEqualTo(2);
    }

    /**
     * Every worker is reported, exactly once.
     *
     * <p>Order is deliberately not asserted: workers are reported as they FINISH, so that a quick
     * one is not held back behind a slow one submitted before it. Three tasks that do nothing finish
     * in whatever order the pool's threads are scheduled, and this test failed intermittently while
     * it demanded submission order -- which is the very thing the pool no longer promises.</p>
     */
    @Test
    public void progressIsReportedOncePerWorker() {
        List<String> seen = new CopyOnWriteArrayList<>();

        WorkerPool.run(tasks(3), 0, r -> seen.add(r.task().name()), (task, steps) -> 0);

        assertThat(seen).containsExactlyInAnyOrder("Worker 1", "Worker 2", "Worker 3");
    }

    @Test
    public void moreWorkersThanTheCapIsRefusedRatherThanSilentlyTrimmed() {
        Throwable thrown = catchThrowable(
                () -> WorkerPool.run(tasks(WorkerPool.MAX_WORKERS + 1), 0, null, (t, s) -> 0));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void anEmptyRunIsAllowedAndDoesNothing() {
        assertThat(WorkerPool.run(List.of(), 0, null, (t, s) -> 0)).isEmpty();
    }

    @Test
    public void theActivityCounterIsClearedWhenTheRunEnds() {
        WorkerPool.run(tasks(2), 0, null, (task, steps) -> 0);

        // Left set, the status line would claim workers were running for the rest of the session.
        assertThat(WorkerActivity.active()).isFalse();
        assertThat(WorkerActivity.describe()).isEmpty();
    }

    @Test (timeout = 15_000)
    public void aRunThatCannotStartItsWorkersStillFinishesInsteadOfHanging() {
        // The failure this reproduces: the pool is gone by the time the coordinator submits, so
        // every submission is rejected. Before, that escaped the coordinator before its finally
        // block, and nothing ever counted the latch down -- await(0) below would never return, and
        // the status line would claim workers were running for the rest of the session.
        WorkerRun run = WorkerPool.start(tasks(3), 0, null, (task, steps) -> 0);
        run.cancel();

        assertThat(run.await(10_000)).isTrue();
        assertThat(WorkerActivity.active()).isFalse();
    }

    @Test (timeout = 15_000)
    public void aWorkerThatThrowsAnErrorIsReportedRatherThanLosingTheRun() {
        // An Error is not a RuntimeException, so it passes the worker's own guard and surfaces
        // through the coordinator. The run must still complete and say what happened.
        List<WorkerResult> results = WorkerPool.run(tasks(2), 0, null, (task, steps) -> {
            if (task.index() == 1) {
                throw new StackOverflowError("too deep");
            }
            return 0;
        });

        assertThat(results).hasSize(2);
        assertThat(results.get(0).status()).isEqualTo(WorkerResult.Status.FAILED);
        assertThat(results.get(1).status()).isEqualTo(WorkerResult.Status.COMPLETED);
        assertThat(WorkerActivity.active()).isFalse();
    }

    /**
     * A worker is counted as finished when it finishes, not when its turn to be waited for comes.
     *
     * <p><b>The defect</b>: the coordinator waited on the futures by index. Worker 3 could have been
     * done for a minute while worker 1 was still going, and nothing would know: its result was not
     * recorded, {@link WorkerRun#stillRunning} still listed it, the count the shell shows still had
     * it running, and the caller's progress callback had not fired. Everything the run said about
     * itself described the slowest worker so far rather than what had happened.</p>
     */
    @Test (timeout = 15_000)
    public void aworkerThatHasFinishedIsReportedBeforeTheSlowOneAheadOfItHas() throws Exception {
        CountDownLatch      fastOneDone = new CountDownLatch(1);
        CountDownLatch      letSlowGo   = new CountDownLatch(1);
        List<String>        told        = new CopyOnWriteArrayList<>();

        WorkerRun run = WorkerPool.start(tasks(2), 0, result -> told.add(result.task().name()),
                                         (task, steps) -> {
            if (task.index() == 1) {
                await(letSlowGo);
                return 0;
            }
            fastOneDone.countDown();
            return 0;
        });

        assertThat(fastOneDone.await(10, TimeUnit.SECONDS)).isTrue();
        // The second worker has returned. Its result has to reach the run without waiting on the
        // first, which is still blocked.
        for (int waited = 0; waited < 1_000 && told.isEmpty(); waited++) {
            Thread.sleep(10);
        }

        assertThat(told)
                .as("the worker that finished is the one reported, while the other is still going")
                .containsExactly("Worker 2");
        assertThat(run.stillRunning()).containsExactly(1);

        letSlowGo.countDown();
        assertThat(run.await(10_000)).isTrue();
        assertThat(told).containsExactly("Worker 2", "Worker 1");
    }

    /**
     * What a worker had already said is part of the result, even when it ended by throwing.
     *
     * <p>Its output is collected into the run line by line as it is produced, so it exists whether
     * or not the worker reached the end. Reported as an empty list, the only account of what the
     * worker was doing when it broke is thrown away at the moment it becomes worth reading.</p>
     */
    @Test (timeout = 15_000)
    public void whatAworkerSaidBeforeItFailedIsInItsResult() {
        List<WorkerResult> results = WorkerPool.run(tasks(1), 0, null, (task, steps) -> {
            OutputFormatter.printInfo("got as far as here");
            throw new IllegalStateException("and then broke");
        });

        assertThat(results).hasSize(1);
        assertThat(results.get(0).status()).isEqualTo(WorkerResult.Status.FAILED);
        assertThat(String.join("\n", results.get(0).output())).contains("got as far as here");
        assertThat(results.get(0).failure()).contains("and then broke");
    }

    /** Waits without making every caller of it say what it does about being interrupted. */
    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        }
    }
}
