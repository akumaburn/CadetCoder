package com.eonmux.cadetcoder.net;

import com.eonmux.cadetcoder.ai.PromptData;
import com.eonmux.cadetcoder.ai.SharedOpening;
import com.eonmux.cadetcoder.ai.SystemPromptProvider;
import com.eonmux.cadetcoder.ai.providers.AuthScheme;
import com.eonmux.cadetcoder.commands.CommandCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A request marks the end of the opening every loop's system prompt shares, as well as the end of
 * the system prompt and of the conversation.
 *
 * <h2>The defect</h2>
 *
 * <p>Every loop's system prompt opens with the operating principles and the command list, so that a
 * provider can reuse that opening across a chat, an agent run and a worker. A provider that caches
 * only what the request marks -- Anthropic, Bedrock, and the Anthropic models behind OpenRouter --
 * was sent one marker at the end of the system prompt. The end differs between loops, so nothing
 * one loop wrote to the cache could be read by another.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>Where the opening ends; that Anthropic, Bedrock and OpenRouter split the system prompt there
 * into two marked parts that together are the system prompt unchanged; and that OpenAI's GPT-5.6
 * and later get one explicit breakpoint there, which older models would reject.</p>
 */
public class TheSharedOpeningIsCachedOnItsOwnTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String OPENING =
            SystemPromptProvider.compose(CommandCatalog.promptBlock(CommandCatalog.coreCommands()));

    private static final String REST   = "\n\n# Chat Command Prompt\n" + "R".repeat(3000);
    private static final String SYSTEM = OPENING + REST;
    private static final String USER   = "User request: list the files\n";

    private static final Map<String, String> EPHEMERAL = Map.of("type", "ephemeral");

    @BeforeEach
    @AfterEach
    public void clearLatch() {
        PromptCachePolicy.resetForTesting();
    }

    // ------------------------------------------------------------------ where the opening ends

    @Test
    public void theOpeningEndsAfterTheCommandList() {
        assertThat(SharedOpening.lengthIn(SYSTEM)).isEqualTo(OPENING.length());
    }

    @Test
    public void aWorkersOpeningEndsAfterTheWorkerList() {
        String worker = SystemPromptProvider.compose(
                CommandCatalog.promptBlock(CommandCatalog.workerCommands()));

        assertThat(SharedOpening.lengthIn(worker + REST)).isEqualTo(worker.length());
    }

    @Test
    public void aPromptWithoutTheListHasNoOpeningToMark() {
        assertThat(SharedOpening.lengthIn(SystemPromptProvider.compose("Explain this code."))).isZero();
        assertThat(SharedOpening.lengthIn(null)).isZero();
        // Nothing follows the opening, so the marker at the end of the system prompt already covers it.
        assertThat(SharedOpening.lengthIn(OPENING)).isZero();
        // A block of blank text is refused by Anthropic, and the refusal names no cache field, so no
        // retry without markers would follow it.
        assertThat(SharedOpening.lengthIn(OPENING + "\n\n")).isZero();
        assertThat(SharedOpening.split(null)).isEmpty();
    }

    // ------------------------------------------------------------------ Anthropic Messages

    @Test
    @SuppressWarnings("unchecked")
    public void anthropicSplitsTheSystemPromptIntoTwoMarkedBlocks() throws Exception {
        Map<String, Object> body = new AnthropicBackend("claude-x", "https://example.invalid/v1", "key")
                .buildBody(SYSTEM, USER, 0.2f, 1024, true);

        List<Map<String, Object>> system = (List<Map<String, Object>>) body.get("system");
        assertThat(system).hasSize(2);
        assertThat(system).allSatisfy(block -> assertThat(block).containsEntry("cache_control", EPHEMERAL));
        assertThat(system.get(0)).containsEntry("text", OPENING);
        assertThat(system.get(1)).containsEntry("text", REST);
        assertThat(markers(body, "cache_control")).as("Anthropic permits four").isEqualTo(3);
    }

    @Test
    public void anthropicSendsOneStringWithoutBreakpoints() {
        Map<String, Object> body = new AnthropicBackend("claude-x", "https://example.invalid/v1", "key")
                .buildBody(SYSTEM, USER, 0.2f, 1024, false);

        assertThat(body.get("system")).isEqualTo(SYSTEM);
    }

    // ------------------------------------------------------------------ Bedrock Converse

    @Test
    public void bedrockPutsACachePointAfterTheOpeningAndAfterTheRest() throws Exception {
        Map<String, Object> body = new AmazonBedrockBackend("model-x", null, "us-east-1", "bearer", null)
                .buildBody(SYSTEM, USER, 0.2f, 1024, true);

        Map<String, Object> cachePoint = Map.of("cachePoint", Map.of("type", "default"));
        assertThat(body.get("system")).isEqualTo(List.of(
                Map.of("text", OPENING), cachePoint, Map.of("text", REST), cachePoint));
        assertThat(markers(body, "cachePoint")).as("Converse permits four").isEqualTo(3);
        assertThat(OPENING + REST).isEqualTo(SYSTEM);
    }

    // ------------------------------------------------------------------ OpenRouter

    @Test
    @SuppressWarnings("unchecked")
    public void openRouterSplitsTheSystemMessageIntoTwoMarkedParts() {
        OpenAICompatibleBackend backend = new OpenAICompatibleBackend(
                "anthropic/claude-x", "https://example.invalid/v1", "key",
                AuthScheme.BEARER, Map.of(), Map.of());
        backend.setProviderId("openrouter");

        List<Map<String, Object>> messages =
                backend.buildMessages(new PromptData(SYSTEM, USER, "plain"), true);

        List<Map<String, Object>> parts = (List<Map<String, Object>>) messages.get(0).get("content");
        assertThat(parts).hasSize(2);
        assertThat(parts).allSatisfy(part -> assertThat(part).containsEntry("cache_control", EPHEMERAL));
        assertThat(parts.get(0)).containsEntry("text", OPENING);
        assertThat(parts.get(1)).containsEntry("text", REST);
    }

    @Test
    public void openRouterSendsOneStringWithoutBreakpoints() {
        OpenAICompatibleBackend backend = new OpenAICompatibleBackend(
                "anthropic/claude-x", "https://example.invalid/v1", "key",
                AuthScheme.BEARER, Map.of(), Map.of());
        backend.setProviderId("openrouter");

        List<Map<String, Object>> messages =
                backend.buildMessages(new PromptData(SYSTEM, USER, "plain"), false);

        assertThat(messages.get(0)).containsEntry("content", SYSTEM);
    }

    // ------------------------------------------------------------------ OpenAI and Azure OpenAI

    @Test
    @SuppressWarnings("unchecked")
    public void gpt56MarksTheEndOfTheOpeningWithAnExplicitBreakpoint() throws Exception {
        OpenAICompatibleBackend backend = openAi("openai", "gpt-5.6");

        assertThat(backend.inlineCacheMarker(promptChars())).isEqualTo(InlineCacheMarker.OPENING_BREAKPOINT);
        Map<String, Object> body = backend.buildBody(
                new PromptData(SYSTEM, USER, "plain"), 0.2f, 0, backend.inlineCacheMarker(promptChars()));

        List<Map<String, Object>> messages = (List<Map<String, Object>>) body.get("messages");
        assertThat(messages.get(0).get("content")).isEqualTo(List.of(
                Map.of("type", "text", "text", OPENING,
                       "prompt_cache_breakpoint", Map.of("mode", "explicit")),
                Map.of("type", "text", "text", REST)));
        // OpenAI places its own breakpoint on the latest message, so the user message stays as it was.
        assertThat(messages.get(1)).containsEntry("content", USER);
        assertThat(MAPPER.writeValueAsString(body)).doesNotContain("cache_control");
    }

    @Test
    public void laterGptFamiliesAreMarkedToo() {
        assertThat(openAi("openai", "gpt-6").inlineCacheMarker(promptChars()))
                .isEqualTo(InlineCacheMarker.OPENING_BREAKPOINT);
        assertThat(openAi("openai", "gpt-5.10-codex").inlineCacheMarker(promptChars()))
                .isEqualTo(InlineCacheMarker.OPENING_BREAKPOINT);
        assertThat(openAi("azure", "gpt-5.6-mini").inlineCacheMarker(promptChars()))
                .isEqualTo(InlineCacheMarker.OPENING_BREAKPOINT);
        assertThat(openAi("openai", "ft:gpt-5.6:acme::abc123").inlineCacheMarker(promptChars()))
                .isEqualTo(InlineCacheMarker.OPENING_BREAKPOINT);
    }

    @Test
    public void aGpt56PromptWithoutTheOpeningIsSentAsBefore() {
        // OpenAI marks the latest message itself, so there is nothing left to mark.
        List<Map<String, Object>> messages = ChatCompletionMessages.of(
                new PromptData("Explain this code. " + "x".repeat(5000), USER, "plain"),
                InlineCacheMarker.OPENING_BREAKPOINT);

        assertThat(messages.get(0).get("content")).isInstanceOf(String.class);
        assertThat(messages.get(1)).containsEntry("content", USER);
    }

    @Test
    public void modelsBeforeGpt56AreNotMarkedBecauseTheyRejectTheField() {
        assertThat(openAi("openai", "gpt-5.5").inlineCacheMarker(promptChars()))
                .isEqualTo(InlineCacheMarker.NONE);
        assertThat(openAi("openai", "gpt-4.1").inlineCacheMarker(promptChars()))
                .isEqualTo(InlineCacheMarker.NONE);
        assertThat(openAi("openai", "o3").inlineCacheMarker(promptChars()))
                .isEqualTo(InlineCacheMarker.NONE);
        // An Azure deployment is named by its owner, and a name that says nothing is not guessed at.
        assertThat(openAi("azure", "production-chat").inlineCacheMarker(promptChars()))
                .isEqualTo(InlineCacheMarker.NONE);
        // Azure's name for GPT-3.5 is not family 35.
        assertThat(openAi("azure", "gpt-35-turbo").inlineCacheMarker(promptChars()))
                .isEqualTo(InlineCacheMarker.NONE);
        assertThat(openAi("openai", "chatgpt-4o-latest").inlineCacheMarker(promptChars()))
                .isEqualTo(InlineCacheMarker.NONE);
        assertThat(openAi("openai", "gpt-oss-120b").inlineCacheMarker(promptChars()))
                .isEqualTo(InlineCacheMarker.NONE);
        // Another provider serving an OpenAI model has not documented the field.
        assertThat(openAi("togetherai", "gpt-6").inlineCacheMarker(promptChars()))
                .isEqualTo(InlineCacheMarker.NONE);
    }

    @Test
    public void aRejectionOfTheBreakpointIsRecognisedAsACacheRejection() {
        assertThat(PromptCachePolicy.looksLikeCacheRejection(
                "{\"error\":{\"message\":\"Unrecognized request argument supplied: "
                + "prompt_cache_breakpoint\"}}")).isTrue();
    }

    private static OpenAICompatibleBackend openAi(String provider, String model) {
        OpenAICompatibleBackend backend = new OpenAICompatibleBackend(
                model, "https://example.invalid/v1", "key", AuthScheme.BEARER, Map.of(), Map.of());
        backend.setProviderId(provider);
        return backend;
    }

    private static int promptChars() {
        return SYSTEM.length() + USER.length();
    }

    private static int markers(Map<String, Object> body, String field) throws Exception {
        return MAPPER.writeValueAsString(body).split(field, -1).length - 1;
    }
}
