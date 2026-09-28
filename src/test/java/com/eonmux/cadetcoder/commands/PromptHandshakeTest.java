package com.eonmux.cadetcoder.commands;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two askers, one input line.
 *
 * <p>The defect these pin down was reachable whenever more than one thing could ask at once, which a
 * worker run makes ordinary: the second asker overwrote the first's answer queue, the typed answer
 * went to whichever was installed at that instant, and the first polled a queue nobody would ever
 * fill. Nothing reported it — the thread simply never returned, and to the person at the terminal an
 * answered prompt looked ignored and immediately re-asked.</p>
 */
class PromptHandshakeTest {

    private static final BooleanRunning ALIVE = new BooleanRunning(true);

    /** A settable liveness flag, so a test can stop the "UI" without a real one. */
    private static final class BooleanRunning implements java.util.function.BooleanSupplier {
        private final AtomicBoolean running;

        BooleanRunning(boolean initial) {
            this.running = new AtomicBoolean(initial);
        }

        @Override
        public boolean getAsBoolean() {
            return running.get();
        }

        void stop() {
            running.set(false);
        }
    }

    /** Waits for a condition rather than sleeping a fixed time. */
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
    @DisplayName("Each of two concurrent askers gets its own answer, and neither is stranded")
    void concurrentAskersAreServedOneAtATime() throws Exception {
        PromptHandshake handshake = new PromptHandshake();
        List<String> answers = new CopyOnWriteArrayList<>();
        CountDownLatch finished = new CountDownLatch(2);

        Runnable asker = () -> {
            answers.add(handshake.ask("q", ALIVE, null));
            finished.countDown();
        };
        Thread first  = new Thread(asker, "asker-1");
        Thread second = new Thread(asker, "asker-2");
        first.start();
        until("the first asker to take the line", handshake::isActive);
        second.start();

        // The second must NOT have displaced the first: only one prompt is up, and one answer
        // releases exactly one asker.
        handshake.submit("alpha");
        until("the second asker to take the line in turn",
              () -> handshake.isActive() && answers.size() == 1);
        handshake.submit("beta");

        assertThat(finished.await(5, TimeUnit.SECONDS))
                .as("neither asker may be left waiting on a queue nobody will fill")
                .isTrue();
        assertThat(answers).containsExactlyInAnyOrder("alpha", "beta");
    }

    @Test
    @DisplayName("An answer reaches the asker and the line is released")
    void anAnswerIsDelivered() throws Exception {
        PromptHandshake handshake = new PromptHandshake();
        BlockingQueue<String> got = new ArrayBlockingQueue<>(1);

        Thread asker = new Thread(() -> got.add(handshake.ask("q", ALIVE, null)));
        asker.start();
        until("the prompt to go up", handshake::isActive);

        assertThat(handshake.text()).isEqualTo("q");
        assertThat(handshake.submit("typed")).isTrue();

        assertThat(got.poll(5, TimeUnit.SECONDS)).isEqualTo("typed");
        until("the line to be released", () -> !handshake.isActive());
        assertThat(handshake.text()).isNull();
    }

    @Test
    @DisplayName("A cancelled prompt returns empty, and is told apart from someone typing 'cancelled'")
    void cancellationIsDistinctFromTypingTheWord() throws Exception {
        PromptHandshake cancelled = new PromptHandshake();
        BlockingQueue<String> fromCancel = new ArrayBlockingQueue<>(1);
        new Thread(() -> fromCancel.add(cancelled.ask("q", ALIVE, null))).start();
        until("the prompt to go up", cancelled::isActive);
        cancelled.cancel();
        assertThat(fromCancel.poll(5, TimeUnit.SECONDS)).isEmpty();

        PromptHandshake typed = new PromptHandshake();
        BlockingQueue<String> fromTyping = new ArrayBlockingQueue<>(1);
        new Thread(() -> fromTyping.add(typed.ask("q", ALIVE, null))).start();
        until("the prompt to go up", typed::isActive);
        typed.submit("cancelled");
        // Identity, not equality: the sentinel must not swallow a real answer that happens to
        // spell it.
        assertThat(fromTyping.poll(5, TimeUnit.SECONDS)).isEqualTo("cancelled");
    }

    @Test
    @DisplayName("A UI that stops releases the asker instead of parking it forever")
    void aStoppedUiReleasesTheAsker() throws Exception {
        PromptHandshake handshake = new PromptHandshake();
        BooleanRunning running = new BooleanRunning(true);
        BlockingQueue<String> got = new ArrayBlockingQueue<>(1);

        new Thread(() -> got.add(handshake.ask("q", running, null))).start();
        until("the prompt to go up", handshake::isActive);

        running.stop();

        assertThat(got.poll(5, TimeUnit.SECONDS))
                .as("a prompt that can no longer be answered must not hold its thread")
                .isEmpty();
    }

    @Test
    @DisplayName("A UI that stops also releases an asker still queued for the line")
    void aStoppedUiReleasesAQueuedAsker() throws Exception {
        PromptHandshake handshake = new PromptHandshake();
        BooleanRunning running = new BooleanRunning(true);
        BlockingQueue<String> queued = new ArrayBlockingQueue<>(1);

        // Holder takes the line and never answers.
        new Thread(() -> handshake.ask("held", running, null)).start();
        until("the holder to take the line", handshake::isActive);

        new Thread(() -> queued.add(handshake.ask("waiting", running, null))).start();
        // The waiter is behind the holder, so it must not have displaced the prompt that is up.
        assertThat(handshake.text()).isEqualTo("held");

        running.stop();

        assertThat(queued.poll(5, TimeUnit.SECONDS))
                .as("waiting for the line must not outlive the UI either")
                .isEmpty();
    }

    @Test
    @DisplayName("Submitting or cancelling with nothing waiting is a no-op, not a lost answer")
    void deliveringWithNoPromptUpIsHarmless() {
        PromptHandshake handshake = new PromptHandshake();

        assertThat(handshake.submit("stray")).isFalse();
        assertThat(handshake.cancel()).isFalse();
        assertThat(handshake.isActive()).isFalse();
    }
}
