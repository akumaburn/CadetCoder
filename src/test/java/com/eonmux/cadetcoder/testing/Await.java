package com.eonmux.cadetcoder.testing;

import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.fail;

/**
 * Waits for a condition instead of guessing how long it will take.
 *
 * <h2>Why a fixed sleep is the wrong tool</h2>
 *
 * <p>{@code Thread.sleep(500)} to let an async writer flush is simultaneously too long and too short.
 * Too long, because the work usually finishes in a millisecond and the test pays the full 500 every
 * run; too short, because on a loaded machine it sometimes does not, and the test fails for a reason
 * that has nothing to do with the code. Polling for the actual condition removes both: it returns as
 * soon as the thing is true, and it only gives up after a bound generous enough that reaching it
 * means something is genuinely wrong.</p>
 */
public final class Await {

    /** How long to keep polling before treating the condition as genuinely unmet. */
    private static final long DEFAULT_TIMEOUT_MILLIS = 5_000;

    /** Gap between polls: short enough to be invisible, long enough not to spin a core. */
    private static final long POLL_MILLIS = 5;

    private Await() {
    }

    /**
     * Blocks until {@code condition} holds.
     *
     * @param what      what is being waited for, used in the failure message
     * @param condition the thing that should become true
     */
    public static void until(String what, BooleanSupplier condition) {
        until(what, condition, DEFAULT_TIMEOUT_MILLIS);
    }

    /**
     * Blocks until {@code condition} holds, or fails the test.
     *
     * @param what          what is being waited for, used in the failure message
     * @param condition     the thing that should become true
     * @param timeoutMillis how long to keep trying
     */
    public static void until(String what, BooleanSupplier condition, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep();
        }
        if (condition.getAsBoolean()) {
            return;
        }
        fail("timed out after " + timeoutMillis + "ms waiting for: " + what);
    }

    /**
     * Blocks for a short, bounded window to show that something does NOT happen.
     *
     * <p>Proving a negative needs a wait, but it does not need a long one: the point is that the
     * thing has not happened by the time anything else in the test could have observed it.</p>
     *
     * @param what      what must not happen, used in the failure message
     * @param condition the thing that must stay false
     */
    public static void staysFalse(String what, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 150;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                fail("happened but should not have: " + what);
            }
            sleep();
        }
    }

    /**
     * A short bounded pause where there is no condition to poll.
     *
     * <p>Used where a test asserts that something did NOT reach an async writer: there is nothing to
     * wait FOR, so the only option is to give the writer a chance and then look. Kept small, because
     * its length is a guess and a guess should cost as little as possible.</p>
     */
    public static void settle() {
        long deadline = System.currentTimeMillis() + 60;
        while (System.currentTimeMillis() < deadline) {
            sleep();
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(POLL_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting", e);
        }
    }
}
