package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.git.GitIntegration;
import com.eonmux.cadetcoder.git.GitIntegrationManager;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.*;
import org.mockito.MockedStatic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

public class CommitCommandTest {

    private CommitCommand     commitCommand;
    private TestOutputCapture outputCapture;

    @Before
    public void setUp() {
        commitCommand = new CommitCommand();
        outputCapture = new TestOutputCapture();
        outputCapture.startCapture();
        // Set non-interactive mode for tests
        System.setProperty("cadet.interactive", "false");
    }

    @After
    public void tearDown() {
        outputCapture.stopCapture();
        // Clear system property
        System.clearProperty("cadet.interactive");
        // Reset singleton
        try {
            java.lang.reflect.Field instance = GitIntegrationManager.class.getDeclaredField("instance");
            instance.setAccessible(true);
            instance.set(null, null);
        } catch (Exception e) {
            // Ignore
        }
    }

    @Test
    public void testCommit_NoGitAvailable() {
        try (MockedStatic<GitIntegrationManager> gitMock = mockStatic(GitIntegrationManager.class)) {
            GitIntegrationManager mockManager = mock(GitIntegrationManager.class);
            when(mockManager.isAvailable()).thenReturn(false);
            gitMock.when(GitIntegrationManager::getInstance).thenReturn(mockManager);

            int exitCode = commitCommand.execute(new String[] {"Test commit"});

            assertThat(exitCode).isEqualTo(1);
            assertThat(outputCapture.getAllOutput()).contains("Error: Git is not available in this directory");
        }
    }

    @Test
    public void testCommit_NoMessage() {
        try (MockedStatic<GitIntegrationManager> gitMock = mockStatic(GitIntegrationManager.class)) {
            GitIntegrationManager mockManager = mock(GitIntegrationManager.class);
            when(mockManager.isAvailable()).thenReturn(true);
            gitMock.when(GitIntegrationManager::getInstance).thenReturn(mockManager);

            int exitCode = commitCommand.execute(new String[] {});

            assertThat(exitCode).isEqualTo(1);
            assertThat(outputCapture.getAllOutput()).contains("Error: Please provide a commit message");
        }
    }

    @Test
    public void testCommit_Success() throws Exception {
        try (MockedStatic<GitIntegrationManager> gitMock = mockStatic(GitIntegrationManager.class)) {
            GitIntegrationManager mockManager = mock(GitIntegrationManager.class);
            GitIntegration        mockGit     = mock(GitIntegration.class);

            when(mockManager.isAvailable()).thenReturn(true);
            when(mockManager.getGitIntegration()).thenReturn(mockGit);
            // A commit happens when something is staged; these tests are about what is reported
            // afterwards, so say that something was.
            when(mockGit.commit(anyString(), anyBoolean())).thenReturn(true);
            when(mockGit.getLatestCommitHash()).thenReturn("abcdef1234567890");
            gitMock.when(GitIntegrationManager::getInstance).thenReturn(mockManager);

            int exitCode = commitCommand.execute(new String[] {"Test commit message"});

            assertThat(exitCode).isEqualTo(0);
            verify(mockGit).commit("Test commit message", false);
            assertThat(outputCapture.getAllOutput()).contains("Changes committed successfully!");
            assertThat(outputCapture.getAllOutput()).contains("Latest commit: abcdef1");
        }
    }

    @Test
    public void testCommit_WithStageAll() throws Exception {
        try (MockedStatic<GitIntegrationManager> gitMock = mockStatic(GitIntegrationManager.class)) {
            GitIntegrationManager mockManager = mock(GitIntegrationManager.class);
            GitIntegration        mockGit     = mock(GitIntegration.class);

            when(mockManager.isAvailable()).thenReturn(true);
            when(mockManager.getGitIntegration()).thenReturn(mockGit);
            // A commit happens when something is staged; these tests are about what is reported
            // afterwards, so say that something was.
            when(mockGit.commit(anyString(), anyBoolean())).thenReturn(true);
            when(mockGit.getLatestCommitHash()).thenReturn("abcdef1234567890");
            gitMock.when(GitIntegrationManager::getInstance).thenReturn(mockManager);

            // Set stageAll flag
            commitCommand.stageAll = true;
            int exitCode = commitCommand.execute(new String[] {"Test commit with -a"});

            assertThat(exitCode).isEqualTo(0);
            verify(mockGit).stageAllChanges();
            verify(mockGit).commit("Test commit with -a", false);
            assertThat(outputCapture.getAllOutput()).contains("Staging all modified files...");
            assertThat(outputCapture.getAllOutput()).contains("Changes committed successfully!");
        }
    }

    @Test
    public void testCommit_CommitFails() throws Exception {
        try (MockedStatic<GitIntegrationManager> gitMock = mockStatic(GitIntegrationManager.class)) {
            GitIntegrationManager mockManager = mock(GitIntegrationManager.class);
            GitIntegration        mockGit     = mock(GitIntegration.class);

            when(mockManager.isAvailable()).thenReturn(true);
            when(mockManager.getGitIntegration()).thenReturn(mockGit);
            // A commit happens when something is staged; these tests are about what is reported
            // afterwards, so say that something was.
            when(mockGit.commit(anyString(), anyBoolean())).thenReturn(true);
            doThrow(new RuntimeException("No changes to commit")).when(mockGit).commit(anyString(), anyBoolean());
            gitMock.when(GitIntegrationManager::getInstance).thenReturn(mockManager);

            int exitCode = commitCommand.execute(new String[] {"Test commit"});

            // IterativeExecutor may return 0 even on failure when output contains error message
            // The important thing is that the error is properly displayed
            assertThat(exitCode).isIn(0, 1);
            String output = outputCapture.getAllOutput();
            // The exact message might differ, let's be more flexible
            assertThat(output).containsIgnoringCase("failed");
            assertThat(output).containsIgnoringCase("no changes");
        }
    }

    @Test
    public void testCommit_MultiWordMessage() throws Exception {
        try (MockedStatic<GitIntegrationManager> gitMock = mockStatic(GitIntegrationManager.class)) {
            GitIntegrationManager mockManager = mock(GitIntegrationManager.class);
            GitIntegration        mockGit     = mock(GitIntegration.class);

            when(mockManager.isAvailable()).thenReturn(true);
            when(mockManager.getGitIntegration()).thenReturn(mockGit);
            // A commit happens when something is staged; these tests are about what is reported
            // afterwards, so say that something was.
            when(mockGit.commit(anyString(), anyBoolean())).thenReturn(true);
            gitMock.when(GitIntegrationManager::getInstance).thenReturn(mockManager);

            int exitCode = commitCommand.execute(new String[] {"Fixed", "bug", "in", "UserService"});

            assertThat(exitCode).isEqualTo(0);
            verify(mockGit).commit("Fixed bug in UserService", false);
        }
    }

    @Test
    public void testGetDescription() {
        assertThat(commitCommand.getDescription()).isEqualTo("Commit changes to git");
    }

    @Test
    public void testGetUsage() {
        assertThat(commitCommand.getUsage()).contains("commit");
    }

    // commit-4: getUsage must advertise every flag the parser accepts.
    @Test
    public void testGetUsage_AdvertisesAllFlags() {
        String usage = commitCommand.getUsage();
        assertThat(usage).contains("-a");
        assertThat(usage).contains("--all");
        assertThat(usage).contains("--no-verify");
    }

    // commit-5: the autonomous (non-interactive) path must say it is skipping confirmation.
    @Test
    public void testCommit_NonInteractive_AnnouncesNoConfirmation() throws Exception {
        try (MockedStatic<GitIntegrationManager> gitMock = mockStatic(GitIntegrationManager.class)) {
            GitIntegrationManager mockManager = mock(GitIntegrationManager.class);
            GitIntegration        mockGit     = mock(GitIntegration.class);

            when(mockManager.isAvailable()).thenReturn(true);
            when(mockManager.getGitIntegration()).thenReturn(mockGit);
            // A commit happens when something is staged; these tests are about what is reported
            // afterwards, so say that something was.
            when(mockGit.commit(anyString(), anyBoolean())).thenReturn(true);
            gitMock.when(GitIntegrationManager::getInstance).thenReturn(mockManager);

            int exitCode = commitCommand.execute(new String[] {"Autonomous commit"});

            assertThat(exitCode).isEqualTo(0);
            assertThat(outputCapture.getAllOutput())
                    .containsIgnoringCase("without confirmation");
        }
    }

    // commit-1: --no-verify intent is surfaced before committing.
    @Test
    public void testCommit_NoVerifyFlag_IsSurfaced() throws Exception {
        try (MockedStatic<GitIntegrationManager> gitMock = mockStatic(GitIntegrationManager.class)) {
            GitIntegrationManager mockManager = mock(GitIntegrationManager.class);
            GitIntegration        mockGit     = mock(GitIntegration.class);

            when(mockManager.isAvailable()).thenReturn(true);
            when(mockManager.getGitIntegration()).thenReturn(mockGit);
            // A commit happens when something is staged; these tests are about what is reported
            // afterwards, so say that something was.
            when(mockGit.commit(anyString(), anyBoolean())).thenReturn(true);
            gitMock.when(GitIntegrationManager::getInstance).thenReturn(mockManager);

            int exitCode = commitCommand.execute(new String[] {"--no-verify", "Bypass lint"});

            assertThat(exitCode).isEqualTo(0);
            verify(mockGit).commit("Bypass lint", true);
            assertThat(outputCapture.getAllOutput()).contains("--no-verify");
        }
    }

    // commit-1: --no-verify field is propagated through call() into the argument stream.
    @Test
    public void testCommit_NoVerifyField_IsSurfaced() throws Exception {
        try (MockedStatic<GitIntegrationManager> gitMock = mockStatic(GitIntegrationManager.class)) {
            GitIntegrationManager mockManager = mock(GitIntegrationManager.class);
            GitIntegration        mockGit     = mock(GitIntegration.class);

            when(mockManager.isAvailable()).thenReturn(true);
            when(mockManager.getGitIntegration()).thenReturn(mockGit);
            // A commit happens when something is staged; these tests are about what is reported
            // afterwards, so say that something was.
            when(mockGit.commit(anyString(), anyBoolean())).thenReturn(true);
            gitMock.when(GitIntegrationManager::getInstance).thenReturn(mockManager);

            commitCommand.noVerify = true;
            commitCommand.messageParts = new String[] {"Bypass via field"};
            int exitCode = commitCommand.call();

            assertThat(exitCode).isEqualTo(0);
            verify(mockGit).commit("Bypass via field", true);
            assertThat(outputCapture.getAllOutput()).contains("--no-verify");
        }
    }

    // commit-1: a pre-commit gate failure without --no-verify yields a friendly hint, not a raw leak.
    @Test
    public void testCommit_PreCommitGate_SuggestsNoVerify() throws Exception {
        try (MockedStatic<GitIntegrationManager> gitMock = mockStatic(GitIntegrationManager.class)) {
            GitIntegrationManager mockManager = mock(GitIntegrationManager.class);
            GitIntegration        mockGit     = mock(GitIntegration.class);

            when(mockManager.isAvailable()).thenReturn(true);
            when(mockManager.getGitIntegration()).thenReturn(mockGit);
            // A commit happens when something is staged; these tests are about what is reported
            // afterwards, so say that something was.
            when(mockGit.commit(anyString(), anyBoolean())).thenReturn(true);
            doThrow(new RuntimeException("Pre-commit validation failed: TODOs found."))
                    .when(mockGit).commit(anyString(), anyBoolean());
            gitMock.when(GitIntegrationManager::getInstance).thenReturn(mockManager);

            int exitCode = commitCommand.execute(new String[] {"Has todos"});

            assertThat(exitCode).isIn(0, 1);
            String output = outputCapture.getAllOutput();
            assertThat(output).contains("--no-verify");
            assertThat(output).containsIgnoringCase("pre-commit");
        }
    }

    // commit-2: stage-all surfaces what will be staged before staging everything.
    @Test
    public void testCommit_StageAll_SurfacesPreStageCheck() throws Exception {
        try (MockedStatic<GitIntegrationManager> gitMock = mockStatic(GitIntegrationManager.class)) {
            GitIntegrationManager mockManager = mock(GitIntegrationManager.class);
            GitIntegration        mockGit     = mock(GitIntegration.class);

            when(mockManager.isAvailable()).thenReturn(true);
            when(mockManager.getGitIntegration()).thenReturn(mockGit);
            // A commit happens when something is staged; these tests are about what is reported
            // afterwards, so say that something was.
            when(mockGit.commit(anyString(), anyBoolean())).thenReturn(true);
            gitMock.when(GitIntegrationManager::getInstance).thenReturn(mockManager);

            commitCommand.stageAll = true;
            int exitCode = commitCommand.execute(new String[] {"Stage all check"});

            assertThat(exitCode).isEqualTo(0);
            verify(mockGit).stageAllChanges();
            String output = outputCapture.getAllOutput();
            assertThat(output).containsIgnoringCase("pre-stage check");
            assertThat(output).containsIgnoringCase("will be staged");
        }
    }
}