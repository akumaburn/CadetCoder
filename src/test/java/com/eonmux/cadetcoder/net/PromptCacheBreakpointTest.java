package com.eonmux.cadetcoder.net;

import com.eonmux.cadetcoder.ai.PromptData;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The exact JSON each protocol emits for a cache breakpoint.
 *
 * <p>These assert the request BODY rather than a live round trip: the wire format is the part this
 * project controls, and it cannot be validated against the real services from a test suite. Each
 * protocol spells a breakpoint differently, and getting the spelling wrong fails silently (no
 * caching) or loudly (a rejected request), so the shape is pinned here.</p>
 */
public class PromptCacheBreakpointTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Long enough to clear the "too small to be worth caching" gate. */
    private static final String BIG_SYSTEM = "S".repeat(5000);
    private static final String BIG_USER   = "U".repeat(5000);

    @Before
    @After
    public void clearLatch() {
        PromptCachePolicy.resetForTesting();
        System.clearProperty(PromptCachePolicy.ENABLED_PROPERTY);
    }

    // ------------------------------------------------------------------ Anthropic Messages

    @Test
    @SuppressWarnings("unchecked")
    public void anthropicMarksTheSystemAndUserBlocksWithEphemeralCacheControl() {
        AnthropicBackend backend =
                new AnthropicBackend("claude-x", "https://example.invalid/v1", "key");

        Map<String, Object> body = backend.buildBody(BIG_SYSTEM, BIG_USER, 0.2f, 1024, true);

        List<Map<String, Object>> system = (List<Map<String, Object>>) body.get("system");
        assertThat(system).hasSize(1);
        assertThat(system.get(0)).containsEntry("type", "text");
        assertThat(system.get(0)).containsEntry("cache_control", Map.of("type", "ephemeral"));

        List<Map<String, Object>> messages = (List<Map<String, Object>>) body.get("messages");
        List<Map<String, Object>> content  = (List<Map<String, Object>>) messages.get(0).get("content");
        assertThat(content.get(0)).containsEntry("cache_control", Map.of("type", "ephemeral"));
    }

    @Test
    public void anthropicKeepsPlainStringContentWhenBreakpointsAreOff() {
        AnthropicBackend backend =
                new AnthropicBackend("claude-x", "https://example.invalid/v1", "key");

        Map<String, Object> body = backend.buildBody(BIG_SYSTEM, BIG_USER, 0.2f, 1024, false);

        assertThat(body.get("system")).isInstanceOf(String.class);
        assertThat(MAPPER.valueToTree(body).toString()).doesNotContain("cache_control");
    }

    @Test
    public void anthropicUsesAtMostTwoBreakpointsWellInsideTheLimitOfFour() throws Exception {
        AnthropicBackend backend =
                new AnthropicBackend("claude-x", "https://example.invalid/v1", "key");

        String json = MAPPER.writeValueAsString(
                backend.buildBody(BIG_SYSTEM, BIG_USER, 0.2f, 1024, true));

        assertThat(json.split("cache_control", -1).length - 1)
                .as("Anthropic permits four; two is the whole prompt")
                .isEqualTo(2);
    }

    // ------------------------------------------------------------------ Bedrock Converse

    @Test
    @SuppressWarnings("unchecked")
    public void bedrockAppendsACachePointBlockAfterEachContentBlock() {
        AmazonBedrockBackend backend =
                new AmazonBedrockBackend("model-x", null, "us-east-1", "bearer", null);

        Map<String, Object> body = backend.buildBody(BIG_SYSTEM, BIG_USER, 0.2f, 1024, true);

        List<Map<String, Object>> system = (List<Map<String, Object>>) body.get("system");
        assertThat(system).hasSize(2);
        assertThat(system.get(0)).containsKey("text");
        assertThat(system.get(1)).isEqualTo(Map.of("cachePoint", Map.of("type", "default")));

        List<Map<String, Object>> messages = (List<Map<String, Object>>) body.get("messages");
        List<Map<String, Object>> content  = (List<Map<String, Object>>) messages.get(0).get("content");
        assertThat(content).hasSize(2);
        assertThat(content.get(1)).isEqualTo(Map.of("cachePoint", Map.of("type", "default")));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void bedrockEmitsNoCachePointWhenBreakpointsAreOff() {
        AmazonBedrockBackend backend =
                new AmazonBedrockBackend("model-x", null, "us-east-1", "bearer", null);

        Map<String, Object> body = backend.buildBody(BIG_SYSTEM, BIG_USER, 0.2f, 1024, false);

        List<Map<String, Object>> messages = (List<Map<String, Object>>) body.get("messages");
        assertThat((List<?>) messages.get(0).get("content")).hasSize(1);
        assertThat(MAPPER.valueToTree(body).toString()).doesNotContain("cachePoint");
    }

    // ------------------------------------------------------------------ the policy

    @Test
    public void breakpointsAreSkippedForPromptsTooSmallToBeCacheable() {
        assertThat(PromptCachePolicy.shouldMark("anthropic", "claude-x", 100)).isFalse();
        assertThat(PromptCachePolicy.shouldMark("anthropic", "claude-x",
                                                PromptCachePolicy.MIN_CACHEABLE_CHARS))
                .isTrue();
    }

    @Test
    public void breakpointsCanBeTurnedOffEntirely() {
        System.setProperty(PromptCachePolicy.ENABLED_PROPERTY, "false");

        assertThat(PromptCachePolicy.shouldMark("anthropic", "claude-x", 100_000)).isFalse();
    }

    /**
     * The latch is on the model, not on the provider.
     *
     * <p>Support is a property of the model, and one account reaches several through the same
     * provider. Latched on the provider alone, one legacy model's rejection stopped every other
     * model on that provider from being cached for the rest of the process.</p>
     */
    @Test
    public void aRejectionNamingAcacheFieldLatchesBreakpointsOffForThatModelAlone() {
        assertThat(PromptCachePolicy.shouldMark("amazon-bedrock", "legacy-model", 100_000)).isTrue();

        assertThat(PromptCachePolicy.looksLikeCacheRejection(
                "{\"message\":\"This model doesn't support cachePoint blocks\"}")).isTrue();
        PromptCachePolicy.disableFor("amazon-bedrock", "legacy-model");

        assertThat(PromptCachePolicy.shouldMark("amazon-bedrock", "legacy-model", 100_000)).isFalse();
        assertThat(PromptCachePolicy.isDisabledFor("amazon-bedrock", "legacy-model")).isTrue();
        assertThat(PromptCachePolicy.shouldMark("amazon-bedrock", "claude-sonnet-4-5", 100_000))
                .as("another model on the same provider is unaffected")
                .isTrue();
        assertThat(PromptCachePolicy.shouldMark("anthropic", "legacy-model", 100_000))
                .as("one provider's rejection must not disable another's")
                .isTrue();
    }

    @Test
    public void themodelNameIsMatchedWithoutRegardToCase() {
        PromptCachePolicy.disableFor("anthropic", "Claude-Sonnet-4-5");

        assertThat(PromptCachePolicy.isDisabledFor("anthropic", "claude-sonnet-4-5"))
                .as("a model id comes from whatever the user typed")
                .isTrue();
    }

    @Test
    public void anOrdinaryRejectionIsNotMistakenForACacheRejection() {
        // Retrying these without markers would waste a request and hide the real cause.
        assertThat(PromptCachePolicy.looksLikeCacheRejection(
                "{\"error\":{\"message\":\"model not found\"}}")).isFalse();
        assertThat(PromptCachePolicy.looksLikeCacheRejection(
                "{\"error\":{\"message\":\"prompt is too long\"}}")).isFalse();
        assertThat(PromptCachePolicy.looksLikeCacheRejection(null)).isFalse();
        assertThat(PromptCachePolicy.looksLikeCacheRejection("")).isFalse();
    }

    @Test
    public void aCacheRejectionIsRecognisedInEachProvidersWording() {
        assertThat(PromptCachePolicy.looksLikeCacheRejection(
                "{\"error\":{\"message\":\"cache_control: unexpected field\"}}")).isTrue();
        assertThat(PromptCachePolicy.looksLikeCacheRejection(
                "ValidationException: cachePoint is not supported for this model")).isTrue();
        assertThat(PromptCachePolicy.looksLikeCacheRejection(
                "prompt caching is not supported")).isTrue();
    }

    // ------------------------------------------------------------------ implicit-caching providers

    @Test
    public void plainOpenAiSendsNoBreakpointBecauseItCachesPrefixesAutomatically() throws Exception {
        OpenAICompatibleBackend backend = new OpenAICompatibleBackend(
                "gpt-x", "https://example.invalid/v1", "key",
                com.eonmux.cadetcoder.ai.providers.AuthScheme.BEARER, Map.of(), Map.of());
        backend.setProviderId("openai");

        String json = renderMessages(backend);

        assertThat(json)
                .as("an unrecognised field risks a rejection and buys nothing here")
                .doesNotContain("cache_control");
    }

    @Test
    public void openRouterSendsABreakpointBecauseItForwardsOneToAnthropicModels() throws Exception {
        OpenAICompatibleBackend backend = new OpenAICompatibleBackend(
                "anthropic/claude-x", "https://example.invalid/v1", "key",
                com.eonmux.cadetcoder.ai.providers.AuthScheme.BEARER, Map.of(), Map.of());
        backend.setProviderId("openrouter");

        String json = renderMessages(backend);

        assertThat(json).contains("cache_control").contains("ephemeral");
    }

    /** Renders just the messages array a backend would send for a large prompt. */
    private static String renderMessages(OpenAICompatibleBackend backend) throws Exception {
        PromptData promptData = new PromptData(BIG_SYSTEM, BIG_USER, "plain");
        boolean marks = backend.supportsInlineCacheBreakpoints()
                && PromptCachePolicy.shouldMark(backend.providerId(), backend.getModelName(),
                                                BIG_SYSTEM.length() + BIG_USER.length());

        return MAPPER.writeValueAsString(backend.buildMessages(promptData, marks));
    }
}
