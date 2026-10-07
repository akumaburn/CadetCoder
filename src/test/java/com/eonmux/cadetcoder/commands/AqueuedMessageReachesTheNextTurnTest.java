package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ai.PromptData;
import com.eonmux.cadetcoder.test.StubbedProvider;
import com.eonmux.cadetcoder.test.TestOutputCapture;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A message sent while a conversation runs reaches the model at its next request.
 *
 * <p><b>What is locked here</b>: that the chat loop puts the message in its transcript after the
 * step that was running when it arrived, as the person's words; that the agent's classic loop
 * takes it through the handle the executor passes in the step context, though it runs its whole
 * loop inside one step; and that its history keeps the message in its place among the actions.</p>
 */
public class AqueuedMessageReachesTheNextTurnTest {

    private static final String LIST_FILES = "ACTION_START\nCOMMAND: ls\nARGS: .\n"
                                             + "REASON: see what is there\nACTION_END";

    /**
     * A message no source file contains, built when the test runs. The agent's prompt carries
     * snippets of the project, this test among them, so a literal would be found there.
     */
    private static final String NOTE = String.join("-", "keep", "every", "edit", "small");

    private TestOutputCapture output;

    @Before
    public void setUp() {
        QueuedMessages.clear();
        System.setProperty("cadet.interactive", "false");
        output = new TestOutputCapture();
        output.startCapture();
        QueuedMessages.ownedBy(Thread.currentThread());
    }

    @After
    public void tearDown() {
        output.stopCapture();
        QueuedMessages.clear();
        System.clearProperty("cadet.interactive");
    }

    private static QueuedMessages.Line message(String text) {
        return new QueuedMessages.Line(text, text, text, text);
    }

    private static List<String> userPrompts(StubbedProvider provider) {
        return provider.asked().stream().map(PromptData::getUserPrompt).toList();
    }

    @Test
    public void theChatLoopAddsTheMessageAfterTheStepItArrivedDuring() {
        String[]         args    = {"look"};
        IterativeCommand command = Mockito.mock(IterativeCommand.class);
        Mockito.when(command.supportsIterativeExecution(args)).thenReturn(true);
        Mockito.when(command.getInitialPrompt(args)).thenReturn("Look at the widget");
        Mockito.when(command.executeStep(Mockito.any(), Mockito.anyMap(), Mockito.anyString()))
               .thenAnswer(call -> {
                   // Sent while the step runs, as a person typing during a command would.
                   QueuedMessages.add(message("also check the docs"));
                   return new IterativeCommand.StepResult(false, "the widget is blue",
                                                          new HashMap<>(), "What next?");
               })
               .thenReturn(IterativeCommand.StepResult.success("done", new HashMap<>()));

        try (StubbedProvider provider = StubbedProvider.answering("Looked at it.", "Done.")) {
            new IterativeExecutor().execute(command, args);

            List<String> prompts = userPrompts(provider);
            assertThat(prompts).hasSize(2);
            assertThat(prompts.get(0)).doesNotContain("also check the docs");
            String second = prompts.get(1);
            assertThat(second).contains("User: also check the docs");
            assertThat(second.indexOf("User: also check the docs"))
                    .as("after the step that was running when it was sent")
                    .isGreaterThan(second.indexOf("Next prompt: What next?"));
        }
        assertThat(QueuedMessages.size()).isZero();
        assertThat(output.getAllOutput())
                .contains("Added to the conversation: also check the docs");
    }

    /** The request that carried it was refused, so the model never read it; the shell says so. */
    @Test
    public void aMessageTheModelNeverReadIsReportedWhenTheRunEnds() {
        String[]         args    = {"look"};
        IterativeCommand command = Mockito.mock(IterativeCommand.class);
        Mockito.when(command.supportsIterativeExecution(args)).thenReturn(true);
        Mockito.when(command.getInitialPrompt(args)).thenReturn("Look at the widget");
        Mockito.when(command.executeStep(Mockito.any(), Mockito.anyMap(), Mockito.anyString()))
               .thenAnswer(call -> {
                   QueuedMessages.add(message("also check the docs"));
                   return new IterativeCommand.StepResult(false, "the widget is blue",
                                                          new HashMap<>(), "What next?");
               });

        try (StubbedProvider ignored = StubbedProvider.refusingAt(2, "Looked at it.")) {
            new IterativeExecutor().execute(command, args);
        }

        assertThat(QueuedMessages.size()).isZero();
        assertThat(output.getAllOutput())
                .contains("The run ended before the model read: also check the docs");
    }

    @Test
    public void theAgentsClassicLoopTakesTheMessageThroughTheStepContext() {
        QueuedMessages.add(message(NOTE));

        try (StubbedProvider provider =
                     StubbedProvider.answering(LIST_FILES, "TASK COMPLETE: done")) {
            new IterativeExecutor().execute(new AgentCommand(),
                                            new String[] {"--classic", "-y", "tidy the notes"});

            assertThat(userPrompts(provider)).isNotEmpty();
            assertThat(userPrompts(provider).get(0)).contains("- The user said: " + NOTE);
        }
        assertThat(QueuedMessages.size()).isZero();
    }

    @Test
    public void theAgentsHistoryKeepsAMessageInItsPlaceAmongTheActions() throws Exception {
        AgentState state = new AgentState("tidy the notes", 0, System::currentTimeMillis);
        state.addExecutedAction(AgentActions.from(LIST_FILES, ignored -> { }), 0, "notes.md");
        state.addMessage("only touch the notes");
        state.addExecutedAction(AgentActions.from(LIST_FILES, ignored -> { }), 0, "notes.md");
        Map<String, Object> context = new HashMap<>();

        String prompt = AgentPrompts.user(state, context, Mockito.mock(
                com.eonmux.cadetcoder.context.ContextEngine.class));

        int message = prompt.indexOf("The user said: only touch the notes");
        assertThat(message).isGreaterThan(prompt.indexOf("- ls"));
        assertThat(prompt.indexOf("- ls", message)).as("the second action follows it")
                                                   .isGreaterThan(message);
    }

    /** A resumed classic run is told what the person said, where they said it. */
    @Test
    public void aResumedClassicRunKeepsTheMessagesInTheirPlace() {
        AgentState state = new AgentState("tidy the notes", 0, System::currentTimeMillis);
        state.addExecutedAction(AgentActions.from(LIST_FILES, ignored -> { }), 0, "notes.md");
        state.addMessage("only touch the notes");
        state.addExecutedAction(AgentActions.from(LIST_FILES, ignored -> { }), 0, "notes.md");

        List<String> kept = AgentResumeNote.actionsOf(state);

        assertThat(kept).hasSize(3);
        assertThat(kept.get(0)).startsWith("ls");
        assertThat(kept.get(1)).isEqualTo("The user said: only touch the notes");
        assertThat(kept.get(2)).startsWith("ls");
    }
}
