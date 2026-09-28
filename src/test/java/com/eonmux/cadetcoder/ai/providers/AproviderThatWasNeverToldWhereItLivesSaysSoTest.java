package com.eonmux.cadetcoder.ai.providers;

import com.eonmux.cadetcoder.net.LLMBackend;
import org.junit.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A connector with no endpoint is unavailable, rather than available at a nonsense one.
 *
 * <p><b>The defect</b>: both Cloudflare connectors built their base URL by concatenating options
 * that default to the empty string, so with nothing configured they produced
 * {@code https://gateway.ai.cloudflare.com/v1///compat} and
 * {@code https://api.cloudflare.com/client/v4/accounts//ai/v1}. Neither is blank, so the backend
 * reported itself available, the run announced "Using connector: cloudflare-ai-gateway", and every
 * request 404'd against a URL with a hole in it. {@code azure} in the same method has always failed
 * closed -- it leaves the base null unless a {@code resourceName} was given -- so the two halves of
 * one method disagreed about what an unconfigured provider is.</p>
 */
public class AproviderThatWasNeverToldWhereItLivesSaysSoTest {

    private static LLMBackend backendFor(String providerId, Map<String, String> options) {
        ProviderConnector connector = ProviderRegistry.getInstance().get(providerId);
        assertThat(connector).as(providerId + " is a registered connector").isNotNull();
        return connector.createBackend("some-model", "a-key", options);
    }

    /** True only when the environment is not already carrying the id the option would supply. */
    private static boolean environmentIsSilentAbout(String variable) {
        String value = System.getenv(variable);
        return value == null || value.isBlank();
    }

    @Test
    public void anAiGatewayWithNoAccountOrGatewayIsNotAvailable() {
        if (!environmentIsSilentAbout("CLOUDFLARE_ACCOUNT_ID")
            || !environmentIsSilentAbout("CLOUDFLARE_GATEWAY_ID")) {
            return; // This machine really is configured for Cloudflare; nothing to assert.
        }
        assertThat(backendFor("cloudflare-ai-gateway", Map.of()).isAvailable())
                .as("an unconfigured gateway must not claim to be reachable")
                .isFalse();
    }

    @Test
    public void anAiGatewayGivenOnlyHalfOfWhatItNeedsIsNotAvailable() {
        if (!environmentIsSilentAbout("CLOUDFLARE_GATEWAY_ID")) {
            return;
        }
        // The account without the gateway is the case that produced ".../v1/acct//compat".
        assertThat(backendFor("cloudflare-ai-gateway", Map.of("accountId", "acct")).isAvailable())
                .isFalse();
    }

    @Test
    public void workersAiWithNoAccountIsNotAvailable() {
        if (!environmentIsSilentAbout("CLOUDFLARE_ACCOUNT_ID")) {
            return;
        }
        assertThat(backendFor("cloudflare-workers-ai", Map.of()).isAvailable()).isFalse();
    }

    @Test
    public void aFullyConfiguredGatewayIsAvailable() {
        assertThat(backendFor("cloudflare-ai-gateway",
                              Map.of("accountId", "acct", "gatewayId", "gw")).isAvailable())
                .as("configured, it must still work -- failing closed must not fail shut")
                .isTrue();
    }

    @Test
    public void workersAiWithAnAccountIsAvailable() {
        assertThat(backendFor("cloudflare-workers-ai", Map.of("accountId", "acct")).isAvailable())
                .isTrue();
    }

    @Test
    public void anExplicitBaseUrlIsEnoughOnItsOwn() {
        // The connector-level escape hatch: someone pointing at a proxy never supplies the ids.
        assertThat(backendFor("cloudflare-ai-gateway",
                              Map.of("baseURL", "https://proxy.example/v1")).isAvailable())
                .isTrue();
    }

    @Test
    public void azureStillFailsClosedWithoutAResourceName() {
        // The behaviour the Cloudflare branches were brought into line with.
        assertThat(backendFor("azure", Map.of()).isAvailable()).isFalse();
        assertThat(backendFor("azure", Map.of("resourceName", "contoso")).isAvailable()).isTrue();
    }
}
