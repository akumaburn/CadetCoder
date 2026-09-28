package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.context.ContextEngine;
import com.eonmux.cadetcoder.test.StubbedProvider;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import com.eonmux.cadetcoder.timers.AgentTimer;
import com.eonmux.cadetcoder.timers.TimerRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where a timer that has come due actually reaches the model, and where it must not.
 *
 * <p><b>The defect</b>: a firing is true of one moment, and the prompt it goes into is otherwise an
 * append-only record that every later turn extends. Recorded in the transcript, or placed anywhere
 * but the very end, a firing would rewrite the leading bytes of every subsequent prompt -- so a run
 * with a two-minute timer would lose its prompt cache every two minutes and pay full price for its
 * whole history each time. Recorded in the transcript it would also be re-read on every later turn,
 * so a single check-in would look like one that kept happening.</p>
 *
 * <p><b>What is locked here</b>: that a turn with nothing momentary to add is byte-identical to the
 * conversation it extends; that what is momentary is appended in order, after everything else; and
 * that both loops that build their own prompt -- the chat/iterative loop through the executor, and
 * the agent loop through its own builder -- carry a firing at the end rather than among the history
 * they build.</p>
 */
public class ACheckInArrivesWithTheNextTurnTest {

    private static final Duration EVERY_MINUTE = Duration.ofMinutes(1);

    @Before
    public void clearTimers() {
        TimerRegistry.clearAll();
    }

    @After
    public void leaveNothingRunning() {
        TimerRegistry.clearAll();
        System.clearProperty("cadet.interactive");
    }

    /** The ordinary case, and the one the cache is for. */
    @Test
    public void aturnWithNothingToAddIsUnchanged() {
        assertThat(IterativeExecutor.turnPrompt("the conversation so far", "", ""))
                .isEqualTo("the conversation so far");
        assertThat(IterativeExecutor.turnPrompt("the conversation so far", null, null))
                .isEqualTo("the conversation so far");
        assertThat(IterativeExecutor.turnPrompt("the conversation so far"))
                .isEqualTo("the conversation so far");
    }

    @Test
    public void whatIsMomentaryGoesLastAndInOrder() {
        String prompt = IterativeExecutor.turnPrompt("history", " nudge", " [timer] t1 fired");

        assertThat(prompt).isEqualTo("history nudge [timer] t1 fired");
        assertThat(prompt).startsWith("history");
    }

    /**
     * The agent loop builds its own prompt rather than going through the executor's transcript, so
     * it is the second place a firing has to arrive -- and the second place it could be buried among
     * the history instead.
     */
    @Test
    public void theAgentLoopsPromptCarriesAFiringAtItsEnd() throws Exception {
        TimerRegistry.create("check whether the build finished", EVERY_MINUTE,
                             AgentTimer.UNLIMITED, Instant.now().minusSeconds(120));

        AgentState          state   = new AgentState("add a retry", 0, System::currentTimeMillis);
        Map<String, Object> context = new HashMap<>();

        String prompt = AgentPrompts.user(state, context, noProjectContext());

        assertThat(prompt).contains("check whether the build finished");
        assertThat(prompt.indexOf("[timer]"))
                .as("a firing is momentary, so it belongs after the step's own instruction")
                .isGreaterThan(prompt.indexOf("decide the next step"));
    }

    @Test
    public void theAgentLoopsPromptIsUntouchedWhenNothingIsDue() throws Exception {
        AgentState          state   = new AgentState("add a retry", 0, System::currentTimeMillis);
        Map<String, Object> context = new HashMap<>();

        assertThat(AgentPrompts.user(state, context, noProjectContext()))
                .doesNotContain("[timer]");
    }

    /**
     * The chat/iterative loop, end to end: a real run, a real executor, a stubbed provider.
     *
     * <p>The one delivery point the unit tests above cannot reach. {@code turnPrompt} can be correct
     * while the executor never hands it a firing at all, and that deletion -- removing
     * {@code TimerNotice.dueNow()} from the request the executor builds -- would leave every other
     * test in this project passing while the loop the user actually talks to never checks in.</p>
     */
    @Test
    public void theChatLoopsRequestCarriesAFiringAtItsEnd() {
        TimerRegistry.create("check whether the workers have finished", EVERY_MINUTE,
                             AgentTimer.UNLIMITED, Instant.now().minusSeconds(120));

        String sent = whatOneChatTurnSends();

        assertThat(sent).contains("Look at the widget")
                        .contains("check whether the workers have finished");
        assertThat(sent.indexOf("[timer]"))
                .as("a firing is momentary, so it belongs after the conversation it extends")
                .isGreaterThan(sent.indexOf("Look at the widget"));
    }

    @Test
    public void theChatLoopsRequestIsUntouchedWhenNothingIsDue() {
        assertThat(whatOneChatTurnSends()).doesNotContain("[timer]");
    }

    /**
     * One turn of a real iterative run, and what the provider was handed for it.
     *
     * <p>The command is a stub with one prompt and a step that finishes, so the run is exactly one
     * request long: what is being read is what the executor built, not what a command did with the
     * answer.</p>
     */
    private static String whatOneChatTurnSends() {
        System.setProperty("cadet.interactive", "false");
        String[]         args    = {"look"};
        IterativeCommand command = Mockito.mock(IterativeCommand.class);
        Mockito.when(command.supportsIterativeExecution(args)).thenReturn(true);
        Mockito.when(command.getInitialPrompt(args)).thenReturn("Look at the widget");
        Mockito.when(command.executeStep(Mockito.any(), Mockito.anyMap(), Mockito.anyString()))
               .thenReturn(IterativeCommand.StepResult.success("looked", new HashMap<>()));

        TestOutputCapture quiet = new TestOutputCapture();
        try (StubbedProvider provider = StubbedProvider.answering("Looked at it.")) {
            int exitCode = new IterativeExecutor().execute(command, args);

            quiet.restore();
            assertThat(exitCode).isEqualTo(0);
            assertThat(provider.lastAsked()).as("the run has to have reached the provider").isNotNull();
            return provider.lastUserPrompt();
        } finally {
            quiet.restore();
        }
    }

    /**
     * A turn that failed on its way to the model leaves the firing owed.
     *
     * <p>The one deletion this catches: the notice was taken while the prompt was BUILT, so a run
     * whose provider was unreachable lost the reminder outright -- the timer advanced, its number
     * was counted against the limit, and nobody ever saw it.</p>
     */
    @Test
    public void afiringSurvivesAturnThatNeverReachedTheModel() {
        System.setProperty("cadet.interactive", "false");
        TimerRegistry.create("check whether the workers have finished", EVERY_MINUTE,
                             AgentTimer.UNLIMITED, Instant.now().minusSeconds(120));

        String[]         args    = {"look"};
        IterativeCommand command = Mockito.mock(IterativeCommand.class);
        Mockito.when(command.supportsIterativeExecution(args)).thenReturn(true);
        Mockito.when(command.getInitialPrompt(args)).thenReturn("Look at the widget");

        TestOutputCapture quiet = new TestOutputCapture();
        try (StubbedProvider provider = StubbedProvider.failing("the provider is unreachable")) {
            new IterativeExecutor().execute(command, args);
            quiet.restore();
            assertThat(provider.lastAsked())
                    .as("the run has to have tried to reach the provider")
                    .isNotNull();
        } finally {
            quiet.restore();
        }

        assertThat(TimerRegistry.takeDue(Instant.now()))
                .as("the reminder is still owed, because nothing ever read it")
                .singleElement()
                .satisfies(firing -> {
                    assertThat(firing.instruction()).isEqualTo("check whether the workers have finished");
                    assertThat(firing.nonce()).isEqualTo(1);
                });
    }

    /**
     * A project index that finds nothing.
     *
     * <p>Stubbed rather than real: the prompt's project snippets are not what this is about, and
     * indexing the repository to assert where a timer notice lands would make every run of this test
     * depend on what happens to be in the working tree.</p>
     */
    private static ContextEngine noProjectContext() throws Exception {
        ContextEngine project = Mockito.mock(ContextEngine.class);
        Mockito.when(project.searchRelevantSnippets(Mockito.anyString())).thenReturn(List.of());
        return project;
    }
}
