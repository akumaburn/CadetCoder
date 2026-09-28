package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.context.ProjectContext;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.*;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class ContextCommandTest {

    private final ByteArrayOutputStream        outputStream = new ByteArrayOutputStream();
    private final ByteArrayOutputStream        errorStream  = new ByteArrayOutputStream();
    private final PrintStream                  originalOut  = System.out;
    private final PrintStream                  originalErr  = System.err;
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();
    private       ContextCommand               command;
    private       MockedStatic<ProjectContext> mockedProjectContext;
    private       ProjectContext               mockProjectContext;
    private Path tempDir;

    @Before
    public void setUp() {
        command = new ContextCommand();
        System.setOut(new PrintStream(outputStream));
        System.setErr(new PrintStream(errorStream));

        // Set up temp directory
        try {
            tempDir = tempFolder.getRoot().toPath();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // Mock ProjectContext
        mockProjectContext   = mock(ProjectContext.class);
        mockedProjectContext = mockStatic(ProjectContext.class);
        mockedProjectContext.when(ProjectContext::getInstance).thenReturn(mockProjectContext);
    }

    @After
    public void tearDown() {
        System.setOut(originalOut);
        System.setErr(originalErr);
        if (mockedProjectContext != null) {
            mockedProjectContext.close();
        }
    }

    @Test
    public void testShowContextWithNoContext() {
        // Arrange
        when(mockProjectContext.hasProjectContext()).thenReturn(false);

        // Act
        int result = command.execute(new String[] {"show"});

        // Assert
        assertEquals(0, result);
        String output = outputStream.toString();
        assertTrue(output.contains("No project context found (CADET.md)"));
        // Spelled for the surface the reader is on; no shell is running in a test.
        assertTrue(output.contains("Use 'cadet context create' to create a default context file"));
    }

    @Test
    public void testShowContextDefault() {
        // Arrange
        when(mockProjectContext.hasProjectContext()).thenReturn(true);
        when(mockProjectContext.getContextFiles()).thenReturn(List.of("CADET.md"));
        String contextContent = "# Project Context\nThis is a test project.\nLine 3\nLine 4\nLine 5\n" +
                                "Line 6\nLine 7\nLine 8\nLine 9\nLine 10\nLine 11\nLine 12";
        when(mockProjectContext.getProjectContext()).thenReturn(contextContent);

        // Act - No arguments defaults to "show"
        int result = command.execute(new String[0]);

        // Assert
        assertEquals(0, result);
        String output = outputStream.toString();
        assertTrue(output.contains("Project Context"));
        assertTrue(output.contains("Context files: CADET.md"));
        assertTrue(output.contains("Context size: 12 lines"));
        assertTrue(output.contains("First 10 lines:"));
        assertTrue(output.contains("# Project Context"));
        assertTrue(output.contains("Line 10"));
        assertTrue(output.contains("... (2 more lines)"));
    }

    @Test
    public void testShowContextVerbose() {
        // Arrange
        when(mockProjectContext.hasProjectContext()).thenReturn(true);
        when(mockProjectContext.getContextFiles()).thenReturn(List.of("CADET.md"));
        String contextContent = "# Project Context\nDetailed content here";
        when(mockProjectContext.getProjectContext()).thenReturn(contextContent);

        // Act
        int result = command.execute(new String[] {"show", "-v"});

        // Assert
        assertEquals(0, result);
        String output = outputStream.toString();
        assertTrue(output.contains("Context files: CADET.md"));
        assertTrue(output.contains("Context content:"));
        assertTrue(output.contains("================"));
        assertTrue(output.contains("# Project Context"));
        assertTrue(output.contains("Detailed content here"));
    }

    @Test
    public void testShowContextVerboseLongForm() {
        // Arrange
        when(mockProjectContext.hasProjectContext()).thenReturn(true);
        when(mockProjectContext.getContextFiles()).thenReturn(Collections.singletonList("CADET.md"));
        when(mockProjectContext.getProjectContext()).thenReturn("Test content");

        // Act
        int result = command.execute(new String[] {"show", "--verbose"});

        // Assert
        assertEquals(0, result);
        String output = outputStream.toString();
        assertTrue(output.contains("Context content:"));
        assertTrue(output.contains("Test content"));
    }

    @Test
    public void testShowContextShortContent() {
        // Arrange
        when(mockProjectContext.hasProjectContext()).thenReturn(true);
        when(mockProjectContext.getContextFiles()).thenReturn(Collections.emptyList());
        String shortContent = "Line 1\nLine 2\nLine 3";
        when(mockProjectContext.getProjectContext()).thenReturn(shortContent);

        // Act
        int result = command.execute(new String[] {"show"});

        // Assert
        assertEquals(0, result);
        String output = outputStream.toString();
        assertTrue(output.contains("Context size: 3 lines"));
        assertTrue(output.contains("Line 1"));
        assertTrue(output.contains("Line 2"));
        assertTrue(output.contains("Line 3"));
        assertFalse(output.contains("... ("));
    }

    @Test
    public void testCreateContext() throws Exception {
        // Arrange
        mockedProjectContext.when(ProjectContext::createDefaultContextFile).thenReturn(true);

        // Act
        int result = command.execute(new String[] {"create"});

        // Assert
        assertEquals(0, result);
        mockedProjectContext.verify(ProjectContext::createDefaultContextFile);
        verify(mockProjectContext).reload();

        String output = outputStream.toString();
        assertTrue(output.contains("You can now edit CADET.md to customize your project context"));
    }

    @Test
    public void testCreateContextWhenFileAlreadyExists() throws Exception {
        // Arrange: createDefaultContextFile reports no file was written (one already existed).
        mockedProjectContext.when(ProjectContext::createDefaultContextFile).thenReturn(false);

        // Act
        int result = command.execute(new String[] {"create"});

        // Assert
        assertEquals(0, result);
        mockedProjectContext.verify(ProjectContext::createDefaultContextFile);
        verify(mockProjectContext).reload();

        String output = outputStream.toString();
        // The "creation" message must NOT be printed when nothing was created.
        assertFalse(output.contains("You can now edit CADET.md to customize your project context"));
        assertTrue(output.contains("CADET.md already exists; edit it to customize your project context"));
    }

    @Test
    public void testCreateContextFailure() throws Exception {
        // Arrange
        mockedProjectContext.when(ProjectContext::createDefaultContextFile)
                            .thenThrow(new java.io.IOException("Permission denied"));

        // Act
        int result = command.execute(new String[] {"create"});

        // Assert
        assertEquals(1, result);
        String output = outputStream.toString() + errorStream;
        assertTrue(output.contains("Failed to create context file: Permission denied"));
    }

    @Test
    public void testReloadContext() {
        // Arrange
        when(mockProjectContext.hasProjectContext()).thenReturn(true);

        // Act
        int result = command.execute(new String[] {"reload"});

        // Assert
        assertEquals(0, result);
        verify(mockProjectContext).reload();

        String output = outputStream.toString();
        assertTrue(output.contains("Project context reloaded successfully"));
    }

    @Test
    public void testOptionBeforeActionIsParsedAsAction() {
        // Arrange: -v supplied before the action token must not be mistaken for the action.
        when(mockProjectContext.hasProjectContext()).thenReturn(true);

        // Act
        int result = command.execute(new String[] {"-v", "reload"});

        // Assert: reload runs (exit 0), not "Unknown action: -v" (exit 1).
        assertEquals(0, result);
        verify(mockProjectContext).reload();

        String output = outputStream.toString() + errorStream;
        assertFalse(output.contains("Unknown action"));
        assertTrue(output.contains("Project context reloaded successfully"));
    }

    @Test
    public void testReloadContextNoContext() {
        // Arrange
        when(mockProjectContext.hasProjectContext()).thenReturn(false);

        // Act
        int result = command.execute(new String[] {"reload"});

        // Assert
        assertEquals(0, result);
        verify(mockProjectContext).reload();

        String output = outputStream.toString();
        assertTrue(output.contains("No project context found after reload"));
    }

    @Test
    public void testUnknownAction() {
        // Act
        int result = command.execute(new String[] {"invalid"});

        // Assert
        assertEquals(1, result);
        String output = outputStream.toString() + errorStream;
        assertTrue(output.contains("Unknown action: invalid"));
        assertTrue(output.contains("Valid actions: show, create, reload, clear"));
    }

    @Test
    public void testClearContextWhenLoaded() {
        // Arrange
        when(mockProjectContext.hasProjectContext()).thenReturn(true);

        // Act
        int result = command.execute(new String[] {"clear"});

        // Assert
        assertEquals(0, result);
        verify(mockProjectContext).clear();

        String output = outputStream.toString();
        assertTrue(output.contains("Project context cleared"));
    }

    @Test
    public void testClearContextWhenNothingLoaded() {
        // Arrange
        when(mockProjectContext.hasProjectContext()).thenReturn(false);

        // Act
        int result = command.execute(new String[] {"clear"});

        // Assert
        assertEquals(0, result);
        verify(mockProjectContext, never()).clear();

        String output = outputStream.toString();
        assertTrue(output.contains("No project context loaded; nothing to clear"));
    }

    @Test
    public void testExceptionHandling() {
        // Arrange
        when(mockProjectContext.hasProjectContext()).thenThrow(new RuntimeException("Test error"));

        // Act
        int result = command.execute(new String[] {"show"});

        // Assert
        assertEquals(1, result);
        String output = outputStream.toString() + errorStream;
        assertTrue(output.contains("Error managing context: Test error"));
    }

    @Test
    public void testCallMethodDefaultAction() throws Exception {
        // Arrange
        when(mockProjectContext.hasProjectContext()).thenReturn(true);
        when(mockProjectContext.getContextFiles()).thenReturn(Collections.singletonList("CADET.md"));
        when(mockProjectContext.getProjectContext()).thenReturn("Test content");

        // Act
        Integer result = command.call();

        // Assert
        assertEquals(0, (int) result);
        String output = outputStream.toString();
        assertTrue(output.contains("Project Context"));
    }

    @Test
    public void testCallMethodWithAction() throws Exception {
        // Arrange
        ContextCommand cmdWithParams = new ContextCommand();
        var            actionField   = ContextCommand.class.getDeclaredField("action");
        actionField.setAccessible(true);
        actionField.set(cmdWithParams, "reload");

        when(mockProjectContext.hasProjectContext()).thenReturn(true);

        // Act
        Integer result = cmdWithParams.call();

        // Assert
        assertEquals(0, (int) result);
        verify(mockProjectContext).reload();
    }

    @Test
    public void testEmptyContextContent() {
        // Arrange
        when(mockProjectContext.hasProjectContext()).thenReturn(true);
        when(mockProjectContext.getContextFiles()).thenReturn(Collections.singletonList("CADET.md"));
        when(mockProjectContext.getProjectContext()).thenReturn("");

        // Act
        int result = command.execute(new String[] {"show"});

        // Assert
        assertEquals(0, result);
        String output = outputStream.toString();
        assertTrue(output.contains("Context size: 1 lines, 0 characters"));
    }

    @Test
    public void testGetDescription() {
        assertEquals("Show or rebuild the project notes (CADET.md)", command.getDescription());
    }

    @Test
    public void testGetUsage() {
        assertEquals("context [show|create|reload|clear] [-v|--verbose]", command.getUsage());
    }
}