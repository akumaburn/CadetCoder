package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ai.AIManager;
import com.eonmux.cadetcoder.ai.PromptData;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.context.ContextEngine;
import com.eonmux.cadetcoder.error.ErrorHandler;
import com.eonmux.cadetcoder.git.GitIntegration;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

public class EditCommandTest {

    @Rule
    public TemporaryFolder tempFolder = new ProjectFolder();

    @Rule
    public TemporaryFolder outside = new TemporaryFolder();

    private EditCommand       editCommand;
    private TestOutputCapture outputCapture;
    private Path              testFile;

    @Before
    public void setUp() throws Exception {
        editCommand   = new EditCommand();
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
        System.clearProperty("cadet.test.mode");
        resetSingletons();
    }

    private void resetSingletons() {
        try {
            // Reset all singletons
            resetSingleton(AIManager.class);
            resetSingleton(SessionManager.class);
            resetSingleton(ConfigManager.class);
            resetSingleton(ContextEngine.class);
            resetSingleton(GitIntegrationManager.class);
            resetSingleton(ErrorHandler.class);
        } catch (Exception e) {
            // Ignore
        }
    }

    private void resetSingleton(Class<?> clazz) throws Exception {
        java.lang.reflect.Field instance = clazz.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    @Test
    public void testExecute_NoArguments() {
        // Mock AIManager for iterative execution
        try (MockedStatic<AIManager> aiMock = mockStatic(AIManager.class);
             MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class);
             MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedStatic<ContextEngine> contextMock = mockStatic(ContextEngine.class);
             MockedStatic<GitIntegrationManager> gitMock = mockStatic(GitIntegrationManager.class);
             MockedStatic<ErrorHandler> errorMock = mockStatic(ErrorHandler.class)) {

            // Mock basic dependencies
            setupBasicMocks(aiMock, sessionMock, contextMock);

            // Mock ConfigManager
            ConfigManager mockConfigManager = mock(ConfigManager.class);
            Configuration mockConfig        = new Configuration();
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            // Mock GitIntegrationManager
            GitIntegrationManager mockGitManager = mock(GitIntegrationManager.class);
            when(mockGitManager.isAvailable()).thenReturn(false);
            gitMock.when(GitIntegrationManager::getInstance).thenReturn(mockGitManager);

            // Mock ErrorHandler
            ErrorHandler mockErrorHandler = mock(ErrorHandler.class);
            errorMock.when(ErrorHandler::getInstance).thenReturn(mockErrorHandler);

            // Mock AIManager to return empty response (user cancels)
            AIManager mockAIManager = mock(AIManager.class);
            aiMock.when(AIManager::getInstance).thenReturn(mockAIManager);
            when(mockAIManager.complete(any(PromptData.class), anyMap())).thenReturn("");

            // Reset output capture just before executing to avoid initialization messages
            outputCapture.reset();

            int result = editCommand.execute(new String[0]);

            assertThat(result).isEqualTo(1);
            // Check both stdout and stderr since error messages go to stderr
            String allOutput = outputCapture.getStdout() + outputCapture.getStderr();
            assertThat(allOutput).contains("No edit request provided");
            assertThat(allOutput).contains("cancelled");
        }
    }

    private void setupBasicMocks(MockedStatic<AIManager> aiMock,
                                 MockedStatic<SessionManager> sessionMock,
                                 MockedStatic<ContextEngine> contextMock) {

        // Mock SessionManager
        SessionManager mockSessionManager = mock(SessionManager.class);
        sessionMock.when(SessionManager::getInstance).thenReturn(mockSessionManager);

        // Mock ContextEngine
        ContextEngine mockContextEngine = mock(ContextEngine.class);
        try {
            when(mockContextEngine.searchRelevantSnippets(anyString())).thenReturn(new ArrayList<>());
        } catch (Exception e) {
            // Won't happen with mock
        }
        contextMock.when(ContextEngine::getInstance).thenReturn(mockContextEngine);
    }

    @Test
    public void testExecute_NoArgumentsWithUserInput() throws Exception {
        // This test verifies that in non-interactive mode with no arguments, the command fails
        // Create test file
        Path testFile2 = tempFolder.newFile("UserFile.java").toPath();
        Files.write(testFile2, "public class UserFile {}".getBytes());

        try (MockedStatic<AIManager> aiMock = mockStatic(AIManager.class);
             MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class);
             MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedStatic<ContextEngine> contextMock = mockStatic(ContextEngine.class);
             MockedStatic<GitIntegrationManager> gitMock = mockStatic(GitIntegrationManager.class);
             MockedStatic<ErrorHandler> errorMock = mockStatic(ErrorHandler.class)) {

            // Mock dependencies
            setupMocks(aiMock, sessionMock, configMock, contextMock, gitMock);

            // Mock ErrorHandler
            ErrorHandler mockErrorHandler = mock(ErrorHandler.class);
            errorMock.when(ErrorHandler::getInstance).thenReturn(mockErrorHandler);

            // Execute without arguments in non-interactive mode
            int result = editCommand.execute(new String[0]);

            // In non-interactive mode with no arguments, command should fail
            assertThat(result).isEqualTo(1);
            // Check both stdout and stderr since error messages go to stderr
            String allOutput = outputCapture.getStdout() + outputCapture.getStderr();
            assertThat(allOutput).contains("No edit request provided");
            assertThat(allOutput).contains("cancelled");
        }
    }

    // Helper methods
    private void setupMocks(MockedStatic<AIManager> aiMock,
                            MockedStatic<SessionManager> sessionMock,
                            MockedStatic<ConfigManager> configMock,
                            MockedStatic<ContextEngine> contextMock,
                            MockedStatic<GitIntegrationManager> gitMock) {

        // Mock ErrorHandler
        ErrorHandler mockErrorHandler = mock(ErrorHandler.class);
        ErrorHandler.class.getDeclaredFields(); // Force class loading

        setupBasicMocks(aiMock, sessionMock, contextMock);

        // Mock ConfigManager with real Configuration object
        ConfigManager mockConfigManager = mock(ConfigManager.class);
        Configuration mockConfig        = new Configuration();
        mockConfig.getGit().setAutoCommitEnabled(false);
        when(mockConfigManager.getConfig()).thenReturn(mockConfig);
        configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

        // Mock GitIntegrationManager
        GitIntegrationManager mockGitManager = mock(GitIntegrationManager.class);
        when(mockGitManager.isAvailable()).thenReturn(false);
        gitMock.when(GitIntegrationManager::getInstance).thenReturn(mockGitManager);
    }

    @Test
    public void testExecute_SimpleEdit() throws Exception {
        try (MockedStatic<AIManager> aiMock = mockStatic(AIManager.class);
             MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class);
             MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedStatic<ContextEngine> contextMock = mockStatic(ContextEngine.class);
             MockedStatic<GitIntegrationManager> gitMock = mockStatic(GitIntegrationManager.class);
             MockedStatic<ErrorHandler> errorMock = mockStatic(ErrorHandler.class)) {

            // Mock dependencies
            setupMocks(aiMock, sessionMock, configMock, contextMock, gitMock);

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

            // Verify
            assertThat(result).isEqualTo(0);
            assertThat(outputCapture.getOutput()).contains("Changes applied successfully");

            // Check file was modified
            String content = Files.readString(testFile);
            assertThat(content).contains("System.out.println(\"Hello, World!\");");
        }
    }

    @Test
    public void testExecute_SearchReplaceBlock_appliesTargetedEditWithoutCorruption() throws Exception {
        try (MockedStatic<AIManager> aiMock = mockStatic(AIManager.class);
             MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class);
             MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedStatic<ContextEngine> contextMock = mockStatic(ContextEngine.class);
             MockedStatic<GitIntegrationManager> gitMock = mockStatic(GitIntegrationManager.class);
             MockedStatic<ErrorHandler> errorMock = mockStatic(ErrorHandler.class)) {

            setupMocks(aiMock, sessionMock, configMock, contextMock, gitMock);

            ErrorHandler mockErrorHandler = mock(ErrorHandler.class);
            errorMock.when(ErrorHandler::getInstance).thenReturn(mockErrorHandler);

            AIManager mockAIManager = mock(AIManager.class);
            aiMock.when(AIManager::getInstance).thenReturn(mockAIManager);

            // A COMPLIANT response in the format the system prompt demands. Previously this
            // wrote the marker lines verbatim and discarded the rest of the file.
            String aiResponse = "File: " + testFile.toString() + "\n```java\n" +
                                "<<<<<<< SEARCH\n" +
                                "    public void test() {}\n" +
                                "=======\n" +
                                "    public void test() {\n" +
                                "        System.out.println(\"Hello, World!\");\n" +
                                "    }\n" +
                                ">>>>>>> REPLACE\n" +
                                "```";
            when(mockAIManager.complete(any(PromptData.class), anyMap())).thenReturn(aiResponse);

            int result = editCommand.execute(new String[] {"add hello world to test method"});

            assertThat(result).isEqualTo(0);
            String content = Files.readString(testFile);
            // The targeted change is applied ...
            assertThat(content).contains("System.out.println(\"Hello, World!\");");
            // ... the surrounding original content is preserved ...
            assertThat(content).contains("public class TestClass {");
            assertThat(content.trim()).endsWith("}");
            // ... and the SEARCH/REPLACE scaffolding is NOT persisted into the file.
            assertThat(content).doesNotContain("<<<<<<<")
                               .doesNotContain("=======")
                               .doesNotContain("REPLACE");
        }
    }

    @Test
    public void testExecute_SearchReplaceBlock_searchNotFound_leavesFileUnchanged() throws Exception {
        try (MockedStatic<AIManager> aiMock = mockStatic(AIManager.class);
             MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class);
             MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedStatic<ContextEngine> contextMock = mockStatic(ContextEngine.class);
             MockedStatic<GitIntegrationManager> gitMock = mockStatic(GitIntegrationManager.class);
             MockedStatic<ErrorHandler> errorMock = mockStatic(ErrorHandler.class)) {

            setupMocks(aiMock, sessionMock, configMock, contextMock, gitMock);

            ErrorHandler mockErrorHandler = mock(ErrorHandler.class);
            errorMock.when(ErrorHandler::getInstance).thenReturn(mockErrorHandler);

            AIManager mockAIManager = mock(AIManager.class);
            aiMock.when(AIManager::getInstance).thenReturn(mockAIManager);

            // SEARCH text that does not exist in the file: the edit must fail and the file
            // must be left exactly as-is rather than partially/incorrectly rewritten.
            String aiResponse = "File: " + testFile.toString() + "\n```java\n" +
                                "<<<<<<< SEARCH\n" +
                                "this text is not in the file\n" +
                                "=======\n" +
                                "replacement\n" +
                                ">>>>>>> REPLACE\n" +
                                "```";
            when(mockAIManager.complete(any(PromptData.class), anyMap())).thenReturn(aiResponse);

            String originalContent = Files.readString(testFile);

            int result = editCommand.execute(new String[] {"edit the test method"});

            assertThat(result).isEqualTo(1);
            assertThat(Files.readString(testFile)).isEqualTo(originalContent);
        }
    }

    @Test
    public void testExecute_TruncatedSearchReplaceBlock_doesNotOverwriteFile() throws Exception {
        try (MockedStatic<AIManager> aiMock = mockStatic(AIManager.class);
             MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class);
             MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedStatic<ContextEngine> contextMock = mockStatic(ContextEngine.class);
             MockedStatic<GitIntegrationManager> gitMock = mockStatic(GitIntegrationManager.class);
             MockedStatic<ErrorHandler> errorMock = mockStatic(ErrorHandler.class)) {

            setupMocks(aiMock, sessionMock, configMock, contextMock, gitMock);

            ErrorHandler mockErrorHandler = mock(ErrorHandler.class);
            errorMock.when(ErrorHandler::getInstance).thenReturn(mockErrorHandler);

            AIManager mockAIManager = mock(AIManager.class);
            aiMock.when(AIManager::getInstance).thenReturn(mockAIManager);

            // A SEARCH block whose REPLACE body contained a nested fence: the outer regex
            // truncates the body at the inner ``` so the >>>>>>> terminator is lost. The
            // truncated marker-laden fragment must NOT be written over the file.
            String aiResponse = "File: " + testFile.toString() + "\n```java\n" +
                                "<<<<<<< SEARCH\n" +
                                "    public void test() {}\n" +
                                "=======\n" +
                                "    // replacement that referenced a nested fence\n" +
                                "```";
            when(mockAIManager.complete(any(PromptData.class), anyMap())).thenReturn(aiResponse);

            String originalContent = Files.readString(testFile);

            int result = editCommand.execute(new String[] {"edit the test method"});

            assertThat(result).isEqualTo(1);
            assertThat(Files.readString(testFile)).isEqualTo(originalContent);
            assertThat(outputCapture.getStdout() + outputCapture.getStderr())
                    .contains("truncated/malformed SEARCH/REPLACE");
        }
    }

    @Test
    public void testExecute_NoFenceResponse_SkipsWriteAndFails() throws Exception {
        try (MockedStatic<AIManager> aiMock = mockStatic(AIManager.class);
             MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class);
             MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedStatic<ContextEngine> contextMock = mockStatic(ContextEngine.class);
             MockedStatic<GitIntegrationManager> gitMock = mockStatic(GitIntegrationManager.class);
             MockedStatic<ErrorHandler> errorMock = mockStatic(ErrorHandler.class)) {

            setupMocks(aiMock, sessionMock, configMock, contextMock, gitMock);

            ErrorHandler mockErrorHandler = mock(ErrorHandler.class);
            errorMock.when(ErrorHandler::getInstance).thenReturn(mockErrorHandler);

            AIManager mockAIManager = mock(AIManager.class);
            aiMock.when(AIManager::getInstance).thenReturn(mockAIManager);

            // Plain prose with NO fenced code block: must NOT be written verbatim
            // over any file (that would be a destructive whole-file overwrite).
            String aiResponse = "Sure, I can help. Here is what I would change in your class.";
            when(mockAIManager.complete(any(PromptData.class), anyMap())).thenReturn(aiResponse);

            String originalContent = Files.readString(testFile);

            int result = editCommand.execute(new String[] {"edit the test method"});

            // The edit must fail (no fenced block to apply) and leave the file
            // untouched rather than overwriting it with the prose response.
            assertThat(result).isEqualTo(1);
            assertThat(Files.readString(testFile)).isEqualTo(originalContent);
            String allOutput = outputCapture.getStdout() + outputCapture.getStderr();
            assertThat(allOutput).contains("no fenced code block");
        }
    }

    /**
     * An edit leaves the file it edited and nothing else.
     *
     * <p><b>The defect</b>: {@code edit} copied every file it was about to change to a
     * {@code <name>.backup} sibling and never removed it. The copies were not a recovery mechanism
     * -- {@code undo} works through git, which already has the previous contents -- they were litter
     * in the project tree, untracked so a {@code undo --edit} hard reset left them, and picked up by
     * the auto-commit's {@code git add .} so they could be committed as source. {@code write} and
     * {@code multiedit} both keep none; only this one did.</p>
     */
    @Test
    public void testExecute_NoBackupOrTempSiblingLeftBehind() throws Exception {
        try (MockedStatic<AIManager> aiMock = mockStatic(AIManager.class);
             MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class);
             MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedStatic<ContextEngine> contextMock = mockStatic(ContextEngine.class);
             MockedStatic<GitIntegrationManager> gitMock = mockStatic(GitIntegrationManager.class);
             MockedStatic<ErrorHandler> errorMock = mockStatic(ErrorHandler.class)) {

            // Mock dependencies
            setupMocks(aiMock, sessionMock, configMock, contextMock, gitMock);

            // Mock ErrorHandler
            ErrorHandler mockErrorHandler = mock(ErrorHandler.class);
            errorMock.when(ErrorHandler::getInstance).thenReturn(mockErrorHandler);

            // Mock AI response
            AIManager mockAIManager = mock(AIManager.class);
            aiMock.when(AIManager::getInstance).thenReturn(mockAIManager);

            String aiResponse = "File: " + testFile.toString() + "\n```java\n" +
                                "public class TestClass {\n" +
                                "    // Modified version\n" +
                                "}\n```";
            when(mockAIManager.complete(any(PromptData.class), anyMap())).thenReturn(aiResponse);

            // Execute
            int result = editCommand.execute(new String[] {"modify the class"});

            assertThat(result).isEqualTo(0);
            assertThat(Files.readString(testFile)).contains("Modified version");
            Path backupPath = testFile.getParent().resolve(testFile.getFileName() + ".backup");
            assertThat(Files.exists(backupPath)).isFalse();
            try (java.util.stream.Stream<Path> siblings = Files.list(testFile.getParent())) {
                assertThat(siblings.map(path -> path.getFileName().toString()))
                        .as("an edit leaves the file it edited and nothing beside it")
                        .containsExactly(testFile.getFileName().toString());
            }
        }
    }

    @Test
    public void testExecute_MultipleFiles() throws Exception {
        // Create second test file
        Path testFile2 = tempFolder.newFile("TestClass2.java").toPath();
        Files.write(testFile2, "public class TestClass2 {}".getBytes());

        try (MockedStatic<AIManager> aiMock = mockStatic(AIManager.class);
             MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class);
             MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedStatic<ContextEngine> contextMock = mockStatic(ContextEngine.class);
             MockedStatic<GitIntegrationManager> gitMock = mockStatic(GitIntegrationManager.class);
             MockedStatic<ErrorHandler> errorMock = mockStatic(ErrorHandler.class)) {

            // Mock dependencies
            setupMocks(aiMock, sessionMock, configMock, contextMock, gitMock);

            // Mock ErrorHandler
            ErrorHandler mockErrorHandler = mock(ErrorHandler.class);
            errorMock.when(ErrorHandler::getInstance).thenReturn(mockErrorHandler);

            // Mock AI response with multiple files
            AIManager mockAIManager = mock(AIManager.class);
            aiMock.when(AIManager::getInstance).thenReturn(mockAIManager);

            String aiResponse = "File: " + testFile.toString() + "\n```java\n" +
                                "public class TestClass {\n    // Updated\n}\n```\n\n" +
                                "File: " + testFile2 + "\n```java\n" +
                                "public class TestClass2 {\n    // Updated\n}\n```";
            when(mockAIManager.complete(any(PromptData.class), anyMap())).thenReturn(aiResponse);

            // Execute
            int result = editCommand.execute(new String[] {"update both classes"});

            // Verify both files were modified
            assertThat(result).isEqualTo(0);
            assertThat(Files.readString(testFile)).contains("// Updated");
            assertThat(Files.readString(testFile2)).contains("// Updated");
        }
    }

    @Test
    public void testExecute_WithGitAutoCommit() throws Exception {
        try (MockedStatic<AIManager> aiMock = mockStatic(AIManager.class);
             MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class);
             MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedStatic<ContextEngine> contextMock = mockStatic(ContextEngine.class);
             MockedStatic<GitIntegrationManager> gitMock = mockStatic(GitIntegrationManager.class);
             MockedStatic<ErrorHandler> errorMock = mockStatic(ErrorHandler.class)) {

            // Setup basic mocks first
            setupBasicMocks(aiMock, sessionMock, contextMock);

            // Mock ErrorHandler
            ErrorHandler mockErrorHandler = mock(ErrorHandler.class);
            errorMock.when(ErrorHandler::getInstance).thenReturn(mockErrorHandler);

            // Mock dependencies with Git enabled
            ConfigManager mockConfigManager = mock(ConfigManager.class);
            Configuration mockConfig        = new Configuration();
            mockConfig.getGit().setAutoCommitEnabled(true);
            mockConfig.getGit().setCommitMessageTemplate("Auto-commit: {changeSummary}");
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            // Mock GitIntegrationManager
            GitIntegrationManager mockGitManager     = mock(GitIntegrationManager.class);
            GitIntegration        mockGitIntegration = mock(GitIntegration.class);
            when(mockGitManager.isAvailable()).thenReturn(true);
            when(mockGitManager.getGitIntegration()).thenReturn(mockGitIntegration);
            gitMock.when(GitIntegrationManager::getInstance).thenReturn(mockGitManager);

            // Setup other mocks
            setupBasicMocks(aiMock, sessionMock, contextMock);

            // Mock AI response
            AIManager mockAIManager = mock(AIManager.class);
            aiMock.when(AIManager::getInstance).thenReturn(mockAIManager);

            String aiResponse = "File: " + testFile.toString() + "\n```java\n" +
                                "public class TestClass { /* edited */ }\n```";
            when(mockAIManager.complete(any(PromptData.class), anyMap())).thenReturn(aiResponse);

            // Execute
            int result = editCommand.execute(new String[] {"edit the class"});

            // Verify git commit was called
            assertThat(result).isEqualTo(0);
            verify(mockGitIntegration).commitEverything(startsWith("Auto-commit: updated "));
        }
    }

    @Test
    public void testGetDescription() {
        assertThat(editCommand.getDescription()).isEqualTo("Edit files with AI assistance");
    }

    /**
     * A file block the model names outside the project is refused when the edit is applied, and
     * nothing is written there, even when the name climbs out with {@code ..}.
     */
    @Test
    public void aFileTheModelNamesOutsideTheProjectIsNotWritten() throws Exception {
        try (MockedStatic<AIManager> aiMock = mockStatic(AIManager.class);
             MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class);
             MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedStatic<ContextEngine> contextMock = mockStatic(ContextEngine.class);
             MockedStatic<GitIntegrationManager> gitMock = mockStatic(GitIntegrationManager.class);
             MockedStatic<ErrorHandler> errorMock = mockStatic(ErrorHandler.class)) {

            setupMocks(aiMock, sessionMock, configMock, contextMock, gitMock);
            errorMock.when(ErrorHandler::getInstance).thenReturn(mock(ErrorHandler.class));
            AIManager mockAIManager = mock(AIManager.class);
            aiMock.when(AIManager::getInstance).thenReturn(mockAIManager);

            Path   target  = outside.getRoot().toPath().resolve("sensitive.txt");
            String climbed = tempFolder.getRoot().toPath().relativize(target).toString();
            assertThat(climbed).startsWith("..");
            when(mockAIManager.complete(any(PromptData.class), anyMap()))
                    .thenReturn("File: " + climbed + "\n```\noverwritten\n```");

            int result = editCommand.execute(new String[] {"change " + climbed});

            assertThat(result).isEqualTo(1);
            assertThat(target).doesNotExist();
            assertThat(outputCapture.getStdout() + outputCapture.getStderr())
                    .contains("No changes were applied");
        }
    }

    @Test
    public void testGetUsage() {
        assertThat(editCommand.getUsage()).contains("edit");
    }
}