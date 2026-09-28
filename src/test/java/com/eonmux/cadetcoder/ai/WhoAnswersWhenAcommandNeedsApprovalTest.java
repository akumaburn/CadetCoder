package com.eonmux.cadetcoder.ai;

import com.eonmux.cadetcoder.agents.WorkerPool;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.agents.WorkerTask;
import com.eonmux.cadetcoder.ui.OutputCapture;
import com.eonmux.cadetcoder.ui.OutputRouter;

import org.junit.After;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * A command that needs approval is approved by somebody, and a silence is not approval.
 *
 * <h2>Why there are two modes</h2>
 *
 * <p>{@code manual} asks the person at the terminal. That is the right answer whenever there is a
 * person, and no answer at all inside a worker: a worker's output is collected rather than
 * displayed, so its question reaches nobody, and the code that noticed as much answered "no" on
 * their behalf. The step was lost without anyone deciding anything. {@code auto} asks the model
 * instead.</p>
 *
 * <h2>What the mode cannot do</h2>
 *
 * <p>Approve what the static screens refuse outright. Those refusals are about what a command does,
 * not about what could not be determined, and there is no question left in them to put to anybody.
 * See {@code SecurityValidator.CommandScreening}.</p>
 */
public class WhoAnswersWhenAcommandNeedsApprovalTest {

    @After
    public void clearTheOverride() {
        System.clearProperty(CommandApproval.PROPERTY);
    }

    @Test
    public void thePropertyDecidesWhoIsAskedForThisRun() {
        System.setProperty(CommandApproval.PROPERTY, "auto");
        assertThat(CommandApproval.mode()).isEqualTo(CommandApproval.Mode.AUTO);
        assertThat(CommandApproval.isAuto()).isTrue();

        System.setProperty(CommandApproval.PROPERTY, "manual");
        assertThat(CommandApproval.mode()).isEqualTo(CommandApproval.Mode.MANUAL);
        assertThat(CommandApproval.isAuto()).isFalse();
    }

    @Test
    public void thePersonAnswersWhenNobodyHasSaidOtherwise() {
        // The shipped default. Letting the model approve its own commands is a choice the user
        // makes; a fresh install does not make it for them.
        System.clearProperty(CommandApproval.PROPERTY);
        try (MockedStatic<ConfigManager> configs = mockStatic(ConfigManager.class)) {
            ConfigManager manager = mock(ConfigManager.class);
            when(manager.getConfig()).thenReturn(new Configuration());
            configs.when(ConfigManager::getInstance).thenReturn(manager);

            assertThat(CommandApproval.mode()).isEqualTo(CommandApproval.Mode.MANUAL);
        }
    }

    @Test
    public void aClearedSettingMeansTheShippedDefault() {
        Configuration.SecurityConfig security = new Configuration().getSecurity();
        security.setCommandApproval("auto");
        security.setCommandApproval("  ");

        assertThat(security.getCommandApproval()).isEqualTo("manual");
    }

    @Test
    public void aConfigurationThatCannotBeReadFallsBackToThePersonRatherThanTheModel() {
        // Not a configuration that says "auto"; the narrower mode is the one to land in.
        System.clearProperty(CommandApproval.PROPERTY);
        try (MockedStatic<ConfigManager> configs = mockStatic(ConfigManager.class)) {
            configs.when(ConfigManager::getInstance).thenThrow(new IllegalStateException("no config"));

            assertThat(CommandApproval.mode()).isEqualTo(CommandApproval.Mode.MANUAL);
        }
    }

    @Test
    public void aSpellingNobodyRecognisesIsReadAsManual() {
        // The permissive answer must never be the one a typo falls into.
        System.setProperty(CommandApproval.PROPERTY, "automatic-ish");
        assertThat(CommandApproval.mode()).isEqualTo(CommandApproval.Mode.MANUAL);
    }

    @Test
    public void theVerdictIsTheFirstThingTheReplySays() {
        assertThat(CommandApproval.read("ALLOW: it only reads files").allowed()).isTrue();
        assertThat(CommandApproval.read("allow: counts tests").allowed()).isTrue();
        assertThat(CommandApproval.read("DENY: it deletes the build directory").allowed()).isFalse();
        assertThat(CommandApproval.read("**ALLOW**: it lists files").allowed()).isTrue();
        assertThat(CommandApproval.read("- DENY: it writes outside the project").allowed()).isFalse();
    }

    @Test
    public void theReasonGivenIsCarriedBackWithTheVerdict() {
        assertThat(CommandApproval.read("DENY: it rewrites git history").reason())
                .isEqualTo("it rewrites git history");
        assertThat(CommandApproval.read("ALLOW").reason()).isEqualTo("no reason given");
    }

    @Test
    public void aReplyThatDiscussesTheCommandInsteadOfDecidingIsARefusal() {
        // A model that weighs it up uses both words while it does. Reading whichever appeared
        // anywhere would be reading the deliberation rather than the conclusion, and the conclusion
        // is the only part that is consent.
        CommandApproval.Decision weighed = CommandApproval.read(
                "This could be allowed, though I would deny anything that touched /etc.\nALLOW: fine");

        assertThat(weighed.allowed()).isFalse();
        assertThat(weighed.reason()).contains("did not answer with ALLOW or DENY");
    }

    @Test
    public void nothingSaidIsNothingApproved() {
        assertThat(CommandApproval.read(null).allowed()).isFalse();
        assertThat(CommandApproval.read("").allowed()).isFalse();
        assertThat(CommandApproval.read("   ").allowed()).isFalse();
    }

    @Test
    public void nobodyCanBeAskedFromInsideAworker() {
        // A worker's output is collected into a transcript nobody is watching as it is written, so
        // the terminal being able to prompt says nothing about whether the question would be seen.
        List<Boolean> answered = new ArrayList<>();
        try (MockedStatic<OutputRouter> routers = mockStatic(OutputRouter.class)) {
            OutputRouter router = mock(OutputRouter.class);
            when(router.canPrompt()).thenReturn(true);
            routers.when(OutputRouter::getInstance).thenReturn(router);

            WorkerPool.run(List.of(new WorkerTask(1, "anything", "")), 0, null, (task, steps) -> {
                answered.add(CommandApproval.aPersonCanBeAsked());
                return 0;
            });
        }

        assertThat(answered).containsExactly(false);
    }

    @Test
    public void thePersonWatchingAnAgentStepCanStillBeAsked() {
        // An agent step collects its output too, so the model can read what a command said. The
        // user is sitting in front of the run. Reading the collection itself as "nobody is there"
        // refused every unscreened command of every agent run without asking anyone.
        try (MockedStatic<OutputRouter> routers = mockStatic(OutputRouter.class)) {
            OutputRouter router = mock(OutputRouter.class);
            when(router.canPrompt()).thenReturn(true);
            routers.when(OutputRouter::getInstance).thenReturn(router);

            OutputCapture.collectInto(text -> { },
                    () -> assertThat(CommandApproval.aPersonCanBeAsked()).isTrue());
        }
    }

    @Test
    public void theQuestionGoesToTheScreenRatherThanIntoTheStepsTranscript() {
        List<String> stepSaid = new ArrayList<>();
        try (MockedStatic<OutputRouter> routers = mockStatic(OutputRouter.class)) {
            OutputRouter router = mock(OutputRouter.class);
            when(router.canPrompt()).thenReturn(true);
            when(router.getConfirmation(anyString())).thenReturn(true);
            routers.when(OutputRouter::getInstance).thenReturn(router);

            boolean[] allowed = {false};
            OutputCapture.collectInto(stepSaid::add,
                    () -> allowed[0] = CommandApproval.askThePerson("ls -la", "it cannot be read"));

            assertThat(allowed[0]).isTrue();
        }

        assertThat(stepSaid)
                .as("a question filed into the transcript is a question nobody was shown")
                .noneMatch(line -> line.contains("could not be checked"));
    }

    @Test
    public void aWorkerIsNotAskedAndIsNotApproved() {
        List<Boolean> answered = new ArrayList<>();
        try (MockedStatic<OutputRouter> routers = mockStatic(OutputRouter.class)) {
            OutputRouter router = mock(OutputRouter.class);
            when(router.canPrompt()).thenReturn(true);
            when(router.getConfirmation(anyString())).thenReturn(true);
            routers.when(OutputRouter::getInstance).thenReturn(router);

            WorkerPool.run(List.of(new WorkerTask(1, "anything", "")), 0, null, (task, steps) -> {
                answered.add(CommandApproval.askThePerson("ls -la", "it cannot be read"));
                return 0;
            });
        }

        assertThat(answered).containsExactly(false);
    }

    @Test
    public void thereIsNothingToApproveWithoutACommand() {
        assertThat(CommandApproval.judge(null, null, null).allowed()).isFalse();
        assertThat(CommandApproval.judge("  ", null, null).allowed()).isFalse();
    }
}
