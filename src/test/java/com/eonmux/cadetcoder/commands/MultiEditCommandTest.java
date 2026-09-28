package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ai.AIManager;
import com.eonmux.cadetcoder.ai.PromptData;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

public class MultiEditCommandTest {

    @Rule
    public TemporaryFolder tempFolder = new ProjectFolder();

    private MultiEditCommand  multiEditCommand;
    private TestOutputCapture outputCapture;
    private Path              testFile;

    @Before
    public void setUp() throws Exception {
        multiEditCommand = new MultiEditCommand();
        outputCapture    = new TestOutputCapture();

        // Create test file
        testFile = tempFolder.newFile("test.txt").toPath();
        Files.write(testFile, "Line 1\nLine 2\nLine 3\nLine 4\nLine 5".getBytes());

        // Set non-interactive mode for tests
        System.setProperty("cadet.interactive", "false");

        // Reset output capture after any initialization messages
        outputCapture.reset();
    }

    @After
    public void tearDown() {
        outputCapture.restore();
        resetSingletons();
        // Clear system property
        System.clearProperty("cadet.interactive");
    }

    private void resetSingletons() {
        try {
            resetSingleton(AIManager.class);
            resetSingleton(SessionManager.class);
            resetSingleton(ConfigManager.class);
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
        try (MockedStatic<AIManager> aiManagerMock = mockStatic(AIManager.class)) {
            AIManager mockManager = mock(AIManager.class);
            aiManagerMock.when(AIManager::getInstance).thenReturn(mockManager);

            // Mock empty response to cancel
            when(mockManager.complete(any(PromptData.class), anyMap()))
                    .thenReturn("");

            // Reset output capture just before executing
            outputCapture.reset();

            int result = multiEditCommand.execute(new String[0]);

            assertThat(result).isEqualTo(1);
            String allOutput = outputCapture.getStdout() + outputCapture.getStderr();
            // Now it shows "No file specified" instead of "Usage: multiedit"
            assertThat(allOutput.contains("No file specified") ||
                       allOutput.contains("multiedit cancelled")).isTrue();
        }
    }

    @Test
    public void testExecute_FileNotFound() {
        // Mock AIManager for iterative execution
        try (MockedStatic<AIManager> aiManagerMock = mockStatic(AIManager.class)) {
            AIManager mockManager = mock(AIManager.class);
            aiManagerMock.when(AIManager::getInstance).thenReturn(mockManager);

            // Mock "no" response to search prompt
            when(mockManager.complete(any(PromptData.class), anyMap()))
                    .thenReturn("no");

            // Reset output capture just before executing
            outputCapture.reset();

            int result = multiEditCommand.execute(new String[] {"nonexistent.txt", "old", "new"});

            assertThat(result).isEqualTo(1);
            String allOutput = outputCapture.getStdout() + outputCapture.getStderr();
            assertThat(allOutput).contains("File not found: nonexistent.txt");
        }
    }

    @Test
    public void testExecute_DirectEditOperations() throws Exception {
        try (MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class);
             MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedStatic<GitIntegrationManager> gitMock = mockStatic(GitIntegrationManager.class)) {

            setupBasicMocks(sessionMock, configMock, gitMock);

            // Execute direct edit operations
            int result = multiEditCommand.execute(new String[] {
                    testFile.toString(),
                    "Line 2", "Modified Line 2",
                    "Line 4", "Modified Line 4"
            });

            // Verify
            assertThat(result).isEqualTo(0);
            String content = Files.readString(testFile);
            assertThat(content).contains("Modified Line 2");
            assertThat(content).contains("Modified Line 4");
            assertThat(content).contains("Line 1");
            assertThat(content).contains("Line 3");
            assertThat(content).contains("Line 5");
            // Output format may vary with iterative execution
            String output = outputCapture.getOutput();
            assertThat(output.contains("Applying 2 edit operations") ||
                       output.contains("Applied edit") ||
                       output.contains("edits applied")).isTrue();
        }
    }

    // Helper methods
    private void setupBasicMocks(MockedStatic<SessionManager> sessionMock,
                                 MockedStatic<ConfigManager> configMock,
                                 MockedStatic<GitIntegrationManager> gitMock) {

        // Mock SessionManager
        SessionManager mockSessionManager = mock(SessionManager.class);
        sessionMock.when(SessionManager::getInstance).thenReturn(mockSessionManager);

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
    public void testExecute_ReplaceAll() throws Exception {
        // Create file with repeated content
        Files.write(testFile, "foo bar foo baz foo".getBytes());

        try (MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class);
             MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedStatic<GitIntegrationManager> gitMock = mockStatic(GitIntegrationManager.class)) {

            setupBasicMocks(sessionMock, configMock, gitMock);

            // Create command with replaceAll flag. The flag is passed in argv: execute() is the
            // single place that parses it now, so -r works from the shell / script / agentic paths
            // too, where it was previously advertised but inert.
            MultiEditCommand cmd = new MultiEditCommand();

            // Execute
            int result = cmd.execute(new String[] {testFile.toString(), "foo", "replaced", "-r"});

            // Verify all occurrences were replaced
            assertThat(result).isEqualTo(0);
            String content = Files.readString(testFile);
            assertThat(content).isEqualTo("replaced bar replaced baz replaced");
        }
    }

    @Test
    public void testExecute_NaturalLanguageRequest() throws Exception {
        try (MockedStatic<AIManager> aiMock = mockStatic(AIManager.class);
             MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class);
             MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedStatic<GitIntegrationManager> gitMock = mockStatic(GitIntegrationManager.class);
             MockedStatic<ErrorHandler> errorMock = mockStatic(ErrorHandler.class)) {

            setupBasicMocks(sessionMock, configMock, gitMock);

            // Mock ErrorHandler
            ErrorHandler mockErrorHandler = mock(ErrorHandler.class);
            errorMock.when(ErrorHandler::getInstance).thenReturn(mockErrorHandler);

            // Mock AI response
            AIManager mockAIManager = mock(AIManager.class);
            aiMock.when(AIManager::getInstance).thenReturn(mockAIManager);

            String aiResponse = "EDIT_START\n" +
                                "OLD: Line 2\n" +
                                "NEW: Updated Line 2\n" +
                                "REPLACE_ALL: false\n" +
                                "EDIT_END\n\n" +
                                "EDIT_START\n" +
                                "OLD: Line 4\n" +
                                "NEW: Updated Line 4\n" +
                                "REPLACE_ALL: true\n" +
                                "EDIT_END";
            when(mockAIManager.complete(any(PromptData.class), anyMap())).thenReturn(aiResponse);

            // Execute
            int result = multiEditCommand.execute(new String[] {
                    testFile.toString(),
                    "please update lines 2 and 4"
            });

            // Verify
            assertThat(result).isEqualTo(0);
            String content = Files.readString(testFile);
            assertThat(content).contains("Updated Line 2");
            assertThat(content).contains("Updated Line 4");
        }
    }

    @Test
    public void testExecute_MultilineEdit() throws Exception {
        // Create file with multiline content
        String originalContent = "function test() {\n    console.log('old');\n    return true;\n}";
        Files.write(testFile, originalContent.getBytes());

        try (MockedStatic<AIManager> aiMock = mockStatic(AIManager.class);
             MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class);
             MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedStatic<GitIntegrationManager> gitMock = mockStatic(GitIntegrationManager.class);
             MockedStatic<ErrorHandler> errorMock = mockStatic(ErrorHandler.class)) {

            setupBasicMocks(sessionMock, configMock, gitMock);

            // Mock ErrorHandler
            ErrorHandler mockErrorHandler = mock(ErrorHandler.class);
            errorMock.when(ErrorHandler::getInstance).thenReturn(mockErrorHandler);

            // Mock AI response with multiline edit
            AIManager mockAIManager = mock(AIManager.class);
            aiMock.when(AIManager::getInstance).thenReturn(mockAIManager);

            String aiResponse = "EDIT_START\n" +
                                "OLD: function test() {\n    console.log('old');\n    return true;\n}\n" +
                                "NEW: function test() {\n    console.log('new');\n    console.log('added line');\n    return false;\n}\n" +
                                "REPLACE_ALL: false\n" +
                                "EDIT_END";
            when(mockAIManager.complete(any(PromptData.class), anyMap())).thenReturn(aiResponse);

            // Execute
            int result = multiEditCommand.execute(new String[] {
                    testFile.toString(),
                    "update the function"
            });

            // Verify
            assertThat(result).isEqualTo(0);
            String content = Files.readString(testFile);
            assertThat(content).contains("console.log('new')");
            assertThat(content).contains("console.log('added line')");
            assertThat(content).contains("return false");
        }
    }

    @Test
    public void testExecute_FailedEdit_Rollback() throws Exception {
        try (MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class);
             MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedStatic<GitIntegrationManager> gitMock = mockStatic(GitIntegrationManager.class)) {

            setupBasicMocks(sessionMock, configMock, gitMock);

            String originalContent = Files.readString(testFile);

            // Execute with non-existent string
            int result = multiEditCommand.execute(new String[] {
                    testFile.toString(),
                    "Line 2", "Modified Line 2",
                    "NonExistent", "Should Fail"
            });

            // Verify rollback
            assertThat(result).isEqualTo(1);
            String allOutput = outputCapture.getStdout() + outputCapture.getStderr();
            // Either old or new error message is acceptable
            assertThat(allOutput.contains("No edits were applied") ||
                       allOutput.contains("Rolling back all changes") ||
                       allOutput.contains("No matches found") ||
                       allOutput.contains("String not found")).isTrue();

            // File should be unchanged
            String content = Files.readString(testFile);
            assertThat(content).isEqualTo(originalContent);
        }
    }

    @Test
    public void testExecute_NoChanges() throws Exception {
        try (MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class);
             MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedStatic<GitIntegrationManager> gitMock = mockStatic(GitIntegrationManager.class)) {

            setupBasicMocks(sessionMock, configMock, gitMock);

            // Execute with non-existent strings
            int result = multiEditCommand.execute(new String[] {
                    testFile.toString(),
                    "NonExistent1", "Replace1",
                    "NonExistent2", "Replace2"
            });

            // Should fail since no matches found
            assertThat(result).isEqualTo(1);
            String allOutput = outputCapture.getStdout() + outputCapture.getStderr();
            // Either message is acceptable
            assertThat(allOutput.contains("No edits were applied") ||
                       allOutput.contains("No matches found") ||
                       allOutput.contains("String not found") ||
                       allOutput.contains("Rolling back")).isTrue();
        }
    }

    @Test
    public void testExecute_NoBackupOrTempSiblingLeftBehind() throws Exception {
        try (MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class);
             MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedStatic<GitIntegrationManager> gitMock = mockStatic(GitIntegrationManager.class)) {

            setupBasicMocks(sessionMock, configMock, gitMock);

            // Execute
            int result = multiEditCommand.execute(new String[] {
                    testFile.toString(),
                    "Line 2", "Modified"
            });

            // Verify the edit succeeded and no sibling backup/temp file lingers.
            assertThat(result).isEqualTo(0);
            assertThat(Files.readString(testFile)).contains("Modified");
            Path backupPath = testFile.getParent().resolve(testFile.getFileName() + ".backup");
            Path tempPath   = testFile.getParent().resolve(testFile.getFileName() + ".tmp");
            assertThat(Files.exists(backupPath)).isFalse();
            assertThat(Files.exists(tempPath)).isFalse();
        }
    }

    @Test
    public void testExecute_StructuredEditBlocks_ParsedWithoutAI() throws Exception {
        // A single edits argument containing EDIT_START blocks must be parsed
        // deterministically with no LLM round-trip.
        try (MockedStatic<AIManager> aiMock = mockStatic(AIManager.class);
             MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class);
             MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedStatic<GitIntegrationManager> gitMock = mockStatic(GitIntegrationManager.class)) {

            setupBasicMocks(sessionMock, configMock, gitMock);

            AIManager mockAIManager = mock(AIManager.class);
            aiMock.when(AIManager::getInstance).thenReturn(mockAIManager);

            String structuredEdits =
                    "EDIT_START\n" +
                    "OLD: Line 2\n" +
                    "NEW: Updated Line 2\n" +
                    "REPLACE_ALL: false\n" +
                    "EDIT_END\n\n" +
                    "EDIT_START\n" +
                    "OLD: Line 4\n" +
                    "NEW: Updated Line 4\n" +
                    "REPLACE_ALL: false\n" +
                    "EDIT_END";

            int result = multiEditCommand.execute(new String[] {
                    testFile.toString(),
                    structuredEdits
            });

            assertThat(result).isEqualTo(0);
            String content = Files.readString(testFile);
            assertThat(content).contains("Updated Line 2");
            assertThat(content).contains("Updated Line 4");
            // No hidden LLM call should occur on the structured path.
            verify(mockAIManager, never()).complete(any(PromptData.class), anyMap());
        }
    }

    @Test
    public void testExecute_StructuredEditBlock_PreservesIndentation() throws Exception {
        // The OLD/NEW values must keep leading/trailing whitespace verbatim; previously the parser
        // stripped it, so indented source lines never matched and ALL edits rolled back.
        Path indented = tempFolder.newFile("Indented.java").toPath();
        Files.writeString(indented, "class C {\n    int x = 1;\n}\n");

        try (MockedStatic<AIManager> aiMock = mockStatic(AIManager.class);
             MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class);
             MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedStatic<GitIntegrationManager> gitMock = mockStatic(GitIntegrationManager.class)) {

            setupBasicMocks(sessionMock, configMock, gitMock);
            AIManager mockAIManager = mock(AIManager.class);
            aiMock.when(AIManager::getInstance).thenReturn(mockAIManager);

            String structuredEdits =
                    "EDIT_START\n" +
                    "OLD:     int x = 1;\n" +
                    "NEW:     int x = 2;\n" +
                    "REPLACE_ALL: false\n" +
                    "EDIT_END";

            int result = multiEditCommand.execute(new String[] {indented.toString(), structuredEdits});

            assertThat(result).isEqualTo(0);
            // Indentation preserved and the edit applied exactly.
            assertThat(Files.readString(indented)).isEqualTo("class C {\n    int x = 2;\n}\n");
            verify(mockAIManager, never()).complete(any(PromptData.class), anyMap());
        }
    }

    @Test
    public void testExecute_StructuredEditBlock_EmptyOldRejectedNoCorruption() throws Exception {
        try (MockedStatic<AIManager> aiMock = mockStatic(AIManager.class);
             MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class);
             MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedStatic<GitIntegrationManager> gitMock = mockStatic(GitIntegrationManager.class)) {

            setupBasicMocks(sessionMock, configMock, gitMock);
            AIManager mockAIManager = mock(AIManager.class);
            aiMock.when(AIManager::getInstance).thenReturn(mockAIManager);

            String original = Files.readString(testFile);
            // An empty OLD would otherwise match at index 0 / between every char and corrupt the file.
            String structuredEdits =
                    "EDIT_START\n" +
                    "OLD:\n" +
                    "NEW: injected\n" +
                    "REPLACE_ALL: true\n" +
                    "EDIT_END";

            int result = multiEditCommand.execute(new String[] {testFile.toString(), structuredEdits});

            assertThat(result).isEqualTo(1);
            assertThat(Files.readString(testFile)).isEqualTo(original);
        }
    }

    @Test
    public void testExecute_StructuredEditBlocks_ReplaceAllHonored() throws Exception {
        Files.write(testFile, "foo bar foo baz foo".getBytes());

        try (MockedStatic<AIManager> aiMock = mockStatic(AIManager.class);
             MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class);
             MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedStatic<GitIntegrationManager> gitMock = mockStatic(GitIntegrationManager.class)) {

            setupBasicMocks(sessionMock, configMock, gitMock);

            AIManager mockAIManager = mock(AIManager.class);
            aiMock.when(AIManager::getInstance).thenReturn(mockAIManager);

            String structuredEdits =
                    "EDIT_START\n" +
                    "OLD: foo\n" +
                    "NEW: replaced\n" +
                    "REPLACE_ALL: true\n" +
                    "EDIT_END";

            int result = multiEditCommand.execute(new String[] {
                    testFile.toString(),
                    structuredEdits
            });

            assertThat(result).isEqualTo(0);
            assertThat(Files.readString(testFile)).isEqualTo("replaced bar replaced baz replaced");
            verify(mockAIManager, never()).complete(any(PromptData.class), anyMap());
        }
    }

    @Test
    public void testExecute_NoOperationsParsed_IsFailure() throws Exception {
        // When the AI returns no valid edit blocks, the command must fail
        // (non-zero) rather than report a no-op as success.
        try (MockedStatic<AIManager> aiMock = mockStatic(AIManager.class);
             MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class);
             MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedStatic<GitIntegrationManager> gitMock = mockStatic(GitIntegrationManager.class)) {

            setupBasicMocks(sessionMock, configMock, gitMock);

            AIManager mockAIManager = mock(AIManager.class);
            aiMock.when(AIManager::getInstance).thenReturn(mockAIManager);
            when(mockAIManager.complete(any(PromptData.class), anyMap()))
                    .thenReturn("Sorry, I could not determine any edits.");

            String originalContent = Files.readString(testFile);

            int result = multiEditCommand.execute(new String[] {
                    testFile.toString(),
                    "please make some change"
            });

            assertThat(result).isEqualTo(1);
            // File must remain untouched.
            assertThat(Files.readString(testFile)).isEqualTo(originalContent);
        }
    }

    @Test
    public void testGetDescription() {
        assertThat(multiEditCommand.getDescription()).isEqualTo("Apply several edits to one file, all or nothing");
    }

    @Test
    public void testGetUsage() {
        assertThat(multiEditCommand.getUsage()).contains("multiedit");
    }
}