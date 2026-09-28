package com.eonmux.cadetcoder.net;

import com.eonmux.cadetcoder.InterruptSignal;
import com.eonmux.cadetcoder.ai.ManualRetry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Being stopped is reported as being stopped.
 *
 * <p><b>The defect</b>: pressing the interrupt key during a request produced
 * {@code "Cannot reach https://api.commandcode.ai/provider/v1/chat/completions for provider
 * 'commandcode' model 'deepseek/deepseek-v4-flash': InterruptedException. Check the endpoint is
 * running and reachable"}, repeated until the process was killed. Nothing was wrong with the
 * endpoint.</p>
 *
 * <p>The chain had four links. {@code AbstractLLMBackend} caught the {@link InterruptedException}
 * and threw an {@code LLMTransportException}, whose message is the one above. That kind is
 * retryable, so {@code AIManager} offered the user another attempt. Offering it cancels nothing --
 * but asking to stop cancels a waiting prompt, and a cancelled prompt answers with an empty line,
 * which is exactly what pressing Enter sends, and {@code ManualRetry} reads an empty line as
 * "try again". The thread's interrupt flag was still set, so the retry failed instantly and the
 * whole thing went round again as fast as the terminal could print it.</p>
 */
public class AstopIsNotAFailedConnectionTest {

    private String savedManualRetry;

    @Before
    public void setUp() {
        savedManualRetry = System.getProperty(ManualRetry.PROPERTY);
        InterruptSignal.clear();
        Thread.interrupted();
    }

    @After
    public void tearDown() {
        if (savedManualRetry == null) {
            System.clearProperty(ManualRetry.PROPERTY);
        } else {
            System.setProperty(ManualRetry.PROPERTY, savedManualRetry);
        }
        InterruptSignal.clear();
        Thread.interrupted();
    }

    @Test
    public void aStopHasItsOwnKind() {
        LLMException stopped = LLMException.stopped("commandcode", "deepseek/deepseek-v4-flash",
                "https://api.commandcode.ai/provider/v1/chat/completions",
                new InterruptedException("stopped"));

        assertThat(stopped.getKind()).isEqualTo(LLMException.Kind.STOPPED);
    }

    @Test
    public void aStopIsNeverRetried() {
        // The whole loop turned on this. Retrying something the user asked to end cannot succeed,
        // because the flag that ended it is still set.
        assertThat(LLMException.Kind.STOPPED.isRetryable()).isFalse();
    }

    @Test
    public void aStopDoesNotAccuseTheEndpoint() {
        LLMException stopped = LLMException.stopped("commandcode", "deepseek/deepseek-v4-flash",
                "https://api.commandcode.ai/provider/v1/chat/completions",
                new InterruptedException("stopped"));

        assertThat(stopped.getMessage())
                .contains("stopped")
                .doesNotContain("Cannot reach")
                .doesNotContain("reachable");
    }

    @Test
    public void aStopStillSaysWhichModelItWas() {
        assertThat(LLMException.stopped("commandcode", "deepseek/deepseek-v4-flash", null, null)
                .getMessage())
                .contains("commandcode")
                .contains("deepseek/deepseek-v4-flash");
    }

    @Test
    public void theRetryOfferIsNotMadeAfterAStopWasAsked() {
        System.setProperty(ManualRetry.PROPERTY, "true");
        InterruptSignal.request();

        // A retryable failure, so only the stop can be what declines it.
        boolean offered = ManualRetry.offer(new LLMRateLimitException(
                "commandcode", "deepseek/deepseek-v4-flash", "https://example.invalid", 429, null,
                Duration.ofSeconds(1)));

        assertThat(offered)
                .as("the question would go to somebody who has just said they want this to end")
                .isFalse();
    }

    @Test
    public void theRetryOfferIsNotMadeForAStop() {
        System.setProperty(ManualRetry.PROPERTY, "true");

        boolean offered = ManualRetry.offer(LLMException.stopped(
                "commandcode", "deepseek/deepseek-v4-flash", "https://example.invalid", null));

        assertThat(offered).isFalse();
    }

    @Test
    public void anInterruptedThreadAloneDeclinesTheOffer() {
        // The shared signal is not the only route. A request interrupted outside the registry's
        // monitor gets nothing but the thread's own flag.
        System.setProperty(ManualRetry.PROPERTY, "true");
        Thread.currentThread().interrupt();
        try {
            boolean offered = ManualRetry.offer(new LLMRateLimitException(
                    "commandcode", "deepseek/deepseek-v4-flash", "https://example.invalid", 429,
                    null, Duration.ofSeconds(1)));

            assertThat(offered).isFalse();
        } finally {
            Thread.interrupted();
        }
    }
}
