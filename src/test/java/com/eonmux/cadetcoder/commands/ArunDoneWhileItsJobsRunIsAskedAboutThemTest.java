package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.context.ContextEngine;
import com.eonmux.cadetcoder.jobs.JobRegistry;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A run that says it is done while jobs it started are still running is asked about them once.
 *
 * <h2>The defect</h2>
 *
 * <p>A run could start its jobs, do the work that did not depend on them, and declare the task
 * finished. The run then ended, the jobs went on, and their endings were announced to nobody:
 * nothing could resume a run when a job it had started ended. Asked once, the model chooses. It can
 * wait for the jobs whose results the answer needs, stop the ones it no longer needs, or say it is
 * done again and leave them running.</p>
 */
class ArunDoneWhileItsJobsRunIsAskedAboutThemTest {

    private static final File HERE = new File(System.getProperty("user.dir"));

    private boolean           uberWasOn;
    private TestOutputCapture output;

    @BeforeEach
    void setUp() {
        JobRegistry.clear();
        Configuration.AiConfig ai = ConfigManager.getInstance().getConfig().getAi();
        uberWasOn = ai.isUberMode();
        ai.setUberMode(false);
        output = new TestOutputCapture();
        output.startCapture();
    }

    @AfterEach
    void tearDown() {
        output.stopCapture();
        ConfigManager.getInstance().getConfig().getAi().setUberMode(uberWasOn);
        JobRegistry.stopAll();
        JobRegistry.clear();
    }

    private static ChatContext chatAbout(String request) {
        ChatContext context = new ChatContext();
        context.setUserRequest(request);
        return context;
    }

    @Test
    void achatRunIsShownItsRunningJobsAndHowToWaitOrStopThem() throws Exception {
        JobRegistry.start("sleep 30", "fit with cap 120", HERE);
        ChatContext context = chatAbout("fit both caps");

        IterativeCommand.StepResult asked =
                CompletionChallenge.questioning(context, "SUCCESS: the fits are running");

        assertThat(asked).isNotNull();
        assertThat(asked.isComplete()).isFalse();
        assertThat(asked.getNextPrompt()).contains("j1").contains("fit with cap 120")
                                         .contains("job wait").contains("job stop");
    }

    @Test
    void sayingItIsDoneAgainLeavesTheSameJobsRunning() throws Exception {
        JobRegistry.start("sleep 30", null, HERE);
        ChatContext context = chatAbout("fit");
        CompletionChallenge.questioning(context, "SUCCESS: done");

        IterativeCommand.StepResult again = CompletionChallenge.questioning(context, "SUCCESS: done");

        assertThat(again).isNull();
        assertThat(JobRegistry.running()).hasSize(1);
    }

    @Test
    void ajobStartedAfterTheQuestionIsAskedAboutToo() throws Exception {
        JobRegistry.start("sleep 30", null, HERE);
        ChatContext context = chatAbout("fit");
        CompletionChallenge.questioning(context, "SUCCESS: done");
        JobRegistry.start("sleep 30", "the second fit", HERE);

        IterativeCommand.StepResult asked = CompletionChallenge.questioning(context, "SUCCESS: done");

        assertThat(asked).isNotNull();
        assertThat(asked.getNextPrompt()).contains("the second fit");
    }

    @Test
    void theQuestionSurvivesTheStepBoundary() throws Exception {
        JobRegistry.start("sleep 30", null, HERE);
        ChatContext context = chatAbout("fit");
        CompletionChallenge.questioning(context, "SUCCESS: done");

        ChatContext rebuilt = ChatContext.fromMap(context.toMap(), new ChatCommand());

        assertThat(CompletionChallenge.questioning(rebuilt, "SUCCESS: done")).isNull();
    }

    @Test
    void arunWithNoJobsRunningEndsAsBefore() {
        assertThat(CompletionChallenge.questioning(chatAbout("x"), "SUCCESS: done")).isNull();
    }

    @Test
    void anAgentRunIsAskedInItsNextPromptAndOnlyOnce() throws Exception {
        JobRegistry.start("sleep 30", "fit with cap 120", HERE);
        AgentCommand        agent   = new AgentCommand();
        AgentState          state   = new AgentState("fit both caps", 0, System::currentTimeMillis);
        Map<String, Object> context = new HashMap<>();

        assertThat(agent.questionedCompletion(state, context, "the fits are running")).isTrue();
        ContextEngine project = Mockito.mock(ContextEngine.class);
        assertThat(AgentPrompts.user(state, context, project))
                .contains("fit with cap 120").contains("job wait").contains("job stop");

        assertThat(agent.questionedCompletion(state, context, "the fits are running")).isFalse();
    }
}
