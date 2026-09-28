package com.eonmux.cadetcoder.ai.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The arithmetic each provider needs, and the cases where reporting nothing beats reporting zero.
 */
class TokenUsageTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @SuppressWarnings("unchecked")
    private static Optional<TokenUsage> parse(String json) throws Exception {
        return TokenUsage.from(MAPPER.readValue(json, Map.class));
    }

    @Test
    @DisplayName("OpenAI: prompt_tokens is the whole input, cached_tokens a part of it")
    void openAiSplitsCachedOutOfPromptTokens() throws Exception {
        Optional<TokenUsage> usage = parse("""
                {"usage": {"prompt_tokens": 1000, "completion_tokens": 250,
                           "prompt_tokens_details": {"cached_tokens": 800}}}""");

        assertThat(usage).isPresent();
        assertThat(usage.get().cachedInputTokens()).isEqualTo(800);
        assertThat(usage.get().newInputTokens()).isEqualTo(200);
        assertThat(usage.get().inputTokens()).isEqualTo(1000);
        assertThat(usage.get().outputTokens()).isEqualTo(250);
    }

    @Test
    @DisplayName("OpenAI: no cache details means the whole prompt was new")
    void openAiWithoutCacheDetailsCountsEverythingNew() throws Exception {
        Optional<TokenUsage> usage = parse(
                "{\"usage\": {\"prompt_tokens\": 640, \"completion_tokens\": 12}}");

        assertThat(usage.orElseThrow().cachedInputTokens()).isZero();
        assertThat(usage.orElseThrow().newInputTokens()).isEqualTo(640);
    }

    @Test
    @DisplayName("Anthropic: input_tokens EXCLUDES the cache figures, so the input is their sum")
    void anthropicAddsCacheFiguresToInput() throws Exception {
        Optional<TokenUsage> usage = parse("""
                {"usage": {"input_tokens": 30, "output_tokens": 500,
                           "cache_read_input_tokens": 900,
                           "cache_creation_input_tokens": 70}}""");

        assertThat(usage.orElseThrow().cachedInputTokens()).isEqualTo(900);
        // 30 fresh + 70 being written to cache: a cache WRITE is billed as new input.
        assertThat(usage.orElseThrow().newInputTokens()).isEqualTo(100);
        assertThat(usage.orElseThrow().inputTokens()).isEqualTo(1000);
        assertThat(usage.orElseThrow().outputTokens()).isEqualTo(500);
    }

    @Test
    @DisplayName("Anthropic arithmetic is not applied to an OpenAI body, or the cache hit would be invented")
    void anthropicAndOpenAiAreNotConflated() throws Exception {
        // Same numbers, different provider: if OpenAI were treated as Anthropic the input would
        // come out as 1800 rather than 1000, and the cache ratio would be halved.
        Optional<TokenUsage> openAi = parse("""
                {"usage": {"prompt_tokens": 1000, "completion_tokens": 10,
                           "prompt_tokens_details": {"cached_tokens": 800}}}""");
        assertThat(openAi.orElseThrow().inputTokens()).isEqualTo(1000);
    }

    @Test
    @DisplayName("Gemini: promptTokenCount includes the cached count")
    void geminiSubtractsCachedFromPrompt() throws Exception {
        Optional<TokenUsage> usage = parse("""
                {"usageMetadata": {"promptTokenCount": 2048, "candidatesTokenCount": 64,
                                   "cachedContentTokenCount": 2000}}""");

        assertThat(usage.orElseThrow().cachedInputTokens()).isEqualTo(2000);
        assertThat(usage.orElseThrow().newInputTokens()).isEqualTo(48);
        assertThat(usage.orElseThrow().outputTokens()).isEqualTo(64);
    }

    @Test
    @DisplayName("A body with no usage block reports nothing rather than zero")
    void absentUsageIsEmpty() throws Exception {
        assertThat(parse("{\"choices\": [{\"message\": {\"content\": \"hi\"}}]}")).isEmpty();
        assertThat(TokenUsage.from(null)).isEmpty();
    }

    @Test
    @DisplayName("An empty or unrecognised usage block reports nothing rather than zero")
    void emptyUsageIsEmpty() throws Exception {
        assertThat(parse("{\"usage\": {}}")).isEmpty();
        assertThat(parse("{\"usage\": {\"something_else\": 5}}")).isEmpty();
        // All-zero is indistinguishable from "told us nothing", and an estimate beats a false zero.
        assertThat(parse("{\"usage\": {\"prompt_tokens\": 0, \"completion_tokens\": 0}}")).isEmpty();
    }

    @Test
    @DisplayName("A cached count larger than the prompt cannot make the new count negative")
    void cachedIsClampedToTheInput() throws Exception {
        Optional<TokenUsage> usage = parse("""
                {"usage": {"prompt_tokens": 100, "completion_tokens": 5,
                           "prompt_tokens_details": {"cached_tokens": 400}}}""");

        assertThat(usage.orElseThrow().cachedInputTokens()).isEqualTo(100);
        assertThat(usage.orElseThrow().newInputTokens()).isZero();
    }

    @Test
    @DisplayName("Large counts arriving as Long rather than Integer are read")
    void longValuedCountsAreRead() {
        Optional<TokenUsage> usage = TokenUsage.from(Map.of(
                "usage", Map.of("prompt_tokens", 5_000_000_000L, "completion_tokens", 3L)));

        assertThat(usage.orElseThrow().inputTokens()).isEqualTo(Integer.MAX_VALUE);
        assertThat(usage.orElseThrow().outputTokens()).isEqualTo(3);
    }
}
