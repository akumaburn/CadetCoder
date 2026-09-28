package com.eonmux.cadetcoder.agents;

import com.eonmux.cadetcoder.ui.OutputCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A worker's output has to be readable while the worker is still producing it.
 *
 * <p>Collected output is what keeps concurrent runs attributable, and it used to mean the lines
 * existed nowhere anything else could reach until the worker finished: {@code collect} built a
 * private list and handed it back at the end. For a run measured in minutes that left a counter as
 * the only thing to look at, and the shell had nothing to show for a worker even when asked.</p>
 */
class LiveWorkerOutputTest {

    @AfterEach
    void tearDown() {
        WorkerRegistry.active().ifPresent(WorkerRun::cancel);
        WorkerRegistry.clear();
    }

    private static List<WorkerTask> tasks(int count) {
        return java.util.stream.IntStream.rangeClosed(1, count)
                .mapToObj(i -> new WorkerTask(i, "task " + i, ""))
                .collect(java.util.stream.Collectors.toList());
    }

    private static void until(String what, java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted waiting for " + what, e);
            }
        }
        throw new AssertionError("timed out waiting for " + what);
    }

    @Test
    @Timeout(15)
    @DisplayName("A running worker's lines are readable before it finishes")
    void outputIsVisibleWhileTheWorkerRuns() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch printed = new CountDownLatch(1);

        WorkerRun run = WorkerPool.start(tasks(1), 0, null, (task, steps) -> {
            com.eonmux.cadetcoder.ui.UnifiedOutput.println("first line");
            printed.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            com.eonmux.cadetcoder.ui.UnifiedOutput.println("after release");
            return 0;
        });

        assertThat(printed.await(5, TimeUnit.SECONDS)).isTrue();
        until("the first line to reach the run", () -> !run.outputSoFar(1).isEmpty());

        // The decisive assertion: readable with the worker still blocked, not merely at the end.
        assertThat(run.isDone()).isFalse();
        assertThat(run.outputSoFar(1)).containsExactly("first line");

        release.countDown();
        assertThat(run.await(10_000)).isTrue();
        assertThat(run.outputSoFar(1)).containsExactly("first line", "after release");
    }

    @Test
    @Timeout(15)
    @DisplayName("Each worker's lines stay under that worker, not merged")
    void outputStaysAttributedPerWorker() {
        List<WorkerResult> results = WorkerPool.run(tasks(3), 0, null, (task, steps) -> {
            com.eonmux.cadetcoder.ui.UnifiedOutput.println("from " + task.name());
            return 0;
        });

        assertThat(results).hasSize(3);
        assertThat(results.get(0).output()).containsExactly("from Worker 1");
        assertThat(results.get(1).output()).containsExactly("from Worker 2");
        assertThat(results.get(2).output()).containsExactly("from Worker 3");
    }

    @Test
    @Timeout(15)
    @DisplayName("The returned result carries the same lines the live view showed")
    void theFinalResultMatchesTheLiveTranscript() {
        WorkerRun run = WorkerPool.start(tasks(1), 0, null, (task, steps) -> {
            com.eonmux.cadetcoder.ui.UnifiedOutput.println("one");
            com.eonmux.cadetcoder.ui.UnifiedOutput.println("two");
            return 0;
        });
        assertThat(run.await(10_000)).isTrue();

        assertThat(run.results().get(0).output()).isEqualTo(run.outputSoFar(1));
    }

    @Test
    @DisplayName("A worker number the run never had reads as empty rather than throwing")
    void anUnknownWorkerNumberIsEmpty() {
        WorkerRun run = WorkerRun.finished(List.of(
                new WorkerResult(new WorkerTask(1, "only", ""),
                                 WorkerResult.Status.COMPLETED, List.of("x"), 10, null)));

        assertThat(run.outputSoFar(9)).isEmpty();
        assertThat(run.taskFor(9)).isEmpty();
        assertThat(run.taskFor(1)).isPresent();
    }

    @Test
    @DisplayName("collectInto restores the previous sink, so a nested capture cannot detach the outer one")
    void nestedCaptureRestoresTheOuterSink() {
        List<String> outer = new java.util.ArrayList<>();
        List<String> inner = new java.util.ArrayList<>();

        OutputCapture.collectInto(outer::add, () -> {
            com.eonmux.cadetcoder.ui.UnifiedOutput.println("before");
            OutputCapture.collectInto(inner::add,
                    () -> com.eonmux.cadetcoder.ui.UnifiedOutput.println("nested"));
            com.eonmux.cadetcoder.ui.UnifiedOutput.println("after");
        });

        assertThat(inner).containsExactly("nested");
        assertThat(outer).containsExactly("before", "after");
    }
}
