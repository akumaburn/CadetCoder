package com.eonmux.cadetcoder.ui;

import org.junit.Test;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Capturing a command's output must not be visible to any other thread.
 *
 * <p>The agent loop used to capture by replacing the process-global {@code System.out}, saving the
 * previous value in a local and restoring it in a {@code finally}. {@link
 * com.eonmux.cadetcoder.agents.WorkerPool} runs up to eight agent loops at once, so two of them
 * interleaving meant the second saved the FIRST one's buffer as "the original" and restored that on
 * the way out: from then on every {@code System.out} write in the process went into a discarded
 * byte array, and the terminal was silent for the rest of the session.</p>
 */
public class CapturedRunTest {

    private static final String ESC = String.valueOf((char) 27);

    @Test
    public void concurrentCapturesDoNotLeaveSystemOutPointingAtABuffer() throws Exception {
        // Installing the thread-aware bridge replaces System.out once, deliberately and permanently.
        // The invariant under test is the other one: whatever stream is in place when concurrent
        // captures begin is still in place when they end.
        OutputCapture.installStreamBridge();
        PrintStream before = System.out;
        int threads = 8;
        CountDownLatch allInside = new CountDownLatch(threads);
        CountDownLatch release = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final int index = i;
            Thread thread = new Thread(() -> CapturedRun.of(() -> {
                // Every thread is inside its own capture at the same moment: this is exactly the
                // interleaving the stream-swapping version could not survive.
                allInside.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                UnifiedOutput.println("line from " + index);
                return index;
            }));
            workers.add(thread);
            thread.start();
        }

        assertThat(allInside.await(5, TimeUnit.SECONDS)).isTrue();
        release.countDown();
        for (Thread thread : workers) {
            thread.join(5000);
        }

        assertThat(System.out)
                .as("after every capture has ended, the process stream must be the one it started as")
                .isSameAs(before);
    }

    @Test
    public void eachThreadSeesOnlyItsOwnOutput() throws Exception {
        int threads = 4;
        List<Thread> workers = new ArrayList<>();
        String[] captured = new String[threads];

        for (int i = 0; i < threads; i++) {
            final int index = i;
            Thread thread = new Thread(() -> {
                CapturedRun.Result result = CapturedRun.of(() -> {
                    for (int line = 0; line < 20; line++) {
                        UnifiedOutput.println("thread-" + index + " line-" + line);
                    }
                    return 0;
                });
                captured[index] = result.output();
            });
            workers.add(thread);
            thread.start();
        }
        for (Thread thread : workers) {
            thread.join(5000);
        }

        for (int i = 0; i < threads; i++) {
            final int index = i;
            assertThat(captured[i]).contains("thread-" + i + " line-19");
            assertThat(captured[i].lines())
                    .as("a worker's transcript is the record of what IT did; another thread's lines "
                        + "in it are not merely untidy, they are attributed to the wrong worker")
                    .allSatisfy(line -> assertThat(line).startsWith("thread-" + index + " "));
        }
    }

    /** The bridge must not swallow output from a thread that is not capturing. */
    @Test
    public void outputPrintedOutsideACaptureStillReachesTheConsole() {
        OutputCapture.installStreamBridge();

        List<String> seen = OutputCapture.collect(() -> System.out.println("visible-line"));

        assertThat(seen).anySatisfy(line -> assertThat(line).contains("visible-line"));
    }

    @Test
    public void theExitCodeAndTheTextBothComeBack() {
        CapturedRun.Result result = CapturedRun.of(() -> {
            UnifiedOutput.println("hello");
            return 3;
        });

        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.output()).contains("hello");
    }

    @Test
    public void ansiIsStrippedSoTheModelReadsTextRatherThanEscapes() {
        CapturedRun.Result result = CapturedRun.of(() -> {
            UnifiedOutput.println(ESC + "[31mred" + ESC + "[0m");
            return 0;
        });

        assertThat(result.output()).contains("red").doesNotContain(ESC);
    }
}
