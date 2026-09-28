package com.eonmux.cadetcoder.ai.providers;

import com.eonmux.cadetcoder.net.AmazonBedrockBackend;
import com.eonmux.cadetcoder.net.AnthropicBackend;
import com.eonmux.cadetcoder.net.GoogleGenerativeAIBackend;
import com.eonmux.cadetcoder.net.LLMBackend;
import com.eonmux.cadetcoder.net.OpenAICompatibleBackend;
import org.junit.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class ProviderRegistryTest {

    private final ProviderRegistry registry = ProviderRegistry.getInstance();

    @Test
    public void registersTheOpencodeConnectorSet() {
        assertThat(registry.has("anthropic")).isTrue();
        assertThat(registry.has("openai")).isTrue();
        assertThat(registry.has("openrouter")).isTrue();
        assertThat(registry.has("google")).isTrue();
        assertThat(registry.has("xai")).isTrue();
        assertThat(registry.has("azure")).isTrue();
        assertThat(registry.has("amazon-bedrock")).isTrue();
        assertThat(registry.has("cloudflare-ai-gateway")).isTrue();
        assertThat(registry.has("cloudflare-workers-ai")).isTrue();
        assertThat(registry.has("github-copilot")).isTrue();
        assertThat(registry.has("groq")).isTrue();
        assertThat(registry.has("deepseek")).isTrue();
        assertThat(registry.has("opencode")).isTrue();
        assertThat(registry.has("opencode-go")).isTrue();
    }

    @Test
    public void registersOpencodeZenGateways() {
        ProviderConnector zen = registry.get("opencode");
        assertThat(zen.getProtocol()).isEqualTo(ConnectorProtocol.OPENAI_CHAT);
        assertThat(zen.getAuthScheme()).isEqualTo(AuthScheme.BEARER);
        assertThat(zen.getEnv()).contains("OPENCODE_API_KEY");

        ProviderConnector go = registry.get("opencode-go");
        assertThat(go.getProtocol()).isEqualTo(ConnectorProtocol.OPENAI_CHAT);
        assertThat(go.getAuthScheme()).isEqualTo(AuthScheme.BEARER);
        assertThat(go.getEnv()).contains("OPENCODE_API_KEY");
        // OpenAI-compatible bearer backend should be usable once a key is supplied.
        assertThat(go.createBackend("minimax-m2.5", "key", Map.of()).isAvailable()).isTrue();
    }

    @Test
    public void connectorMetadataMatchesProtocolsAndAuth() {
        ProviderConnector anthropic = registry.get("anthropic");
        assertThat(anthropic.getProtocol()).isEqualTo(ConnectorProtocol.ANTHROPIC_MESSAGES);
        assertThat(anthropic.getAuthScheme()).isEqualTo(AuthScheme.X_API_KEY);
        assertThat(anthropic.getEnv()).contains("ANTHROPIC_API_KEY");

        assertThat(registry.get("google").getProtocol()).isEqualTo(ConnectorProtocol.GOOGLE_GENERATIVE_AI);
        assertThat(registry.get("google").getAuthScheme()).isEqualTo(AuthScheme.X_GOOG_API_KEY);
        assertThat(registry.get("amazon-bedrock").getProtocol()).isEqualTo(ConnectorProtocol.BEDROCK_CONVERSE);
        assertThat(registry.get("openai").getProtocol()).isEqualTo(ConnectorProtocol.OPENAI_CHAT);
        assertThat(registry.get("azure").getAuthScheme()).isEqualTo(AuthScheme.API_KEY_HEADER);
    }

    @Test
    public void createsCorrectBackendType() {
        assertThat(registry.get("anthropic").createBackend("claude-x", "k", Map.of()))
                .isInstanceOf(AnthropicBackend.class);
        assertThat(registry.get("google").createBackend("gemini-x", "k", Map.of()))
                .isInstanceOf(GoogleGenerativeAIBackend.class);
        assertThat(registry.get("amazon-bedrock").createBackend("m", "bearer", Map.of()))
                .isInstanceOf(AmazonBedrockBackend.class);
        LLMBackend openai = registry.get("openai").createBackend("gpt-x", "k", Map.of());
        assertThat(openai).isInstanceOf(OpenAICompatibleBackend.class);
        assertThat(openai.isAvailable()).isTrue();
        assertThat(openai.getModelName()).isEqualTo("gpt-x");
    }

    @Test
    public void bearerBackendAvailableOnlyWithKey() {
        assertThat(registry.get("openai").createBackend("gpt", "", Map.of()).isAvailable()).isFalse();
        assertThat(registry.get("openai").createBackend("gpt", "key", Map.of()).isAvailable()).isTrue();
    }
}
