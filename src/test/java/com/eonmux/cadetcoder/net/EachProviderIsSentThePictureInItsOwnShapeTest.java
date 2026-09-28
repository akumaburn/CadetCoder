package com.eonmux.cadetcoder.net;

import com.eonmux.cadetcoder.ai.PromptData;
import com.eonmux.cadetcoder.ai.PromptImage;
import com.eonmux.cadetcoder.ai.providers.AuthScheme;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The exact JSON each protocol emits for an attached image.
 *
 * <h2>Why the body is asserted rather than a round trip</h2>
 *
 * <p>The wire format is the part this project controls, and it cannot be checked against the real
 * services from a test suite. All four providers carry the same base64 bytes and disagree about
 * everything else: what the block is called, where the type goes, whether it is a URL. A wrong
 * spelling is a 400 from the provider, which is the sort of failure worth catching here.</p>
 *
 * <h2>Why the order of the blocks is asserted too</h2>
 *
 * <p>All four answer better when the picture comes before the question about it, which is what each
 * of their own guides says to do. The order also decides what is cached: a breakpoint marks
 * everything ahead of it, so an image behind one is outside the cached part and paid for on every
 * request -- and an image is the most expensive thing a request carries.</p>
 *
 * <p><b>Why the Chat Completions assertions changed</b>: that builder put the text first and the
 * pictures after it, which is the wrong side of its own cache breakpoint and the opposite of what
 * the other three wires do deliberately. The same conversation was therefore laid out two different
 * ways depending on which provider answered it.</p>
 */
public class EachProviderIsSentThePictureInItsOwnShapeTest {

    @TempDir
    Path folder;

    private PromptImage picture;

    @BeforeEach
    void readApicture() throws IOException {
        byte[] png = new byte[32];
        System.arraycopy(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}, 0,
                         png, 0, 8);
        Path file = folder.resolve("shot.png");
        Files.write(file, png);
        picture = PromptImage.of(file);
    }

    // ------------------------------------------------------------------ Anthropic Messages

    @Test
    @SuppressWarnings("unchecked")
    void anthropicSendsAbase64SourceBlockBeforeTheText() {
        AnthropicBackend backend =
                new AnthropicBackend("claude-x", "https://example.invalid/v1", "key");

        Map<String, Object> body =
                backend.buildBody("sys", "what is this", List.of(picture), 0.2f, 1024, false);

        List<Map<String, Object>> messages = (List<Map<String, Object>>) body.get("messages");
        List<Map<String, Object>> content  = (List<Map<String, Object>>) messages.get(0).get("content");
        assertThat(content).hasSize(2);
        assertThat(content.get(0)).containsEntry("type", "image");
        assertThat((Map<String, Object>) content.get(0).get("source"))
                .containsEntry("type", "base64")
                .containsEntry("media_type", "image/png")
                .containsEntry("data", picture.base64());
        assertThat(content.get(1)).containsEntry("type", "text")
                                  .containsEntry("text", "what is this");
    }

    @Test
    void anthropicKeepsPlainStringContentWhenThereIsNoPicture() {
        AnthropicBackend backend =
                new AnthropicBackend("claude-x", "https://example.invalid/v1", "key");

        Map<String, Object> body = backend.buildBody("sys", "hello", List.of(), 0.2f, 1024, false);

        List<?> messages = (List<?>) body.get("messages");
        assertThat(((Map<?, ?>) messages.get(0)).get("content")).isEqualTo("hello");
    }

    @Test
    @SuppressWarnings("unchecked")
    void anthropicStillMarksTheTextBlockForCachingWithApictureInFrontOfIt() {
        AnthropicBackend backend =
                new AnthropicBackend("claude-x", "https://example.invalid/v1", "key");

        Map<String, Object> body =
                backend.buildBody("sys", "what is this", List.of(picture), 0.2f, 1024, true);

        List<Map<String, Object>> messages = (List<Map<String, Object>>) body.get("messages");
        List<Map<String, Object>> content  = (List<Map<String, Object>>) messages.get(0).get("content");
        assertThat(content.get(1)).containsEntry("cache_control", Map.of("type", "ephemeral"));
    }

    // ------------------------------------------------------------------ Chat Completions

    @Test
    @SuppressWarnings("unchecked")
    void chatCompletionsSendsAdataUrlPartBecauseTheFileIsOnThisMachine() {
        OpenAICompatibleBackend backend = new OpenAICompatibleBackend(
                "gpt-x", "https://example.invalid/v1", "key", AuthScheme.BEARER, Map.of(), Map.of());

        List<Map<String, Object>> messages = backend.buildMessages(
                new PromptData("sys", "what is this", "plain").withImages(List.of(picture)), false);

        Map<String, Object>       user  = messages.get(messages.size() - 1);
        List<Map<String, Object>> parts = (List<Map<String, Object>>) user.get("content");
        assertThat(user).containsEntry("role", "user");
        assertThat(parts.get(0)).containsEntry("type", "image_url")
                                .containsEntry("image_url", Map.of("url", picture.dataUrl()));
        assertThat(parts.get(1)).containsEntry("type", "text")
                                .containsEntry("text", "what is this");
    }

    @Test
    void chatCompletionsKeepsPlainStringContentWhenThereIsNoPicture() {
        OpenAICompatibleBackend backend = new OpenAICompatibleBackend(
                "gpt-x", "https://example.invalid/v1", "key", AuthScheme.BEARER, Map.of(), Map.of());

        List<Map<String, Object>> messages =
                backend.buildMessages(new PromptData("sys", "hello", "plain"), false);

        assertThat(messages.get(messages.size() - 1).get("content")).isEqualTo("hello");
    }

    // ------------------------------------------------------------------ Google Generative AI

    @Test
    @SuppressWarnings("unchecked")
    void googleSendsAninlineDataPartBesideTheText() {
        List<Map<String, Object>> parts = GoogleGenerativeAIBackend.userParts(
                new PromptData("sys", "what is this", "plain").withImages(List.of(picture)));

        assertThat(parts).hasSize(2);
        assertThat((Map<String, Object>) parts.get(0).get("inlineData"))
                .containsEntry("mimeType", "image/png")
                .containsEntry("data", picture.base64());
        assertThat(parts.get(1)).containsEntry("text", "what is this");
    }

    @Test
    void googleSendsTheTextAloneWhenThereIsNoPicture() {
        List<Map<String, Object>> parts =
                GoogleGenerativeAIBackend.userParts(new PromptData("sys", "hello", "plain"));

        assertThat(parts).containsExactly(Map.of("text", "hello"));
    }

    // ------------------------------------------------------------------ Bedrock Converse

    @Test
    @SuppressWarnings("unchecked")
    void bedrockNamesTheFormatOnItsOwnAndPutsTheBytesUnderSource() {
        AmazonBedrockBackend backend =
                new AmazonBedrockBackend("model-x", null, "us-east-1", "bearer", null);

        Map<String, Object> body =
                backend.buildBody("sys", "what is this", List.of(picture), 0.2f, 1024, false);

        List<Map<String, Object>> messages = (List<Map<String, Object>>) body.get("messages");
        List<Map<String, Object>> content  = (List<Map<String, Object>>) messages.get(0).get("content");
        assertThat(content).hasSize(2);
        assertThat((Map<String, Object>) content.get(0).get("image"))
                .containsEntry("format", "png")
                .containsEntry("source", Map.of("bytes", picture.base64()));
        assertThat(content.get(1)).containsEntry("text", "what is this");
    }

    @Test
    @SuppressWarnings("unchecked")
    void bedrockPutsThePictureInsideThePartAcachePointCovers() {
        AmazonBedrockBackend backend =
                new AmazonBedrockBackend("model-x", null, "us-east-1", "bearer", null);

        Map<String, Object> body =
                backend.buildBody("sys", "what is this", List.of(picture), 0.2f, 1024, true);

        List<Map<String, Object>> messages = (List<Map<String, Object>>) body.get("messages");
        List<Map<String, Object>> content  = (List<Map<String, Object>>) messages.get(0).get("content");
        assertThat(content).hasSize(3);
        assertThat(content.get(0)).containsKey("image");
        assertThat(content.get(2)).isEqualTo(Map.of("cachePoint", Map.of("type", "default")));
    }

    // ------------------------------------------------------------------ the two hand-built bodies

    @Test
    @SuppressWarnings("unchecked")
    void thelegacyOpenAiBodyCarriesThePictureInTheSameShape() {
        // This body used to be concatenated as a string, so it could carry text and nothing else.
        OpenAIBackend backend = new OpenAIBackend("gpt-x", "https://example.invalid", "key");

        Map<String, Object> body = backend.buildBody(
                new PromptData("sys", "what is this", "plain").withImages(List.of(picture)),
                0.2f, 1024);

        List<Map<String, Object>> messages = (List<Map<String, Object>>) body.get("messages");
        List<Map<String, Object>> parts =
                (List<Map<String, Object>>) messages.get(messages.size() - 1).get("content");
        assertThat(parts.get(0)).containsEntry("type", "image_url")
                                .containsEntry("image_url", Map.of("url", picture.dataUrl()));
        assertThat(parts.get(1)).containsEntry("type", "text");
    }

    @Test
    @SuppressWarnings("unchecked")
    void thelocalServerBodyCarriesThePictureInTheSameShape() {
        // llama-server implements the same endpoint and serves image parts to a multimodal model.
        LlamaServerBackend backend = new LlamaServerBackend("local-x", "http://127.0.0.1:8080");

        Map<String, Object> body = backend.buildBody(
                new PromptData("sys", "what is this", "plain").withImages(List.of(picture)),
                0.2f, 1024);

        List<Map<String, Object>> messages = (List<Map<String, Object>>) body.get("messages");
        List<Map<String, Object>> parts =
                (List<Map<String, Object>>) messages.get(messages.size() - 1).get("content");
        assertThat(parts.get(0)).containsEntry("type", "image_url");
        assertThat(body).containsEntry("stream", false).containsEntry("model", "local-x");
    }

    @Test
    void whatUsedToBeEscapedByHandIsNowEscapedByTheMapper() throws Exception {
        // A quote, a backslash, a newline and a control character in one prompt: the body has to
        // parse, and the text has to come back out exactly as it went in.
        String awkward = "say \"hi\"\\ \n\u0007 done";
        OpenAIBackend backend = new OpenAIBackend("gpt-x", "https://example.invalid", "key");

        String json = new ObjectMapper()
                .writeValueAsString(
                        backend.buildBody(new PromptData("sys", awkward, "plain"), 0.2f, 1024));

        JsonNode parsed   = new ObjectMapper().readTree(json);
        JsonNode messages = parsed.get("messages");
        JsonNode user     = messages.get(messages.size() - 1);
        assertThat(user.get("role").asText()).isEqualTo("user");
        assertThat(user.get("content").asText()).isEqualTo(awkward);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aloggedCopyOfTheBodyLeavesThePictureBytesOut() {
        // The payload is redacted, measured and written to a file that outlives the session.
        List<Map<String, Object>> messages = ChatCompletionMessages.elided(
                new PromptData("sys", "what is this", "plain").withImages(List.of(picture)), false);

        List<Map<String, Object>> parts =
                (List<Map<String, Object>>) messages.get(messages.size() - 1).get("content");
        String url = (String) ((Map<String, Object>) parts.get(0).get("image_url")).get("url");
        assertThat(url).startsWith("data:image/png;base64,").doesNotContain(picture.base64());
        assertThat(parts.get(1)).containsEntry("text", "what is this");
    }

    // ------------------------------------------------------------------ what each wire admits to

    @Test
    void everyWireShippedHereNamesTheFormatsItsApiDocuments() {
        assertThat(new AnthropicBackend("claude-x", "https://example.invalid/v1", "key")
                           .imageMediaTypes())
                .containsExactlyInAnyOrder("image/png", "image/jpeg", "image/gif", "image/webp");
        assertThat(new OpenAICompatibleBackend("gpt-x", "https://example.invalid/v1", "key",
                                               AuthScheme.BEARER, Map.of(), Map.of())
                           .imageMediaTypes())
                .containsExactlyInAnyOrder("image/png", "image/jpeg", "image/gif", "image/webp");
        assertThat(new AmazonBedrockBackend("model-x", null, "us-east-1", "bearer", null)
                           .imageMediaTypes())
                .containsExactlyInAnyOrder("image/png", "image/jpeg", "image/gif", "image/webp");
        assertThat(new OpenAIBackend("gpt-x", "https://example.invalid", "key").imageMediaTypes())
                .containsExactlyInAnyOrder("image/png", "image/jpeg", "image/gif", "image/webp");
        assertThat(new LlamaServerBackend("local-x", "http://127.0.0.1:8080").imageMediaTypes())
                .containsExactlyInAnyOrder("image/png", "image/jpeg", "image/gif", "image/webp");
    }

    /**
     * <h2>Why Gemini is asserted on its own</h2>
     *
     * <p>Gemini is the one wire here whose list is not the other four. It documents no GIF, and it
     * documents HEIC and HEIF, which none of the others take. Asserted beside the other five so
     * that a copy of the common list cannot be pasted over it without a test saying so.</p>
     */
    @Test
    void geminiIsTheOneWireThatTakesNoGif() {
        assertThat(new GoogleGenerativeAIBackend("gemini-x", null, "key").imageMediaTypes())
                .containsExactlyInAnyOrder("image/png", "image/jpeg", "image/webp",
                                           "image/heic", "image/heif")
                .doesNotContain("image/gif");
    }

    @Test
    void awireThatNamesNoFormatIsAssumedNotToCarryApicture() {
        // The safe answer for a backend added later: sending the text alone would have the model
        // answering about a picture it was never shown.
        LLMBackend silent = new LLMBackend() {
            @Override
            public String complete(PromptData promptData, Map<String, Object> parameters) {
                return "";
            }

            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String getModelName() {
                return "silent";
            }

            @Override
            public String getApiEndpoint() {
                return "https://example.invalid";
            }
        };

        assertThat(silent.imageMediaTypes()).isEmpty();
    }
}
