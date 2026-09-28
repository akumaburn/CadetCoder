package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ai.UberMode;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.test.StubbedProvider;
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
 * What the chat loop does with a run that says it has finished, while uber mode is on.
 *
 * <p><b>The defect</b>: the loop's only completion signal is the model saying so, and it is produced
 * by the one participant that cannot check it. A run that answered two thirds of a request ends with
 * a summary of the two thirds and looks, from outside, exactly like one that answered all of it.</p>
 *
 * <p><b>What is locked here</b>: that nothing changes while the mode is off, so an ordinary run is
 * untouched; that a claim made while it is on becomes another turn rather than the end of the run,
 * addressed to the model and carrying both the request and the claim; that the count of questions
 * lives with the run and survives the step boundary, since a fresh context is built on every step
 * and a count that reset would question every claim for ever; that a claim which answers every
 * question in a row is believed, so the run ends; and that work done in between starts the
 * questions again, so nothing caps how often one run is asked.</p>
 */
public class AClaimOfCompletionIsCheckedTest {

    private static final String[] NO_ARGS = new String[0];

    private final ChatCommand log = new ChatCommand();

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

    private static ChatContext runAsking(String request) {
        ChatContext context = new ChatContext();
        context.setUserRequest(request);
        return context;
    }

    @Test
    public void nothingIsQuestionedWhileTheModeIsOff() {
        ConfigManager.getInstance().getConfig().getAi().setUberMode(false);

        assertThat(CompletionChallenge.questioning(runAsking("add a retry"),
                                                   "SUCCESS: done")).isNull();
    }

    @Test
    public void aclaimBecomesAnotherTurnRatherThanTheEndOfTheRun() {
        ChatContext context = runAsking("add a retry to the uploader");

        IterativeCommand.StepResult questioned =
                CompletionChallenge.questioning(context, "SUCCESS: added the retry");

        assertThat(questioned).isNotNull();
        assertThat(questioned.isComplete()).isFalse();
        assertThat(questioned.isError()).isFalse();
        assertThat(questioned.getNextPrompt()).contains("add a retry to the uploader");
        assertThat(questioned.getNextPrompt()).contains("SUCCESS: added the retry");
    }

    /**
     * The prompt is written for the model, and the executor decides where a prompt goes by being
     * told rather than by reading it -- a challenge routed to the person at the terminal would stop
     * the run dead waiting for an answer nobody was asked for.
     */
    @Test
    public void thequestionIsAddressedToTheModel() {
        IterativeCommand.StepResult questioned =
                CompletionChallenge.questioning(runAsking("add a retry"), "SUCCESS: done");

        assertThat(questioned.getAudience()).isEqualTo(IterativeCommand.StepResult.Audience.MODEL);
    }

    /**
     * A reply that neither parses as an action nor carries the marker is treated as a format
     * failure, so a model told to keep going has to be told how to say either thing.
     */
    @Test
    public void thequestionRestatesBothWaysOutOfIt() {
        String prompt = CompletionChallenge
                .questioning(runAsking("add a retry"), "SUCCESS: done")
                .getNextPrompt();

        assertThat(prompt).contains("ACTION_START");
        assertThat(prompt).contains("SUCCESS:");
    }

    /**
     * A claim that answers every question in a row is believed, so the run can end.
     *
     * <p>Without a last question there is nothing for an answer to be an answer to, and the run
     * would be sent back for ever. The way out is answering, not outlasting.</p>
     */
    @Test
    public void aclaimThatAnswersEveryQuestionInArowIsBelieved() {
        ChatContext context = runAsking("add a retry");

        for (int question = 1; question <= UberMode.questionCount(); question++) {
            assertThat(CompletionChallenge.questioning(context, "SUCCESS: done")).isNotNull();
            assertThat(context.getUberChecksPassed()).isEqualTo(question);
        }
        assertThat(CompletionChallenge.questioning(context, "SUCCESS: really done")).isNull();
    }

    /**
     * Doing any work puts the run back at the first question.
     *
     * <p>This is what makes the questioning unbounded, and it is the whole of the fix: a run used
     * to get a fixed budget of questions, so the claims it made after the budget was spent -- the
     * late ones, in the longest runs -- were the ones nobody checked. Work restarts the sequence,
     * so a model that keeps finding something to do keeps being asked, however long it goes on.</p>
     */
    @Test
    public void workDoneSinceTheLastQuestionStartsThemAgain() {
        ChatContext context = runAsking("add a retry");

        // Twenty rounds of "claim, get questioned, do one more thing" -- five times what the old
        // budget allowed for a whole run.
        for (int round = 0; round < 20; round++) {
            assertThat(CompletionChallenge.questioning(context, "SUCCESS: done")).isNotNull();
            CompletionChallenge.workWasDone(context);
            assertThat(context.getUberChecksPassed()).isZero();
        }
        assertThat(CompletionChallenge.questioning(context, "SUCCESS: done")).isNotNull();
    }

    /** A fresh context is built on every step, so a count held only in a field would never grow. */
    @Test
    public void thecountSurvivesTheStepBoundary() {
        ChatContext first = runAsking("add a retry");
        CompletionChallenge.questioning(first, "SUCCESS: done");

        ChatContext rebuilt = ChatContext.fromMap(first.toMap(), log);
        assertThat(rebuilt.getUberChecksPassed()).isEqualTo(1);

        assertThat(CompletionChallenge.questioning(rebuilt, "SUCCESS: done again")).isNotNull();
        ChatContext again = ChatContext.fromMap(rebuilt.toMap(), log);
        assertThat(CompletionChallenge.questioning(again, "SUCCESS: really done")).isNull();
    }

    /** The second question asks about what was changed, which is where left-behind work lives. */
    @Test
    public void thesecondQuestionIsADifferentQuestion() {
        ChatContext context = runAsking("add a retry");

        String first  = CompletionChallenge.questioning(context, "SUCCESS: done").getNextPrompt();
        String second = CompletionChallenge.questioning(context, "SUCCESS: done").getNextPrompt();

        assertThat(second).isNotEqualTo(first);
    }

    /**
     * The three places the chat loop can decide a run is over.
     *
     * <p>Everything above tests the decision; these test the places it has to be made from. A loop
     * that reached any one of them without asking would end the run there, and the decision itself
     * would still be perfectly correct -- so each ending is driven here, through the command's own
     * step machine, exactly as the executor drives it.</p>
     */
    @Test
    public void arunThatDidTheWorkAndThenAnsweredIsStillAsked() {
        Map<String, Object> context = aRunAt("next_step", "add a retry to the uploader");

        IterativeCommand.StepResult result = new ChatCommand()
                .executeStep(NO_ARGS, context, "All of the requested work is now finished.");

        assertThat(result.isComplete())
                .as("a claim made after doing the work is a claim like any other")
                .isFalse();
        assertThat(result.getNextPrompt()).contains("add a retry to the uploader");
        assertThat(result.getContext().get("uberChecksPassed")).isEqualTo(1);
    }

    @Test
    public void arunThatAnsweredAfterBeingCorrectedIsStillAsked() {
        Map<String, Object> context = aRunAt("format_retry", "add a retry to the uploader");

        IterativeCommand.StepResult result = new ChatCommand()
                .executeStep(NO_ARGS, context, "SUCCESS: added the retry");

        assertThat(result.isComplete()).isFalse();
        assertThat(result.getNextPrompt()).contains("SUCCESS: added the retry");
        assertThat(result.getContext().get("uberChecksPassed")).isEqualTo(1);
    }

    /**
     * The shortest run there is: the model answers the opening request outright, having done
     * nothing. The answer is deliberately NOT printed on this path -- printing it and then sending
     * it back would show the user an answer the run had not accepted.
     */
    @Test
    public void arunThatAnsweredItsOpeningRequestIsStillAsked() {
        System.setProperty("cadet.interactive", "false");
        Map<String, Object> context = aRunAt("analyze_request", "add a retry to the uploader");

        TestOutputCapture quiet = new TestOutputCapture();
        IterativeCommand.StepResult result;
        try (StubbedProvider provider = StubbedProvider.answering("SUCCESS: nothing to do")) {
            result = new ChatCommand().executeStep(NO_ARGS, context, null);
        } finally {
            quiet.restore();
            System.clearProperty("cadet.interactive");
        }

        assertThat(result.isComplete()).isFalse();
        assertThat(result.getNextPrompt()).contains("SUCCESS: nothing to do");
        assertThat(shown(quiet))
                .as("an answer that has not been accepted is not shown as the answer")
                .doesNotContain("SUCCESS: nothing to do");
    }

    /**
     * The lines the run printed as themselves, rather than as part of something it was narrating.
     *
     * <p>An answer is shown by printing it, and on a verbose run the same text also appears inside a
     * log line that reports what the model said. Only the first of those is the run telling the user
     * this is the answer, so only whole lines are read here.</p>
     */
    private static List<String> shown(TestOutputCapture output) {
        List<String> lines = new ArrayList<>();
        for (String line : output.getAllOutput().split("\n")) {
            lines.add(line.strip());
        }
        return lines;
    }

    /**
     * Doing anything at all puts the run back at the first question.
     *
     * <p>Driven through the step machine rather than against the rule alone, because the rule is
     * only worth anything if the loop applies it. A run that answered one question, then worked for
     * another hour, would otherwise end on the questions it had left -- with everything it changed
     * in between never checked against the request at all.</p>
     */
    @Test
    public void arunThatDoesSomethingIsQuestionedFromTheBeginningAgain() {
        System.setProperty("cadet.interactive", "false");
        TestOutputCapture quiet = new TestOutputCapture();
        try {
            Map<String, Object> context = aRunAt("execute_single_action", "add a retry");
            context.put("uberChecksPassed", 1);
            context.put("currentAction",
                        new ChatCommand.AIAction("todoread", new String[0], "see what is left"));

            IterativeCommand.StepResult acted =
                    new ChatCommand().executeStep(NO_ARGS, context, null);

            assertThat(acted.getContext().get("uberChecksPassed"))
                    .as("the run did something, so the questions start again")
                    .isEqualTo(0);
        } finally {
            quiet.restore();
            System.clearProperty("cadet.interactive");
        }
    }

    /** A run of this loop that has reached the given step with the given request behind it. */
    private static Map<String, Object> aRunAt(String step, String request) {
        Map<String, Object> context = new HashMap<>();
        context.put("step", step);
        context.put("userRequest", request);
        return context;
    }

    /**
     * A question is only a question if the answer is read.
     *
     * <p>The challenge goes out as the next prompt, and the step the run is left in decides who
     * reads the reply. Left at {@code analyze_request} -- the step that puts the ORIGINAL request to
     * the model and takes no reply of its own -- the answer to the question is dropped where it
     * arrives and the whole request is asked again from the beginning: two turns spent, the question
     * never read, and the same claim made again for the same reasons, until the run ends exactly
     * where it would have ended anyway.</p>
     */
    @Test
    public void theanswerToAquestionIsReadRatherThanAskedAgain() {
        System.setProperty("cadet.interactive", "false");
        TestOutputCapture quiet = new TestOutputCapture();
        try {
            IterativeCommand.StepResult questioned;
            try (StubbedProvider opening = StubbedProvider.answering("SUCCESS: nothing to do")) {
                questioned = new ChatCommand()
                        .executeStep(NO_ARGS, aRunAt("analyze_request", "add a retry"), null);
            }
            assertThat(questioned.getNextPrompt()).contains("SUCCESS: nothing to do");

            try (StubbedProvider shouldNotBeAsked = StubbedProvider.answering("(never read)")) {
                IterativeCommand.StepResult answered = new ChatCommand().executeStep(
                        NO_ARGS, questioned.getContext(),
                        "SUCCESS: I went back through it and every part is done.");

                assertThat(shouldNotBeAsked.asked())
                        .as("the reply to the question is what continues the run, not a reason to "
                            + "put the original request to the model all over again")
                        .isEmpty();
                assertThat(answered.getContext().get("uberChecksPassed"))
                        .as("the answer was read, and questioned in its turn")
                        .isEqualTo(2);
            }
        } finally {
            quiet.restore();
            System.clearProperty("cadet.interactive");
        }
    }
}
