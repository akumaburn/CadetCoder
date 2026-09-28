package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.prompts.PromptTemplateEngine;
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

public class PromptCommandTest {

    @Rule
    public TemporaryFolder tempFolder = new ProjectFolder();
    private PromptCommand     command;
    private TestOutputCapture outputCapture;

    @Before
    public void setUp() {
        command       = new PromptCommand();
        outputCapture = new TestOutputCapture();
    }

    @After
    public void tearDown() {
        outputCapture.restore();
    }

    @Test
    public void testGetDescription() {
        assertThat(command.getDescription()).isEqualTo("Show or edit the system prompts the AI is sent");
    }

    @Test
    public void testGetUsage() {
        assertThat(command.getUsage()).contains("prompt [list|show|edit|reset|reload]");
    }

    @Test
    public void testListPrompts() {
        try (MockedStatic<PromptTemplateEngine> engineMock = mockStatic(PromptTemplateEngine.class)) {
            PromptTemplateEngine mockEngine = mock(PromptTemplateEngine.class);
            engineMock.when(PromptTemplateEngine::getInstance).thenReturn(mockEngine);

            Map<String, String> prompts = Map.of(
                    "system", "default",
                    "chat", "default",
                    "debug", "default"
                                                );
            when(mockEngine.listAvailablePrompts()).thenReturn(prompts);

            int result = command.execute(new String[] {"list"});

            assertThat(result).isEqualTo(0);
            String output = outputCapture.getOutput();
            assertThat(output).contains("Available Prompt Templates");
            assertThat(output).contains("system");
            assertThat(output).contains("chat");
            assertThat(output).contains("debug");
        }
    }

    @Test
    public void testShowPrompt() {
        try (MockedStatic<PromptTemplateEngine> engineMock = mockStatic(PromptTemplateEngine.class)) {
            PromptTemplateEngine mockEngine = mock(PromptTemplateEngine.class);
            engineMock.when(PromptTemplateEngine::getInstance).thenReturn(mockEngine);

            when(mockEngine.getRawPrompt("test-prompt")).thenReturn("Test prompt content");

            int result = command.execute(new String[] {"show", "test-prompt"});

            assertThat(result).isEqualTo(0);
            String output = outputCapture.getOutput();
            assertThat(output).contains("Prompt Template: test-prompt");
            assertThat(output).contains("Test prompt content");
        }
    }

    @Test
    public void testShowPrompt_RejectsPathTraversalName() {
        try (MockedStatic<PromptTemplateEngine> engineMock = mockStatic(PromptTemplateEngine.class)) {
            PromptTemplateEngine mockEngine = mock(PromptTemplateEngine.class);
            engineMock.when(PromptTemplateEngine::getInstance).thenReturn(mockEngine);

            int result = command.execute(new String[] {"show", "../../etc/passwd"});

            assertThat(result).isEqualTo(1);
            String output = outputCapture.getAllOutput();
            assertThat(output).contains("Invalid prompt name");
            // The unsafe name must never reach the template loader.
            verify(mockEngine, never()).getRawPrompt(anyString());
        }
    }

    @Test
    public void testShowPrompt_NotFound_Null() {
        try (MockedStatic<PromptTemplateEngine> engineMock = mockStatic(PromptTemplateEngine.class)) {
            PromptTemplateEngine mockEngine = mock(PromptTemplateEngine.class);
            engineMock.when(PromptTemplateEngine::getInstance).thenReturn(mockEngine);

            when(mockEngine.getRawPrompt("missing")).thenReturn(null);

            int result = command.execute(new String[] {"show", "missing"});

            // prompt-2: a non-existent prompt must report failure, not exit 0.
            assertThat(result).isEqualTo(1);
            String output = outputCapture.getAllOutput();
            assertThat(output).contains("Prompt not found: missing");
            // A null body must never be printed as the template.
            assertThat(outputCapture.getOutput()).doesNotContain("Prompt Template: missing");
        }
    }

    @Test
    public void testShowPrompt_NotFound_Sentinel() {
        try (MockedStatic<PromptTemplateEngine> engineMock = mockStatic(PromptTemplateEngine.class)) {
            PromptTemplateEngine mockEngine = mock(PromptTemplateEngine.class);
            engineMock.when(PromptTemplateEngine::getInstance).thenReturn(mockEngine);

            // The engine returns this sentinel (never null) for unknown names.
            when(mockEngine.getRawPrompt("missing"))
                    .thenReturn("No template found for prompt: missing");

            int result = command.execute(new String[] {"show", "missing"});

            assertThat(result).isEqualTo(1);
            assertThat(outputCapture.getAllOutput()).contains("Prompt not found: missing");
        }
    }

    @Test
    public void testEditPrompt_WithContent() throws Exception {
        try (MockedStatic<PromptTemplateEngine> engineMock = mockStatic(PromptTemplateEngine.class)) {
            PromptTemplateEngine mockEngine = mock(PromptTemplateEngine.class);
            engineMock.when(PromptTemplateEngine::getInstance).thenReturn(mockEngine);

            int result = command.execute(new String[] {"edit", "test-prompt", "-c", "New content"});

            assertThat(result).isEqualTo(0);
            verify(mockEngine).saveCustomPrompt(eq("test-prompt"), eq("New content"), any());
            assertThat(outputCapture.getOutput()).contains("Prompt 'test-prompt' has been customized");
        }
    }

    @Test
    public void testEditPrompt_WithFile() throws Exception {
        Path contentFile = tempFolder.newFile("prompt.txt").toPath();
        Files.writeString(contentFile, "Content from file");

        try (MockedStatic<PromptTemplateEngine> engineMock = mockStatic(PromptTemplateEngine.class)) {
            PromptTemplateEngine mockEngine = mock(PromptTemplateEngine.class);
            engineMock.when(PromptTemplateEngine::getInstance).thenReturn(mockEngine);

            int result = command.execute(new String[] {"edit", "test-prompt", "-f", contentFile.toString()});

            assertThat(result).isEqualTo(0);
            verify(mockEngine).saveCustomPrompt(eq("test-prompt"), eq("Content from file"), any());
        }
    }

    @Test
    public void testEditPrompt_NoContent() {
        int result = command.execute(new String[] {"edit", "test-prompt"});

        assertThat(result).isEqualTo(1);
        assertThat(outputCapture.getStderr()).contains("Please provide content with -c or -f option");
    }

    @Test
    public void testResetPrompt_DeletesViaEngine() throws Exception {
        try (MockedStatic<PromptTemplateEngine> engineMock = mockStatic(PromptTemplateEngine.class)) {
            PromptTemplateEngine mockEngine = mock(PromptTemplateEngine.class);
            engineMock.when(PromptTemplateEngine::getInstance).thenReturn(mockEngine);

            when(mockEngine.deleteCustomPrompt("test-prompt")).thenReturn(true);

            int result = command.execute(new String[] {"reset", "test-prompt"});

            // prompt-1: reset must delegate to the engine (same dir as list/save),
            // not re-derive the prompts path itself.
            assertThat(result).isEqualTo(0);
            verify(mockEngine).deleteCustomPrompt("test-prompt");
            assertThat(outputCapture.getOutput()).contains("reset to default");
        }
    }

    @Test
    public void testResetPrompt_NoOverride() throws Exception {
        try (MockedStatic<PromptTemplateEngine> engineMock = mockStatic(PromptTemplateEngine.class)) {
            PromptTemplateEngine mockEngine = mock(PromptTemplateEngine.class);
            engineMock.when(PromptTemplateEngine::getInstance).thenReturn(mockEngine);

            when(mockEngine.deleteCustomPrompt("test-prompt")).thenReturn(false);

            int result = command.execute(new String[] {"reset", "test-prompt"});

            assertThat(result).isEqualTo(1);
            assertThat(outputCapture.getAllOutput())
                    .contains("No user override found for prompt 'test-prompt'");
        }
    }

    @Test
    public void testResetPrompt_RejectsPathTraversalName() throws Exception {
        try (MockedStatic<PromptTemplateEngine> engineMock = mockStatic(PromptTemplateEngine.class)) {
            PromptTemplateEngine mockEngine = mock(PromptTemplateEngine.class);
            engineMock.when(PromptTemplateEngine::getInstance).thenReturn(mockEngine);

            int result = command.execute(new String[] {"reset", "../../etc/passwd"});

            assertThat(result).isEqualTo(1);
            assertThat(outputCapture.getAllOutput()).contains("Invalid prompt name");
            verify(mockEngine, never()).deleteCustomPrompt(anyString());
        }
    }

    @Test
    public void testResetPrompt_DeleteFailureReported() throws Exception {
        try (MockedStatic<PromptTemplateEngine> engineMock = mockStatic(PromptTemplateEngine.class)) {
            PromptTemplateEngine mockEngine = mock(PromptTemplateEngine.class);
            engineMock.when(PromptTemplateEngine::getInstance).thenReturn(mockEngine);

            when(mockEngine.deleteCustomPrompt("test-prompt"))
                    .thenThrow(new java.io.IOException("disk error"));

            int result = command.execute(new String[] {"reset", "test-prompt"});

            assertThat(result).isEqualTo(1);
            assertThat(outputCapture.getAllOutput())
                    .contains("Failed to delete prompt override");
        }
    }

    @Test
    public void testEditPrompt_RejectsTraversalFilePath() throws Exception {
        try (MockedStatic<PromptTemplateEngine> engineMock = mockStatic(PromptTemplateEngine.class)) {
            PromptTemplateEngine mockEngine = mock(PromptTemplateEngine.class);
            engineMock.when(PromptTemplateEngine::getInstance).thenReturn(mockEngine);

            // prompt-5: -f must be validated by SecurityValidator; a traversal path
            // is rejected before any read and the prompt is never saved.
            int result = command.execute(
                    new String[] {"edit", "test-prompt", "-f", "../../etc/passwd"});

            assertThat(result).isEqualTo(1);
            assertThat(outputCapture.getAllOutput()).contains("Access to file is not allowed");
            verify(mockEngine, never()).saveCustomPrompt(anyString(), anyString(), any());
        }
    }

    @Test
    public void testEditPrompt_ContentValueLookingLikeOption() throws Exception {
        try (MockedStatic<PromptTemplateEngine> engineMock = mockStatic(PromptTemplateEngine.class)) {
            PromptTemplateEngine mockEngine = mock(PromptTemplateEngine.class);
            engineMock.when(PromptTemplateEngine::getInstance).thenReturn(mockEngine);

            // prompt-4: a content value that starts with "-" must be taken literally
            // as the value, not treated as another option.
            int result = command.execute(
                    new String[] {"edit", "test-prompt", "-c", "--not-a-flag"});

            assertThat(result).isEqualTo(0);
            verify(mockEngine).saveCustomPrompt(eq("test-prompt"), eq("--not-a-flag"), any());
        }
    }

    @Test
    public void testEditPrompt_OptionBeforeName() throws Exception {
        try (MockedStatic<PromptTemplateEngine> engineMock = mockStatic(PromptTemplateEngine.class)) {
            PromptTemplateEngine mockEngine = mock(PromptTemplateEngine.class);
            engineMock.when(PromptTemplateEngine::getInstance).thenReturn(mockEngine);

            // prompt-4: the prompt name may appear AFTER the option.
            int result = command.execute(
                    new String[] {"edit", "-c", "New content", "test-prompt"});

            assertThat(result).isEqualTo(0);
            verify(mockEngine).saveCustomPrompt(eq("test-prompt"), eq("New content"), any());
        }
    }

    @Test
    public void testReloadPrompts() {
        try (MockedStatic<PromptTemplateEngine> engineMock = mockStatic(PromptTemplateEngine.class)) {
            PromptTemplateEngine mockEngine = mock(PromptTemplateEngine.class);
            engineMock.when(PromptTemplateEngine::getInstance).thenReturn(mockEngine);

            int result = command.execute(new String[] {"reload"});

            assertThat(result).isEqualTo(0);
            verify(mockEngine).clearCache();
            assertThat(outputCapture.getOutput()).contains("Prompt template cache cleared");
        }
    }

    @Test
    public void testUnknownAction() {
        int result = command.execute(new String[] {"unknown"});

        assertThat(result).isEqualTo(1);
        assertThat(outputCapture.getStderr()).contains("Unknown action: unknown");
        assertThat(outputCapture.getOutput()).contains("Available actions: list, show, edit, reset, reload");
    }

    @Test
    public void testEditPrompt_FileNotFound() {
        // Inside the project, so the refusal is about existence rather than the boundary.
        String missing = tempFolder.getRoot().toPath().resolve("nonexistent/file.txt").toString();
        int    result  = command.execute(new String[] {"edit", "test-prompt", "-f", missing});

        assertThat(result).isEqualTo(1);
        assertThat(outputCapture.getStderr()).contains("File not found: " + missing);
    }
}