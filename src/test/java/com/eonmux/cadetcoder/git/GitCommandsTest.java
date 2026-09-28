package com.eonmux.cadetcoder.git;

import com.eonmux.cadetcoder.commands.PushCommand;
import com.eonmux.cadetcoder.commands.UndoCommand;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.junit.*;
import org.mockito.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

public class GitCommandsTest {

    @Mock
    private GitIntegrationManager mockGitManager;

    @Mock
    private GitIntegration mockGitIntegration;

    private TestOutputCapture outputCapture;
    private AutoCloseable     mocks;

    @Before
    public void setUp() throws Exception {
        mocks         = MockitoAnnotations.openMocks(this);
        outputCapture = new TestOutputCapture();
        resetSingletons();
    }

    private void resetSingletons() {
        try {
            // Reset GitIntegrationManager
            java.lang.reflect.Field gitInstance = GitIntegrationManager.class.getDeclaredField("instance");
            gitInstance.setAccessible(true);
            gitInstance.set(null, null);
        } catch (Exception e) {
            // Ignore
        }
    }

    @After
    public void tearDown() throws Exception {
        outputCapture.restore();
        if (mocks != null) {
            mocks.close();
        }
        resetSingletons();
    }

    @Test
    public void testPushCommand_Execute() throws Exception {
        try (MockedStatic<GitIntegrationManager> gitMock = mockStatic(GitIntegrationManager.class)) {
            gitMock.when(GitIntegrationManager::getInstance).thenReturn(mockGitManager);
            when(mockGitManager.isAvailable()).thenReturn(true);
            when(mockGitManager.getGitIntegration()).thenReturn(mockGitIntegration);

            PushCommand command = new PushCommand();
            int         result  = command.execute(new String[0]);

            assertThat(result).isEqualTo(0);
            verify(mockGitIntegration).push(false, null, false);
        }
    }

    @Test
    public void testPushCommand_GitNotAvailable() throws Exception {
        try (MockedStatic<GitIntegrationManager> gitMock = mockStatic(GitIntegrationManager.class)) {
            gitMock.when(GitIntegrationManager::getInstance).thenReturn(mockGitManager);
            when(mockGitManager.isAvailable()).thenReturn(false);

            PushCommand command = new PushCommand();
            int         result  = command.execute(new String[0]);

            assertThat(result).isEqualTo(1);
            assertThat(outputCapture.getStderr()).contains("Git is not available in this directory");
        }
    }

    @Test
    public void testPushCommand_GitException() throws Exception {
        try (MockedStatic<GitIntegrationManager> gitMock = mockStatic(GitIntegrationManager.class)) {
            gitMock.when(GitIntegrationManager::getInstance).thenReturn(mockGitManager);
            when(mockGitManager.isAvailable()).thenReturn(true);
            when(mockGitManager.getGitIntegration()).thenReturn(mockGitIntegration);
            doThrow(new GitAPIException("Push failed") {
            }).when(mockGitIntegration).push(false, null, false);

            PushCommand command = new PushCommand();
            int         result  = command.execute(new String[0]);

            assertThat(result).isEqualTo(1);
            assertThat(outputCapture.getStderr()).contains("Failed to push: Push failed");
        }
    }

    @Test
    public void testPushCommand_Description() {
        PushCommand command = new PushCommand();
        assertThat(command.getDescription()).isEqualTo("Push commits to the remote");
    }

    @Test
    public void testPushCommand_Usage() {
        PushCommand command = new PushCommand();
        assertThat(command.getUsage()).contains("push");
    }

    @Test
    public void testUndoCommand_Execute() throws Exception {
        try (MockedStatic<GitIntegrationManager> gitMock = mockStatic(GitIntegrationManager.class)) {
            gitMock.when(GitIntegrationManager::getInstance).thenReturn(mockGitManager);
            when(mockGitManager.isAvailable()).thenReturn(true);
            when(mockGitManager.getGitIntegration()).thenReturn(mockGitIntegration);

            UndoCommand command = new UndoCommand();
            // Default behavior is to undo edits; --force skips the new confirmation guard.
            int result = command.execute(new String[] {"--force"});

            assertThat(result).isEqualTo(0);
            verify(mockGitIntegration).undo("HEAD");
        }
    }

    @Test
    public void testUndoCommand_UndoEditWithException() throws Exception {
        try (MockedStatic<GitIntegrationManager> gitMock = mockStatic(GitIntegrationManager.class)) {
            gitMock.when(GitIntegrationManager::getInstance).thenReturn(mockGitManager);
            when(mockGitManager.isAvailable()).thenReturn(true);
            when(mockGitManager.getGitIntegration()).thenReturn(mockGitIntegration);
            doThrow(new RuntimeException("Undo failed")).when(mockGitIntegration).undo("HEAD");

            UndoCommand command = new UndoCommand();
            int         result  = command.execute(new String[] {"--force"});

            // A genuine undo failure (a hard reset to HEAD only throws on a real problem)
            // now reports failure with exit code 1 instead of masking it as success.
            assertThat(result).isEqualTo(1);
            assertThat(outputCapture.getAllOutput()).contains("No changes to undo or operation failed");
        }
    }

    @Test
    public void testUndoCommand_GitNotAvailable() throws Exception {
        try (MockedStatic<GitIntegrationManager> gitMock = mockStatic(GitIntegrationManager.class)) {
            gitMock.when(GitIntegrationManager::getInstance).thenReturn(mockGitManager);
            when(mockGitManager.isAvailable()).thenReturn(false);

            UndoCommand command = new UndoCommand();
            int         result  = command.execute(new String[0]);

            assertThat(result).isEqualTo(1);
            assertThat(outputCapture.getStderr()).contains("Git is not available in this directory");
        }
    }

    @Test
    public void testUndoCommand_UndoCommit() throws Exception {
        try (MockedStatic<GitIntegrationManager> gitMock = mockStatic(GitIntegrationManager.class)) {
            gitMock.when(GitIntegrationManager::getInstance).thenReturn(mockGitManager);
            when(mockGitManager.isAvailable()).thenReturn(true);
            when(mockGitManager.getGitIntegration()).thenReturn(mockGitIntegration);
            when(mockGitIntegration.getLatestCommitHash()).thenReturn("abc123");

            // Create a picocli CommandLine to parse the command with options
            picocli.CommandLine cmd    = new picocli.CommandLine(new UndoCommand());
            int                 result = cmd.execute("-c", "-f");

            verify(mockGitIntegration).undo("HEAD~1");
        }
    }

    @Test
    public void testUndoCommand_Description() {
        UndoCommand command = new UndoCommand();
        assertThat(command.getDescription()).isEqualTo("Undo the last commit or discard all uncommitted changes");
    }

    @Test
    public void testUndoCommand_Usage() {
        UndoCommand command = new UndoCommand();
        assertThat(command.getUsage()).contains("undo [-c|--commit] [-e|--edit]");
    }
}