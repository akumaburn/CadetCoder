package com.eonmux.cadetcoder.commands;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/**
 * The shell's one input line, handed to one asker at a time.
 *
 * <h2>Why this is a type rather than three fields</h2>
 *
 * <p>It was three fields on {@link InteractiveShell} — whether a prompt is up, its text, and the
 * queue the answer goes to — and nothing tied them together or said that they describe exactly one
 * question. Two threads asking at once simply overwrote each other: the second installed its queue
 * over the first's, the UI delivered the typed answer to whichever was installed at that instant,
 * and the loser polled a queue nobody would ever fill until the runner stopped. Its cleanup then
 * correctly declined to clear the winner's slot, so nothing reported a problem; the thread just
 * never returned.</p>
 *
 * <p>That was reachable in ordinary use. Worker runs execute several agent loops at once, and any
 * of them could ask. The symptom was an answered prompt that appeared to be ignored and immediately
 * asked again — one asker released, the next one's prompt going straight back up.</p>
 *
 * <p>Callers now queue for the line instead of taking it. Waiting is timed rather than indefinite so
 * a runner that stops while a thread is queued releases it, rather than parking it behind a question
 * that will never be answered.</p>
 */
final class PromptHandshake {

    /** How long a queued asker waits before re-checking that the UI is still running. */
    private static final long LIVENESS_POLL_MILLIS = 100;

    /**
     * Distinguishes a cancelled prompt from an empty answer.
     *
     * <p>Compared by identity, so a user who literally types this text is still treated as having
     * answered rather than cancelled.</p>
     */
    private static final String CANCELLED = new String("cancelled");

    /** Fair, so a waiting asker is served in arrival order rather than starved by later ones. */
    private final ReentrantLock line = new ReentrantLock(true);

    private volatile boolean               active;
    private volatile String                text;
    private volatile BlockingQueue<String> response;

    /** @return whether a question is currently on the input line */
    boolean isActive() {
        return active;
    }

    /** @return the question currently on the input line, or {@code null} when there is none */
    String text() {
        return text;
    }

    /**
     * Puts a question on the input line and waits for the answer.
     *
     * @param prompt    what to ask
     * @param uiRunning whether the UI can still deliver an answer; polled while waiting
     * @param onChange  run whenever the display needs repainting; may be {@code null}
     * @return the answer, or {@code ""} when the prompt was cancelled or the UI stopped
     */
    String ask(String prompt, BooleanSupplier uiRunning, Runnable onChange) {
        if (!acquire(uiRunning)) {
            return "";
        }
        BlockingQueue<String> queue = new ArrayBlockingQueue<>(1);
        response = queue;
        text     = prompt;
        active   = true;
        repaint(onChange);
        try {
            // Polled rather than blocked outright, so a quit that stops the runner releases this
            // thread instead of leaving it waiting on an answer that can no longer be typed.
            while (true) {
                String answer = queue.poll(LIVENESS_POLL_MILLIS, TimeUnit.MILLISECONDS);
                if (answer != null) {
                    //noinspection StringEquality — identity is the point; see CANCELLED.
                    return answer == CANCELLED ? "" : answer;
                }
                if (!uiRunning.getAsBoolean()) {
                    return "";
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } finally {
            active = false;
            text   = null;
            // Still guarded: a cancel from the UI thread may already have cleared the slot.
            if (response == queue) {
                response = null;
            }
            repaint(onChange);
            line.unlock();
        }
    }

    /**
     * Delivers a typed answer to whoever is waiting.
     *
     * @param answer what the user typed
     * @return whether a prompt was waiting for it
     */
    boolean submit(String answer) {
        return deliver(answer == null ? "" : answer);
    }

    /**
     * Cancels the prompt that is up, releasing its asker with an empty answer.
     *
     * @return whether a prompt was cancelled
     */
    boolean cancel() {
        return deliver(CANCELLED);
    }

    private boolean deliver(String value) {
        if (!active) {
            return false;
        }
        active = false;
        BlockingQueue<String> queue = response;
        if (queue == null) {
            return false;
        }
        // The answer is the return value: a queue that would not take it has not answered
        // anything, and telling the caller otherwise leaves the asker waiting for a reply that
        // was reported as delivered.
        return queue.offer(value);
    }

    /** @return whether the line was acquired; {@code false} when the UI stopped while waiting */
    private boolean acquire(BooleanSupplier uiRunning) {
        try {
            while (!line.tryLock(LIVENESS_POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                if (!uiRunning.getAsBoolean()) {
                    return false;
                }
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void repaint(Runnable onChange) {
        if (onChange != null) {
            onChange.run();
        }
    }
}
