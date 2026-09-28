package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.ExitCode;
import com.eonmux.cadetcoder.InterruptSignal;
import com.eonmux.cadetcoder.test.TestOutputCapture;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every loop that can be stopped part-way says so, rather than reporting the task as done.
 *
 * <h2>The defect</h2>
 *
 * <p>Three of the loops built their interruption result out of {@code StepResult.success}, which the
 * executor reports as {@link ExitCode#OK}. The rationale written beside the agent's was that an
 * interruption is "a clean termination, not a failure" -- true about the agent's own state, and not
 * an answer to the question an exit code asks, which is whether the next command may build on this
 * work. A script that stopped an agent was told the agent had finished.</p>
 *
 * <h2>Why the signal is what drives these</h2>
 *
 * <p>{@link InterruptSignal} is what the key press sets, and it is set before anything else knows.
 * These tests use it rather than an {@code InterruptionContext} because the context is written by
 * the registry's polling monitor, which lags -- and a checkpoint that only reads the context is
 * blind for the whole of that lag.</p>
 */
public class AloopTakenBackDoesNotReportSuccessTest {

    private TestOutputCapture output;

    @Before
    public void setUp() {
        output = new TestOutputCapture();
        output.startCapture();
    }

    @After
    public void tearDown() {
        InterruptSignal.clear();
        output.stopCapture();
    }

    private static Map<String, Object> runInProgress(String step) {
        Map<String, Object> context = new HashMap<>();
        context.put("step", step);
        context.put("agentState", new AgentState("add a retry", 0, System::currentTimeMillis));
        context.put("verboseMode", Boolean.FALSE);
        return context;
    }

    @Test
    public void anagentRunStoppedPartWayIsNotReportedAsFinished() {
        InterruptSignal.request();

        IterativeCommand.StepResult stopped =
                new AgentCommand().executeStep(new String[0], runInProgress("execute_agent_step"), null);

        assertThat(stopped.isComplete()).as("the run is over either way").isTrue();
        assertThat(stopped.isInterrupted()).as("it was stopped, not finished").isTrue();
        assertThat(stopped.isError()).as("nothing went wrong with it").isFalse();
    }

    @Test
    public void achatRunStoppedBeforeItsFirstRequestIsNotReportedAsFinished() {
        InterruptSignal.request();

        Map<String, Object> context = new HashMap<>();
        context.put("step", "analyze_request");
        context.put("userRequest", "list the files");

        IterativeCommand.StepResult stopped =
                new ChatCommand().executeStep(new String[] {"list", "the", "files"}, context, null);

        assertThat(stopped.isInterrupted()).isTrue();
        assertThat(stopped.isError()).isFalse();
    }

    @Test
    public void aneditStoppedBeforeItsRequestIsNotReportedAsFinished() {
        InterruptSignal.request();

        Map<String, Object> context = new HashMap<>();
        context.put("step", "generate_changes");
        context.put("editRequest", "rename the field");
        context.put("promptBuilder", new com.eonmux.cadetcoder.ai.PromptBuilder("edit"));
        context.put("fileContent", "class A {}");

        IterativeCommand.StepResult stopped =
                new EditCommand().executeStep(new String[] {"A.java"}, context, null);

        assertThat(stopped.isInterrupted()).isTrue();
        assertThat(stopped.isError()).isFalse();
    }

    /**
     * The whole command, not just the step: what the executor turns that result into is the number
     * the shell actually reads.
     */
    @Test
    public void whatTheShellReadsForAstoppedAgentIsTheInterruptionCode() {
        InterruptSignal.request();

        assertThat(new IterativeExecutor().execute(new AgentCommand(),
                                                   new String[] {"--classic", "-y", "add a retry"}))
                .isEqualTo(ExitCode.INTERRUPTED);
    }

    /**
     * The checkpoint has to see the request the moment it is made.
     *
     * <p>Four commands answered this from their {@code InterruptionContext} alone, which nothing had
     * written yet, and three answered it from the thread's interrupt flag alone, which nothing had
     * set. Both said "carry on" to a user who had already asked them to stop.</p>
     */
    /**
     * Every command the registry holds, rather than a list kept by hand.
     *
     * @return the interruptible commands of a real registry, which is what a session runs
     */
    private static List<CommandRegistry.InterruptibleCommand> everyInterruptibleCommand() {
        List<CommandRegistry.InterruptibleCommand> found = new ArrayList<>();
        for (CommandRegistry.Command command : new CommandRegistry().getCommands().values()) {
            if (command instanceof CommandRegistry.InterruptibleCommand interruptible) {
                found.add(interruptible);
            }
        }
        assertThat(found).as("a registry with no interruptible commands proves nothing").isNotEmpty();
        return found;
    }

    @Test
    public void everyCommandSeesTheRequestAssoonAsItIsMade() {
        List<CommandRegistry.InterruptibleCommand> commands = everyInterruptibleCommand();
        InterruptSignal.request();

        for (CommandRegistry.InterruptibleCommand command : commands) {
            assertThat(command.shouldInterrupt())
                    .as("%s was asked to stop", command.getClass().getSimpleName())
                    .isTrue();
        }
    }

    @Test
    public void nocommandStopsWhenNobodyAskedItTo() {
        for (CommandRegistry.InterruptibleCommand command : everyInterruptibleCommand()) {
            assertThat(command.shouldInterrupt())
                    .as("%s was never asked to stop", command.getClass().getSimpleName())
                    .isFalse();
        }
    }

    /**
     * A context written by the registry's monitor still stops the command that was given it, for a
     * command reached by a driver that does not set the process-wide signal.
     */
    @Test
    public void acontextTheRegistryWroteStillStopsTheCommandItWasGiven() {
        CommandRegistry.InterruptionContext given = new CommandRegistry.InterruptionContext();
        given.setInterrupted(true);

        LSCommand ls = new LSCommand();
        ls.setInterruptionContext(given);

        assertThat(ls.shouldInterrupt()).isTrue();
    }
}
