package com.eonmux.cadetcoder.ai;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.ConfigOverrides;
import com.eonmux.cadetcoder.config.Configuration;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Uber mode: what changes when a run has to finish rather than stop.
 *
 * <p><b>The defect</b>: a run ends when the model says it is finished, and the model says so from
 * inside the same context that decided what finished meant. The parts of a request that get left
 * behind are the parts that were never taken in, so they are absent from the closing summary too --
 * which makes a run that did two thirds of the work look exactly like one that did all of it.</p>
 *
 * <p><b>What is locked here</b>: that the mode is off unless it is asked for, since it spends the
 * user's tokens on work they may consider done; that the directive reaches an agentic prompt only
 * while it is on, and is appended rather than prepended so it qualifies the contract above it; that
 * a run that has answered every question in a row is believed, so a model that would always find
 * one more thing cannot hold a run open for ever; and that a challenge names the request and quotes
 * the claim, because a model asked whether it is "really done" agrees and a model asked whether a
 * named claim holds has to look.</p>
 */
public class DrivingATaskToTheEndTest {

    private boolean wasOn;

    @Before
    public void rememberTheSetting() {
        wasOn = ConfigManager.getInstance().getConfig().getAi().isUberMode();
    }

    @After
    public void restoreTheSetting() {
        ConfigManager.getInstance().getConfig().getAi().setUberMode(wasOn);
    }

    private static void turn(boolean on) {
        ConfigManager.getInstance().getConfig().getAi().setUberMode(on);
    }

    /** It costs turns against someone else's tokens, so it is asked for and never assumed. */
    @Test
    public void itIsOffUnlessItIsAskedFor() {
        assertThat(new Configuration().getAi().isUberMode()).isFalse();
    }

    @Test
    public void theDirectiveReachesAPromptOnlyWhileTheModeIsOn() {
        turn(false);
        assertThat(UberMode.directive()).isEmpty();
        assertThat(UberMode.applyTo("the command's own contract")).isEqualTo("the command's own contract");

        turn(true);
        assertThat(UberMode.directive()).isNotEmpty();
        assertThat(UberMode.applyTo("the command's own contract"))
                .startsWith("the command's own contract")
                .contains(UberMode.directive());
    }

    /** An instruction that qualifies another one has to be read after it, not before it. */
    @Test
    public void theDirectiveIsAppendedSoItQualifiesTheContractAboveIt() {
        turn(true);
        String composed = UberMode.applyTo("ACTION_START ... ACTION_END");
        assertThat(composed.indexOf("ACTION_START"))
                .isLessThan(composed.indexOf(UberMode.directive()));
    }

    @Test
    public void acommandWithNoContractOfItsOwnStillGetsTheDirective() {
        turn(true);
        assertThat(UberMode.applyTo("")).isEqualTo(UberMode.directive());
        assertThat(UberMode.applyTo(null)).isEqualTo(UberMode.directive());
    }

    /**
     * A run that has answered every question in a row is let go.
     *
     * <p>Otherwise "is it really finished?" would be asked of the answer to itself and the run
     * would have no way to end at all. What makes the questioning unbounded is not that the count
     * never reaches the end -- it is that doing any work sends it back to the start.</p>
     */
    @Test
    public void arunThatHasAnsweredEveryQuestionInArowIsBelieved() {
        turn(true);

        for (int answered = 0; answered < UberMode.questionCount(); answered++) {
            assertThat(UberMode.shouldQuestion(answered)).isTrue();
        }
        assertThat(UberMode.shouldQuestion(UberMode.questionCount())).isFalse();
        assertThat(UberMode.shouldQuestion(UberMode.questionCount() + 7)).isFalse();
    }

    @Test
    public void nothingIsQuestionedWhileTheModeIsOff() {
        turn(false);
        assertThat(UberMode.shouldQuestion(0)).isFalse();
    }

    @Test
    public void achallengeNamesTheRequestAndQuotesTheClaim() {
        String challenge = UberMode.challenge(1, "add a retry to the uploader",
                                              "SUCCESS: added the retry",
                                              "reply SUCCESS: again to end the run");

        assertThat(challenge).contains("add a retry to the uploader");
        assertThat(challenge).contains("SUCCESS: added the retry");
        assertThat(challenge).contains("reply SUCCESS: again to end the run");
        assertThat(challenge.indexOf("add a retry to the uploader"))
                .isLessThan(challenge.indexOf("SUCCESS: added the retry"));
    }

    /**
     * The second question has to be a different question. Asked the first one twice, a model gives
     * the first answer twice -- it has already checked the request against its own account of the
     * work and found them to agree.
     */
    @Test
    public void thesecondQuestionAsksSomethingTheFirstDidNot() {
        assertThat(UberMode.questionFor(2)).isNotEqualTo(UberMode.questionFor(1));
        assertThat(UberMode.questionFor(1)).contains("originally");
        assertThat(UberMode.questionFor(2)).contains("actually changed");
        assertThat(UberMode.questionFor(3)).isEqualTo(UberMode.questionFor(2));
    }

    @Test
    public void achallengeSurvivesKnowingNeitherTheRequestNorTheClaim() {
        assertThat(UberMode.challenge(1, null, null, null)).isEqualTo(UberMode.questionFor(1));
        assertThat(UberMode.challenge(1, "  ", "  ", "  ")).isEqualTo(UberMode.questionFor(1));
    }

    @Test
    public void thesettingIsReachableByNameAndRefusesWhatIsNotAnAnswer() {
        Configuration config = new Configuration();

        ConfigOverrides.apply(config, "ai.uberMode", "true");
        assertThat(config.getAi().isUberMode()).isTrue();

        assertThatThrownBy(() -> ConfigOverrides.apply(new Configuration(), "ai.uberMode", "sure"))
                .isInstanceOf(IllegalArgumentException.class);
        // Reading a setting has always ignored case, so writing one does too: the tool used to
        // refuse "ai.ubermode" by naming the setting it had just printed under that spelling.
        Configuration byLowerCase = new Configuration();
        ConfigOverrides.apply(byLowerCase, "ai.ubermode", "true");
        assertThat(byLowerCase.getAi().isUberMode()).isTrue();
    }
}
