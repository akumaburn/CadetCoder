package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ai.AIManager;
import com.eonmux.cadetcoder.ai.PromptData;
import com.eonmux.cadetcoder.ai.UberMode;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.context.ContextEngine;
import com.eonmux.cadetcoder.test.TestOutputCapture;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the agent loop does with a claim of completion, and what both loops are told while uber mode
 * is on.
 *
 * <p><b>The defect</b>: the mode has three parts -- a directive in the system prompt, a question put
 * to a claim, and that question reaching the next prompt -- and each is one call at one site. Any of
 * the three could be removed and the run would still look ordinary from outside: a model never told
 * what finishing means, or a claim believed the moment it is made, or a question asked into a prompt
 * the model never sees, all end a run with a confident summary.</p>
 *
 * <p><b>What is locked here</b>: that the directive is in the system prompt of both loops when the
 * mode is on and in neither when it is off; that a claim made while it is on does not end the classic
 * run; that the question it earns is carried into the next step's prompt; and that the questions run
 * out, so the run ends.</p>
 */
public class TheAgentLoopIsHeldToTheEndTest {

    private final AgentCommand agent = new AgentCommand();

    private boolean wasOn;

    @Before
    public void rememberTheSetting() {
        Configuration.AiConfig ai = ConfigManager.getInstance().getConfig().getAi();
        wasOn = ai.isUberMode();
        ai.setUberMode(true);
    }

    @After
    public void restoreTheSetting() {
        ConfigManager.getInstance().getConfig().getAi().setUberMode(wasOn);
    }

    private static void theModeIsOff() {
        ConfigManager.getInstance().getConfig().getAi().setUberMode(false);
    }

    private static AgentState runDoing(String task) {
        return new AgentState(task, 0, System::currentTimeMillis);
    }

    /**
     * The directive is what makes the questions answerable: a model asked whether it is finished,
     * having never been told what finishing means here, answers from whatever it already believed.
     */
    @Test
    public void theAgentsSystemPromptCarriesTheDirectiveOnlyWhileTheModeIsOn() {
        String directive = UberMode.directive();
        assertThat(directive).isNotEmpty();

        String held = AgentPrompts.system(runDoing("add a retry"), AgentOptions.UNLIMITED,
                                          message -> { });
        assertThat(held).contains(directive);

        theModeIsOff();
        assertThat(AgentPrompts.system(runDoing("add a retry"), AgentOptions.UNLIMITED,
                                       message -> { }))
                .doesNotContain(directive);
    }

    @Test
    public void thechatLoopsSystemPromptCarriesTheDirectiveOnlyWhileTheModeIsOn() {
        String directive = UberMode.directive();

        assertThat(new ChatCommand().getIterativeSystemPrompt()).contains(directive);

        theModeIsOff();
        assertThat(new ChatCommand().getIterativeSystemPrompt()).doesNotContain(directive);
    }

    @Test
    public void nothingIsQuestionedWhileTheModeIsOff() {
        theModeIsOff();
        Map<String, Object> context = new HashMap<>();

        assertThat(agent.questionedCompletion(runDoing("add a retry"), context,
                                              "TASK COMPLETE: added it")).isFalse();
        assertThat(context).isEmpty();
    }

    @Test
    public void aclaimDoesNotEndTheRunWhileTheModeIsOn() {
        Map<String, Object> context = new HashMap<>();

        assertThat(agent.questionedCompletion(runDoing("add a retry to the uploader"), context,
                                              "TASK COMPLETE: added the retry")).isTrue();
        assertThat(context.get("uberChecksPassed")).isEqualTo(1);
        assertThat((String) context.get("uberChallenge"))
                .contains("add a retry to the uploader")
                .contains("TASK COMPLETE: added the retry")
                .contains("ACTION_START");
    }

    /**
     * The claim ends a step, so the question can only be asked in the next step's prompt -- and a
     * question recorded in the context but never rendered would leave the run going on with nothing
     * to answer.
     */
    @Test
    public void thequestionIsCarriedIntoTheNextStepsPrompt() throws Exception {
        AgentState          state   = runDoing("add a retry to the uploader");
        Map<String, Object> context = new HashMap<>();
        agent.questionedCompletion(state, context, "TASK COMPLETE: added the retry");

        assertThat(AgentPrompts.user(state, context, noProjectContext()))
                .contains("add a retry to the uploader")
                .contains("TASK COMPLETE: added the retry");
    }

    /**
     * A claim that answers every question in a row is believed, so the run can end.
     *
     * <p>Answering is the way out, rather than outlasting a budget. A run used to stop being asked
     * after a fixed number of claims, which left the claims a long run made after that -- the late
     * ones, the ones worth checking -- unchecked.</p>
     */
    @Test
    public void aclaimThatAnswersEveryQuestionInArowIsBelieved() {
        AgentState          state   = runDoing("add a retry");
        Map<String, Object> context = new HashMap<>();

        for (int question = 1; question <= UberMode.questionCount(); question++) {
            assertThat(agent.questionedCompletion(state, context, "TASK COMPLETE: again")).isTrue();
            assertThat(context.get("uberChecksPassed")).isEqualTo(question);
        }
        assertThat(agent.questionedCompletion(state, context, "TASK COMPLETE: truly")).isFalse();
    }

    /**
     * Doing anything at all puts the run back at the first question.
     *
     * <p>This is what makes the questioning unbounded, and it is the whole of the fix. Driven
     * through the loop rather than against the rule alone, because the rule is only worth anything
     * if the loop applies it: a run that answered one question, worked for another hour and then
     * ended on the questions it had left would have everything it changed in between checked by
     * nobody.</p>
     *
     * <p>The model here claims completion, is questioned, does one thing, and then claims
     * completion three more times. A loop that started the questions again asks two of those and
     * believes the third, which is five requests. A loop that did not would have had only one
     * question left to ask, and would have ended a request sooner.</p>
     */
    @Test
    public void workDoneSinceTheLastQuestionStartsThemAgain() throws Exception {
        AIManager model = Mockito.mock(AIManager.class);
        Mockito.when(model.complete(Mockito.any(PromptData.class), Mockito.anyMap()))
               .thenReturn("TASK COMPLETE: added the retry",
                           "ACTION_START\nCOMMAND: todoread\nARGS:\nREASON: see what is left"
                           + "\nACTION_END",
                           "TASK COMPLETE: and now it really is",
                           "TASK COMPLETE: still is",
                           "TASK COMPLETE: still is");

        TestableAgentCommand agent = new TestableAgentCommand();
        agent.setCommandRegistry(Mockito.mock(com.eonmux.cadetcoder.CommandRegistry.class));
        agent.setMockAIManager(model);
        agent.setMockContextEngine(Mockito.mock(ContextEngine.class));

        TestOutputCapture quiet = new TestOutputCapture();
        try {
            assertThat(agent.executeStep(new String[] {"add a retry"}, classicRunAwaitingConsent(),
                                         "yes").isComplete()).isTrue();
        } finally {
            quiet.restore();
        }

        Mockito.verify(model, Mockito.times(5)).complete(Mockito.any(PromptData.class),
                                                         Mockito.anyMap());
    }

    /**
     * A question the run has no turn left to answer is not asked.
     *
     * <p><b>The defect</b>: the step budget is checked at the START of a step, and the claim is
     * questioned at the END of one. A model that said it was finished on its last allowed step was
     * therefore sent back to check itself, and the next step never happened: the run ended on
     * "Agent reached maximum steps without completing the task" and the completion it had actually
     * claimed -- with the summary it had written -- was thrown away. Turning uber mode ON made the
     * run end worse than leaving it off, which is the opposite of what it is for.</p>
     */
    @Test
    public void aclaimMadeOnTheLastAllowedStepIsBelieved() {
        AgentState          state   = new AgentState("add a retry", 3, System::currentTimeMillis);
        Map<String, Object> context = new HashMap<>();
        state.incrementStep();
        state.incrementStep();
        state.incrementStep();

        assertThat(agent.questionedCompletion(state, context, "TASK COMPLETE: added the retry"))
                .as("there is no step left in which the answer could be read")
                .isFalse();
        assertThat(context)
                .as("nothing is recorded for a question that was never put")
                .isEmpty();
    }

    /** With a step still to spare, the claim is questioned as usual. */
    @Test
    public void aclaimMadeWithAstepInHandIsStillQuestioned() {
        AgentState          state   = new AgentState("add a retry", 3, System::currentTimeMillis);
        Map<String, Object> context = new HashMap<>();
        state.incrementStep();
        state.incrementStep();

        assertThat(agent.questionedCompletion(state, context, "TASK COMPLETE: added the retry"))
                .isTrue();
    }

    /** A run whose time is already up has no turn left either. */
    @Test
    public void aclaimMadeAfterTheTimeBudgetIsBelieved() {
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(0);
        AgentState state = new AgentState("add a retry", AgentOptions.UNLIMITED, clock::get);
        Map<String, Object> context = new HashMap<>();
        context.put("timeoutSec", 1);
        clock.set(5_000);

        assertThat(agent.questionedCompletion(state, context, "TASK COMPLETE: added the retry"))
                .as("the next step would be refused by the time budget")
                .isFalse();
    }

    /**
     * The classic loop end to end, with a model that says the same thing every time.
     *
     * <p>The tests above reach the decision; this one reaches the place the decision is made from. A
     * loop that never asked would take the first claim, so the run would be one request long -- and
     * every other test here would still pass.</p>
     */
    @Test
    public void aclaimIsNotBelievedTheFirstTimeTheClassicLoopHearsIt() throws Exception {
        AIManager        model     = Mockito.mock(AIManager.class);
        ArgumentCaptor<PromptData> asked = ArgumentCaptor.forClass(PromptData.class);
        Mockito.when(model.complete(Mockito.any(PromptData.class), Mockito.anyMap()))
               .thenReturn("TASK COMPLETE: added the retry");

        TestableAgentCommand agent = new TestableAgentCommand();
        agent.setCommandRegistry(Mockito.mock(com.eonmux.cadetcoder.CommandRegistry.class));
        agent.setMockAIManager(model);
        agent.setMockContextEngine(Mockito.mock(ContextEngine.class));

        TestOutputCapture quiet = new TestOutputCapture();
        try {
            IterativeCommand.StepResult result =
                    agent.executeStep(new String[] {"add a retry"}, classicRunAwaitingConsent(),
                                      "yes");

            assertThat(result.isComplete()).isTrue();
            assertThat(result.isError()).isFalse();
        } finally {
            quiet.restore();
        }

        Mockito.verify(model, Mockito.times(3)).complete(asked.capture(), Mockito.anyMap());
        assertThat(asked.getAllValues().get(0).getUserPrompt())
                .as("the first claim has not been made yet")
                .doesNotContain("You have just said this run is finished");
        assertThat(asked.getAllValues().get(1).getUserPrompt())
                .contains("TASK COMPLETE: added the retry");
    }

    /**
     * A correction is cleared by the reply that read it, whatever that reply turned out to be.
     *
     * <p>The loop guard's guidance is the one correction cleared on the path that RUNS an action
     * rather than on the path that reads one. A model that has an action refused as repetition and
     * then says the task is finished has read the guidance -- it is the reason it stopped proposing
     * that action -- so carrying it into the next prompt tells the run off a second time for
     * something it has already corrected, in the same breath as being asked whether it is really
     * done.</p>
     */
    @Test
    public void acorrectionTheModelHasAlreadyReadIsNotRepeatedBackToIt() throws Exception {
        AIManager model = Mockito.mock(AIManager.class);
        Mockito.when(model.complete(Mockito.any(PromptData.class), Mockito.anyMap()))
               .thenReturn("TASK COMPLETE: added the retry");
        ConfigManager.getInstance().getConfig().getAi().setUberMode(false);

        TestableAgentCommand agent = new TestableAgentCommand();
        agent.setCommandRegistry(Mockito.mock(com.eonmux.cadetcoder.CommandRegistry.class));
        agent.setMockAIManager(model);
        agent.setMockContextEngine(Mockito.mock(ContextEngine.class));

        Map<String, Object> run = classicRunAwaitingConsent();
        run.put("loopGuidance", "You have run that command twice; try something else.");

        TestOutputCapture quiet = new TestOutputCapture();
        IterativeCommand.StepResult result;
        try {
            result = agent.executeStep(new String[] {"add a retry"}, run, "yes");
        } finally {
            quiet.restore();
        }

        assertThat(result.isComplete()).isTrue();
        assertThat(result.getContext())
                .as("a correction survives only until the reply that answers it")
                .doesNotContainKey("loopGuidance");
    }

    /** A classic run that has been proposed and is one "yes" away from starting. */
    private static Map<String, Object> classicRunAwaitingConsent() {
        Map<String, Object> context = new HashMap<>();
        context.put("task", "add a retry to the uploader");
        context.put("timeoutSec", AgentOptions.UNLIMITED);
        context.put("maxStepCount", AgentOptions.UNLIMITED);
        context.put("verboseMode", false);
        context.put("classic", true);
        context.put("step", "confirm_execution");
        return context;
    }

    /** A project index that finds nothing; what the index says is not what this is about. */
    private static ContextEngine noProjectContext() throws Exception {
        ContextEngine project = Mockito.mock(ContextEngine.class);
        Mockito.when(project.searchRelevantSnippets(Mockito.anyString())).thenReturn(List.of());
        return project;
    }
}
