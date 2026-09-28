package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.git.GitIntegration;
import com.eonmux.cadetcoder.git.GitIntegrationManager;
import com.eonmux.cadetcoder.ui.OutputRouter;
import org.junit.*;
import org.mockito.MockedStatic;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class PushCommandTest {

    private final ByteArrayOutputStream               outputStream = new ByteArrayOutputStream();
    private final ByteArrayOutputStream               errorStream  = new ByteArrayOutputStream();
    private final PrintStream                         originalOut  = System.out;
    private final PrintStream                         originalErr  = System.err;
    private       PushCommand                         command;
    private       MockedStatic<GitIntegrationManager> mockedGitManager;
    private       GitIntegrationManager               mockGitManager;
    private       GitIntegration                      mockGitIntegration;

    @Before
    public void setUp() {
        command = new PushCommand();
        System.setOut(new PrintStream(outputStream));
        System.setErr(new PrintStream(errorStream));

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
    }

    @Test
    public void testSuccessfulPush() throws Exception {
        // Arrange
        when(mockGitManager.isAvailable()).thenReturn(true);
        when(mockGitManager.getGitIntegration()).thenReturn(mockGitIntegration);
        when(mockGitIntegration.getLatestCommitHash()).thenReturn("abc1234567890");

        // Act
        int result = command.execute(new String[0]);

        // Assert
        assertEquals(0, result);
        verify(mockGitIntegration).push(false, null, false);

        String output = outputStream.toString() + errorStream;
        assertTrue(output.contains("Pushing to remote repository..."));
        assertTrue(output.contains("Successfully pushed to remote repository!"));
        assertTrue(output.contains("Latest commit pushed: abc1234"));
    }

    @Test
    public void testPushWithoutGit() throws Exception {
        // Arrange
        when(mockGitManager.isAvailable()).thenReturn(false);

        // Act
        int result = command.execute(new String[0]);

        // Assert
        assertEquals(1, result);
        verify(mockGitIntegration, never()).push(anyBoolean(), any(), anyBoolean());

        String output = outputStream.toString() + errorStream;
        assertTrue(output.contains("Git is not available in this directory"));
    }

    @Test
    public void testPushWithException() throws Exception {
        // Arrange
        when(mockGitManager.isAvailable()).thenReturn(true);
        when(mockGitManager.getGitIntegration()).thenReturn(mockGitIntegration);
        doThrow(new RuntimeException("Network error"))
                .when(mockGitIntegration).push(false, null, false);

        // Act
        int result = command.execute(new String[0]);

        // Assert
        assertEquals(1, result);

        String output = outputStream.toString() + errorStream;
        assertTrue(output.contains("Failed to push: Network error"));
    }

    @Test
    public void testPushRejected() throws Exception {
        // Arrange
        when(mockGitManager.isAvailable()).thenReturn(true);
        when(mockGitManager.getGitIntegration()).thenReturn(mockGitIntegration);
        doThrow(new RuntimeException("rejected - non-fast-forward"))
                .when(mockGitIntegration).push(false, null, false);

        // Act
        int result = command.execute(new String[0]);

        // Assert
        assertEquals(1, result);

        String output = outputStream.toString() + errorStream;
        assertTrue(output.contains("Failed to push: rejected - non-fast-forward"));
        assertTrue(output.contains("Hint: The push was rejected. You may need to pull changes first or use --force"));
    }

    /** Runs a push with a person at the terminal who answers yes. */
    private int withThePersonsYes(java.util.concurrent.Callable<Integer> push) throws Exception {
        OutputRouter router = mock(OutputRouter.class);
        when(router.canPrompt()).thenReturn(true);
        when(router.getConfirmation(anyString())).thenReturn(true);
        try (MockedStatic<OutputRouter> routers = mockStatic(OutputRouter.class)) {
            routers.when(OutputRouter::getInstance).thenReturn(router);
            return push.call();
        }
    }

    @Test
    public void testForcePushIsCarriedOutAndSaysWhatItCosts() throws Exception {
        // These three used to be recognised and then refused with "not supported by the JGit
        // integration. Please use git command line." JGit supports all three; nothing had asked it
        // to, and the README documented them as working the whole time.
        when(mockGitManager.isAvailable()).thenReturn(true);
        when(mockGitManager.getGitIntegration()).thenReturn(mockGitIntegration);

        // interactive-shell path: the flag arrives as an arg token
        int result = withThePersonsYes(() -> command.execute(new String[] {"-f"}));

        assertEquals(0, result);
        verify(mockGitIntegration).push(true, null, false);

        String output = outputStream.toString() + errorStream;
        // What a force push discards is said with the question that confirms it.
        assertTrue(output.contains("overwrites the remote's history"));
    }

    @Test
    public void testSetUpstreamIsCarriedOut() throws Exception {
        when(mockGitManager.isAvailable()).thenReturn(true);
        when(mockGitManager.getGitIntegration()).thenReturn(mockGitIntegration);

        int result = command.execute(new String[] {"-u"});

        assertEquals(0, result);
        verify(mockGitIntegration).push(false, null, true);
    }

    @Test
    public void testPushWithNullLatestCommit() throws Exception {
        // Arrange
        when(mockGitManager.isAvailable()).thenReturn(true);
        when(mockGitManager.getGitIntegration()).thenReturn(mockGitIntegration);
        when(mockGitIntegration.getLatestCommitHash()).thenReturn(null);

        // Act
        int result = command.execute(new String[0]);

        // Assert
        assertEquals(0, result);
        verify(mockGitIntegration).push(false, null, false);

        String output = outputStream.toString() + errorStream;
        assertTrue(output.contains("Successfully pushed to remote repository!"));
        assertFalse(output.contains("Latest commit pushed:"));
    }

    @Test
    public void testBranchFlagParsedFromArgs() throws Exception {
        // Regression for finding 8: 'push -b main' tokens must be honored on the
        // interactive-shell execute(args) path, not silently dropped.
        when(mockGitManager.isAvailable()).thenReturn(true);
        when(mockGitManager.getGitIntegration()).thenReturn(mockGitIntegration);

        int result = command.execute(new String[] {"-b", "main"});

        assertEquals(0, result);
        verify(mockGitIntegration).push(false, "main", false);
    }

    @Test
    public void testCallMethod() throws Exception {
        // Arrange
        when(mockGitManager.isAvailable()).thenReturn(true);
        when(mockGitManager.getGitIntegration()).thenReturn(mockGitIntegration);
        when(mockGitIntegration.getLatestCommitHash()).thenReturn("def4567890123");

        // Act
        Integer result = command.call();

        // Assert
        assertEquals(0, (int) result);
        verify(mockGitIntegration).push(false, null, false);
    }

    @Test
    public void testCallMethodForwardsForceField() throws Exception {
        // picocli sets the force field before call(); call() must forward it through args
        when(mockGitManager.isAvailable()).thenReturn(true);
        when(mockGitManager.getGitIntegration()).thenReturn(mockGitIntegration);
        command.force = true;

        // Act
        Integer result = withThePersonsYes(command::call);

        // Assert
        assertEquals(0, (int) result);
        verify(mockGitIntegration).push(true, null, false);
    }

    @Test
    public void testGetDescription() {
        assertEquals("Push commits to the remote", command.getDescription());
    }

    @Test
    public void testGetUsage() {
        // Every option the command accepts appears here; CommandUsageTest enforces it, and these
        // three were deliberately withheld for as long as they were refused rather than performed.
        String usage = command.getUsage();
        assertTrue(usage.startsWith("push"));
        assertTrue(usage.contains("--branch"));
        assertTrue(usage.contains("--set-upstream"));
        assertTrue(usage.contains("--force"));
    }
}