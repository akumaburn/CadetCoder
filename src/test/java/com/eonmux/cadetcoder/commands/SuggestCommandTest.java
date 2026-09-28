package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ai.AIManager;
import com.eonmux.cadetcoder.ai.PromptData;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import com.eonmux.cadetcoder.test.ProjectFolder;
import org.mockito.MockedStatic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

public class SuggestCommandTest {

    @Rule
    public TemporaryFolder tempFolder = new ProjectFolder();

    private SuggestCommand    suggestCommand;
    private TestOutputCapture outputCapture;

    @Before
    public void setUp() {
        suggestCommand = new SuggestCommand();
        outputCapture  = new TestOutputCapture();
        outputCapture.startCapture();
        // Set non-interactive mode for tests
        System.setProperty("cadet.interactive", "false");
    }

    @After
    public void tearDown() {
        outputCapture.stopCapture();
        // Clear system property
        System.clearProperty("cadet.interactive");
    }

    @Test
    public void testSuggestCommand_ImprovementsSuccess() throws Exception {
        // Create test file
        Path testFile = tempFolder.newFile("UserService.java").toPath();
        String code = "public class UserService {\n" +
                      "    public void saveUser(String name, int age) {\n" +
                      "        System.out.println(name + \" \" + age);\n" +
                      "    }\n" +
                      "}";
        Files.writeString(testFile, code);

        // Mock AIManager
        try (MockedStatic<AIManager> aiManagerMock = mockStatic(AIManager.class)) {
            AIManager mockManager = mock(AIManager.class);
            aiManagerMock.when(AIManager::getInstance).thenReturn(mockManager);

            // Setup mock response for generate_suggestions step
            String mockSuggestions = "Suggested improvements:\n" +
                                     "1. Add validation for name (null/empty check)\n" +
                                     "2. Add age validation (must be positive)\n" +
                                     "3. Use logging framework instead of System.out\n" +
                                     "4. Return a result object or boolean to indicate success";
            when(mockManager.complete(any(PromptData.class), any(Map.class)))
                    .thenReturn(mockSuggestions);

            // Execute command
            int exitCode = suggestCommand.execute(new String[] {"improvements", testFile.toString()});

            // Verify
            assertThat(exitCode).isEqualTo(0);
            String output = outputCapture.getStdout();
            assertThat(output).contains("Generating improvements suggestions for");
            assertThat(output).contains("AI Suggestions:");
            assertThat(output).contains("validation");

            // Verify AI was called once (generate_suggestions only in non-interactive mode)
            verify(mockManager, times(1)).complete(any(PromptData.class), any(Map.class));
        }
    }

    @Test
    public void testSuggestCommand_TestsSuccess() throws Exception {
        // Create test file
        Path testFile = tempFolder.newFile("Calculator.java").toPath();
        String code = "public class Calculator {\n" +
                      "    public int divide(int a, int b) {\n" +
                      "        return a / b;\n" +
                      "    }\n" +
                      "}";
        Files.writeString(testFile, code);

        // Mock AIManager
        try (MockedStatic<AIManager> aiManagerMock = mockStatic(AIManager.class)) {
            AIManager mockManager = mock(AIManager.class);
            aiManagerMock.when(AIManager::getInstance).thenReturn(mockManager);

            // Setup mock response for iterative execution
            String mockSuggestions = "Suggested test cases:\n" +
                                     "1. Test normal division: divide(10, 2) = 5\n" +
                                     "2. Test division by zero: should throw ArithmeticException\n" +
                                     "3. Test negative numbers: divide(-10, 2) = -5\n" +
                                     "4. Test zero dividend: divide(0, 5) = 0";
            when(mockManager.complete(any(PromptData.class), any(Map.class)))
                    .thenReturn(mockSuggestions);

            // Execute command
            int exitCode = suggestCommand.execute(new String[] {"tests", testFile.toString()});

            // Verify
            assertThat(exitCode).isEqualTo(0);
            String output = outputCapture.getStdout();
            assertThat(output).contains("Generating tests suggestions for");
            assertThat(output).contains("AI Suggestions:");
            assertThat(output).contains("division by zero");

            // Verify AI was called twice
            verify(mockManager, times(1)).complete(any(PromptData.class), any(Map.class));
        }
    }

    @Test
    public void testSuggestCommand_RefactoringSuccess() throws Exception {
        // Create test file
        Path testFile = tempFolder.newFile("LegacyCode.java").toPath();
        String code = "public class LegacyCode {\n" +
                      "    public String process(String s) {\n" +
                      "        if (s != null) {\n" +
                      "            if (s.length() > 0) {\n" +
                      "                return s.toUpperCase();\n" +
                      "            }\n" +
                      "        }\n" +
                      "        return null;\n" +
                      "    }\n" +
                      "}";
        Files.writeString(testFile, code);

        // Mock AIManager
        try (MockedStatic<AIManager> aiManagerMock = mockStatic(AIManager.class)) {
            AIManager mockManager = mock(AIManager.class);
            aiManagerMock.when(AIManager::getInstance).thenReturn(mockManager);

            // Setup mock response for iterative execution
            String mockSuggestions = "Refactoring suggestions:\n" +
                                     "1. Replace nested if with single condition using &&\n" +
                                     "2. Use Optional to handle null cases\n" +
                                     "3. Consider using StringUtils.isNotBlank()\n" +
                                     "4. Return empty string instead of null";
            when(mockManager.complete(any(PromptData.class), any(Map.class)))
                    .thenReturn(mockSuggestions);

            // Execute command
            int exitCode = suggestCommand.execute(new String[] {"refactoring", testFile.toString()});

            // Verify
            assertThat(exitCode).isEqualTo(0);
            String output = outputCapture.getStdout();
            assertThat(output).contains("Generating refactoring suggestions for");
            assertThat(output).contains("AI Suggestions:");
            assertThat(output).contains("Optional");

            // Verify AI was called twice
            verify(mockManager, times(1)).complete(any(PromptData.class), any(Map.class));
        }
    }

    @Test
    public void testSuggestCommand_FileNotFound() {
        // Mock AIManager for iterative execution
        try (MockedStatic<AIManager> aiManagerMock = mockStatic(AIManager.class)) {
            AIManager mockManager = mock(AIManager.class);
            aiManagerMock.when(AIManager::getInstance).thenReturn(mockManager);

            // Mock "no" response to search prompt
            when(mockManager.complete(any(PromptData.class), any(Map.class)))
                    .thenReturn("no");

            // Execute command with non-existent file
            int exitCode = suggestCommand.execute(new String[] {"improvements", "/non/existent/file.java"});

            // Verify
            assertThat(exitCode).isEqualTo(1);
            String output = outputCapture.getAllOutput();
            assertThat(output).contains("File not found");
        }
    }

    @Test
    public void testSuggestCommand_NoArguments() {
        // Mock AIManager for iterative execution
        try (MockedStatic<AIManager> aiManagerMock = mockStatic(AIManager.class)) {
            AIManager mockManager = mock(AIManager.class);
            aiManagerMock.when(AIManager::getInstance).thenReturn(mockManager);

            // Mock empty response to cancel
            when(mockManager.complete(any(PromptData.class), any(Map.class)))
                    .thenReturn("");

            // Execute command without arguments
            int exitCode = suggestCommand.execute(new String[] {});

            // Verify
            assertThat(exitCode).isEqualTo(1);
            String output = outputCapture.getAllOutput();
            assertThat(output).contains("No suggestion type specified");
        }
    }

    @Test
    public void testSuggestCommand_MissingFile() {
        // Mock AIManager for iterative execution
        try (MockedStatic<AIManager> aiManagerMock = mockStatic(AIManager.class)) {
            AIManager mockManager = mock(AIManager.class);
            aiManagerMock.when(AIManager::getInstance).thenReturn(mockManager);

            // Mock empty response to cancel
            when(mockManager.complete(any(PromptData.class), any(Map.class)))
                    .thenReturn("");

            // Execute command with only suggestion type
            int exitCode = suggestCommand.execute(new String[] {"improvements"});

            // Verify
            assertThat(exitCode).isEqualTo(1);
            String output = outputCapture.getAllOutput();
            assertThat(output).contains("Suggestion type: improvements");
        }
    }

    @Test
    public void testSuggestCommand_EmptyFile() throws Exception {
        // Create empty test file
        Path testFile = tempFolder.newFile("Empty.java").toPath();
        Files.writeString(testFile, "");

        // Execute command - empty file is handled directly without AI
        int exitCode = suggestCommand.execute(new String[] {"improvements", testFile.toString()});

        // Verify
        assertThat(exitCode).isEqualTo(0);
        String output = outputCapture.getStdout();
        assertThat(output).contains("The file is empty - no suggestions possible");
    }

    @Test
    public void testSuggestCommand_PathLikeFirstArgRejected() throws Exception {
        // A caller following an outdated "suggest <filepath>" signature passes a path
        // as arg-0. This must fail clearly instead of treating the path as a type.
        Path testFile = tempFolder.newFile("Service.java").toPath();
        Files.writeString(testFile, "public class Service {}");

        try (MockedStatic<AIManager> aiManagerMock = mockStatic(AIManager.class)) {
            AIManager mockManager = mock(AIManager.class);
            aiManagerMock.when(AIManager::getInstance).thenReturn(mockManager);

            int exitCode = suggestCommand.execute(new String[] {testFile.toString()});

            assertThat(exitCode).isEqualTo(1);
            String output = outputCapture.getAllOutput();
            assertThat(output).contains("looks like a file path");
            assertThat(output).contains("improvements, tests, refactoring, documentation, performance");

            // The AI must never be invoked when arg-0 fails type validation.
            verify(mockManager, never()).complete(any(PromptData.class), any(Map.class));
        }
    }

    @Test
    public void testSuggestCommand_InvalidTypeRejected() {
        try (MockedStatic<AIManager> aiManagerMock = mockStatic(AIManager.class)) {
            AIManager mockManager = mock(AIManager.class);
            aiManagerMock.when(AIManager::getInstance).thenReturn(mockManager);

            int exitCode = suggestCommand.execute(new String[] {"bananas", "SomeFile.java"});

            assertThat(exitCode).isEqualTo(1);
            String output = outputCapture.getAllOutput();
            assertThat(output).contains("Invalid suggestion type: 'bananas'");
            verify(mockManager, never()).complete(any(PromptData.class), any(Map.class));
        }
    }

    @Test
    public void testSuggestCommand_TypeIsCaseInsensitive() throws Exception {
        Path testFile = tempFolder.newFile("CaseService.java").toPath();
        Files.writeString(testFile, "public class CaseService {}");

        try (MockedStatic<AIManager> aiManagerMock = mockStatic(AIManager.class)) {
            AIManager mockManager = mock(AIManager.class);
            aiManagerMock.when(AIManager::getInstance).thenReturn(mockManager);
            when(mockManager.complete(any(PromptData.class), any(Map.class)))
                    .thenReturn("Some suggestions");

            // Mixed-case type should normalize to a valid lowercase type.
            int exitCode = suggestCommand.execute(new String[] {"Improvements", testFile.toString()});

            assertThat(exitCode).isEqualTo(0);
            String output = outputCapture.getStdout();
            assertThat(output).contains("Generating improvements suggestions for");
            verify(mockManager, times(1)).complete(any(PromptData.class), any(Map.class));
        }
    }

    @Test
    public void testGetDescription() {
        assertThat(suggestCommand.getDescription())
                .isEqualTo("Suggest tests, refactors or improvements for a file");
    }

    @Test
    public void testGetUsage() {
        assertThat(suggestCommand.getUsage())
                .isEqualTo("suggest <improvements|tests|refactoring|documentation|performance> [filepath]");
    }
}