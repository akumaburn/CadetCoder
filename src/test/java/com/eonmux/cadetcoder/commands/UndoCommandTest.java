package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.git.GitIntegration;
import com.eonmux.cadetcoder.git.GitIntegrationManager;
import org.junit.*;
import org.mockito.MockedStatic;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.*;

public class UndoCommandTest {

    private final ByteArrayOutputStream               outputStream = new ByteArrayOutputStream();
    private final ByteArrayOutputStream               errorStream  = new ByteArrayOutputStream();
    private final PrintStream                         originalOut  = System.out;
    private final PrintStream                         originalErr  = System.err;
    private       UndoCommand                         command;
    private       MockedStatic<GitIntegrationManager> mockedGitManager;
    private       GitIntegrationManager               mockGitManager;
    private       GitIntegration                      mockGitIntegration;
    private       String                              originalInteractive;

    @Before
    public void setUp() {
        command = new UndoCommand();
        System.setOut(new PrintStream(outputStream));
        System.setErr(new PrintStream(errorStream));

        // Preserve and default the interactive flag so each test controls confirmation behavior
        // deterministically and never leaks the property to sibling tests.
        originalInteractive = System.getProperty("cadet.interactive");

        // Mock GitIntegrationManager
        mockGitManager     = mock(GitIntegrationManager.class);
        mockGitIntegration = mock(GitIntegration.class);

        mockedGitManager = mockStatic(GitIntegrationManager.class);
        mockedGitManager.when(GitIntegrationManager::getInstance).thenReturn(mockGitManager);
    }

    @After
    public void tearDown() {
        System.setOut(originalOut);
        System.setErr(originalErr);
        if (mockedGitManager != null) {
            mockedGitManager.close();
        }
        if (originalInteractive == null) {
            System.clearProperty("cadet.interactive");
        } else {
            System.setProperty("cadet.interactive", originalInteractive);
        }
    }

    @Test
    public void testUndoWithoutGit() throws Exception {
        // Arrange
        when(mockGitManager.isAvailable()).thenReturn(false);

        // Act
        int result = command.execute(new String[0]);

        // Assert
        assertEquals(1, result);
        verify(mockGitIntegration, never()).undo(anyString());

        String output = outputStream.toString() + errorStream;
        assertTrue(output.contains("Git is not available in this directory"));
    }

    @Test
    public void testDefaultUndoEditWithForce() throws Exception {
        // Arrange - --force bypasses the destructive-action confirmation
        when(mockGitManager.isAvailable()).thenReturn(true);
        when(mockGitManager.getGitIntegration()).thenReturn(mockGitIntegration);

        // Act
        int result = command.execute(new String[] {"--force"});

        // Assert
        assertEquals(0, result);
        verify(mockGitIntegration).undo("HEAD");

        String output = outputStream.toString() + errorStream;
        assertTrue(output.contains("Discarding uncommitted changes..."));
        assertTrue(output.contains("This will discard all uncommitted changes!"));
        assertTrue(output.contains("Uncommitted changes discarded."));
    }

    @Test
    public void testUndoCommitWithForce() throws Exception {
        // Arrange
        when(mockGitManager.isAvailable()).thenReturn(true);
        when(mockGitManager.getGitIntegration()).thenReturn(mockGitIntegration);
        when(mockGitIntegration.getLatestCommitHash()).thenReturn("abc123");

        // Act
        int result = command.execute(new String[] {"--commit", "--force"});

        // Assert
        assertEquals(0, result);
        verify(mockGitIntegration).undo("HEAD~1");

        String output = outputStream.toString() + errorStream;
        assertTrue(output.contains("Undoing last commit..."));
        assertTrue(output.contains("This will perform a hard reset and lose uncommitted changes!"));
        assertTrue(output.contains("Reset to previous commit."));
    }

    @Test
    public void testUndoCommitNoHistory() throws Exception {
        // Arrange
        when(mockGitManager.isAvailable()).thenReturn(true);
        when(mockGitManager.getGitIntegration()).thenReturn(mockGitIntegration);
        when(mockGitIntegration.getLatestCommitHash()).thenReturn(null);

        // Act
        int result = command.execute(new String[] {"--commit", "--force"});

        // Assert
        assertEquals(1, result);
        verify(mockGitIntegration, never()).undo(anyString());

        String output = outputStream.toString() + errorStream;
        assertTrue(output.contains("No previous commit found."));
    }

    @Test
    public void testUndoEditException() throws Exception {
        // Arrange
        when(mockGitManager.isAvailable()).thenReturn(true);
        when(mockGitManager.getGitIntegration()).thenReturn(mockGitIntegration);
        doThrow(new RuntimeException("Nothing to undo")).when(mockGitIntegration).undo("HEAD");

        // Act
        int result = command.execute(new String[] {"--edit", "--force"});

        // Assert
        assertEquals(0, result);

        String output = outputStream.toString() + errorStream;
        assertTrue(output.contains("No changes to undo or operation failed: Nothing to undo"));
    }

    @Test
    public void testBothUndoCommitAndEditWithForce() throws Exception {
        // Arrange
        when(mockGitManager.isAvailable()).thenReturn(true);
        when(mockGitManager.getGitIntegration()).thenReturn(mockGitIntegration);
        when(mockGitIntegration.getLatestCommitHash()).thenReturn("abc123");

        // Act
        int result = command.execute(new String[] {"--commit", "--edit", "--force"});

        // Assert
        assertEquals(0, result);
        verify(mockGitIntegration).undo("HEAD~1");
        verify(mockGitIntegration).undo("HEAD");

        String output = outputStream.toString() + errorStream;
        assertTrue(output.contains("Undoing last commit..."));
        assertTrue(output.contains("Discarding uncommitted changes..."));
    }

    @Test
    public void testGeneralException() throws Exception {
        // Arrange
        when(mockGitManager.isAvailable()).thenReturn(true);
        when(mockGitManager.getGitIntegration()).thenThrow(new RuntimeException("Git error"));

        // Act
        int result = command.execute(new String[] {"--force"});

        // Assert
        assertEquals(1, result);

        String output = outputStream.toString() + errorStream;
        assertTrue(output.contains("Failed to undo: Git error"));
    }

    @Test
    public void testCallMethodWithForce() throws Exception {
        // Arrange - picocli sets the force field before call(); call() must forward it through args
        when(mockGitManager.isAvailable()).thenReturn(true);
        when(mockGitManager.getGitIntegration()).thenReturn(mockGitIntegration);
        command.force = true;

        // Act
        Integer result = command.call();

        // Assert
        assertEquals(0, (int) result);
        verify(mockGitIntegration).undo("HEAD");
    }

    @Test
    public void testCallMethodForwardsCommitField() throws Exception {
        // Arrange - picocli sets the fields before call(); call() must forward them through args
        when(mockGitManager.isAvailable()).thenReturn(true);
        when(mockGitManager.getGitIntegration()).thenReturn(mockGitIntegration);
        when(mockGitIntegration.getLatestCommitHash()).thenReturn("abc123");
        command.undoCommit = true;
        command.force = true;

        // Act
        Integer result = command.call();

        // Assert
        assertEquals(0, (int) result);
        verify(mockGitIntegration).undo("HEAD~1");
        verify(mockGitIntegration, never()).undo("HEAD");
    }

    /**
     * Regression for the destructive flag-leak: after 'undo --commit' on the reused singleton,
     * a later plain 'undo' must NOT carry over undoCommit and hard-reset HEAD~1; it must only
     * discard uncommitted changes (undo "HEAD"). The --force flag must likewise not leak.
     */
    @Test
    public void testCommitFlagDoesNotLeakToNextPlainUndo() throws Exception {
        // Arrange
        when(mockGitManager.isAvailable()).thenReturn(true);
        when(mockGitManager.getGitIntegration()).thenReturn(mockGitIntegration);
        when(mockGitIntegration.getLatestCommitHash()).thenReturn("abc123");

        // First invocation: undo the last commit (sets undoCommit on the singleton historically)
        int first = command.execute(new String[] {"--commit", "--force"});
        assertEquals(0, first);
        verify(mockGitIntegration).undo("HEAD~1");

        // Second invocation on the SAME instance with only --force (no --commit)
        int second = command.execute(new String[] {"--force"});

        // Assert: must only discard changes, never reset past HEAD again
        assertEquals(0, second);
        verify(mockGitIntegration).undo("HEAD");
        verify(mockGitIntegration, times(1)).undo("HEAD~1"); // not invoked a second time
    }

    /**
     * push-undo-2: a hard reset must never run unattended. In a non-interactive context without
     * --force the edit-mode reset must be refused before touching the working tree.
     */
    @Test
    public void testNonInteractiveEditWithoutForceIsRefused() throws Exception {
        // Arrange
        System.setProperty("cadet.interactive", "false");
        when(mockGitManager.isAvailable()).thenReturn(true);
        when(mockGitManager.getGitIntegration()).thenReturn(mockGitIntegration);

        // Act
        int result = command.execute(new String[] {"--edit"});

        // Assert
        assertEquals(1, result);
        verify(mockGitIntegration, never()).undo(anyString());

        String output = outputStream.toString() + errorStream;
        assertTrue(output.contains("Refusing to perform a destructive hard reset in a non-interactive context"));
        assertTrue(output.contains("--force"));
    }

    /**
     * push-undo-2: same guard for the commit-undo path in a non-interactive context.
     */
    @Test
    public void testNonInteractiveCommitWithoutForceIsRefused() throws Exception {
        // Arrange
        System.setProperty("cadet.interactive", "false");
        when(mockGitManager.isAvailable()).thenReturn(true);
        when(mockGitManager.getGitIntegration()).thenReturn(mockGitIntegration);
        when(mockGitIntegration.getLatestCommitHash()).thenReturn("abc123");

        // Act
        int result = command.execute(new String[] {"--commit"});

        // Assert
        assertEquals(1, result);
        verify(mockGitIntegration, never()).undo(anyString());

        String output = outputStream.toString() + errorStream;
        assertTrue(output.contains("Refusing to perform a destructive hard reset in a non-interactive context"));
    }

    /**
     * push-undo-2: --force allows the reset to proceed in a non-interactive context.
     */
    @Test
    public void testNonInteractiveEditWithForceProceeds() throws Exception {
        // Arrange
        System.setProperty("cadet.interactive", "false");
        when(mockGitManager.isAvailable()).thenReturn(true);
        when(mockGitManager.getGitIntegration()).thenReturn(mockGitIntegration);

        // Act
        int result = command.execute(new String[] {"--edit", "--force"});

        // Assert
        assertEquals(0, result);
        verify(mockGitIntegration).undo("HEAD");
    }

    /**
     * push-undo-2: in an interactive context with no TUI/console attached, getConfirmation
     * resolves to "no", so the destructive reset must be cancelled (not executed).
     */
    @Test
    public void testInteractiveEditDeclinedConfirmationCancels() throws Exception {
        // Arrange - interactive mode, no --force; no TUI active so confirmation defaults to "no"
        System.setProperty("cadet.interactive", "true");
        when(mockGitManager.isAvailable()).thenReturn(true);
        when(mockGitManager.getGitIntegration()).thenReturn(mockGitIntegration);

        // Act
        int result = command.execute(new String[] {"--edit"});

        // Assert: declined by a person is the interruption code, not the failure code. The other
        // refusal this method can give -- no --force in a non-interactive context -- stays 1,
        // because that one is the invocation's author to fix.
        assertEquals(com.eonmux.cadetcoder.ExitCode.INTERRUPTED, result);
        verify(mockGitIntegration, never()).undo(anyString());

        String output = outputStream.toString() + errorStream;
        assertTrue(output.contains("Undo cancelled."));
    }

    /**
     * push-undo-5: a single-commit repository has no HEAD~1, so the reset throws. The command
     * must report that clearly instead of letting the generic handler mask the cause.
     */
    @Test
    public void testUndoCommitSingleCommitReportedClearly() throws Exception {
        // Arrange
        when(mockGitManager.isAvailable()).thenReturn(true);
        when(mockGitManager.getGitIntegration()).thenReturn(mockGitIntegration);
        when(mockGitIntegration.getLatestCommitHash()).thenReturn("abc123");
        doThrow(new RuntimeException("Ref HEAD~1 cannot be resolved"))
                .when(mockGitIntegration).undo("HEAD~1");

        // Act
        int result = command.execute(new String[] {"--commit", "--force"});

        // Assert
        assertEquals(1, result);

        String output = outputStream.toString() + errorStream;
        assertTrue(output.contains("only one commit"));
        assertTrue(output.contains("no previous commit"));
    }

    @Test
    public void testGetDescription() {
        assertEquals("Undo the last commit or discard all uncommitted changes",
                command.getDescription());
    }

    @Test
    public void testGetUsage() {
        // The usage now states the destructive default explicitly: bare `undo` discards ALL
        // uncommitted changes, which the previous one-liner never said.
        String usage = command.getUsage();
        assertTrue(usage.contains("undo [-c|--commit] [-e|--edit] [-f|--force]"));
        assertTrue(usage.contains("(DEFAULT)"));
        assertTrue(usage.contains("discard ALL uncommitted working-tree changes"));
    }
}
