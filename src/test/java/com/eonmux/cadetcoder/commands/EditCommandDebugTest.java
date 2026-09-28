package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ai.AIManager;
import com.eonmux.cadetcoder.ai.PromptData;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.context.ContextEngine;
import com.eonmux.cadetcoder.error.ErrorHandler;
import com.eonmux.cadetcoder.git.GitIntegrationManager;
import com.eonmux.cadetcoder.session.SessionManager;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import com.eonmux.cadetcoder.test.ProjectFolder;
import org.mockito.MockedStatic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

public class EditCommandDebugTest {

    @Rule
    public TemporaryFolder tempFolder = new ProjectFolder();

    private EditCommand       editCommand;
    private TestOutputCapture outputCapture;
    private Path              testFile;

    @Before
    public void setUp() throws Exception {
        editCommand   = new EditCommand() {
            @Override
            public StepResult executeStep(String[] args, Map<String, Object> context, String llmResponse) {
                String step = (String) context.getOrDefault("step", "initial");
                System.err.println("DEBUG: Step = " + step + ", llmResponse = " + llmResponse);
                StepResult result = super.executeStep(args, context, llmResponse);
                System.err.println("DEBUG: StepResult complete=" +
                                   result.isComplete() +
                                   ", output=" +
                                   result.getOutput());
                return result;
            }
        };
        outputCapture = new TestOutputCapture();

        // Create test file
        testFile = tempFolder.newFile("TestClass.java").toPath();
        Files.write(testFile, "public class TestClass {\n    public void test() {}\n}".getBytes());

        // Set non-interactive mode for tests
        System.setProperty("cadet.interactive", "false");

        // Reset output capture after any initialization messages
        outputCapture.reset();
    }

    @After
    public void tearDown() {
        outputCapture.restore();
        // Clear system property
        System.clearProperty("cadet.interactive");
    }

    @Test
    public void testDebugSimpleEdit() throws Exception {
        try (MockedStatic<AIManager> aiMock = mockStatic(AIManager.class);
             MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class);
             MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedStatic<ContextEngine> contextMock = mockStatic(ContextEngine.class);
             MockedStatic<GitIntegrationManager> gitMock = mockStatic(GitIntegrationManager.class);
             MockedStatic<ErrorHandler> errorMock = mockStatic(ErrorHandler.class)) {

            // Mock SessionManager
            SessionManager mockSessionManager = mock(SessionManager.class);
            sessionMock.when(SessionManager::getInstance).thenReturn(mockSessionManager);

            // Mock ContextEngine
            ContextEngine mockContextEngine = mock(ContextEngine.class);
            when(mockContextEngine.searchRelevantSnippets(anyString())).thenReturn(new ArrayList<>());
            contextMock.when(ContextEngine::getInstance).thenReturn(mockContextEngine);

            // Mock ConfigManager
            ConfigManager mockConfigManager = mock(ConfigManager.class);
            Configuration mockConfig        = new Configuration();
            mockConfig.getGit().setAutoCommitEnabled(false);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            // Mock GitIntegrationManager
            GitIntegrationManager mockGitManager = mock(GitIntegrationManager.class);
            when(mockGitManager.isAvailable()).thenReturn(false);
            gitMock.when(GitIntegrationManager::getInstance).thenReturn(mockGitManager);

            // Mock ErrorHandler
            ErrorHandler mockErrorHandler = mock(ErrorHandler.class);
            errorMock.when(ErrorHandler::getInstance).thenReturn(mockErrorHandler);

            // Mock AI response with file content
            AIManager mockAIManager = mock(AIManager.class);
            aiMock.when(AIManager::getInstance).thenReturn(mockAIManager);

            String aiResponse = "File: " + testFile.toString() + "\n```java\n" +
                                "public class TestClass {\n" +
                                "    public void test() {\n" +
                                "        System.out.println(\"Hello, World!\");\n" +
                                "    }\n" +
                                "}\n```";
            when(mockAIManager.complete(any(PromptData.class), anyMap())).thenReturn(aiResponse);

            // Execute
            int result = editCommand.execute(new String[] {"add hello world to test method"});

            // Print debug info
            System.err.println("Exit code: " + result);
            System.err.println("All output: " + outputCapture.getAllOutput());

            // Verify
            assertThat(result).isEqualTo(0);
        }
    }
}