package com.eonmux.cadetcoder.ui;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A capture handed to a second thread has to survive both threads writing to it.
 *
 * <p><b>The defect</b>: {@link OutputCapture#carrying} passes the calling thread's sink to the
 * thread an interruptible command is moved onto, on the stated assumption that only one of them
 * runs at a time. {@code CommandRegistry} breaks that assumption by design: when an interrupt's
 * grace period expires it stops waiting, reports, and returns while the command thread is still
 * alive, still running and still holding the same sink. {@code CapturedRun} made that sink a plain
 * {@link StringBuilder}, so the two threads appended to it with nothing between them -- which loses
 * lines, interleaves halves of two of them, and can throw out of {@code append} while the internal
 * array is being resized. Worse, a thread nobody was waiting for any more went on writing into a
 * transcript its owner had already read and attributed to a finished step.</p>
 */
public class AcaptureSharedByTwoThreadsKeepsEveryLineWholeTest {

    /** Enough lines from each side that an unguarded append is overwhelmingly likely to be caught. */
    private static final int LINES = 2_000;

    @Test
    public void bothThreadsLinesArriveWholeAndNoneIsLost() throws Exception {
        AtomicReference<Throwable> carriedFailure = new AtomicReference<>();

        CapturedRun.Result result = CapturedRun.of(() -> {
            CountDownLatch started = new CountDownLatch(1);
            Runnable carried = OutputCapture.carrying(() -> {
                started.countDown();
                for (int line = 0; line < LINES; line++) {
                    OutputCapture.offer("carried-" + line);
                }
            });
            Thread other = new Thread(() -> {
                try {
                    carried.run();
                } catch (Throwable failed) {
                    carriedFailure.set(failed);
                }
            }, "carried-work");
            other.start();
            try {
                started.await(5, TimeUnit.SECONDS);
                for (int line = 0; line < LINES; line++) {
                    OutputCapture.offer("owner-" + line);
                }
                other.join(30_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return 0;
        });

        assertThat(carriedFailure.get())
                .as("appending to the shared transcript must not throw on either thread")
                .isNull();

        java.util.List<String> lines = result.output().lines().toList();
        assertThat(lines)
                .as("every line both threads wrote is in the transcript exactly once")
                .hasSize(LINES * 2);
        assertThat(lines)
                .as("and each one arrived whole rather than spliced into another")
                .allSatisfy(line -> assertThat(line).matches("(carried|owner)-\\d+"));
    }

    /**
     * The run reads its transcript at the moment an abandoned thread is still writing to it.
     *
     * <p>This is the shape the interrupt path actually produces: the waiting thread gives up and
     * takes the output while the command thread is still going. Reading an unsynchronized
     * {@link StringBuilder} while another thread appends to it returns whatever the array happened
     * to hold -- a half-written line, a line spliced into another -- or throws out of
     * {@code toString} when the array is resized mid-copy. Afterwards, the lines that thread goes
     * on to write belong to nothing: the step that produced them has already reported.</p>
     */
    @Test
    public void whatIsHandedOverIsWellFormedEvenWhileAnAbandonedThreadIsStillWriting() throws Exception {
        Thread[] abandoned = new Thread[1];

        CapturedRun.Result result = CapturedRun.of(() -> {
            Runnable carried = OutputCapture.carrying(() -> {
                for (int line = 0; line < LINES * 10; line++) {
                    OutputCapture.offer("abandoned-line");
                }
            });
            Thread stillGoing = new Thread(carried, "abandoned-work");
            stillGoing.setDaemon(true);
            stillGoing.start();
            abandoned[0] = stillGoing;
            OutputCapture.offer("owner-line");
            // Returns without waiting: exactly what the command layer does when the grace period
            // for an interrupt runs out.
            return 0;
        });

        abandoned[0].join(30_000);

        assertThat(result.output().lines())
                .as("a transcript read while another thread was writing must still be whole lines")
                .allSatisfy(line -> assertThat(line).isIn("owner-line", "abandoned-line"));
        assertThat(result.output()).contains("owner-line");
    }
}
