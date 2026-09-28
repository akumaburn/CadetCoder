package com.eonmux.cadetcoder.net;

import com.eonmux.cadetcoder.ai.PromptData;
import com.eonmux.cadetcoder.ai.providers.ProviderConnector;
import com.eonmux.cadetcoder.ai.providers.ProviderRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Who a failure is attributed to when one vendor's wire is spoken to a different provider.
 *
 * <h2>The defect</h2>
 *
 * <p>{@code AnthropicBackend} named itself {@code anthropic} in every message it produced and in
 * every prompt-cache decision it made. That was true for as long as only the Anthropic connector
 * built one. A gateway reselling Claude has to be called in the Anthropic Messages shape while
 * being an entirely different provider, and the moment one did, two things went wrong at once: a
 * refusal from the gateway told the user to check the API key of a provider they may never have
 * configured -- {@code cadet login anthropic}, for a key that is not the one in use -- and a cache
 * rejection from the gateway latched the real Anthropic connector into sending no cache
 * breakpoints, through a policy keyed on the same borrowed name.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>That a backend built for a connector reports that connector; that the Anthropic connector is
 * unaffected and still reports itself; and that a backend built without one named still says
 * {@code anthropic}, which is what every existing caller expects.</p>
 */
public class AGatewayIsNotTheVendorItResellsTest {

    /** Refuses at once, so the failure comes from the transport rather than from a timeout. */
    private static final String UNREACHABLE = "http://127.0.0.1:1/v1";

    @Before
    public void doNotSpendTheTestBudgetOnBackoff() {
        System.setProperty(RetryPolicy.MAX_ATTEMPTS_PROPERTY, "1");
        System.setProperty(RetryPolicy.BASE_DELAY_MS_PROPERTY, "1");
        System.setProperty(RetryPolicy.MAX_DELAY_MS_PROPERTY, "1");
    }

    @After
    public void restoreTheRetryPolicy() {
        System.clearProperty(RetryPolicy.MAX_ATTEMPTS_PROPERTY);
        System.clearProperty(RetryPolicy.BASE_DELAY_MS_PROPERTY);
        System.clearProperty(RetryPolicy.MAX_DELAY_MS_PROPERTY);
    }

    private static String providerBlamedBy(LLMBackend backend) {
        Throwable thrown = catchThrowable(
                () -> backend.complete(new PromptData("sys", "hi"), new HashMap<>()));

        assertThat(thrown).isInstanceOf(LLMException.class);
        return ((LLMException) thrown).getProvider();
    }

    @Test
    public void abackendBuiltForAGatewayNamesTheGateway() {
        ProviderConnector commandcode = ProviderRegistry.getInstance().get("commandcode");

        LLMBackend backend = commandcode.createBackend("claude-haiku-4-5-20251001", "key",
                                                       Map.of("baseURL", UNREACHABLE));

        assertThat(backend).isInstanceOf(AnthropicBackend.class);
        assertThat(providerBlamedBy(backend)).isEqualTo("commandcode");
    }

    @Test
    public void theAnthropicConnectorStillNamesItself() {
        ProviderConnector anthropic = ProviderRegistry.getInstance().get("anthropic");

        LLMBackend backend = anthropic.createBackend("claude-sonnet-4-5", "key",
                                                    Map.of("baseURL", UNREACHABLE));

        assertThat(providerBlamedBy(backend)).isEqualTo("anthropic");
    }

    @Test
    public void abackendBuiltWithNoConnectorNamedIsStillAnthropic() {
        // The three-argument constructor every existing caller uses.
        assertThat(providerBlamedBy(new AnthropicBackend("claude-sonnet-4-5", UNREACHABLE, "key")))
                .isEqualTo("anthropic");
    }

    @Test
    public void ablankConnectorNameIsNotUsedAsOne() {
        // A provider reported as '' would name nothing at all in the message that has to explain it.
        assertThat(providerBlamedBy(new AnthropicBackend("m", UNREACHABLE, "key", "  ")))
                .isEqualTo("anthropic");
        assertThat(providerBlamedBy(new AnthropicBackend("m", UNREACHABLE, "key", null)))
                .isEqualTo("anthropic");
    }
}
