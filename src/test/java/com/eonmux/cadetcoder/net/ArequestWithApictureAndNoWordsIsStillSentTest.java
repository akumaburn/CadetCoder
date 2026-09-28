package com.eonmux.cadetcoder.net;

import com.eonmux.cadetcoder.ai.PromptData;
import com.eonmux.cadetcoder.ai.PromptImage;
import com.eonmux.cadetcoder.ai.providers.AuthScheme;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The body built for a picture attached to an empty prompt line.
 *
 * <h2>The defect</h2>
 *
 * <p>Dropping a screenshot in and pressing enter with nothing typed after it is an ordinary way to
 * ask "what is this?", and it was the one request none of the four wires could send. Every builder
 * appended a text part unconditionally, so the body carried a content part whose {@code text} was
 * the empty string -- which Anthropic, Google and the Chat Completions endpoints all answer with a
 * 400, and which Bedrock never got as far as sending, because {@code Map.of("text", null)} throws a
 * {@link NullPointerException} out of the builder itself.</p>
 *
 * <p>So the failure was reported as a malformed request or as a crash, in both cases naming nothing
 * the user could act on, for a request that was perfectly reasonable.</p>
 *
 * <h2>What is asserted</h2>
 *
 * <p>That each wire emits the images and no empty text part, for a prompt that is empty and for one
 * that is null -- the two ways the text can be missing by the time a body is built.</p>
 */
public class ArequestWithApictureAndNoWordsIsStillSentTest {

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

    private PromptData wordless(String text) {
        return new PromptData("sys", text, "plain").withImages(List.of(picture));
    }

    // ------------------------------------------------------------------ Chat Completions

    @Test
    @SuppressWarnings("unchecked")
    void chatCompletionsSendsThePictureAloneRatherThanAnEmptyTextPart() {
        OpenAICompatibleBackend backend = new OpenAICompatibleBackend(
                "gpt-x", "https://example.invalid/v1", "key", AuthScheme.BEARER, Map.of(), Map.of());

        for (String nothing : new String[] {"", null}) {
            List<Map<String, Object>> messages = backend.buildMessages(wordless(nothing), false);

            List<Map<String, Object>> parts =
                    (List<Map<String, Object>>) messages.get(messages.size() - 1).get("content");
            assertThat(parts).hasSize(1);
            assertThat(parts.get(0)).containsEntry("type", "image_url");
        }
    }

    // ------------------------------------------------------------------ Anthropic Messages

    @Test
    @SuppressWarnings("unchecked")
    void anthropicSendsThePictureAloneRatherThanAnEmptyTextBlock() {
        AnthropicBackend backend =
                new AnthropicBackend("claude-x", "https://example.invalid/v1", "key");

        for (String nothing : new String[] {"", null}) {
            Map<String, Object> body =
                    backend.buildBody("sys", nothing, List.of(picture), 0.2f, 1024, false);

            List<Map<String, Object>> messages = (List<Map<String, Object>>) body.get("messages");
            List<Map<String, Object>> content =
                    (List<Map<String, Object>>) messages.get(0).get("content");
            assertThat(content).hasSize(1);
            assertThat(content.get(0)).containsEntry("type", "image");
        }
    }

    /**
     * With nothing to say about the picture, the picture itself is what the breakpoint has to mark.
     *
     * <p>Anthropic requires the breakpoint to sit on a content block, and the text block it used to
     * sit on is not there. Leaving it off entirely would mean the most expensive thing the request
     * carries is the one thing never cached.</p>
     */
    @Test
    @SuppressWarnings("unchecked")
    void anthropicMarksThePictureItselfWhenThereIsNoTextToMark() {
        AnthropicBackend backend =
                new AnthropicBackend("claude-x", "https://example.invalid/v1", "key");

        Map<String, Object> body = backend.buildBody("sys", "", List.of(picture), 0.2f, 1024, true);

        List<Map<String, Object>> messages = (List<Map<String, Object>>) body.get("messages");
        List<Map<String, Object>> content  = (List<Map<String, Object>>) messages.get(0).get("content");
        assertThat(content).hasSize(1);
        assertThat(content.get(0)).containsEntry("type", "image")
                                  .containsEntry("cache_control", Map.of("type", "ephemeral"));
    }

    // ------------------------------------------------------------------ Bedrock Converse

    @Test
    @SuppressWarnings("unchecked")
    void bedrockBuildsAbodyInsteadOfThrowingOutOfTheBuilder() {
        AmazonBedrockBackend backend =
                new AmazonBedrockBackend("model-x", null, "us-east-1", "bearer", null);

        for (String nothing : new String[] {"", null}) {
            assertThatCode(() -> backend.buildBody("sys", nothing, List.of(picture), 0.2f, 1024, false))
                    .doesNotThrowAnyException();

            Map<String, Object> body =
                    backend.buildBody("sys", nothing, List.of(picture), 0.2f, 1024, false);
            List<Map<String, Object>> messages = (List<Map<String, Object>>) body.get("messages");
            List<Map<String, Object>> content =
                    (List<Map<String, Object>>) messages.get(0).get("content");
            assertThat(content).hasSize(1);
            assertThat(content.get(0)).containsKey("image");
        }
    }

    // ------------------------------------------------------------------ Google Generative AI

    @Test
    void googleSendsThePartWithTheBytesAndNoEmptyTextPart() {
        for (String nothing : new String[] {"", null}) {
            List<Map<String, Object>> parts =
                    GoogleGenerativeAIBackend.userParts(wordless(nothing));

            assertThat(parts).hasSize(1);
            assertThat(parts.get(0)).containsKey("inlineData");
        }
    }

    /**
     * A request with neither text nor pictures still has to be a well-formed turn.
     *
     * <p>Google rejects a {@code parts} array that is empty outright, so the empty text part that is
     * wrong beside a picture is the only right answer when there is nothing else at all.</p>
     */
    @Test
    void googleStillSendsAnEmptyTurnRatherThanAnEmptyPartsArray() {
        assertThat(GoogleGenerativeAIBackend.userParts(new PromptData("sys", "")))
                .containsExactly(Map.of("text", ""));
    }
}
