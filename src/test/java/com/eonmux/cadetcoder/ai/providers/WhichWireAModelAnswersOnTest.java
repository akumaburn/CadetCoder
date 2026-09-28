package com.eonmux.cadetcoder.ai.providers;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Choosing between a gateway's two endpoints.
 *
 * <h2>The defect</h2>
 *
 * <p>A connector named one protocol and every model it offered was sent on it. That holds for a
 * provider serving its own models and breaks for a gateway serving several vendors: Command Code
 * requires the Anthropic Messages shape for its Claude models and refuses them on
 * {@code /chat/completions}, refusing everything else on {@code /messages} in the same breath. The
 * cost was paid at the first completion, as a 400 naming a model the tool had just listed as
 * available.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>That a Claude id goes to the Anthropic endpoint and everything else to the OpenAI one, across
 * the families actually behind this gateway; that the prefix is matched rather than a fixed list of
 * ids, since the list changes without this build being rebuilt; that a model id naming Claude
 * somewhere other than the front is not mistaken for one, because the gateway's vendor-prefixed ids
 * are its own namespace; and that the single-protocol case every other connector relies on is
 * unaffected.</p>
 */
public class WhichWireAModelAnswersOnTest {

    private final CommandCodeWire wire = new CommandCodeWire();

    @Test
    public void aClaudeModelGoesToTheAnthropicEndpoint() {
        for (String model : new String[] {"claude-sonnet-5", "claude-opus-5", "claude-fable-5-1",
                                          "claude-haiku-4-5-20251001", "claude-sonnet-4-6"}) {
            assertThat(wire.forModel(model))
                    .as("%s is served only from /provider/v1/messages", model)
                    .isEqualTo(ConnectorProtocol.ANTHROPIC_MESSAGES);
        }
    }

    @Test
    public void everythingElseGoesToTheOpenAiEndpoint() {
        for (String model : new String[] {"gpt-5.6-sol", "deepseek/deepseek-v4-flash",
                                          "moonshotai/Kimi-K3", "zai-org/GLM-5.3",
                                          "Qwen/Qwen3.8-Max", "xai/grok-4.6",
                                          "google/gemini-3.8-flash"}) {
            assertThat(wire.forModel(model))
                    .as("%s is served only from /provider/v1/chat/completions", model)
                    .isEqualTo(ConnectorProtocol.OPENAI_CHAT);
        }
    }

    @Test
    public void aModelIdTypedByHandIsStillRecognised() {
        // The one place an id is not copied from the listing is 'models use', typed by a person.
        assertThat(wire.forModel("Claude-Sonnet-5")).isEqualTo(ConnectorProtocol.ANTHROPIC_MESSAGES);
    }

    @Test
    public void naminganthropicLaterInAnIdDoesNotMakeItAnAnthropicModel() {
        // The vendor-prefixed ids are the gateway's own namespace, and a third party is free to
        // publish a model whose name mentions Claude.
        assertThat(wire.forModel("someone/not-claude-at-all")).isEqualTo(ConnectorProtocol.OPENAI_CHAT);
        assertThat(CommandCodeWire.isAnthropic("openrouter/anthropic-claude-clone")).isFalse();
    }

    @Test
    public void anAbsentModelIdIsNotAnAnthropicModel() {
        // Asked when a caller wants to know what the connector does by default.
        assertThat(wire.forModel(null)).isEqualTo(ConnectorProtocol.OPENAI_CHAT);
        assertThat(wire.forModel("")).isEqualTo(ConnectorProtocol.OPENAI_CHAT);
    }

    @Test
    public void aProviderThatSpeaksOneProtocolStillSpeaksItForEverything() {
        ModelWire single = ModelWire.always(ConnectorProtocol.ANTHROPIC_MESSAGES);

        assertThat(single.forModel("claude-sonnet-5")).isEqualTo(ConnectorProtocol.ANTHROPIC_MESSAGES);
        assertThat(single.forModel("anything-at-all")).isEqualTo(ConnectorProtocol.ANTHROPIC_MESSAGES);
        assertThat(single.forModel(null)).isEqualTo(ConnectorProtocol.ANTHROPIC_MESSAGES);
    }

    @Test
    public void everyOtherConnectorIsUnaffectedByTheGatewayCase() {
        // The wire is an exception for one provider; the rest must answer their declared protocol
        // for every model, which is what they did before this existed.
        ProviderRegistry registry = ProviderRegistry.getInstance();

        assertThat(registry.get("anthropic").protocolFor("claude-sonnet-5"))
                .isEqualTo(ConnectorProtocol.ANTHROPIC_MESSAGES);
        assertThat(registry.get("openai").protocolFor("claude-sonnet-5"))
                .isEqualTo(ConnectorProtocol.OPENAI_CHAT);
        assertThat(registry.get("google").protocolFor("gemini-3.8-flash"))
                .isEqualTo(ConnectorProtocol.GOOGLE_GENERATIVE_AI);
        assertThat(registry.get("amazon-bedrock").protocolFor("anything"))
                .isEqualTo(ConnectorProtocol.BEDROCK_CONVERSE);
    }

    @Test
    public void thewireSaysWhichProtocolsItChoosesBetween() {
        // Asked by anything describing the connector. Working it out by probing forModel would be
        // the same decision written a second time, in a class that cannot be kept up to date.
        assertThat(wire.protocols())
                .containsExactly(ConnectorProtocol.OPENAI_CHAT, ConnectorProtocol.ANTHROPIC_MESSAGES);
    }

    @Test
    public void asingleProtocolWireSaysSo() {
        assertThat(ModelWire.always(ConnectorProtocol.GOOGLE_GENERATIVE_AI).protocols())
                .containsExactly(ConnectorProtocol.GOOGLE_GENERATIVE_AI);
    }
}
