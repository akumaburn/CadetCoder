package com.eonmux.cadetcoder.commands;

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
 * Who {@code --force} speaks for.
 *
 * <h2>The defect</h2>
 *
 * <p>{@code --force} means the confirmation has already been answered, so the gate does not ask it
 * again. The flag is read off the argument list, and on the agentic path that list is written by
 * the model: {@code bash -f ./deploy.sh} answered the question on the user's behalf, with the user
 * never asked and no record that anything had been skipped. The flag is the person's, so it counts
 * only when the person typed it.</p>
 */
public class AmodelsForceIsNotThePersonsConsentTest {

    /** Harmless, and not on any list, so what is under test is the confirmation and nothing else. */
    private static final String[] FORCED = {"-f", "echo", "hello"};

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
        when(mockSecurity.isRequireConfirmation()).thenReturn(true);
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

    /** Runs one command with the terminal answering yes to whatever it is asked. */
    private void runWith(OutputRouter router, boolean asTheModel) {
        try (MockedStatic<ConfigManager> configs = mockStatic(ConfigManager.class);
             MockedStatic<OutputRouter> routers = mockStatic(OutputRouter.class)) {
            configs.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);
            routers.when(OutputRouter::getInstance).thenReturn(router);
            if (asTheModel) {
                ModelDispatch.run("bash", () -> bash.execute(FORCED));
            } else {
                bash.execute(FORCED);
            }
        }
    }

    private OutputRouter aterminalThatSaysYes() {
        OutputRouter router = mock(OutputRouter.class);
        when(router.canPrompt()).thenReturn(true);
        when(router.getConfirmation(anyString())).thenReturn(true);
        return router;
    }

    @Test
    public void theFlagTypedByThePersonStillSkipsTheQuestion() {
        OutputRouter router = aterminalThatSaysYes();

        runWith(router, false);

        verify(router, never()).getConfirmation(anyString());
    }

    @Test
    public void theSameFlagWrittenByTheModelDoesNot() {
        OutputRouter router = aterminalThatSaysYes();

        runWith(router, true);

        verify(router).getConfirmation(anyString());
    }

    @Test
    public void thecommandStillRunsOnceThePersonHasSaidYes() {
        runWith(aterminalThatSaysYes(), true);

        assertThat(output.getAllOutput())
                .as("the command still runs once the person says yes")
                .contains("hello");
    }
}
