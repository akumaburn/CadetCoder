package com.eonmux.cadetcoder.config;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code config ai.modelContextTokens.<provider>/<model> <tokens>} gives one model its window.
 *
 * <h2>Why the key is read whole</h2>
 *
 * <p>A setting name is split at its dots, and a model id may hold dots of its own, as
 * {@code qwen2.5-coder} does. Everything after {@code modelContextTokens.} is the key, so the
 * model id is filed as it was typed.</p>
 */
class AmodelsWindowCanBeSetWithConfigTest {

    @Test
    void awindowIsFiledUnderTheProviderAndModel() {
        Configuration config = new Configuration();

        ConfigOverrides.apply(config, "ai.modelContextTokens.Local/qwen2.5-coder", "32768");

        assertThat(config.getAi().getModelContextTokens())
                .containsEntry("local/qwen2.5-coder", 32_768);
    }

    @Test
    void ablankOrZeroWindowRemovesIt() {
        Configuration config = new Configuration();
        config.getAi().getModelContextTokens().put("local/qwen2.5-coder", 32_768);

        ConfigOverrides.apply(config, "ai.modelContextTokens.local/qwen2.5-coder", "0");

        assertThat(config.getAi().getModelContextTokens()).isEmpty();
    }

    @Test
    void akeyThatNamesNoModelIsRefused() {
        Configuration config = new Configuration();

        assertThatThrownBy(() -> ConfigOverrides.apply(config, "ai.modelContextTokens.qwen", "32768"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("<provider>/<model>");
        assertThatThrownBy(() -> ConfigOverrides.apply(config,
                                                       "ai.modelContextTokens.local/qwen", "lots"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void asettingWhoseLastKeyHoldsDotsIsFound() {
        Configuration config = new Configuration();
        config.getAi().getModelContextTokens().put("local/qwen2.5-coder", 32_768);
        JsonNode rendered = ConfigFile.rendered(config);

        JsonPointer path = RunOnlySettings.pointerTo(rendered,
                                                     "ai.modelContextTokens.local/qwen2.5-coder");

        assertThat(path).isNotNull();
        assertThat(rendered.at(path).asInt()).isEqualTo(32_768);
    }
}
