package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ai.CommandApproval;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import com.eonmux.cadetcoder.ui.OutputRouter;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A force push through {@code bash} is confirmed by the person, as {@code push --force} is.
 *
 * <h2>The defect</h2>
 *
 * <p>{@code push --force} asks the person every time, but {@code bash "git push --force"} went
 * through the ordinary approval. Under {@code security.commandApproval auto} that approval is the
 * model's, so a model could rewrite the remote's history by approving its own command. A person's
 * {@code bash -f} skipped the question as well.</p>
 *
 * <p>The command names a folder that does not exist, so even an approved run fails inside git and
 * never reaches a remote.</p>
 */
public class AforcePushThroughTheShellIsThePersonsToAllowTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private final Configuration config = new Configuration();
    private final ConfigManager manager = mock(ConfigManager.class);
    private TestOutputCapture   output;
    private String              previousMode;
    private String              forcePush;

    @Before
    public void setUp() {
        output = new TestOutputCapture();
        output.startCapture();
        previousMode = System.getProperty(CommandApproval.PROPERTY);
        System.setProperty(CommandApproval.PROPERTY, "auto");
        config.getSecurity().setAllowRemoteExecution(true);
        config.getSecurity().setRequireConfirmation(true);
        when(manager.getConfig()).thenReturn(config);
        forcePush = "git -C " + folder.getRoot().toPath().resolve("missing") + " push --force origin main";
    }

    @After
    public void tearDown() {
        output.stopCapture();
        if (previousMode == null) {
            System.clearProperty(CommandApproval.PROPERTY);
        } else {
            System.setProperty(CommandApproval.PROPERTY, previousMode);
        }
    }

    private static OutputRouter aTerminal(boolean someoneIsThere, boolean answer) {
        OutputRouter router = mock(OutputRouter.class);
        when(router.canPrompt()).thenReturn(someoneIsThere);
        when(router.getConfirmation(anyString())).thenReturn(answer);
        return router;
    }

    private int run(OutputRouter router, boolean asTheModel, String... args) {
        try (MockedStatic<ConfigManager> configs = mockStatic(ConfigManager.class);
             MockedStatic<OutputRouter> routers = mockStatic(OutputRouter.class)) {
            configs.when(ConfigManager::getInstance).thenReturn(manager);
            routers.when(OutputRouter::getInstance).thenReturn(router);
            return asTheModel ? ModelDispatch.run("bash", () -> new BashCommand().execute(args))
                              : new BashCommand().execute(args);
        }
    }

    @Test
    public void underAutoTheModelsForcePushIsPutToThePerson() {
        OutputRouter router = aTerminal(true, false);

        int exitCode = run(router, true, forcePush);

        assertThat(exitCode).isNotZero();
        verify(router).getConfirmation(anyString());
        assertThat(output.getAllOutput()).doesNotContain("Safety check allowed");
    }

    @Test
    public void thePersonsBashForceDoesNotSkipTheQuestion() {
        OutputRouter router = aTerminal(true, false);

        run(router, false, "-f", forcePush);

        verify(router).getConfirmation(anyString());
    }

    @Test
    public void withNobodyToAskAForcePushIsRefused() {
        OutputRouter router = aTerminal(false, true);

        int exitCode = run(router, true, forcePush);

        assertThat(exitCode).isNotZero();
        verify(router, never()).getConfirmation(anyString());
        assertThat(output.getAllOutput()).contains("nobody can be asked");
    }

    @Test
    public void onceThePersonSaysYesItRuns() {
        run(aTerminal(true, true), true, forcePush);

        assertThat(output.getAllOutput()).as("git itself was reached, and failed on the missing folder")
                                         .containsIgnoringCase("cannot change to");
    }
}
