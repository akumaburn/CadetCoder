package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ai.AIClient;
import com.eonmux.cadetcoder.ai.AIManager;
import com.eonmux.cadetcoder.ai.PromptData;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A run that stops making progress has to end.
 *
 * <p>The executor drives eleven commands that never propose an action, so {@link ActionLoopGuard} --
 * which watches actions -- does not apply to them, and there is no iteration ceiling by default.
 * Without a guard of its own, a provider that answers every prompt with the same sentence is
 * re-asked without limit, which against a paid provider is billable.</p>
 */
public class IterativeExecutorProgressTest {

    private TestOutputCapture outputCapture;
    private IterativeCommand  mockCommand;
    private RepeatingAIClient client;
    private Object            previousAiManagerInstance;

    @Before
    public void setUp() {
        outputCapture = new TestOutputCapture();
        mockCommand   = mock(IterativeCommand.class);
        System.setProperty("cadet.interactive", "false");
    }

    @After
    public void tearDown() throws Exception {
        restoreAiManager();
        System.clearProperty("cadet.interactive");
        outputCapture.restore();
    }

    @Test
    public void aProviderThatKeepsRepeatingItselfEndsTheRunInsteadOfBeingAskedForever()
            throws Exception {
        installRepeatingAiClient("I will look at the code and get back to you.");
        String[] args = neverCompletingCommand();

        int exitCode = new IterativeExecutor().execute(mockCommand, args);

        assertThat(exitCode).isEqualTo(1);
        assertThat(outputCapture.getAllOutput()).contains("Stopping:")
                                                .contains("did not advance");
        // Bounded, and bounded tightly: a handful of turns, not thousands.
        assertThat(client.prompts).hasSizeLessThan(12);
    }

    @Test
    public void theNudgeIsAppendedToTheRequestOnceRepetitionIsSeen() throws Exception {
        installRepeatingAiClient("the same answer every time");
        String[] args = neverCompletingCommand();

        new IterativeExecutor().execute(mockCommand, args);

        List<String> nudged = new ArrayList<>();
        for (String prompt : client.prompts) {
            if (prompt.contains("repeated the one before it")) {
                nudged.add(prompt);
            }
        }
        assertThat(nudged).as("a repeating provider should have been nudged").isNotEmpty();
    }

    @Test
    public void theNudgeSitsAtTheVeryEndSoTheCachedPrefixSurvives() throws Exception {
        installRepeatingAiClient("the same answer every time");
        String[] args = neverCompletingCommand();

        new IterativeExecutor().execute(mockCommand, args);

        for (String prompt : client.prompts) {
            int at = prompt.indexOf("repeated the one before it");
            if (at < 0) {
                continue;
            }
            // Everything a provider has already cached comes before it, so only the nudge's own
            // tokens are new. Anywhere else in the prompt it would invalidate the shared prefix.
            // Asserted as "nothing of the conversation follows it" rather than as a position, which
            // would only measure how long the transcript happened to be.
            assertThat(prompt.substring(at))
                    .as("no part of the conversation may follow the nudge")
                    .doesNotContain("Next prompt:")
                    .doesNotContain("System:")
                    .doesNotContain("LLM:");
            String lastLine = prompt.stripTrailing().substring(prompt.stripTrailing().lastIndexOf('\n') + 1);
            assertThat(lastLine)
                    .as("the nudge's unique token is the last thing in the prompt")
                    .startsWith("Request id");
        }
    }

    @Test
    public void theNudgeIsNeverRecordedIntoTheConversationSoItCannotAccumulate() throws Exception {
        installRepeatingAiClient("the same answer every time");
        String[] args = neverCompletingCommand();

        new IterativeExecutor().execute(mockCommand, args);

        for (String prompt : client.prompts) {
            long occurrences = prompt.split("repeated the one before it", -1).length - 1;
            // If the nudge entered the transcript, every later turn would carry every earlier nudge,
            // the rendered history would stop matching what was actually sent, and the prompt would
            // grow by a nudge per turn for the rest of the run.
            assertThat(occurrences)
                    .as("a prompt should carry at most the nudge for this turn")
                    .isLessThanOrEqualTo(1);
        }
    }

    /** A command whose steps never complete, so only the guard can end the run. */
    private String[] neverCompletingCommand() {
        String[] args = {"analyse"};
        when(mockCommand.supportsIterativeExecution(args)).thenReturn(true);
        when(mockCommand.getInitialPrompt(args)).thenReturn("Analyse the widget");
        when(mockCommand.getIterativeSystemPrompt()).thenReturn("You are analysing a widget.");
        when(mockCommand.executeStep(any(), any(), any()))
                .thenAnswer(invocation -> new IterativeCommand.StepResult(
                        false, "still working", new HashMap<>(), "Keep analysing the widget"));
        return args;
    }

    private void installRepeatingAiClient(String answer) throws Exception {
        Field instanceField = AIManager.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        previousAiManagerInstance = instanceField.get(null);

        Constructor<AIManager> constructor = AIManager.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        AIManager manager = constructor.newInstance();

        client = new RepeatingAIClient(answer);
        setField(manager, "activeClient", client);
        setField(manager, "initialized", Boolean.TRUE);
        instanceField.set(null, manager);
    }

    private void restoreAiManager() throws Exception {
        Field instanceField = AIManager.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        instanceField.set(null, previousAiManagerInstance);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = AIManager.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    /** A provider that answers everything with the same sentence, and remembers what it was asked. */
    private static final class RepeatingAIClient implements AIClient {

        private final String       answer;
        private final List<String> prompts = new ArrayList<>();

        private RepeatingAIClient(String answer) {
            this.answer = answer;
        }

        @Override
        public String complete(PromptData promptData, Map<String, Object> parameters) {
            prompts.add(promptData.getUserPrompt());
            return answer;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String getModelName() {
            return "stub/repeating";
        }
    }
}
