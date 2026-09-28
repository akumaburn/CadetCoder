package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.git.GitIntegration;
import com.eonmux.cadetcoder.git.GitIntegrationManager;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import com.eonmux.cadetcoder.ui.OutputRouter;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A force push happens only when the person at the terminal says yes to it, and a model's
 * {@code --force} cannot discard the user's uncommitted work.
 *
 * <h2>The defect</h2>
 *
 * <p>{@code CommandGate} already ignored {@code --force} when a model supplied it, but {@code push}
 * and {@code undo} do not go through the gate. {@code push -f} force-pushed with only a warning, and
 * {@code undo --force} ran {@code git reset --hard} without asking, so a model could rewrite the
 * remote's history or throw away every uncommitted change in the project on its own word.</p>
 *
 * <p>A force push overwrites commits on the remote that exist nowhere else, so the person confirms
 * every one, whoever typed {@code -f}. The question never goes to the model, even when
 * {@code security.commandApproval} is {@code auto}. When nobody can be asked, the push is
 * refused.</p>
 */
public class AmodelsForceDoesNotRewriteHistoryTest {

    private TestOutputCapture                   output;
    private MockedStatic<GitIntegrationManager> managers;
    private GitIntegration                      git;
    private String                              originalInteractive;

    @Before
    public void setUp() {
        output = new TestOutputCapture();
        output.startCapture();
        originalInteractive = System.getProperty("cadet.interactive");
        System.setProperty("cadet.interactive", "false");

        GitIntegrationManager manager = mock(GitIntegrationManager.class);
        git = mock(GitIntegration.class);
        when(manager.isAvailable()).thenReturn(true);
        when(manager.getGitIntegration()).thenReturn(git);
        managers = mockStatic(GitIntegrationManager.class);
        managers.when(GitIntegrationManager::getInstance).thenReturn(manager);
    }

    @After
    public void tearDown() {
        managers.close();
        output.stopCapture();
        if (originalInteractive == null) {
            System.clearProperty("cadet.interactive");
        } else {
            System.setProperty("cadet.interactive", originalInteractive);
        }
    }

    /** A terminal where a person can be asked, who gives this answer. */
    private static OutputRouter aPersonWhoSays(boolean answer) {
        OutputRouter router = mock(OutputRouter.class);
        when(router.canPrompt()).thenReturn(true);
        when(router.getConfirmation(anyString())).thenReturn(answer);
        return router;
    }

    private int forcePush(OutputRouter router, boolean asTheModel) {
        try (MockedStatic<OutputRouter> routers = mockStatic(OutputRouter.class)) {
            routers.when(OutputRouter::getInstance).thenReturn(router);
            return asTheModel
                   ? ModelDispatch.run("push", () -> new PushCommand().execute(new String[] {"-f"}))
                   : new PushCommand().execute(new String[] {"--force"});
        }
    }

    private void verifyNothingWasPushed() throws Exception {
        verify(git, never()).push(anyBoolean(), any(), anyBoolean());
    }

    @Test
    public void aForcePushNobodyCanConfirmIsRefusedBeforeAnythingIsSent() throws Exception {
        OutputRouter nobody = mock(OutputRouter.class);
        when(nobody.canPrompt()).thenReturn(false);

        for (boolean asTheModel : new boolean[] {true, false}) {
            int exitCode = forcePush(nobody, asTheModel);

            assertThat(exitCode).as(asTheModel ? "model" : "person").isNotZero();
        }
        verify(nobody, never()).getConfirmation(anyString());
        verifyNothingWasPushed();
        assertThat(output.getAllOutput()).contains("nobody can be asked to confirm it");
    }

    @Test
    public void aForcePushThePersonDeclinesIsNotSent() throws Exception {
        for (boolean asTheModel : new boolean[] {true, false}) {
            int exitCode = forcePush(aPersonWhoSays(false), asTheModel);

            assertThat(exitCode).as(asTheModel ? "model" : "person").isNotZero();
        }
        verifyNothingWasPushed();
    }

    @Test
    public void aForcePushIsSentOnlyAfterThePersonSaysYes() throws Exception {
        OutputRouter router = aPersonWhoSays(true);

        assertThat(forcePush(router, false)).isZero();
        assertThat(forcePush(router, true)).isZero();

        verify(router, org.mockito.Mockito.times(2)).getConfirmation(anyString());
        verify(git, org.mockito.Mockito.times(2)).push(org.mockito.ArgumentMatchers.eq(true), any(), anyBoolean());
    }

    @Test
    public void underAutoApprovalTheQuestionStillGoesToThePerson() throws Exception {
        String previous = System.getProperty(com.eonmux.cadetcoder.ai.CommandApproval.PROPERTY);
        System.setProperty(com.eonmux.cadetcoder.ai.CommandApproval.PROPERTY, "auto");
        try {
            OutputRouter router = aPersonWhoSays(false);

            assertThat(forcePush(router, true)).isNotZero();

            verify(router).getConfirmation(anyString());
            verifyNothingWasPushed();
        } finally {
            if (previous == null) {
                System.clearProperty(com.eonmux.cadetcoder.ai.CommandApproval.PROPERTY);
            } else {
                System.setProperty(com.eonmux.cadetcoder.ai.CommandApproval.PROPERTY, previous);
            }
        }
    }

    @Test
    public void anOrdinaryPushIsNotAskedAbout() throws Exception {
        OutputRouter router = aPersonWhoSays(false);
        try (MockedStatic<OutputRouter> routers = mockStatic(OutputRouter.class)) {
            routers.when(OutputRouter::getInstance).thenReturn(router);

            assertThat(new PushCommand().execute(new String[0])).isZero();
        }
        verify(router, never()).getConfirmation(anyString());
        verify(git).push(org.mockito.ArgumentMatchers.eq(false), any(), anyBoolean());
    }

    @Test
    public void aModelsForcedUndoDoesNotResetAnything() throws Exception {
        for (String[] args : new String[][] {{"--force"}, {"-f", "--commit"}}) {
            int exitCode = ModelDispatch.run("undo", () -> new UndoCommand().execute(args));

            assertThat(exitCode).as(String.join(" ", args)).isNotZero();
        }
        verify(git, never()).undo(anyString());
        assertThat(output.getAllOutput()).contains("Ignoring --force")
                                         .contains("Only the user can confirm it")
                                         .doesNotContain("Re-run with --force");
    }

    @Test
    public void thePersonsForcedUndoStillResets() throws Exception {
        int exitCode = new UndoCommand().execute(new String[] {"--force"});

        assertThat(exitCode).isZero();
        verify(git).undo("HEAD");
    }
}
