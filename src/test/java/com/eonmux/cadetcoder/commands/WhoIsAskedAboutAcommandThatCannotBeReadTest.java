package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ExitCode;
import com.eonmux.cadetcoder.ai.CommandApproval;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import com.eonmux.cadetcoder.ui.OutputRouter;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A command the screens cannot read is put to somebody rather than dropped.
 *
 * <h2>The two kinds of refusal</h2>
 *
 * <p>A line that names {@code rm} is refused for what it does, and nobody is asked about it. A line
 * whose program is built out of a variable is refused because it cannot be read, which is a
 * statement about the checker rather than about the command. {@code security.commandApproval} says
 * who settles the second kind: {@code manual} asks the person at the terminal, {@code auto} asks
 * the model. Where neither can be reached the command is still blocked, and the message says which
 * setting would have given the decision to somebody.</p>
 */
public class WhoIsAskedAboutAcommandThatCannotBeReadTest {

    /** A line whose program cannot be read from it, and which therefore needs approval. */
    private static final String[] UNREADABLE = {"$CADET_TEST_TOOL", "--version"};

    @Mock
    private ConfigManager mockConfigManager;

    @Mock
    private Configuration mockConfig;

    @Mock
    private Configuration.SecurityConfig mockSecurity;

    private BashCommand       bash;
    private TestOutputCapture output;
    private AutoCloseable     mocks;
    private String            previousMode;

    @Before
    public void setUp() {
        mocks  = MockitoAnnotations.openMocks(this);
        bash   = new BashCommand();
        output = new TestOutputCapture();
        output.startCapture();
        previousMode = System.getProperty(CommandApproval.PROPERTY);
        System.setProperty(CommandApproval.PROPERTY, "manual");

        when(mockConfig.getSecurity()).thenReturn(mockSecurity);
        when(mockSecurity.isReadOnlyMode()).thenReturn(false);
        when(mockSecurity.isAllowRemoteExecution()).thenReturn(true);
        when(mockSecurity.isRequireConfirmation()).thenReturn(false);
        when(mockSecurity.isSandboxMode()).thenReturn(false);
    }

    @After
    public void tearDown() throws Exception {
        output.stopCapture();
        if (previousMode == null) {
            System.clearProperty(CommandApproval.PROPERTY);
        } else {
            System.setProperty(CommandApproval.PROPERTY, previousMode);
        }
        mocks.close();
    }

    /** Runs one command with the terminal answering the way this test wants it answered. */
    private int runWith(OutputRouter router, String[] command) {
        try (MockedStatic<ConfigManager> configs = mockStatic(ConfigManager.class);
             MockedStatic<OutputRouter> routers = mockStatic(OutputRouter.class)) {
            configs.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);
            routers.when(OutputRouter::getInstance).thenReturn(router);
            return bash.execute(command);
        }
    }

    @Test
    public void thePersonAtTheTerminalIsAskedAndCanSayYes() {
        OutputRouter router = mock(OutputRouter.class);
        when(router.canPrompt()).thenReturn(true);
        when(router.getConfirmation(anyString())).thenReturn(true);

        runWith(router, UNREADABLE);

        verify(router).getConfirmation(anyString());
        assertThat(output.getAllOutput()).contains("could not be checked");
        assertThat(output.getAllOutput()).doesNotContain("Command blocked by security policy");
    }

    @Test
    public void theSameQuestionAnsweredNoEndsTheCommand() {
        OutputRouter router = mock(OutputRouter.class);
        when(router.canPrompt()).thenReturn(true);
        when(router.getConfirmation(anyString())).thenReturn(false);

        int exitCode = runWith(router, UNREADABLE);

        assertThat(exitCode).isEqualTo(ExitCode.INTERRUPTED);
        assertThat(output.getAllOutput()).contains("Command execution cancelled");
    }

    @Test
    public void whereNobodyCanBeAskedTheMessageSaysWhatWouldHaveDecidedIt() {
        OutputRouter router = mock(OutputRouter.class);
        when(router.canPrompt()).thenReturn(false);

        int exitCode = runWith(router, UNREADABLE);

        assertThat(exitCode).isEqualTo(1);
        verify(router, never()).getConfirmation(anyString());
        assertThat(output.getAllOutput()).contains("security.commandApproval");
    }

    @Test
    public void whatIsRefusedOutrightIsNotPutToAnybody() {
        // The distinction this whole gate rests on: a person is asked what could not be read, never
        // what the denylist named.
        OutputRouter router = mock(OutputRouter.class);
        when(router.canPrompt()).thenReturn(true);
        when(router.getConfirmation(anyString())).thenReturn(true);

        int exitCode = runWith(router, new String[] {"rm", "-rf", "/tmp/whatever"});

        assertThat(exitCode).isEqualTo(1);
        verify(router, never()).getConfirmation(anyString());
        assertThat(output.getAllOutput()).contains("is on the list of programs CadetCoder will not run");
    }
}
