package com.eonmux.cadetcoder.git;

import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PushCommand;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code push --force}, {@code --branch} and {@code --set-upstream} do what they say.
 *
 * <p><b>The defect</b>: all three were accepted by {@code PushCommand}, parsed into fields, and then
 * answered with "not supported by the JGit integration. Please use git command line." JGit supports
 * every one of them; nothing had ever asked it to. The README documented the three as working, the
 * command's own {@code getUsage()} omitted them so the refusal would not be advertised, and the code
 * refused them -- three descriptions of one feature, no two of which agreed.</p>
 */
public class AbranchIsPushedWhereItWasAskedToGoTest {

    @Rule
    public TemporaryFolder projectDir = new TemporaryFolder();

    private final PushCommand      jgitPush = mock(PushCommand.class);
    private       TestOutputCapture outputCapture;
    private       String            originalUserDir;

    @Before
    public void setUp() {
        outputCapture = new TestOutputCapture();
        outputCapture.startCapture();
        originalUserDir = System.getProperty("user.dir");
        when(jgitPush.setForce(anyBoolean())).thenReturn(jgitPush);
    }

    @After
    public void tearDown() {
        System.setProperty("user.dir", originalUserDir);
        outputCapture.stopCapture();
    }

    /** Opens a GitIntegration whose underlying JGit push is the mock above. */
    private MockedConstruction<Git> withMockedGit() {
        System.setProperty("user.dir", projectDir.getRoot().getAbsolutePath());
        return mockConstruction(Git.class, (mock, context) -> {
            when(mock.push()).thenReturn(jgitPush);
            when(jgitPush.call()).thenReturn(List.of());
        });
    }

    @Test
    public void aPlainPushIsNotForcedAndNamesNoRefspec() throws Exception {
        try (MockedConstruction<Git> ignored = withMockedGit()) {
            new GitIntegration().push();

            verify(jgitPush).setForce(false);
            verify(jgitPush, org.mockito.Mockito.never()).setRefSpecs(org.mockito.ArgumentMatchers.<RefSpec>any());
        }
    }

    @Test
    public void forcingIsPassedThroughToJgit() throws Exception {
        try (MockedConstruction<Git> ignored = withMockedGit()) {
            new GitIntegration().push(true, null, false);

            verify(jgitPush).setForce(true);
        }
    }

    @Test
    public void aNamedBranchIsPushedRatherThanWhicheverIsCheckedOut() throws Exception {
        try (MockedConstruction<Git> ignored = withMockedGit()) {
            new GitIntegration().push(false, "release-2", false);

            // Spelled out explicitly: left to the configured refspec, JGit pushes the CURRENT
            // branch, which is the one thing `-b release-2` is asking it not to do.
            ArgumentCaptor<RefSpec> spec = ArgumentCaptor.forClass(RefSpec.class);
            verify(jgitPush).setRefSpecs(spec.capture());
            assertThat(spec.getValue().toString())
                    .isEqualTo("refs/heads/release-2:refs/heads/release-2");
        }
    }

    @Test
    public void anUpstreamIsRecordedOnlyAfterThePushIsAccepted() throws Exception {
        try (MockedConstruction<Git> ignored = withMockedGit()) {
            GitIntegration git = new GitIntegration();

            assertThat(git.push(false, "feature-x", true)).isEmpty();

            // Git records the upstream after a successful push, and so does this: the branch now
            // tracks origin/feature-x, and the user is told so.
            assertThat(outputCapture.getAllOutput()).contains("now tracks origin/feature-x");
        }
    }

    @Test
    public void aRefusedPushRecordsNoUpstream() throws Exception {
        System.setProperty("user.dir", projectDir.getRoot().getAbsolutePath());
        org.eclipse.jgit.transport.PushResult   result = mock(org.eclipse.jgit.transport.PushResult.class);
        org.eclipse.jgit.transport.RemoteRefUpdate update =
                mock(org.eclipse.jgit.transport.RemoteRefUpdate.class);
        when(update.getStatus()).thenReturn(
                org.eclipse.jgit.transport.RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD);
        when(update.getRemoteName()).thenReturn("refs/heads/feature-x");
        when(result.getRemoteUpdates()).thenReturn(List.of(update));

        try (MockedConstruction<Git> ignored = mockConstruction(Git.class, (mock, context) -> {
            when(mock.push()).thenReturn(jgitPush);
            when(jgitPush.call()).thenReturn(List.of(result));
        })) {
            GitIntegration git = new GitIntegration();

            assertThat(git.push(false, "feature-x", true)).isNotEmpty();

            // The commits never reached the remote, so a tracking entry pointing at them would
            // record a relationship that does not exist.
            assertThat(outputCapture.getAllOutput()).doesNotContain("now tracks");
        }
    }
}
