package com.eonmux.cadetcoder.ai;

import com.eonmux.cadetcoder.ai.metrics.ReportedUsage;
import com.eonmux.cadetcoder.ai.metrics.TokenCalibration;
import com.eonmux.cadetcoder.ai.metrics.TokenUsage;
import com.eonmux.cadetcoder.net.LLMContextOverflowException;
import com.eonmux.cadetcoder.net.LLMErrorMapper;
import com.eonmux.cadetcoder.test.StubbedProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Each request a provider counts sets how the next prompts for that model are measured.
 *
 * <h2>What is locked here</h2>
 *
 * <p>That a request whose provider reports usage sets the model's ratio of characters to tokens;
 * that a refusal for length which gives a count lowers it; and that a request carrying a picture,
 * whose tokens are not text, sets nothing.</p>
 */
class AmodelsOwnCountMeasuresItsPromptsTest {

    @TempDir
    Path folder;

    @BeforeEach
    void setUp() {
        TokenCalibration.clear();
        ReportedUsage.clear();
    }

    @AfterEach
    void tearDown() {
        TokenCalibration.clear();
        ReportedUsage.clear();
    }

    /** The characters AIManager sends: the composed system prompt and the user prompt. */
    private static int sent(PromptData request) {
        return request.getSystemPrompt().length() + request.getUserPrompt().length();
    }

    private static String longPrompt() {
        return "def handler(event): return event['path'].split('/')\n".repeat(400);
    }

    @Test
    void aReportedCountSetsTheRatio() {
        try (StubbedProvider ignored = StubbedProvider.answeringWith(request -> {
            ReportedUsage.report(new TokenUsage(0, sent(request) / 3, 5));
            return "done";
        })) {
            AIManager.getInstance().complete(new PromptData("system", longPrompt()));
        }

        assertThat(TokenCalibration.charsPerToken(PromptSize.configuredModel()))
                .isCloseTo(3.0, within(0.01));
        assertThat(PromptSize.tokens("x".repeat(3_000))).isCloseTo(1_000, within(2));
    }

    @Test
    void aRefusalForLengthLowersTheRatio() {
        try (StubbedProvider ignored = StubbedProvider.answeringWith(request -> {
            throw LLMErrorMapper.fromStatus("local", "qwen", null, 400,
                    "{\"error\":{\"message\":\"This model's maximum context length is 8192 tokens."
                    + " However, your prompt contains at least " + (sent(request) / 2)
                    + " input tokens.\"}}", null);
        })) {
            assertThatThrownBy(() -> AIManager.getInstance()
                    .complete(new PromptData("system", longPrompt())))
                    .isInstanceOf(LLMContextOverflowException.class);
        }

        assertThat(TokenCalibration.charsPerToken(PromptSize.configuredModel()))
                .isCloseTo(2.0, within(0.01));
    }

    /** A picture costs tokens that no character of the prompt accounts for. */
    @Test
    void aRequestCarryingAPictureSetsNothing() throws IOException {
        PromptData withPicture = new PromptData("system", longPrompt())
                .withImages(List.of(picture()));
        try (StubbedProvider ignored = StubbedProvider.answeringWith(request -> {
            ReportedUsage.report(new TokenUsage(0, 2_000, 5));
            return "done";
        })) {
            AIManager.getInstance().complete(withPicture);
        }

        assertThat(TokenCalibration.charsPerToken(PromptSize.configuredModel())).isEqualTo(4.0);
    }

    private PromptImage picture() throws IOException {
        byte[] png = new byte[24];
        System.arraycopy(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}, 0,
                         png, 0, 8);
        Path file = folder.resolve("shot.png");
        Files.write(file, png);
        return PromptImage.of(file);
    }
}
