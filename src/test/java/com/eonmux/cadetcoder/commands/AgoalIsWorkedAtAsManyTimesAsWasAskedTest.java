package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ExitCode;
import com.eonmux.cadetcoder.InterruptSignal;
import com.eonmux.cadetcoder.ai.PromptData;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.net.LLMRateLimitException;
import com.eonmux.cadetcoder.test.StubbedProvider;
import com.eonmux.cadetcoder.test.TestOutputCapture;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code loop} and {@code loopfresh} were asked for, and what each pass is told.
 *
 * <h2>What this is for that a single run is not</h2>
 *
 * <p>A run ends when the model says the work is done, and a model says that as soon as it stops
 * seeing anything to do. For a goal with no finish line -- make this faster, raise the coverage --
 * the first stopping point is nowhere near the best one. So the goal is run again, and the count is
 * the point rather than a ceiling.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>That a loop nobody sized runs a hundred passes; that a count can be named and is refused when
 * it is not a number, is smaller than one pass, or is larger than one command may ask for; that
 * each pass is told which pass it is, so it improves on the work in front of it rather than
 * starting again; that {@code loop} tells a pass what the last one said and {@code loopfresh} does
 * not; that a pass which could not reach the model stops the loop rather than being counted; and
 * that a model may start neither, because it is already inside a loop.</p>
 */
public class AgoalIsWorkedAtAsManyTimesAsWasAskedTest {

    private TestOutputCapture output;

    @Before
    public void captureOutput() {
        output = new TestOutputCapture();
        output.startCapture();
    }

    @After
    public void releaseOutput() {
        output.stopCapture();
        InterruptSignal.clear();
    }

    @Test
    public void aloopNobodySizedRunsAhundredPasses() {
        LoopCommand.Options asked = LoopCommand.Options.read(new String[] {"make", "it", "faster"});

        assertThat(asked.refusal).isNull();
        assertThat(asked.times).isEqualTo(100);
        assertThat(asked.goal).isEqualTo("make it faster");
    }

    @Test
    public void thecountCanBeNamedEitherWay() {
        assertThat(LoopCommand.Options.read(new String[] {"--times=7", "tidy up"}).times)
                .isEqualTo(7);
        assertThat(LoopCommand.Options.read(new String[] {"--times", "7", "tidy up"}).times)
                .isEqualTo(7);
        assertThat(LoopCommand.Options.read(new String[] {"-n", "7", "tidy up"}).times)
                .isEqualTo(7);
    }

    @Test
    public void thegoalIsWhatIsLeftAfterTheCount() {
        assertThat(LoopCommand.Options.read(new String[] {"--times=3", "raise", "the", "coverage"})
                                      .goal)
                .isEqualTo("raise the coverage");
    }

    @Test
    public void aloopWithNoGoalIsRefused() {
        assertThat(LoopCommand.Options.read(new String[0]).refusal).isNotNull();
        assertThat(LoopCommand.Options.read(new String[] {"--times=3"}).refusal).isNotNull();
    }

    @Test
    public void acountThatIsNotAnumberIsRefused() {
        LoopCommand.Options asked =
                LoopCommand.Options.read(new String[] {"--times=lots", "tidy up"});

        assertThat(asked.refusal).contains("lots");
    }

    @Test
    public void aloopOfNoPassesIsRefusedRatherThanRunAsNothing() {
        assertThat(LoopCommand.Options.read(new String[] {"--times=0", "tidy up"}).refusal)
                .isNotNull();
        assertThat(LoopCommand.Options.read(new String[] {"--times=-4", "tidy up"}).refusal)
                .isNotNull();
    }

    /**
     * A pass spends a whole model run, so the difference between a typed 100 and a mistyped 10000
     * is somebody's month of tokens. Refused rather than quietly clamped: running a thousand of the
     * ten thousand somebody asked for is a bill they did not agree to either.
     */
    @Test
    public void morePassesThanOneCommandMayAskForIsRefusedRatherThanClamped() {
        LoopCommand.Options asked =
                LoopCommand.Options.read(new String[] {"--times=10000", "tidy up"});

        assertThat(asked.refusal).isNotNull();
        assertThat(asked.times)
                .as("nothing was quietly substituted for what was asked")
                .isNotEqualTo(LoopCommand.MOST_TIMES);
    }

    @Test
    public void themostPassesAllowedIsStillAllowed() {
        assertThat(LoopCommand.Options.read(
                new String[] {"--times=" + LoopCommand.MOST_TIMES, "tidy up"}).refusal).isNull();
    }

    /**
     * A model told only the goal reads the work already done as somebody else's and starts again.
     */
    @Test
    public void apassIsToldWhichPassItIsAndWhatToDoWithWhatIsThere() {
        LoopCommand loop = new LoopCommand();

        assertThat(loop.request("make it faster", 1, 100, null))
                .contains("make it faster")
                .contains("pass 1 of 100");
        assertThat(loop.request("make it faster", 4, 100, null))
                .contains("pass 4 of 100")
                .contains("improve on it rather than starting again");
    }

    @Test
    public void loopTellsApassWhatTheLastOneSaid() {
        String asked = new LoopCommand()
                .request("make it faster", 2, 10, "SUCCESS: cached the lookup");

        assertThat(asked).contains("SUCCESS: cached the lookup");
    }

    /**
     * Being told what was just said is what produces the same answer again, which is the one thing
     * a fresh pass is for.
     */
    @Test
    public void loopfreshTellsApassNothingOfTheLastOne() {
        String asked = new LoopFreshCommand()
                .request("make it faster", 2, 10, "SUCCESS: cached the lookup");

        assertThat(asked).doesNotContain("SUCCESS: cached the lookup");
        assertThat(asked)
                .as("the project is still shared; it is the account of the pass that is withheld")
                .contains("pass 2 of 10");
    }

    @Test
    public void eachCommandNamesItselfInItsOwnUsage() {
        assertThat(new LoopCommand().getUsage()).startsWith("loop ");
        assertThat(new LoopFreshCommand().getUsage()).startsWith("loopfresh ");
        assertThat(new LoopCommand().getUsage()).contains("100");
    }

    /**
     * Every pass runs.
     *
     * <p>Stopping early when the model reports the goal met would put the decision back with the
     * participant that cannot check it, and would make this an ordinary run with extra words. The
     * model here says it is finished on its very first turn, three times over, and is started again
     * each time.</p>
     */
    @Test
    public void everyPassRunsEvenWhenTheModelSaysTheGoalIsAlreadyMet() {
        boolean wasUber = ConfigManager.getInstance().getConfig().getAi().isUberMode();
        ConfigManager.getInstance().getConfig().getAi().setUberMode(false);
        System.setProperty("cadet.interactive", "false");
        try (StubbedProvider model = StubbedProvider.answering("SUCCESS: nothing left to do")) {
            int exitCode = new LoopCommand()
                    .execute(new String[] {"--times=3", "make it faster"});

            assertThat(exitCode).isZero();
            assertThat(model.asked())
                    .as("one run per pass, none of them cut short by the model's own verdict")
                    .hasSize(3);
            for (PromptData asked : model.asked()) {
                assertThat(asked.getUserPrompt()).contains("make it faster");
            }
        } finally {
            System.clearProperty("cadet.interactive");
            ConfigManager.getInstance().getConfig().getAi().setUberMode(wasUber);
        }
    }

    /**
     * Ctrl-C is the way out of a loop, so it has to be one.
     *
     * <p>Nothing else ends a loop early by design, which makes this the only way a user stops one
     * they did not mean to start. A loop that reported success having run none of its passes would
     * be the worst of both.</p>
     */
    @Test
    public void ctrlCendsTheLoopWithoutRunningApass() {
        InterruptSignal.request();
        try (StubbedProvider model = StubbedProvider.answering("SUCCESS: done")) {
            int exitCode = new LoopCommand()
                    .execute(new String[] {"--times=3", "make it faster"});

            assertThat(exitCode).isEqualTo(ExitCode.INTERRUPTED);
            assertThat(model.asked()).isEmpty();
        }
    }

    /**
     * A model inside a run that started another loop would nest runs until the clock or the token
     * budget ran out, and every one of those endings is worse than being told no.
     */
    @Test
    public void amodelMayNotStartAloop() {
        assertThat(ModelDispatch.startsALoop("loop")).isTrue();
        assertThat(ModelDispatch.startsALoop("loopfresh")).isTrue();
    }

    /**
     * A pass that never reached the model is not a pass.
     *
     * <p><b>The defect</b>: a recorded run's provider cut the account off for five hours part way
     * through the second pass. Every pass runs whatever the model says, so the remaining
     * ninety-eight ran too: each opened a request, was refused, and returned in a moment. The loop
     * would have ended by reporting a hundred passes done at a goal it had done nothing to.</p>
     *
     * <p>A pass that FAILED still counts, because a failed pass did work and left it in the project
     * for the next one to read. This is the other case: nothing was attempted at all.</p>
     */
    @Test
    public void apassThatCouldNotReachTheModelStopsTheLoop() {
        System.setProperty("cadet.interactive", "false");
        try (StubbedProvider model = StubbedProvider.failingWith(
                new LLMRateLimitException("commandcode", "deepseek-v4-flash",
                                          "https://example.invalid/v1", 429,
                                          "5-hour usage limit reached", null))) {
            int exitCode = new LoopCommand()
                    .execute(new String[] {"--times=50", "make it faster"});

            assertThat(exitCode).isEqualTo(ExitCode.UNREACHABLE);
            assertThat(model.asked())
                    .as("the first pass asked; the other forty-nine were not attempted")
                    .hasSize(1);
            assertThat(output.getAllOutput())
                    .as("a loop that stopped early has to say how much of it did not run")
                    .contains("50 passes unrun");
        } finally {
            System.clearProperty("cadet.interactive");
        }
    }
}
