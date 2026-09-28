package com.eonmux.cadetcoder.git;

import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.eclipse.jgit.api.*;
import org.eclipse.jgit.api.CommitCommand;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.mockito.*;

import java.io.File;
import java.io.IOException;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Mockito.*;

public class GitIntegrationTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private TestOutputCapture outputCapture;
    private String            originalUserDir;

    @Mock
    private Repository mockRepository;

    @Mock
    private Git mockGit;

    @Mock
    private AddCommand mockAddCommand;

    @Mock
    private CommitCommand mockCommitCommand;

    @Mock
    private PushCommand mockPushCommand;

    @Mock
    private ResetCommand mockResetCommand;

    @Mock
    private LogCommand mockLogCommand;

    @Mock
    private StatusCommand mockStatusCommand;

    @Mock
    private Status mockStatus;

    private AutoCloseable mocks;

    @Before
    public void setUp() throws Exception {
        mocks         = MockitoAnnotations.openMocks(this);
        outputCapture   = new TestOutputCapture();
        originalUserDir = System.getProperty("user.dir");

        // Create a temporary directory with .git folder
        tempFolder.create();
        File gitDir = new File(tempFolder.getRoot(), ".git");
        gitDir.mkdir();
    }

    @After
    public void tearDown() throws Exception {
        // Nine of these tests point user.dir at the temp folder and none of them puts it back.
        // The folder is then deleted, so every later class in the reused fork resolves relative
        // paths against a directory that no longer exists. Restored here once, for all of them.
        if (originalUserDir != null) {
            System.setProperty("user.dir", originalUserDir);
        }
        outputCapture.restore();
        if (mocks != null) {
            mocks.close();
        }
    }

    @Test
    public void testConstructor_NoGitRepository() throws Exception {
        // Skip this test if we're running inside a git repository
        // The FileRepositoryBuilder.findGitDir() will find parent .git directories
        File currentGit = new File(".git");
        if (currentGit.exists()) {
            // Skip test - we're in a git repository
            return;
        }

        // Create a temp directory in /tmp to avoid any parent .git directories
        File tmpRoot = new File("/tmp/cadet-test-" + System.currentTimeMillis());
        tmpRoot.mkdirs();
        File deepDir = new File(tmpRoot, "deep/nested/folder/test");
        deepDir.mkdirs();

        // Change to the deep directory without git
        String originalDir = System.getProperty("user.dir");
        System.setProperty("user.dir", deepDir.getAbsolutePath());

        try {
            new GitIntegration();
            fail("Should have thrown IOException");
        } catch (IOException e) {
            assertThat(e).isInstanceOf(RepositoryNotFoundException.class);
            assertThat(outputCapture.getStderr()).contains("Git repository not found");
        } finally {
            // Restore original directory
            System.setProperty("user.dir", originalDir);
            // Clean up temp directory
            deleteRecursively(tmpRoot);
        }
    }

    private void deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }

    @Test
    public void testStageAllChanges() throws Exception {
        try (MockedConstruction<Git> gitMock = mockConstruction(Git.class, (mock, context) -> {
            when(mock.add()).thenReturn(mockAddCommand);
            when(mockAddCommand.addFilepattern(".")).thenReturn(mockAddCommand);
            when(mockAddCommand.setUpdate(true)).thenReturn(mockAddCommand);
        })) {
            // Change to the temp directory
            System.setProperty("user.dir", tempFolder.getRoot().getAbsolutePath());

            GitIntegration gitIntegration = new GitIntegration();
            gitIntegration.stageAllChanges();

            // Twice: what is on disk, and then what is no longer on disk. See stageAllChanges.
            verify(mockAddCommand, times(2)).addFilepattern(".");
            verify(mockAddCommand).setUpdate(true);
            verify(mockAddCommand, times(2)).call();
            assertThat(outputCapture.getOutput()).contains("All changes staged");
        }
    }

    @Test
    public void testCommit() throws Exception {
        RevCommit mockCommit = mock(RevCommit.class);
        when(mockCommit.getName()).thenReturn("abc123");

        try (MockedConstruction<Git> gitMock = mockConstruction(Git.class, (mock, context) -> {
            when(mock.commit()).thenReturn(mockCommitCommand);
            // A commit only happens when something is staged; say that something is.
            when(mock.status()).thenReturn(mockStatusCommand);
            when(mockStatusCommand.call()).thenReturn(mockStatus);
            when(mockStatus.getAdded()).thenReturn(Set.of("staged.txt"));
            when(mockCommitCommand.setMessage(anyString())).thenReturn(mockCommitCommand);
            when(mockCommitCommand.call()).thenReturn(mockCommit);
        });
             MockedConstruction<ProcessBuilder> pbMock = mockConstruction(ProcessBuilder.class, (mock, context) -> {
                 Process mockProcess = mock(Process.class);
                 when(mock.start()).thenReturn(mockProcess);
                 when(mockProcess.waitFor(anyLong(), any())).thenReturn(true);
                 when(mockProcess.exitValue()).thenReturn(1); // grep returns 1 when no matches found
             })) {

            // Change to the temp directory
            System.setProperty("user.dir", tempFolder.getRoot().getAbsolutePath());

            GitIntegration gitIntegration = new GitIntegration();
            gitIntegration.commit("Test commit message");

            verify(mockCommitCommand).setMessage("Test commit message");
            verify(mockCommitCommand).call();
            assertThat(outputCapture.getOutput()).contains("Committed changes with message: Test commit message");
        }
    }

    @Test
    public void testCommit_SoftLintNeverAborts() throws Exception {
        RevCommit mockCommit = mock(RevCommit.class);
        when(mockCommit.getName()).thenReturn("abc123");

        try (MockedConstruction<Git> gitMock = mockConstruction(Git.class, (mock, context) -> {
            when(mock.commit()).thenReturn(mockCommitCommand);
            // A commit only happens when something is staged; say that something is.
            when(mock.status()).thenReturn(mockStatusCommand);
            when(mockStatusCommand.call()).thenReturn(mockStatus);
            when(mockStatus.getAdded()).thenReturn(Set.of("staged.txt"));
            when(mockCommitCommand.setMessage(anyString())).thenReturn(mockCommitCommand);
            when(mockCommitCommand.call()).thenReturn(mockCommit);
        })) {

            System.setProperty("user.dir", tempFolder.getRoot().getAbsolutePath());

            GitIntegration gitIntegration = new GitIntegration();
            // The soft pre-commit TODO lint must WARN at most and NEVER abort a requested commit
            // (the previous whole-tree `grep -r TODO .` threw and aborted virtually every commit).
            gitIntegration.commit("Test commit message");

            verify(mockCommitCommand).call();
            assertThat(outputCapture.getOutput()).contains("Committed changes with message: Test commit message");
        }
    }

    @Test
    public void testPush() throws Exception {
        try (MockedConstruction<Git> gitMock = mockConstruction(Git.class, (mock, context) -> {
            when(mock.push()).thenReturn(mockPushCommand);
            // setForce is part of building the push now that --force is carried out rather than
            // refused; it returns the command so the call can be chained.
            when(mockPushCommand.setForce(anyBoolean())).thenReturn(mockPushCommand);
            // What the remote said. A push that reports success without reading this is the defect
            // WhatGitWasAskedToDoIsWhatItReportsTest exists for.
            when(mockPushCommand.call()).thenReturn(List.of());
        })) {
            // Change to the temp directory
            System.setProperty("user.dir", tempFolder.getRoot().getAbsolutePath());

            GitIntegration gitIntegration = new GitIntegration();
            assertThat(gitIntegration.push()).isEmpty();

            verify(mockPushCommand).call();
            assertThat(outputCapture.getOutput()).contains("Pushed commits to the remote repository");
        }
    }

    @Test
    public void testUndo() throws Exception {
        try (MockedConstruction<Git> gitMock = mockConstruction(Git.class, (mock, context) -> {
            when(mock.reset()).thenReturn(mockResetCommand);
            when(mockResetCommand.setMode(ResetCommand.ResetType.HARD)).thenReturn(mockResetCommand);
            when(mockResetCommand.setRef(anyString())).thenReturn(mockResetCommand);
        })) {
            // Change to the temp directory
            System.setProperty("user.dir", tempFolder.getRoot().getAbsolutePath());

            GitIntegration gitIntegration = new GitIntegration();
            gitIntegration.undo("abc123");

            verify(mockResetCommand).setMode(ResetCommand.ResetType.HARD);
            verify(mockResetCommand).setRef("abc123");
            verify(mockResetCommand).call();
            assertThat(outputCapture.getOutput()).contains("Rolled back to commit: abc123");
        }
    }

    @Test
    public void testGetLatestCommitHash() throws Exception {
        RevCommit mockCommit = mock(RevCommit.class);
        when(mockCommit.getName()).thenReturn("def456");

        Iterator<RevCommit> mockIterator = mock(Iterator.class);
        when(mockIterator.hasNext()).thenReturn(true).thenReturn(true).thenReturn(false);
        when(mockIterator.next()).thenReturn(mockCommit);

        Iterable<RevCommit> mockIterable = mock(Iterable.class);
        when(mockIterable.iterator()).thenReturn(mockIterator);

        try (MockedConstruction<Git> gitMock = mockConstruction(Git.class, (mock, context) -> {
            when(mock.log()).thenReturn(mockLogCommand);
            when(mockLogCommand.setMaxCount(1)).thenReturn(mockLogCommand);
            when(mockLogCommand.call()).thenReturn(mockIterable);
        })) {
            // Change to the temp directory
            System.setProperty("user.dir", tempFolder.getRoot().getAbsolutePath());

            GitIntegration gitIntegration = new GitIntegration();
            String         hash           = gitIntegration.getLatestCommitHash();

            assertThat(hash).isEqualTo("def456");
        }
    }

    @Test
    public void testGetLatestCommitHash_NoCommits() throws Exception {
        Iterator<RevCommit> mockIterator = mock(Iterator.class);
        when(mockIterator.hasNext()).thenReturn(false);

        Iterable<RevCommit> mockIterable = mock(Iterable.class);
        when(mockIterable.iterator()).thenReturn(mockIterator);

        try (MockedConstruction<Git> gitMock = mockConstruction(Git.class, (mock, context) -> {
            when(mock.log()).thenReturn(mockLogCommand);
            when(mockLogCommand.setMaxCount(1)).thenReturn(mockLogCommand);
            when(mockLogCommand.call()).thenReturn(mockIterable);
        })) {
            // Change to the temp directory
            System.setProperty("user.dir", tempFolder.getRoot().getAbsolutePath());

            GitIntegration gitIntegration = new GitIntegration();
            String         hash           = gitIntegration.getLatestCommitHash();

            assertThat(hash).isNull();
            assertThat(outputCapture.getOutput()).contains("No commits found in the repository");
        }
    }


    @Test
    public void testCommit_SkipVerifySkipsLint() throws Exception {
        RevCommit mockCommit = mock(RevCommit.class);
        when(mockCommit.getName()).thenReturn("abc123");

        try (MockedConstruction<Git> gitMock = mockConstruction(Git.class, (mock, context) -> {
            when(mock.commit()).thenReturn(mockCommitCommand);
            // A commit only happens when something is staged; say that something is.
            when(mock.status()).thenReturn(mockStatusCommand);
            when(mockStatusCommand.call()).thenReturn(mockStatus);
            when(mockStatus.getAdded()).thenReturn(Set.of("staged.txt"));
            when(mockCommitCommand.setMessage(anyString())).thenReturn(mockCommitCommand);
            when(mockCommitCommand.call()).thenReturn(mockCommit);
        })) {

            System.setProperty("user.dir", tempFolder.getRoot().getAbsolutePath());

            GitIntegration gitIntegration = new GitIntegration();
            // skipVerify=true bypasses the soft lint entirely and commits. Status is still read
            // once, to find out whether there is anything staged to commit at all; the lint's own
            // read of it is the one that is skipped.
            gitIntegration.commit("Test commit message", true);

            verify(mockCommitCommand).call();
            verify(gitMock.constructed().get(0), times(1)).status();
            assertThat(outputCapture.getOutput()).contains("Committed changes with message: Test commit message");
        }
    }
}