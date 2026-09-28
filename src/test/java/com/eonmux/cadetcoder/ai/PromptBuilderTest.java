package com.eonmux.cadetcoder.ai;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

public class PromptBuilderTest {

    private PromptBuilder builder;

    @BeforeEach
    void setUp() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager          mockConfigManager = mock(ConfigManager.class);
            Configuration          mockConfig        = mock(Configuration.class);
            Configuration.AiConfig mockAiConfig      = mock(Configuration.AiConfig.class);

            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);
            when(mockConfig.getAi()).thenReturn(mockAiConfig);
            // The builder reads the context section to know how much of a file a prompt may carry.
            when(mockConfig.getContext()).thenReturn(new Configuration.ContextConfig());
            when(mockAiConfig.getMaxTokens()).thenReturn(4096);

            builder = new PromptBuilder("analyze");
        }
    }

    @Test
    void testConstructor() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager          mockConfigManager = mock(ConfigManager.class);
            Configuration          mockConfig        = mock(Configuration.class);
            Configuration.AiConfig mockAiConfig      = mock(Configuration.AiConfig.class);

            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);
            when(mockConfig.getAi()).thenReturn(mockAiConfig);
            // The builder reads the context section to know how much of a file a prompt may carry.
            when(mockConfig.getContext()).thenReturn(new Configuration.ContextConfig());
            when(mockAiConfig.getMaxTokens()).thenReturn(4096);

            PromptBuilder pb = new PromptBuilder("explain");
            assertThat(pb).isNotNull();
        }
    }

    @Test
    void testConstructorWithCustomPrompts() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager          mockConfigManager = mock(ConfigManager.class);
            Configuration          mockConfig        = mock(Configuration.class);
            Configuration.AiConfig mockAiConfig      = mock(Configuration.AiConfig.class);

            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);
            when(mockConfig.getAi()).thenReturn(mockAiConfig);
            // The builder reads the context section to know how much of a file a prompt may carry.
            when(mockConfig.getContext()).thenReturn(new Configuration.ContextConfig());
            when(mockAiConfig.getMaxTokens()).thenReturn(4096);

            PromptBuilder pb = new PromptBuilder("custom", "Custom system prompt", "Custom reminder");
            assertThat(pb).isNotNull();
        }
    }

    @Test
    void testAddCodeFile() {
        builder.addCodeFile("Test.java", "public class Test {}");

        PromptData promptData = builder.buildCompletePromptData("test request");
        String     userPrompt = promptData.getUserPrompt();

        assertThat(userPrompt).contains("File: Test.java");
        assertThat(userPrompt).contains("public class Test {}");
    }

    @Test
    void testAddContextFile() {
        builder.addContextFile("Context.java", "public class Context {}");

        PromptData promptData = builder.buildCompletePromptData("test request");
        String     userPrompt = promptData.getUserPrompt();

        assertThat(userPrompt).contains("File: Context.java");
        assertThat(userPrompt).contains("public class Context {}");
    }

    @Test
    void testSetParameter() {
        builder.setParameter("key1", "value1");
        builder.setParameter("key2", 123);

        // Parameters are used internally for template substitution
        PromptData promptData = builder.buildCompletePromptData("test");
        assertThat(promptData).isNotNull();
    }

    @Test
    void testSetTokenBudget() {
        builder.setTokenBudget(2048);

        // Token budget affects internal allocation
        PromptData promptData = builder.buildCompletePromptData("test");
        assertThat(promptData).isNotNull();
    }

    @Test
    void testAddUserDialogue() {
        builder.addUserDialogue("User question");

        String builtPrompt = builder.build();

        assertThat(builtPrompt).contains("User question");
    }

    @Test
    void testAddSystemDialogue() {
        builder.addSystemDialogue("System info");

        String builtPrompt = builder.build();
        // System messages are included
        assertThat(builtPrompt).isNotNull();
    }

    @Test
    void testBuild_AnalyzeCommand() {
        builder.addCodeFile("Analyze.java", "public class Analyze { }");

        PromptData promptData = builder.buildCompletePromptData("analyze this");

        assertThat(promptData.getSystemPrompt()).contains("expert software engineer analyzing code");
        assertThat(promptData.getUserPrompt()).contains("Analyze.java");
    }

    @Test
    void testBuild_ExplainCommand() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager          mockConfigManager = mock(ConfigManager.class);
            Configuration          mockConfig        = mock(Configuration.class);
            Configuration.AiConfig mockAiConfig      = mock(Configuration.AiConfig.class);

            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);
            when(mockConfig.getAi()).thenReturn(mockAiConfig);
            // The builder reads the context section to know how much of a file a prompt may carry.
            when(mockConfig.getContext()).thenReturn(new Configuration.ContextConfig());
            when(mockAiConfig.getMaxTokens()).thenReturn(4096);

            PromptBuilder explainBuilder = new PromptBuilder("explain");
            explainBuilder.addCodeFile("Explain.java", "public class Explain { }");

            PromptData promptData = explainBuilder.buildCompletePromptData("explain this");

            assertThat(promptData.getSystemPrompt()).contains("expert software engineer explaining code");
        }
    }

    @Test
    void testBuild_SuggestCommand() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager          mockConfigManager = mock(ConfigManager.class);
            Configuration          mockConfig        = mock(Configuration.class);
            Configuration.AiConfig mockAiConfig      = mock(Configuration.AiConfig.class);

            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);
            when(mockConfig.getAi()).thenReturn(mockAiConfig);
            // The builder reads the context section to know how much of a file a prompt may carry.
            when(mockConfig.getContext()).thenReturn(new Configuration.ContextConfig());
            when(mockAiConfig.getMaxTokens()).thenReturn(4096);

            PromptBuilder suggestBuilder = new PromptBuilder("suggest");
            suggestBuilder.addCodeFile("Suggest.java", "public class Suggest { }");

            PromptData promptData = suggestBuilder.buildCompletePromptData("suggest improvements");

            assertThat(promptData.getSystemPrompt()).contains("expert software engineer suggesting improvements");
        }
    }

    @Test
    void testBuild_RefactorCommand() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager          mockConfigManager = mock(ConfigManager.class);
            Configuration          mockConfig        = mock(Configuration.class);
            Configuration.AiConfig mockAiConfig      = mock(Configuration.AiConfig.class);

            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);
            when(mockConfig.getAi()).thenReturn(mockAiConfig);
            // The builder reads the context section to know how much of a file a prompt may carry.
            when(mockConfig.getContext()).thenReturn(new Configuration.ContextConfig());
            when(mockAiConfig.getMaxTokens()).thenReturn(4096);

            PromptBuilder refactorBuilder = new PromptBuilder("refactor");
            refactorBuilder.addCodeFile("Refactor.java", "public class Refactor { }");

            PromptData promptData = refactorBuilder.buildCompletePromptData("refactor this");

            assertThat(promptData.getSystemPrompt()).contains("expert software engineer refactoring code");
        }
    }

    @Test
    void testBuild_EditCommand() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager          mockConfigManager = mock(ConfigManager.class);
            Configuration          mockConfig        = mock(Configuration.class);
            Configuration.AiConfig mockAiConfig      = mock(Configuration.AiConfig.class);

            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);
            when(mockConfig.getAi()).thenReturn(mockAiConfig);
            // The builder reads the context section to know how much of a file a prompt may carry.
            when(mockConfig.getContext()).thenReturn(new Configuration.ContextConfig());
            when(mockAiConfig.getMaxTokens()).thenReturn(4096);

            PromptBuilder editBuilder = new PromptBuilder("edit");
            editBuilder.setParameter("projectName", "TestProject");
            editBuilder.setParameter("currentFiles", "Main.java");
            editBuilder.setParameter("lastCommitMessage", "Initial commit");

            PromptData promptData = editBuilder.buildCompletePromptData("edit this");

            assertThat(promptData.getSystemPrompt()).contains("Java expert");
        }
    }

    @Test
    void testBuild_UnknownCommand() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager          mockConfigManager = mock(ConfigManager.class);
            Configuration          mockConfig        = mock(Configuration.class);
            Configuration.AiConfig mockAiConfig      = mock(Configuration.AiConfig.class);

            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);
            when(mockConfig.getAi()).thenReturn(mockAiConfig);
            // The builder reads the context section to know how much of a file a prompt may carry.
            when(mockConfig.getContext()).thenReturn(new Configuration.ContextConfig());
            when(mockAiConfig.getMaxTokens()).thenReturn(4096);

            PromptBuilder unknownBuilder = new PromptBuilder("unknown");
            unknownBuilder.addCodeFile("Unknown.java", "public class Unknown { }");

            PromptData promptData = unknownBuilder.buildCompletePromptData("unknown request");

            // Should use default system prompt
            assertThat(promptData.getSystemPrompt()).contains("You are an expert software engineer helping with code");
        }
    }

    @Test
    void testBuild_WithChatTemplate() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {

            ConfigManager          mockConfigManager = mock(ConfigManager.class);
            Configuration          mockConfig        = mock(Configuration.class);
            Configuration.AiConfig mockAiConfig      = mock(Configuration.AiConfig.class);

            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);
            when(mockConfig.getAi()).thenReturn(mockAiConfig);
            // The builder reads the context section to know how much of a file a prompt may carry.
            when(mockConfig.getContext()).thenReturn(new Configuration.ContextConfig());
            when(mockAiConfig.getMaxTokens()).thenReturn(4096);
            when(mockAiConfig.getChatTemplate()).thenReturn("test-template");

            builder.addCodeFile("Test.java", "code");
            PromptData promptData = builder.buildCompletePromptData("test");

            // Verify we got a PromptData with the template name
            assertThat(promptData.getChatTemplateName()).isEqualTo("test-template");
        }
    }

    @Test
    void testBuild_MultipleFilesAndMessages() {
        builder.addCodeFile("File1.java", "class File1 {}")
               .addCodeFile("File2.java", "class File2 {}")
               .addContextFile("Context.java", "class Context {}")
               .withContext("Test context")
               .addUserDialogue("Please analyze these files")
               .addSystemDialogue("Additional context");

        PromptData promptData = builder.buildCompletePromptData("analyze request");
        String     userPrompt = promptData.getUserPrompt();

        assertThat(userPrompt).contains("File1.java");
        assertThat(userPrompt).contains("File2.java");
        assertThat(userPrompt).contains("Context.java");
        assertThat(userPrompt).contains("analyze request");
    }

    @Test
    void testTemplate() {
        PromptBuilder.Template template = PromptBuilder.Template.CHATML;
        assertThat(template).isEqualTo(PromptBuilder.Template.CHATML);
    }

    @Test
    void testBuildWithTemplate() {
        builder.withContext("Test context")
               .addUserDialogue("User message")
               .usingTemplate(PromptBuilder.Template.CHATML);

        String result = builder.build();

        assertThat(result).contains("<|im_start|>system");
        assertThat(result).contains("Test context");
        assertThat(result).contains("<|im_start|>user");
        assertThat(result).contains("User message");
    }

    @Test
    void testPrepareInput() {
        builder.usingTemplate(PromptBuilder.Template.CHATML);
        String prepared = builder.prepareInput("test input");

        assertThat(prepared).contains("<|im_start|>user");
        assertThat(prepared).contains("test input");
        assertThat(prepared).contains("<|im_end|><|im_start|>assistant");
    }

    @Test
    void testBuildPrompt() {
        Configuration config = new Configuration();
        String result = builder.buildPrompt("edit request",
                Arrays.asList("snippet1", "snippet2"),
                "file content",
                config);

        assertThat(result).contains("edit request");
        assertThat(result).contains("file content");
    }

    @Test
    void testEquals() {
        PromptBuilder pb1 = new PromptBuilder("analyze").withContext("context");
        PromptBuilder pb2 = new PromptBuilder("analyze").withContext("context");
        PromptBuilder pb3 = new PromptBuilder("analyze").withContext("different");

        assertThat(pb1).isEqualTo(pb2);
        assertThat(pb1).isNotEqualTo(pb3);
    }

    @Test
    void testCompareTo() {
        PromptBuilder pb1 = new PromptBuilder("analyze").withContext("a");
        PromptBuilder pb2 = new PromptBuilder("analyze").withContext("b");
        PromptBuilder pb3 = new PromptBuilder("analyze");

        assertThat(pb1.compareTo(pb2)).isLessThan(0);
        assertThat(pb2.compareTo(pb1)).isGreaterThan(0);
        assertThat(pb1.compareTo(pb1)).isEqualTo(0);
        assertThat(pb3.compareTo(pb1)).isLessThan(0);
    }

    @Test
    void projectContextCannotCrowdOutTheCodeTheUserAskedAbout() {
        // The context is appended before the files, so an oversized one does not merely take space --
        // it pushes the files past the budget and they are dropped, leaving a prompt that describes
        // the project and contains none of its code. This repository's own CADET.md reached the size
        // of the entire budget, which is how the failure was found.
        PromptBuilder builder = new PromptBuilder("analyze")
                .setTokenBudget(4096)
                .addCodeFile("Subject.java", "class Subject { int answer() { return 42; } }");

        String userPrompt = builder.buildCompletePromptData("analyze this").getUserPrompt();

        assertThat(userPrompt).contains("Subject.java");
        assertThat(userPrompt).contains("analyze this");
    }
}