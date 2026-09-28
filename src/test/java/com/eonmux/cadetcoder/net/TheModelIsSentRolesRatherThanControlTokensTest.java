package com.eonmux.cadetcoder.net;

import com.eonmux.cadetcoder.ai.PromptData;
import com.eonmux.cadetcoder.ai.PromptImage;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * What a Chat Completions request carries when a chat template is configured.
 *
 * <h2>Why a chat template must not reach this wire</h2>
 *
 * <p>On this protocol the roles are the template: the server reads {@code system} and {@code user}
 * and renders them with the control tokens the model was trained on. A chat template renders a
 * conversation into one string for a model that is fed raw text, which is a local-inference
 * concern; every backend here posts a {@code messages} array.</p>
 *
 * <p>{@code ai.chatTemplate} defaults to {@code chatml}, and the messages were rendered through it
 * into a single {@code user} message. So by default every request went out reading
 * {@code <|im_start|>system ... <|im_end|>}: the model was handed its own control tokens as
 * literal text, the system prompt arrived with the user's role, and the server templated the
 * result a second time.</p>
 *
 * <h2>Why an attached image is the case that could not hide it</h2>
 *
 * <p>An image makes a message's content a parts array. Collapsed to one message, that array became
 * the first message's content, and a provider that joins message contents as strings refused the
 * request with {@code sequence item 0: expected str instance, list found} -- so the picture and
 * the question typed beside it were both lost.</p>
 */
class TheModelIsSentRolesRatherThanControlTokensTest {

    @TempDir
    Path folder;

    @Test
    void thesystemPromptKeepsItsOwnRoleWhenAchatTemplateIsNamed() {
        List<Map<String, Object>> messages = ChatCompletionMessages.of(
                new PromptData("SYSTEM TEXT", "transcribe this", "chatml"), false);

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0)).containsEntry("role", "system")
                                   .containsEntry("content", "SYSTEM TEXT");
        assertThat(messages.get(1)).containsEntry("role", "user")
                                   .containsEntry("content", "transcribe this");
    }

    @Test
    void nocontrolTokenIsWrittenIntoAnyMessage() {
        for (String template : new String[] {"chatml", "alpaca", "llama3", "plain"}) {
            List<Map<String, Object>> messages = ChatCompletionMessages.of(
                    new PromptData("SYSTEM TEXT", "transcribe this", template), false);

            assertThat(messages.toString())
                    .as("a request built while %s is configured", template)
                    .doesNotContain("<|im_start|>")
                    .doesNotContain("<|begin_of_text|>")
                    .doesNotContain("<|end_of_turn|>")
                    .doesNotContain("### Instruction");
        }
    }

    /**
     * The template the configuration names is the one nobody passes, and it defaults to
     * {@code chatml}, so this is the shape every ordinary request had.
     */
    @Test
    void theconfiguredDefaultTemplateChangesNothingAboutWhatIsSent() {
        try (MockedStatic<ConfigManager> configs = mockStatic(ConfigManager.class)) {
            ConfigManager          manager  = mock(ConfigManager.class);
            Configuration          config   = mock(Configuration.class);
            Configuration.AiConfig aiConfig = mock(Configuration.AiConfig.class);
            configs.when(ConfigManager::getInstance).thenReturn(manager);
            when(manager.getConfig()).thenReturn(config);
            when(config.getAi()).thenReturn(aiConfig);
            when(aiConfig.getChatTemplate()).thenReturn("chatml");

            List<Map<String, Object>> messages = ChatCompletionMessages.of(
                    new PromptData("SYSTEM TEXT", "transcribe this"), false);

            assertThat(messages).hasSize(2);
            assertThat(messages.get(0)).containsEntry("role", "system");
            assertThat(messages.get(1)).containsEntry("role", "user");
            assertThat(messages.toString()).doesNotContain("<|im_start|>");
        }
    }

    @Test
    void anattachedPictureLeavesTheFirstMessageAplainString() throws IOException {
        PromptData withPicture = new PromptData("SYSTEM TEXT", "transcribe this", "chatml")
                .withImages(List.of(picture()));

        List<Map<String, Object>> messages = ChatCompletionMessages.of(withPicture, false);

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0)).containsEntry("role", "system")
                                   .containsEntry("content", "SYSTEM TEXT");
        assertThat(messages.get(0).get("content"))
                .as("the content a provider joining messages as strings reads first")
                .isInstanceOf(String.class);
        assertThat(messages.get(1).get("content")).isInstanceOf(List.class);
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
