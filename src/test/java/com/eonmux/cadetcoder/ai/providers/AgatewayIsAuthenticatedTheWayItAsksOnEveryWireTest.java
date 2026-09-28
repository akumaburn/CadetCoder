package com.eonmux.cadetcoder.ai.providers;

import com.eonmux.cadetcoder.net.LLMBackend;
import org.junit.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A connector's declared auth scheme is honoured on whichever wire its model lands on.
 *
 * <p><b>The defect</b>: {@code AnthropicBackend} hardcoded {@code x-api-key}. That is Anthropic's
 * own scheme and right for the {@code anthropic} connector, but this wire is not only Anthropic's:
 * {@code ModelWire} exists so a gateway can speak the Messages shape for the Claude models it
 * resells. Command Code declares {@link AuthScheme#BEARER} and routes {@code claude-*} to this wire
 * (see {@code CommandCodeWire}), so its Claude models were sent the credential under a header the
 * gateway does not read -- while every other model on the same connector, going out over the
 * OpenAI wire, authenticated correctly. The connector knew the answer the whole time; this wire had
 * simply never asked.</p>
 *
 * <p>The class comment on {@link AuthHeaders} states the rule this restores: one place decides how
 * a credential is carried, because "a second copy is how a provider comes to be authenticated
 * correctly for one request and not for the other".</p>
 */
public class AgatewayIsAuthenticatedTheWayItAsksOnEveryWireTest {

    private static ProviderConnector connector(String id) {
        ProviderConnector connector = ProviderRegistry.getInstance().get(id);
        assertThat(connector).as(id + " is registered").isNotNull();
        return connector;
    }

    @Test
    public void commandCodeRoutesItsClaudeModelsToTheAnthropicWire() {
        // The premise of the defect: without this routing the hardcoded header was harmless.
        assertThat(CommandCodeWire.isAnthropic("claude-sonnet-4")).isTrue();
        assertThat(CommandCodeWire.isAnthropic("gpt-4o")).isFalse();
        assertThat(new CommandCodeWire().forModel("claude-sonnet-4"))
                .isEqualTo(ConnectorProtocol.ANTHROPIC_MESSAGES);
    }

    @Test
    public void commandCodeStillDeclaresBearer() {
        assertThat(connector("commandcode").getAuthScheme()).isEqualTo(AuthScheme.BEARER);
    }

    @Test
    public void aBearerGatewayOnTheAnthropicWireCarriesItsCredentialAsBearer() {
        LLMBackend backend = connector("commandcode")
                .createBackend("claude-sonnet-4", "a-key", Map.of());

        // It is the Messages wire, and it is authenticated the way the connector asked.
        assertThat(backend).isInstanceOf(com.eonmux.cadetcoder.net.AnthropicBackend.class);
        assertThat(authHeaderOf(backend)).isEqualTo("Authorization");
    }

    @Test
    public void anthropicItselfIsUnchanged() {
        // The default is still Anthropic's own scheme, so fixing the gateway must not move this.
        assertThat(connector("anthropic").getAuthScheme()).isEqualTo(AuthScheme.X_API_KEY);

        LLMBackend backend = connector("anthropic")
                .createBackend("claude-sonnet-4", "a-key", Map.of());
        assertThat(authHeaderOf(backend)).isEqualTo("x-api-key");
    }

    /**
     * The header name a backend would put its credential under, read from the scheme it holds.
     *
     * @param backend the backend to inspect
     * @return the header name
     */
    private static String authHeaderOf(LLMBackend backend) {
        try {
            java.lang.reflect.Field field = backend.getClass().getDeclaredField("authScheme");
            field.setAccessible(true);
            AuthScheme scheme = (AuthScheme) field.get(backend);
            return switch (scheme) {
                case BEARER -> "Authorization";
                case X_API_KEY -> "x-api-key";
                case API_KEY_HEADER -> "api-key";
                case X_GOOG_API_KEY -> "x-goog-api-key";
                default -> "(none)";
            };
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("the backend does not carry an auth scheme at all", e);
        }
    }
}
