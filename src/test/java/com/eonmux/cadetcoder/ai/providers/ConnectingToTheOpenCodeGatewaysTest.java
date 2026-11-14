package com.eonmux.cadetcoder.ai.providers;

import com.eonmux.cadetcoder.net.GoogleGenerativeAIBackend;
import com.eonmux.cadetcoder.session.SessionManager;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The OpenCode gateways are called on the endpoint each model is served on.
 *
 * <p><b>The defect</b>: both gateways were registered as OpenAI Chat Completions only, at the
 * retired {@code /zen/...} paths. The gateways partition their models by protocol across distinct
 * paths -- Chat Completions for most, Anthropic Messages for the Claude families (and a few Qwen
 * and MiniMax chat models), Google for Gemini -- and every model answers on exactly one of them.
 * OpenCode's own client reads the partition from its catalog; the catalog this tool reads names
 * the models but not their protocol, so {@link OpencodeGatewayWire} decides from the id.</p>
 *
 * <p><b>What is locked here</b>: the canonical endpoints, the per-model routing with its
 * exceptions, the credential carried as the bearer token the gateway reads on every route, and
 * the session identification riding on the Google wire too -- which it could not before, because
 * the Google backend took no headers at all.</p>
 */
public class ConnectingToTheOpenCodeGatewaysTest {

    private final ProviderRegistry registry = ProviderRegistry.getInstance();

    @Test
    public void theyPointAtTheInferenceHostRatherThanTheRetiredZenPaths() {
        assertThat(registry.get("opencode").getDefaultBaseUrl())
                .isEqualTo("https://opencode.ai/inference/openai/v1");
        assertThat(registry.get("opencode-go").getDefaultBaseUrl())
                .isEqualTo("https://opencode.ai/inference/go/openai/v1");
    }

    @Test
    public void mostModelsAreCalledOnTheChatCompletionsEndpoint() {
        assertThat(registry.get("opencode-go").createBackend(
                "deepseek-v4.1-flash", "key", Map.of()))
                .isInstanceOf(com.eonmux.cadetcoder.net.OpenAICompatibleBackend.class);
        assertThat(registry.get("opencode").createBackend(
                "glm-5.3", "key", Map.of()))
                .isInstanceOf(com.eonmux.cadetcoder.net.OpenAICompatibleBackend.class);
    }

    @Test
    public void aClaudeModelIsCalledOnTheAnthropicEndpoint() {
        ProviderConnector go = registry.get("opencode-go");

        assertThat(go.createBackend("claude-haiku-5-5", "key", Map.of()))
                .isInstanceOf(com.eonmux.cadetcoder.net.AnthropicBackend.class);
        assertThat(go.baseUrlFor(ConnectorProtocol.ANTHROPIC_MESSAGES))
                .isEqualTo("https://opencode.ai/inference/go/anthropic/v1");
    }

    @Test
    public void aGeminiModelIsCalledOnTheGoogleEndpoint() {
        ProviderConnector go = registry.get("opencode-go");

        assertThat(go.createBackend("gemini-4-argon", "key", Map.of()))
                .isInstanceOf(GoogleGenerativeAIBackend.class);
        assertThat(go.baseUrlFor(ConnectorProtocol.GOOGLE_GENERATIVE_AI))
                .isEqualTo("https://opencode.ai/inference/go/google/v1beta");
    }

    /**
     * The exception the vendor prefixes cannot express: the gateways serve these Qwen and MiniMax
     * chat models in Anthropic Messages shape. Enumerated because some of their siblings are
     * served on the Chat Completions endpoint, so a family-wide prefix would misroute them.
     */
    @Test
    public void theQwenAndMinimaxChatModelsTheGatewaysServeAsMessagesGoToTheAnthropicEndpoint() {
        for (String model : new String[] {"qwen3.5-plus", "qwen3.6-plus", "qwen3.6-plus-free",
                                          "qwen3.7-plus", "qwen3.8-flash", "qwen3.8-max",
                                          "minimax-m2.1-free", "minimax-m2.5-free", "minimax-m2.7",
                                          "minimax-m3", "minimax-m3-free"}) {
            assertThat(OpencodeGatewayWire.isAnthropic(model)).as(model).isTrue();
            assertThat(registry.get("opencode-go").createBackend(model, "key", Map.of()))
                    .as(model).isInstanceOf(com.eonmux.cadetcoder.net.AnthropicBackend.class);
        }
    }

    @Test
    public void theWireAnswersItsDefaultAndIsNotFooledByCase() {
        OpencodeGatewayWire wire = new OpencodeGatewayWire();

        assertThat(wire.forModel(null))
                .as("an unknown id goes to the majority endpoint")
                .isEqualTo(ConnectorProtocol.OPENAI_CHAT);
        assertThat(wire.forModel("CLAUDE-SONNET-4-6"))
                .isEqualTo(ConnectorProtocol.ANTHROPIC_MESSAGES);
        assertThat(wire.forModel("Gemini-4-Argon"))
                .isEqualTo(ConnectorProtocol.GOOGLE_GENERATIVE_AI);
        assertThat(wire.protocols()).containsExactly(
                ConnectorProtocol.OPENAI_CHAT,
                ConnectorProtocol.ANTHROPIC_MESSAGES,
                ConnectorProtocol.GOOGLE_GENERATIVE_AI);
    }

    /**
     * The Go gateway refuses a request that names no conversation, and its Google route reads the
     * credential as a bearer token like every other route -- so the session identification and the
     * scheme must reach the Google backend, which until this fix carried neither.
     */
    @Test
    public void theSessionIdentificationAndTheBearerSchemeReachTheGoogleBackend()
            throws Exception {
        GoogleGenerativeAIBackend backend = (GoogleGenerativeAIBackend)
                registry.get("opencode-go").createBackend("gemini-4-argon", "key", Map.of());

        @SuppressWarnings ("unchecked")
        Map<String, String> headers = (Map<String, String>) fieldOf(backend, "extraHeaders");
        assertThat(headers.get("x-opencode-session"))
                .as("the conversation id, which the gateway routes and caches by")
                .isEqualTo(SessionManager.getInstance().getCurrentSessionId());
        assertThat(headers.get("x-opencode-client")).isEqualTo("cadet");
        assertThat(fieldOf(backend, "authScheme")).isEqualTo(AuthScheme.BEARER);
    }

    @Test
    public void theSessionIdentificationAndTheBearerSchemeReachTheAnthropicBackend()
            throws Exception {
        com.eonmux.cadetcoder.net.AnthropicBackend backend =
                (com.eonmux.cadetcoder.net.AnthropicBackend)
                        registry.get("opencode-go")
                                .createBackend("claude-haiku-5-5", "key", Map.of());

        assertThat(((Map<String, String>) fieldOf(backend, "extraHeaders")).get("x-opencode-session"))
                .isEqualTo(SessionManager.getInstance().getCurrentSessionId());
        assertThat(fieldOf(backend, "authScheme")).isEqualTo(AuthScheme.BEARER);
    }

    @Test
    public void theAnthropicConnectorKeepsItsOwnSchemeAndSendsNoSessionHeaders() {
        com.eonmux.cadetcoder.net.AnthropicBackend backend =
                (com.eonmux.cadetcoder.net.AnthropicBackend)
                        registry.get("anthropic").createBackend("claude-sonnet-4", "key", Map.of());

        assertThat(registry.get("anthropic").getSessionHeaderNames()).isEmpty();
        assertThat(registry.get("anthropic").getAuthScheme()).isEqualTo(AuthScheme.X_API_KEY);
        // The backend above must not be carrying an empty session header set forward; the field
        // check lives with the auth-scheme contract in AgatewayIsAuthenticatedTheWayItAsksOnEveryWireTest.
    }

    @Test
    public void anExplicitBaseUrlOptionOverridesEverything() throws Exception {
        ProviderConnector go = registry.get("opencode-go");
        com.eonmux.cadetcoder.net.LLMBackend backend = go.createBackend("claude-haiku-5-5", "key",
                Map.of("baseURL", "https://proxy.example/internal"));

        // The option decides even where the wire would pick another endpoint family: the caller
        // who set it owns the path.
        assertThat((String) fieldOf(backend, "apiEndpoint"))
                .isEqualTo("https://proxy.example/internal");
    }

    /** Guard against silent type drift: read a declared instance field of a backend. */
    private static Object fieldOf(Object backend, String name) throws Exception {
        Class<?> type = backend.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                if (!field.isSynthetic() && !Modifier.isStatic(field.getModifiers())) {
                    field.setAccessible(true);
                    return field.get(backend);
                }
            } catch (NoSuchFieldException ignored) {
                // walk up
            }
            type = type.getSuperclass();
        }
        throw new NoSuchFieldException(name);
    }
}
