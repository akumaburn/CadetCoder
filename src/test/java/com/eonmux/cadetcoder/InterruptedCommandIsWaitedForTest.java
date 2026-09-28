package com.eonmux.cadetcoder;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Command interrupted by user request" has to be true when it is printed.
 *
 * <p>{@code executeInterruptibleCommand} runs the command on its own thread and watches for the
 * interrupt flag. On seeing it, it called {@code Thread.interrupt()}, printed that the command had
 * been interrupted and returned 130 -- without waiting for the thread to notice. Interrupting a
 * thread only sets a flag; it does not stop {@code Files.walkFileTree}, a {@code readLine} on a
 * process pipe, or a provider call in flight. So the shell reported the command finished, released
 * the prompt, and the command carried on writing output and mutating the singleton it belongs to
 * underneath the next one.</p>
 *
 * <p>The wait is bounded: a command that will not stop cannot be killed from inside the JVM, so
 * after the grace period the honest thing is to say it is still running rather than to hang.</p>
 */
public class InterruptedCommandIsWaitedForTest {

    @Test
    public void aCommandThatStopsIsGoneBeforeTheWaitReturns() throws Exception {
        Thread stopsPromptly = new Thread(() -> {
            try {
                Thread.sleep(60);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        stopsPromptly.setDaemon(true);
        stopsPromptly.start();

        assertThat(CommandRegistry.awaitStop(stopsPromptly, 5000))
                .as("the command stopped well inside the grace period")
                .isTrue();
        assertThat(stopsPromptly.isAlive())
                .as("saying a command stopped means its thread is gone")
                .isFalse();
    }

    @Test
    public void aCommandThatIgnoresTheInterruptIsNotReportedAsStopped() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Thread ignoresIt = new Thread(() -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        ignoresIt.setDaemon(true);
        ignoresIt.start();

        try {
            assertThat(CommandRegistry.awaitStop(ignoresIt, 150))
                    .as("it is still running, so the shell must not be told otherwise")
                    .isFalse();
        } finally {
            release.countDown();
            ignoresIt.join(5000);
        }
    }

    /** A command that will not stop must not hang the shell either. */
    @Test
    public void theWaitGivesUpWhenTheGraceIsSpent() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Thread ignoresIt = new Thread(() -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        ignoresIt.setDaemon(true);
        ignoresIt.start();

        long started = System.currentTimeMillis();
        try {
            CommandRegistry.awaitStop(ignoresIt, 150);
            assertThat(System.currentTimeMillis() - started)
                    .as("the wait is bounded by the grace period it was given")
                    .isLessThan(5000L);
        } finally {
            release.countDown();
            ignoresIt.join(5000);
        }
    }
}
