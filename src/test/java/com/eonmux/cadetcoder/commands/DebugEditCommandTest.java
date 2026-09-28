package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ai.AIManager;
import com.eonmux.cadetcoder.ai.PromptData;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.context.ContextEngine;
import com.eonmux.cadetcoder.error.ErrorHandler;
import com.eonmux.cadetcoder.git.GitIntegrationManager;
import com.eonmux.cadetcoder.session.SessionManager;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import com.eonmux.cadetcoder.test.ProjectFolder;
import org.mockito.MockedStatic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

public class DebugEditCommandTest {

    @Rule
    public TemporaryFolder tempFolder = new ProjectFolder();

    @Test
    public void testDebugSimpleEdit() throws Exception {
        // Create test file
        Path testFile = tempFolder.newFile("TestClass.java").toPath();
        Files.write(testFile, "public class TestClass {}".getBytes());

        // Set non-interactive mode for tests
        System.setProperty("cadet.interactive", "false");

        try (MockedStatic<AIManager> aiMock = mockStatic(AIManager.class);
             MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class);
             MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedStatic<ContextEngine> contextMock = mockStatic(ContextEngine.class);
             MockedStatic<GitIntegrationManager> gitMock = mockStatic(GitIntegrationManager.class);
             MockedStatic<ErrorHandler> errorMock = mockStatic(ErrorHandler.class)) {

            // Mock ErrorHandler first
            ErrorHandler mockErrorHandler = mock(ErrorHandler.class);
            errorMock.when(ErrorHandler::getInstance).thenReturn(mockErrorHandler);
            doAnswer(invocation -> {
                Exception e = invocation.getArgument(0);
                System.err.println("ErrorHandler caught: " + e);
                e.printStackTrace();
                return null;
            }).when(mockErrorHandler).handleException(any(Exception.class));

            // Mock SessionManager
            SessionManager mockSessionManager = mock(SessionManager.class);
            sessionMock.when(SessionManager::getInstance).thenReturn(mockSessionManager);

            // Mock ContextEngine
            ContextEngine mockContextEngine = mock(ContextEngine.class);
            when(mockContextEngine.searchRelevantSnippets(anyString())).thenReturn(new ArrayList<>());
            contextMock.when(ContextEngine::getInstance).thenReturn(mockContextEngine);

            // Mock ConfigManager with complete configuration
            ConfigManager           mockConfigManager = mock(ConfigManager.class);
            Configuration           mockConfig        = new Configuration(); // Use real Configuration object
            Configuration.GitConfig gitConfig         = new Configuration.GitConfig();
            gitConfig.setAutoCommitEnabled(false);
            mockConfig.setGit(gitConfig);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            // Mock GitIntegrationManager
            GitIntegrationManager mockGitManager = mock(GitIntegrationManager.class);
            when(mockGitManager.isAvailable()).thenReturn(false);
            gitMock.when(GitIntegrationManager::getInstance).thenReturn(mockGitManager);

            // Mock AIManager
            AIManager mockAIManager = mock(AIManager.class);
            aiMock.when(AIManager::getInstance).thenReturn(mockAIManager);

            String aiResponse =
                    "File: " + testFile + "\n```java\npublic class TestClass {\n    // Modified\n}\n```";
            when(mockAIManager.complete(any(PromptData.class), anyMap())).thenReturn(aiResponse);

            // Execute
            EditCommand editCommand = new EditCommand();

            try {
                int result = editCommand.execute(new String[] {"add comment"});
                System.out.println("Result: " + result);

                // Check file was modified
                String content = Files.readString(testFile);
                System.out.println("File content: " + content);

                assertThat(result).isEqualTo(0);
                assertThat(content).contains("// Modified");
            } catch (Exception e) {
                System.err.println("Caught exception during test: " + e);
                e.printStackTrace();
                throw e;
            }
        } finally {
            // Clear system property
            System.clearProperty("cadet.interactive");
        }
    }
}