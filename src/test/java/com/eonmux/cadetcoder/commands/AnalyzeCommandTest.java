package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ai.AIManager;
import com.eonmux.cadetcoder.ai.PromptData;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import com.eonmux.cadetcoder.test.ProjectFolder;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

public class AnalyzeCommandTest {

    @Rule
    public TemporaryFolder tempFolder = new ProjectFolder();

    private AnalyzeCommand    analyzeCommand;
    private TestOutputCapture outputCapture;

    @Before
    public void setUp() {
        analyzeCommand = new AnalyzeCommand();
        outputCapture  = new TestOutputCapture();
        outputCapture.startCapture();
    }

    @After
    public void tearDown() {
        outputCapture.stopCapture();
    }

    @Test
    public void testAnalyzeCommand_Success() throws Exception {
        // Create test file
        Path testFile = tempFolder.newFile("TestClass.java").toPath();
        String code = "public class TestClass {\n" +
                      "    public void method() {\n" +
                      "        System.out.println(\"Hello\");\n" +
                      "    }\n" +
                      "}";
        Files.writeString(testFile, code);

        // Mock AIManager
        try (MockedStatic<AIManager> aiManagerMock = mockStatic(AIManager.class)) {
            AIManager mockManager = mock(AIManager.class);
            aiManagerMock.when(AIManager::getInstance).thenReturn(mockManager);

            // Setup mock response for iterative execution
            String mockAnalysis = "Code Analysis:\n" +
                                  "- Well-structured class\n" +
                                  "- Consider adding documentation\n" +
                                  "- Method could be static";
            when(mockManager.complete(any(PromptData.class), any(Map.class)))
                    .thenReturn(mockAnalysis);

            // Execute command - it will use IterativeExecutor internally
            int exitCode = analyzeCommand.execute(new String[] {testFile.toString()});

            // Verify
            assertThat(exitCode).isEqualTo(0);
            String output = outputCapture.getAllOutput();
            assertThat(output).contains("AI Analysis:");
            assertThat(output).contains("Code Analysis:");
            assertThat(output).contains("Well-structured class");

            // Verify AI was called at least once
            verify(mockManager, atLeastOnce()).complete(any(PromptData.class), any(Map.class));
        }
    }

    /**
     * A name nothing in the project resembles, so what is being tested is the missing file and not
     * whatever happens to be checked in.
     *
     * <p>This said {@code /non/existent/file.java}, which every one of {@code ConfigFile.java},
     * {@code ProjectFile.java} and {@code OwnerOnlyFile.java} is a partial match for. It passed
     * only because the search that would have found them was walking with a depth counter that
     * leaked, and found nothing at all. With the search working, the command properly offers the
     * three and this stopped being a test about a missing file.</p>
     */
    @Test
    public void testAnalyzeCommand_FileNotFound() {
        // Mock AIManager for iterative execution
        try (MockedStatic<AIManager> aiManagerMock = mockStatic(AIManager.class)) {
            AIManager mockManager = mock(AIManager.class);
            aiManagerMock.when(AIManager::getInstance).thenReturn(mockManager);

            // Setup mock to handle the search prompt
            when(mockManager.complete(any(PromptData.class), any(Map.class)))
                    .thenReturn("No, don't search");

            // Execute command with non-existent file
            int exitCode = analyzeCommand.execute(
                    new String[] {"/non/existent/zzqqxx-nothing-resembles-this.java"});

            // Verify - the iterative executor will handle the missing file
            assertThat(exitCode).isEqualTo(1);
            String output = outputCapture.getAllOutput();
            assertThat(output).contains("File not found");
        }
    }

    /**
     * A name that is not a file but does resemble several is offered as a choice.
     *
     * <p>With nobody at the terminal the executor answers a numbered choice with the first option,
     * which is its stated policy, so the run analyses that file and succeeds. Stated here because
     * it is the outcome of two separate decisions -- the resolver's partial matching and the
     * executor's default answer -- and neither of them mentions the other.</p>
     */
    @Test
    public void apathThatResemblesSeveralRealFilesIsOfferedAsAChoice() {
        try (MockedStatic<AIManager> aiManagerMock = mockStatic(AIManager.class)) {
            AIManager mockManager = mock(AIManager.class);
            aiManagerMock.when(AIManager::getInstance).thenReturn(mockManager);
            when(mockManager.complete(any(PromptData.class), any(Map.class)))
                    .thenReturn("an analysis of whatever it settled on");

            int exitCode = analyzeCommand.execute(new String[] {"/non/existent/file.java"});

            String output = outputCapture.getAllOutput();
            assertThat(output).contains("File not found");
            assertThat(output).contains("Did you mean one of these?");
            assertThat(exitCode).isZero();
        }
    }

    @Test
    public void testAnalyzeCommand_NoArguments() {
        // Mock AIManager for iterative execution
        try (MockedStatic<AIManager> aiManagerMock = mockStatic(AIManager.class)) {
            AIManager mockManager = mock(AIManager.class);
            aiManagerMock.when(AIManager::getInstance).thenReturn(mockManager);

            // Setup mock to handle the file request
            // First call: ask for file name, return a non-existent file
            // Second call: when asked if we should search, say no
            when(mockManager.complete(any(PromptData.class), any(Map.class)))
                    .thenReturn("NonExistentFile.java")  // Provide a file name that doesn't exist
                    .thenReturn("no");  // Don't search when file not found

            // Execute command without arguments
            int exitCode = analyzeCommand.execute(new String[] {});

            // Verify - will complete with cancellation (exit code 1)
            assertThat(exitCode).isEqualTo(1);
            String output = outputCapture.getAllOutput();
            assertThat(output).contains("Analysis cancelled");
        }
    }

    @Test
    public void testAnalyzeCommand_EmptyFile() throws Exception {
        // Create empty test file
        Path testFile = tempFolder.newFile("Empty.java").toPath();
        Files.writeString(testFile, "");

        // Mock AIManager
        try (MockedStatic<AIManager> aiManagerMock = mockStatic(AIManager.class)) {
            AIManager mockManager = mock(AIManager.class);
            aiManagerMock.when(AIManager::getInstance).thenReturn(mockManager);

            // Setup mock response
            String mockAnalysis = "Empty file - no code to analyze";
            when(mockManager.complete(any(PromptData.class), any(Map.class)))
                    .thenReturn(mockAnalysis);

            // Execute command
            int exitCode = analyzeCommand.execute(new String[] {testFile.toString()});

            // Verify
            assertThat(exitCode).isEqualTo(0);
            String output = outputCapture.getAllOutput();
            assertThat(output).contains("AI Analysis:");
            assertThat(output).contains("Empty file");
        }
    }

    @Test
    public void testAnalyzeCommand_LargeFile() throws Exception {
        // Create test file with substantial content
        Path          testFile = tempFolder.newFile("LargeClass.java").toPath();
        StringBuilder code     = new StringBuilder("public class LargeClass {\n");
        for (int i = 0; i < 50; i++) {
            code.append("    public void method").append(i).append("() {\n");
            code.append("        System.out.println(\"Method ").append(i).append("\");\n");
            code.append("    }\n");
        }
        code.append("}");
        Files.writeString(testFile, code.toString());

        // Mock AIManager
        try (MockedStatic<AIManager> aiManagerMock = mockStatic(AIManager.class)) {
            AIManager mockManager = mock(AIManager.class);
            aiManagerMock.when(AIManager::getInstance).thenReturn(mockManager);

            // Setup mock response
            String mockAnalysis = "Large class detected - consider refactoring";
            when(mockManager.complete(any(PromptData.class), any(Map.class)))
                    .thenReturn(mockAnalysis);

            // Execute command
            int exitCode = analyzeCommand.execute(new String[] {testFile.toString()});

            // Verify
            assertThat(exitCode).isEqualTo(0);
            String output = outputCapture.getAllOutput();
            assertThat(output).contains("AI Analysis:");
            assertThat(output).contains("Large class detected");
        }
    }

    @Test
    public void testReadFile_DeniesCredentialFile() throws Exception {
        // A credential-named file (e.g. id_rsa) is denied by SecurityValidator
        // regardless of project-containment policy, so its contents are never
        // read or shipped to the AI. The deny happens before any AIManager call.
        Path credential = tempFolder.newFile("id_rsa").toPath();
        Files.writeString(credential, "-----BEGIN PRIVATE KEY-----\nsecret\n");

        java.util.Map<String, Object> context = new java.util.HashMap<>();
        context.put("step", "read_file");
        context.put("filepath", credential.toString());

        IterativeCommand.StepResult result =
                analyzeCommand.executeStep(new String[0], context, null);

        assertThat(result.isError()).isTrue();
        assertThat(result.getOutput()).contains("Access denied");
    }

    // ---- analyze-2: normalized affirmative gating for the search step ----

    @Test
    public void testIsAffirmative_AcceptsOnlyNormalizedAffirmatives() {
        assertThat(AnalyzeCommand.isAffirmative("y")).isTrue();
        assertThat(AnalyzeCommand.isAffirmative("YES")).isTrue();
        assertThat(AnalyzeCommand.isAffirmative("  search  ")).isTrue();
        // The old contains() scan accepted these by accident; they must now decline.
        assertThat(AnalyzeCommand.isAffirmative("no, don't search")).isFalse();
        assertThat(AnalyzeCommand.isAffirmative("yesterday")).isFalse();
        assertThat(AnalyzeCommand.isAffirmative(null)).isFalse();
        assertThat(AnalyzeCommand.isAffirmative("")).isFalse();
    }

    @Test
    public void testSearchFile_NegativeConfirmationCancels() {
        Map<String, Object> context = new HashMap<>();
        context.put("step", "search_file");
        context.put("searchPattern", "Missing.java");

        // "no, don't search" used to match contains("search") and wrongly proceed.
        IterativeCommand.StepResult result =
                analyzeCommand.executeStep(new String[0], context, "no, don't search");

        assertThat(result.isError()).isTrue();
        assertThat(result.getOutput()).contains("Analysis cancelled");
    }

    // ---- analyze-1: non-interactive single-shot contract ----

    @Test
    public void testInitial_NonInteractive_MissingFileFailsFastWithoutDialog() {
        String previous = System.getProperty("cadet.interactive");
        System.setProperty("cadet.interactive", "false");
        try {
            Map<String, Object> context = new HashMap<>();
            // Single-shot: a missing path must fail directly, never enter search_file/select_*.
            IterativeCommand.StepResult result = analyzeCommand.executeStep(
                    new String[] {"/definitely/not/here/Nope.java"}, context, null);

            assertThat(result.isComplete()).isTrue();
            assertThat(result.isError()).isTrue();
            assertThat(result.getOutput()).contains("File not found");
            assertThat(context.get("step")).isNotEqualTo("search_file");
        } finally {
            restoreInteractive(previous);
        }
    }

    @Test
    public void testInitial_NonInteractive_NoArgsFailsFastWithUsage() {
        String previous = System.getProperty("cadet.interactive");
        System.setProperty("cadet.interactive", "false");
        try {
            Map<String, Object> context = new HashMap<>();
            IterativeCommand.StepResult result =
                    analyzeCommand.executeStep(new String[0], context, null);

            assertThat(result.isComplete()).isTrue();
            assertThat(result.isError()).isTrue();
            assertThat(result.getOutput()).contains("No file path provided");
            assertThat(result.getOutput()).contains("cadet analyze <filepath>");
        } finally {
            restoreInteractive(previous);
        }
    }

    @Test
    public void testGetInitialPrompt_NonInteractiveNoArgsReturnsNull() {
        String previous = System.getProperty("cadet.interactive");
        System.setProperty("cadet.interactive", "false");
        try {
            // No initial prompt in single-shot mode so the executor runs executeStep directly.
            assertThat(analyzeCommand.getInitialPrompt(new String[0])).isNull();
        } finally {
            restoreInteractive(previous);
        }
    }

    // ---- analyze-3: guarded narrowing of context-stored List<Path> ----

    @Test
    public void testAsPathList_RejectsNonListAndNonPathElements() {
        assertThat(AnalyzeCommand.asPathList(null)).isNull();
        assertThat(AnalyzeCommand.asPathList("not a list")).isNull();

        List<Object> mixed = new ArrayList<>();
        mixed.add("string-not-a-path");
        assertThat(AnalyzeCommand.asPathList(mixed)).isNull();

        List<Object> paths = new ArrayList<>();
        paths.add(Paths.get("A.java"));
        paths.add(Paths.get("B.java"));
        assertThat(AnalyzeCommand.asPathList(paths)).hasSize(2);
    }

    @Test
    public void testSelectFile_CorruptMatchingFilesDoesNotThrow() {
        // A non-List<Path> value in context must not trigger a ClassCastException; the step should
        // fall through to its normal "invalid selection" prompt.
        Map<String, Object> context = new HashMap<>();
        context.put("step", "select_file");
        context.put("matchingFiles", "corrupt-not-a-list");

        IterativeCommand.StepResult result =
                analyzeCommand.executeStep(new String[0], context, "1");

        assertThat(result.getOutput()).contains("Invalid selection");
    }

    // ---- analyze-4: file content cap before sending to the LLM ----

    @Test
    public void testCapContent_TruncatesWithExplicitNote() {
        String small = "small content";
        assertThat(AnalyzeCommand.capContent(small)).isEqualTo(small);

        StringBuilder big = new StringBuilder();
        for (int i = 0; i < AnalyzeCommand.MAX_CONTENT_CHARS + 5_000; i++) {
            big.append('x');
        }
        String capped = AnalyzeCommand.capContent(big.toString());

        assertThat(capped.length()).isLessThan(big.length());
        assertThat(capped).contains("content truncated");
        assertThat(capped).startsWith("x");
    }

    @Test
    public void testReadFile_LargeContentIsCappedBeforeLLM() throws Exception {
        Path testFile = tempFolder.newFile("Huge.java").toPath();
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < AnalyzeCommand.MAX_CONTENT_CHARS + 10_000; i++) {
            code.append('a');
        }
        Files.writeString(testFile, code.toString());

        try (MockedStatic<AIManager> aiManagerMock = mockStatic(AIManager.class)) {
            AIManager mockManager = mock(AIManager.class);
            aiManagerMock.when(AIManager::getInstance).thenReturn(mockManager);
            when(mockManager.complete(any(PromptData.class), any(Map.class)))
                    .thenReturn("ok");

            Map<String, Object> context = new HashMap<>();
            context.put("step", "read_file");
            context.put("filepath", testFile.toString());

            IterativeCommand.StepResult result =
                    analyzeCommand.executeStep(new String[0], context, null);

            assertThat(result.isError()).isFalse();

            ArgumentCaptor<PromptData> captor = ArgumentCaptor.forClass(PromptData.class);
            verify(mockManager).complete(captor.capture(), any(Map.class));
            String sentPrompt = captor.getValue().getUserPrompt();
            // The full file is far larger than the cap; the prompt must carry the truncation note
            // and stay well under the raw file size.
            assertThat(sentPrompt).contains("content truncated");
            assertThat(sentPrompt.length()).isLessThan(code.length());
        }
    }

    private static void restoreInteractive(String previous) {
        if (previous == null) {
            System.clearProperty("cadet.interactive");
        } else {
            System.setProperty("cadet.interactive", previous);
        }
    }

    @Test
    public void testGetDescription() {
        assertThat(analyzeCommand.getDescription())
                .isEqualTo("Analyze a file's structure and quality");
    }

    @Test
    public void testGetUsage() {
        assertThat(analyzeCommand.getUsage())
                .isEqualTo("analyze <filepath>");
    }
}