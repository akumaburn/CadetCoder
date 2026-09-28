package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.ai.AIManager;
import com.eonmux.cadetcoder.ai.CommandApproval;
import com.eonmux.cadetcoder.ai.PromptData;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * A CadetCoder command given to {@code bash} is pointed at the action it should have been.
 *
 * <p>A model ran {@code bash job list}. The shell answered "job: command not found", and the model
 * spent its next request on working out that {@code job} is one of this tool's commands.</p>
 */
class AcommandRunAsAprogramIsPointedAtItsOwnActionTest {

    private TestOutputCapture       output;
    private MockedStatic<AIManager> managers;

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty(CommandApproval.PROPERTY, "auto");
        AIManager model = mock(AIManager.class);
        when(model.complete(any(PromptData.class))).thenReturn("ALLOW: it only lists jobs.");
        when(model.complete(any(PromptData.class), any())).thenReturn("ALLOW: it only lists jobs.");
        managers = mockStatic(AIManager.class);
        managers.when(AIManager::getInstance).thenReturn(model);
        output = new TestOutputCapture();
        output.startCapture();
    }

    @AfterEach
    void tearDown() {
        output.stopCapture();
        managers.close();
        System.clearProperty(CommandApproval.PROPERTY);
    }

    @Test
    void themodelIsToldToRunItAsItsOwnAction() {
        // The registered instance, run on this thread: the model is stubbed for this thread only.
        CommandRegistry.Command bash = new CommandRegistry().getCommand("bash");

        int exit = ModelDispatch.run("bash", () -> bash.execute(new String[] {"job list"}));

        assertThat(exit).as(output.getAllOutput()).isEqualTo(127);
        assertThat(output.getAllOutput()).contains("'job' is a CadetCoder command").contains("COMMAND: job");
    }
}
