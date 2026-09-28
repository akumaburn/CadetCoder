package com.eonmux.cadetcoder.ui;

import org.junit.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** Redirecting one thread's output without disturbing any other. */
public class OutputCaptureTest {

    @Test
    public void linesGoToTheCollectorInsteadOfTheConsole() {
        List<String> lines = OutputCapture.collect(() -> {
            UnifiedOutput.println("first");
            UnifiedOutput.println("second");
        });

        assertThat(lines).containsExactly("first", "second");
    }

    /**
     * A run that is the only thing happening is watched, so taking its output away would leave the
     * screen blank for as long as it lasts. {@code loop} keeps the tail of each pass to tell the
     * next one, and a user watching a hundred silent passes could neither steer nor stop it.
     */
    @Test
    public void anEchoingCollectorGetsTheLinesAndSoDoesTheConsole() {
        java.io.ByteArrayOutputStream console = new java.io.ByteArrayOutputStream();
        java.io.PrintStream          was      = System.out;
        List<String>                 lines    = new java.util.ArrayList<>();
        try {
            System.setOut(new java.io.PrintStream(console, true));
            OutputCapture.collectAlongside(lines::add, () -> {
                UnifiedOutput.println("first");
                UnifiedOutput.println("second");
            });
        } finally {
            System.setOut(was);
        }

        assertThat(lines).containsExactly("first", "second");
        assertThat(console.toString()).contains("first").contains("second");
    }

    /** The ordinary collector still takes output instead of printing it. */
    @Test
    public void anOrdinaryCollectorKeepsTheConsoleQuiet() {
        java.io.ByteArrayOutputStream console = new java.io.ByteArrayOutputStream();
        java.io.PrintStream          was      = System.out;
        List<String>                 lines    = new java.util.ArrayList<>();
        try {
            System.setOut(new java.io.PrintStream(console, true));
            OutputCapture.collectInto(lines::add, () -> UnifiedOutput.println("quiet"));
        } finally {
            System.setOut(was);
        }

        assertThat(lines).containsExactly("quiet");
        assertThat(console.toString()).doesNotContain("quiet");
    }

    @Test
    public void anEmptyLineIsCapturedRatherThanEscapingToTheConsole() {
        // Blank lines are how the transcript separates things now, so losing them here would run a
        // worker's output together into one block.
        List<String> lines = OutputCapture.collect(() -> {
            UnifiedOutput.println("a");
            UnifiedOutput.println();
            UnifiedOutput.println("b");
        });

        assertThat(lines).containsExactly("a", "", "b");
    }

    @Test
    public void captureEndsWhenTheBodyDoes() {
        OutputCapture.collect(() -> UnifiedOutput.println("inside"));

        assertThat(OutputCapture.isCapturing()).isFalse();
        assertThat(OutputCapture.offer("after")).isFalse();
    }

    @Test
    public void aFailingBodyStillReleasesTheCapture() {
        try {
            OutputCapture.collect(() -> {
                throw new IllegalStateException("boom");
            });
        } catch (IllegalStateException ignored) {
            // the point is what happens next
        }

        assertThat(OutputCapture.isCapturing()).isFalse();
    }

    @Test
    public void anInnerCaptureHandsTheOuterOneBackRatherThanDetachingIt() {
        List<String> outer = OutputCapture.collect(() -> {
            UnifiedOutput.println("outer before");
            List<String> inner = OutputCapture.collect(() -> UnifiedOutput.println("inner"));
            assertThat(inner).containsExactly("inner");
            UnifiedOutput.println("outer after");
        });

        // Clearing rather than restoring would send everything after the inner block to the console.
        assertThat(outer).containsExactly("outer before", "outer after");
    }

    @Test
    public void oneThreadsCaptureDoesNotTakeAnothersOutput() throws Exception {
        List<String> escaped = new CopyOnWriteArrayList<>();
        CountDownLatch capturing = new CountDownLatch(1);
        CountDownLatch other     = new CountDownLatch(1);

        Thread sibling = new Thread(() -> {
            try {
                capturing.await();
                // Not captured: this thread installed no sink.
                escaped.add(String.valueOf(OutputCapture.isCapturing()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                other.countDown();
            }
        });
        sibling.start();

        List<String> mine = OutputCapture.collect(() -> {
            capturing.countDown();
            try {
                other.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            UnifiedOutput.println("mine");
        });
        sibling.join();

        assertThat(mine).containsExactly("mine");
        assertThat(escaped).containsExactly("false");
    }

    @Test
    public void aQuestionAskedAsideFromTheCaptureReachesTheScreen() {
        // An agent step collects its output so the model can read it. A confirmation printed into
        // that collection was never shown to the user, who was then taken to have said no.
        List<String> collected = new java.util.ArrayList<>();
        boolean[] sawNoSink = {false};

        OutputCapture.collectInto(collected::add, () -> {
            UnifiedOutput.println("before");
            OutputCapture.outsideCapture(() -> {
                sawNoSink[0] = !OutputCapture.isCapturing();
                return null;
            });
            UnifiedOutput.println("after");
        });

        assertThat(sawNoSink[0]).as("the question is printed with no sink installed").isTrue();
        assertThat(collected).containsExactly("before", "after");
        assertThat(OutputCapture.isCapturing())
                .as("the step's capture is put back")
                .isFalse();
    }

    @Test
    public void aHalfWrittenLineIsNotSplitAcrossTheQuestion() {
        // Output arrives in whatever chunks a PrintStream was given. A fragment held back when the
        // capture was set aside would have the question's own text appended to it.
        List<String> collected = new java.util.ArrayList<>();

        OutputCapture.collectInto(collected::add, () -> {
            UnifiedOutput.print("half a line");
            OutputCapture.outsideCapture(() -> null);
            UnifiedOutput.println("the rest");
        });

        assertThat(collected).containsExactly("half a line", "the rest");
    }

    @Test
    public void withNoCaptureInPlaceItSimplyRuns() {
        assertThat(OutputCapture.outsideCapture(() -> "answered")).isEqualTo("answered");
    }
}
