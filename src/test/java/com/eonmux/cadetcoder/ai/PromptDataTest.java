package com.eonmux.cadetcoder.ai;

import com.eonmux.cadetcoder.ai.templates.ChatTemplate;
import com.eonmux.cadetcoder.ai.templates.ChatTemplateRegistry;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

public class PromptDataTest {

    @Test
    void testConstructor_BasicTwoArgs() {
        PromptData promptData = new PromptData("System prompt", "User prompt");

        assertThat(promptData.getSystemPrompt()).isEqualTo("System prompt");
        assertThat(promptData.getUserPrompt()).isEqualTo("User prompt");
        assertThat(promptData.getTemplate()).isNull();
        assertThat(promptData.getChatTemplateName()).isNull();
    }

    @Test
    void testConstructor_WithChatTemplateName() {
        PromptData promptData = new PromptData("System", "User", "chatml");

        assertThat(promptData.getSystemPrompt()).isEqualTo("System");
        assertThat(promptData.getUserPrompt()).isEqualTo("User");
        assertThat(promptData.getChatTemplateName()).isEqualTo("chatml");
        assertThat(promptData.getTemplate()).isNull();
    }

    @Test
    void testConstructor_WithLegacyTemplate() {
        PromptBuilder.Template template   = PromptBuilder.Template.CHATML;
        PromptData             promptData = new PromptData("System", "User", template);

        assertThat(promptData.getSystemPrompt()).isEqualTo("System");
        assertThat(promptData.getUserPrompt()).isEqualTo("User");
        assertThat(promptData.getTemplate()).isEqualTo(template);
        assertThat(promptData.getChatTemplateName()).isNull();
    }

    @Test
    void testConstructor_FullConstructor() {
        PromptBuilder.Template template   = PromptBuilder.Template.CHATML;
        PromptData             promptData = new PromptData("System", "User", template, "chatml");

        assertThat(promptData.getSystemPrompt()).isEqualTo("System");
        assertThat(promptData.getUserPrompt()).isEqualTo("User");
        assertThat(promptData.getTemplate()).isEqualTo(template);
        assertThat(promptData.getChatTemplateName()).isEqualTo("chatml");
    }

    @Test
    void testGetPrompt_DefaultConcatenation() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            // Mock configuration to return null template
            ConfigManager          mockConfigManager = mock(ConfigManager.class);
            Configuration          mockConfig        = mock(Configuration.class);
            Configuration.AiConfig mockAiConfig      = mock(Configuration.AiConfig.class);

            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);
            when(mockConfig.getAi()).thenReturn(mockAiConfig);
            when(mockAiConfig.getChatTemplate()).thenReturn(null);

            PromptData promptData = new PromptData("System prompt", "User prompt");

            String prompt = promptData.getPrompt();
            assertThat(prompt).isEqualTo("System prompt\n\nUser prompt");
        }
    }

    @Test
    void testGetPrompt_WithChatTemplate() {
        try (MockedStatic<ChatTemplateRegistry> registryMock = mockStatic(ChatTemplateRegistry.class)) {
            ChatTemplateRegistry mockRegistry = mock(ChatTemplateRegistry.class);
            ChatTemplate         mockTemplate = mock(ChatTemplate.class);

            registryMock.when(ChatTemplateRegistry::getInstance).thenReturn(mockRegistry);
            when(mockRegistry.getTemplate("chatml")).thenReturn(mockTemplate);
            when(mockTemplate.formatConversation(anyString(), any())).thenReturn("formatted prompt");

            PromptData promptData = new PromptData("System", "User", "chatml");
            String     prompt     = promptData.getPrompt();

            assertThat(prompt).isEqualTo("formatted prompt");
            verify(mockTemplate).formatConversation(eq("System"), any());
        }
    }

    @Test
    void testGetFormattedPrompt() {
        PromptData promptData = new PromptData("System", "User");

        assertThat(promptData.getFormattedPrompt()).isEqualTo(promptData.getPrompt());
    }

    @Test
    void testUseFormattedTemplate_NoChatTemplate() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            // Mock configuration to return null template
            ConfigManager          mockConfigManager = mock(ConfigManager.class);
            Configuration          mockConfig        = mock(Configuration.class);
            Configuration.AiConfig mockAiConfig      = mock(Configuration.AiConfig.class);

            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);
            when(mockConfig.getAi()).thenReturn(mockAiConfig);
            when(mockAiConfig.getChatTemplate()).thenReturn(null);

            PromptData promptData = new PromptData("System", "User");

            assertThat(promptData.useFormattedTemplate()).isFalse();
        }
    }

    @Test
    void testUseFormattedTemplate_WithPlainTemplate() {
        PromptData promptData = new PromptData("System", "User", "plain");

        assertThat(promptData.useFormattedTemplate()).isFalse();
    }

    @Test
    void testUseFormattedTemplate_WithOtherTemplate() {
        PromptData promptData = new PromptData("System", "User", "chatml");

        assertThat(promptData.useFormattedTemplate()).isTrue();
    }

    @Test
    void testToString() {
        PromptData promptData = new PromptData("System", "User");

        assertThat(promptData.toString()).isEqualTo(promptData.getPrompt());
    }

    @Test
    void testEquals() {
        PromptData pd1 = new PromptData("System", "User");
        PromptData pd2 = new PromptData("System", "User");
        PromptData pd3 = new PromptData("Different", "User");
        PromptData pd4 = new PromptData("System", "User", "template");

        assertThat(pd1).isEqualTo(pd2);
        assertThat(pd1).isNotEqualTo(pd3);
        assertThat(pd1).isNotEqualTo(pd4);
        assertThat(pd1).isNotEqualTo(null);
        assertThat(pd1).isNotEqualTo("not a PromptData");
    }

    @Test
    void testHashCode() {
        PromptData pd1 = new PromptData("System", "User");
        PromptData pd2 = new PromptData("System", "User");

        assertThat(pd1.hashCode()).isEqualTo(pd2.hashCode());
    }

    @Test
    void testNullHandling() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            // Mock configuration to return null template
            ConfigManager          mockConfigManager = mock(ConfigManager.class);
            Configuration          mockConfig        = mock(Configuration.class);
            Configuration.AiConfig mockAiConfig      = mock(Configuration.AiConfig.class);

            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);
            when(mockConfig.getAi()).thenReturn(mockAiConfig);
            when(mockAiConfig.getChatTemplate()).thenReturn(null);

            // Constructor should handle nulls
            PromptData promptData = new PromptData(null, null);

            assertThat(promptData.getSystemPrompt()).isNull();
            assertThat(promptData.getUserPrompt()).isNull();

            // getPrompt should handle nulls gracefully
            String prompt = promptData.getPrompt();
            assertThat(prompt).isEqualTo("null\n\nnull");
        }
    }

    @Test
    void testGetPrompt_WithConfiguredTemplate() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedStatic<ChatTemplateRegistry> registryMock = mockStatic(ChatTemplateRegistry.class)) {

            // Mock configuration
            ConfigManager          mockConfigManager = mock(ConfigManager.class);
            Configuration          mockConfig        = mock(Configuration.class);
            Configuration.AiConfig mockAiConfig      = mock(Configuration.AiConfig.class);

            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);
            when(mockConfig.getAi()).thenReturn(mockAiConfig);
            when(mockAiConfig.getChatTemplate()).thenReturn("configured-template");

            // Mock template registry
            ChatTemplateRegistry mockRegistry = mock(ChatTemplateRegistry.class);
            ChatTemplate         mockTemplate = mock(ChatTemplate.class);

            registryMock.when(ChatTemplateRegistry::getInstance).thenReturn(mockRegistry);
            when(mockRegistry.getTemplate("configured-template")).thenReturn(mockTemplate);
            when(mockTemplate.formatConversation(anyString(), any())).thenReturn("configured formatted prompt");

            PromptData promptData = new PromptData("System", "User");
            String     prompt     = promptData.getPrompt();

            assertThat(prompt).isEqualTo("configured formatted prompt");
        }
    }
}