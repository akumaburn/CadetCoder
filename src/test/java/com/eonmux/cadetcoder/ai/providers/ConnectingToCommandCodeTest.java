package com.eonmux.cadetcoder.ai.providers;

import com.eonmux.cadetcoder.net.AnthropicBackend;
import com.eonmux.cadetcoder.net.OpenAICompatibleBackend;
import com.eonmux.cadetcoder.security.SecretRedactor;

import org.junit.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Command Code connector, and the four facts about it that have to agree with the gateway.
 *
 * <h2>Why this is tested</h2>
 *
 * <p>A connector is four pieces of data -- an endpoint, an auth scheme, a wire protocol and the
 * environment variables that supply the key -- and every one of them is a claim about a server this
 * build cannot reach from a test. Getting any single one wrong produces the same symptom, an
 * authentication failure or a 404 at the first completion, with nothing to say which of the four
 * was at fault. They were verified once against the live API; this is what keeps them verified.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>That the connector is registered at all, since the registry is what {@code login} and
 * {@code models} enumerate; that it points at the Provider API root rather than the marketing site
 * or the bare API host, both of which answer and neither of which completes anything; that it is
 * read as OpenAI Chat Completions, the wire every model behind the gateway answers on; that it
 * carries the key as a bearer token, and reads the two environment variables the Command Code
 * documentation names; and that a key of its shape is redacted, because a connector whose key shape
 * the redactor does not know is a connector whose key gets written to the log in full.</p>
 */
public class ConnectingToCommandCodeTest {

    private final ProviderConnector connector = ProviderRegistry.getInstance().get("commandcode");

    @Test
    public void theConnectorIsRegistered() {
        assertThat(ProviderRegistry.getInstance().has("commandcode")).isTrue();
        assertThat(connector.getDisplayName()).isEqualTo("Command Code");
    }

    @Test
    public void itPointsAtTheProviderApiRatherThanTheSiteOrTheApiHost() {
        // https://api.commandcode.ai answers 200 with a pointer to the docs, so a base URL missing
        // the /provider/v1 suffix fails at the first completion and not before.
        assertThat(connector.getDefaultBaseUrl()).isEqualTo("https://api.commandcode.ai/provider/v1");
    }

    @Test
    public void itSpeaksOpenAiChatCompletionsForAllButTheClaudeModels() {
        assertThat(connector.getProtocol()).isEqualTo(ConnectorProtocol.OPENAI_CHAT);
        assertThat(connector.createBackend("deepseek/deepseek-v4-flash", "key", Map.of()))
                .isInstanceOf(OpenAICompatibleBackend.class);
        assertThat(connector.createBackend("gpt-5.5", "key", Map.of()))
                .isInstanceOf(OpenAICompatibleBackend.class);
    }

    /**
     * The defect this was written for: the connector was registered as OpenAI-only, and the gateway
     * answered the first completion with <em>"Model 'claude-sonnet-5' must be called via
     * /provider/v1/messages"</em> -- a 400 naming a model the tool had just offered from its own
     * list.
     */
    @Test
    public void aClaudeModelIsCalledOnTheAnthropicWire() {
        assertThat(connector.protocolFor("claude-sonnet-5"))
                .isEqualTo(ConnectorProtocol.ANTHROPIC_MESSAGES);
        assertThat(connector.createBackend("claude-opus-5", "key", Map.of()))
                .isInstanceOf(AnthropicBackend.class);
    }

    @Test
    public void itCarriesTheKeyAsABearerToken() {
        assertThat(connector.getAuthScheme()).isEqualTo(AuthScheme.BEARER);
    }

    @Test
    public void itReadsTheEnvironmentVariablesTheServiceDocuments() {
        assertThat(connector.getEnv()).containsExactly("COMMAND_CODE_API_KEY", "CMD_API_KEY");
    }

    @Test
    public void aBackendIsUsableOnceAKeyIsSuppliedAndNotBefore() {
        assertThat(connector.createBackend("claude-sonnet-5", "", Map.of()).isAvailable()).isFalse();
        assertThat(connector.createBackend("claude-sonnet-5", "key", Map.of()).isAvailable()).isTrue();
    }

    @Test
    public void aKeyOfItsShapeNeverReachesALogLine() {
        // Structurally a Command Code key -- "user_" and 88 base62 characters -- and invented.
        String key = "user_9Zz88Yy77Xx66Ww55Vv44Uu33Tt22Ss11Rr00Qq99Pp88Oo77Nn66Mm55Ll44Kk33Jj22Ii11Hh00Gg99Ff";

        assertThat(SecretRedactor.redact("POST failed for key " + key)).doesNotContain(key);
    }

    @Test
    public void theConnectorTableSaysBothWiresNotJustTheDefault() {
        // Someone reading the table to find out what a provider speaks would otherwise be told
        // every Command Code model answers on Chat Completions -- the belief the gateway refuses
        // at the first Claude request.
        assertThat(connector.protocolLabel()).isEqualTo("OPENAI_CHAT/ANTHROPIC_MESSAGES");
    }

    @Test
    public void aconnectorWithOneWireIsStillDescribedByItsName() {
        assertThat(ProviderRegistry.getInstance().get("openai").protocolLabel())
                .isEqualTo("OPENAI_CHAT");
        assertThat(ProviderRegistry.getInstance().get("anthropic").protocolLabel())
                .isEqualTo("ANTHROPIC_MESSAGES");
    }
}
