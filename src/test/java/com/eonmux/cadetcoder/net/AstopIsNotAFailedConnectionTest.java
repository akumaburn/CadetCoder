package com.eonmux.cadetcoder.net;

import com.eonmux.cadetcoder.InterruptSignal;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

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
 * <p>A stop that is reported as a transport failure is retryable, and the backend retries a
 * retryable failure until it clears. So a stop has to be its own kind, which nothing retries.</p>
 */
public class AstopIsNotAFailedConnectionTest {

    @Before
    public void setUp() {
        InterruptSignal.clear();
        Thread.interrupted();
    }

    @After
    public void tearDown() {
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
}
