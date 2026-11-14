package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ai.UberMode;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The plan a run files before any work is harness state, and the closing questions quote it.
 *
 * <p><b>The defect, from a real session</b>: the request was "understand and improve the project's
 * functionality". The run explored, wrote a plan beside its first action, worked, and claimed
 * completion. Questioned, it was asked to account for every item of "the plan" -- and the only copy
 * of that plan lived in its memory. It reconstructed one from whatever the exploration had turned
 * up ("verify the README's claims against reality"), answered for the reconstruction as if it had
 * been the plan all along, and the question's own rule -- anything outstanding, continue with ONE
 * action -- compelled the rest of the session into reconciling documentation the request never
 * mentioned. The harness held the run to a plan it could not see.</p>
 *
 * <p><b>What is locked here</b>: that the plan is captured from the first reply that proposed work
 * and never replaced; that it survives the step boundary, which is where everything else in a
 * context dies if not carried; that nothing is captured when the mode is off or from a reply that
 * proposed no work; and that the challenge quotes the filed plan verbatim, between the request and
 * the claim, so the account is of the plan the run began with.</p>
 */
public class ThePlanIsFrozenAndQuotedTest {

    private boolean wasOn;

    private static final String REPLY_WITH_PLAN =
            "I understand the request. My plan:\n"
            + "1. Map the project structure.\n"
            + "2. Improve the upload retry path.\n"
            + "\n"
            + "ACTION_START\nCOMMAND: ls\nARGS: src\nREASON: see the tree\nACTION_END";

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

    @Test
    public void theProseBesideTheFirstActionIsThePlan() {
        turn(true);
        ChatContext context = new ChatContext();

        CompletionChallenge.capturePlan(context, REPLY_WITH_PLAN);

        assertThat(context.getPlan())
                .isEqualTo("I understand the request. My plan:\n"
                           + "1. Map the project structure.\n"
                           + "2. Improve the upload retry path.");
    }

    @Test
    public void thePlanIsNeverReplaced() {
        turn(true);
        ChatContext context = new ChatContext();
        CompletionChallenge.capturePlan(context, REPLY_WITH_PLAN);

        CompletionChallenge.capturePlan(context,
                "A completely different plan I just thought of.\n\nACTION_START\n"
                + "COMMAND: ls\nARGS: src\nREASON: r\nACTION_END");

        assertThat(context.getPlan())
                .as("a plan that can be replaced at claim time is a rationalisation, not a plan")
                .contains("upload retry path")
                .doesNotContain("completely different");
    }

    @Test
    public void aReplyThatProposesNoWorkFilesNothing() {
        turn(true);
        ChatContext context = new ChatContext();

        CompletionChallenge.capturePlan(context, "SUCCESS: nothing to do.");

        assertThat(context.getPlan()).isNull();
    }

    @Test
    public void nothingIsCapturedWhileTheModeIsOff() {
        turn(false);
        ChatContext context = new ChatContext();

        CompletionChallenge.capturePlan(context, REPLY_WITH_PLAN);

        assertThat(context.getPlan()).isNull();
    }

    @Test
    public void thePlanSurvivesTheStepBoundary() {
        turn(true);
        ChatContext context = new ChatContext();
        CompletionChallenge.capturePlan(context, REPLY_WITH_PLAN);

        ChatContext rebuilt = ChatContext.fromMap(context.toMap(), null);

        assertThat(rebuilt.getPlan())
                .as("a fresh context is rebuilt on every step, so an uncarrried plan is no plan")
                .isEqualTo(context.getPlan());
    }

    @Test
    public void theChallengeQuotesThePlanBetweenTheRequestAndTheClaim() {
        String plan   = "1. Map the structure.\n2. Improve the retry path.";
        String prompt = UberMode.challenge(1, "understand and improve the project",
                                           "SUCCESS: done", "reply SUCCESS: again", plan);

        assertThat(prompt).contains("The plan this run filed before any work");
        assertThat(prompt).contains("1. Map the structure.");
        assertThat(prompt).contains("2. Improve the retry path.");
        assertThat(prompt.indexOf("understand and improve the project"))
                .as("the request still leads")
                .isLessThan(prompt.indexOf("The plan this run filed"));
        assertThat(prompt.indexOf("The plan this run filed"))
                .isLessThan(prompt.indexOf("SUCCESS: done"))
                .as("the claim is what is being questioned, and comes after what it answers to");
    }

    @Test
    public void theQuestionBindsFindingsToThePlanRatherThanToTheWork() {
        // The rule that was missing when the run went tangent: something the work uncovered that
        // the plan does not name is reported, not started.
        assertThat(UberMode.questionFor(1))
                .contains("the plan does not name is a finding to report in "
                          + "the answer, not work to start");
    }

    @Test
    public void aRunThatFiledNoPlanIsNotShownAPlanSection() {
        String withNone = UberMode.challenge(1, "the request", "SUCCESS: done", "finish", null);
        String fourArg  = UberMode.challenge(1, "the request", "SUCCESS: done", "finish");

        assertThat(withNone).doesNotContain("The plan this run filed");
        assertThat(withNone).isEqualTo(fourArg);
    }

    @Test
    public void theMapCarriesThePlanUnderItsOwnName() {
        ChatContext context = new ChatContext();
        context.setPlan("1. Map the structure.");
        Map<String, Object> map = new HashMap<>(context.toMap());

        assertThat(map.get("plan")).isEqualTo("1. Map the structure.");
    }
}
