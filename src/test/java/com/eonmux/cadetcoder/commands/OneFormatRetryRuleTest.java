package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.commands.IterativeCommand.StepResult;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A reply that cannot be read as an action is corrected the same way wherever the run notices it.
 *
 * <h2>The defect</h2>
 *
 * <p>Three steps of the chat machine can meet an unreadable reply -- the first analysis, a
 * correction that was itself unreadable, and a follow-up turn -- and each carried its own copy of
 * the rule. The ceiling was the bare literal {@code 2} written out three times, so raising it in one
 * branch would have raised it nowhere; the corrective prompt existed in two near-identical copies
 * that had already drifted apart in wording; and one branch counted the attempt while another
 * forgot to move the run to the step that reads the correction.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>There is one ceiling and it is reached at the same count from every branch; asking again always
 * counts the attempt and always leaves the run at {@code format_retry}, so a correction cannot be
 * requested without being counted; the correction always contains the exemplar the model is being
 * asked to copy; and the give-up message names how many attempts were actually made.</p>
 */
public class OneFormatRetryRuleTest {

    @Test
    public void theCeilingIsReachedAtTheSameCountWhereverItIsChecked() {
        ChatContext context = new ChatContext();

        for (int attempt = 0; attempt < FormatRetry.MAX_ATTEMPTS; attempt++) {
            context.setFormatRetryCount(attempt);
            assertThat(FormatRetry.exhausted(context))
                    .as("attempt %d is still under the ceiling", attempt)
                    .isFalse();
        }
        context.setFormatRetryCount(FormatRetry.MAX_ATTEMPTS);
        assertThat(FormatRetry.exhausted(context)).isTrue();
    }

    @Test
    public void askingAgainCountsTheAttemptAndSendsTheReplyBackToTheCorrectionStep() {
        ChatContext context = new ChatContext();

        StepResult first = FormatRetry.askAgain(context, "Format error attempt", "do it properly");

        assertThat(context.getFormatRetryCount()).isEqualTo(1);
        assertThat(context.getStep()).isEqualTo("format_retry");
        assertThat(first.getOutput()).isEqualTo("Format error attempt 1");
        assertThat(first.getNextPrompt()).isEqualTo("do it properly");
        assertThat(first.isComplete()).isFalse();

        StepResult second = FormatRetry.askAgain(context, "Format error attempt", "still wrong");

        assertThat(context.getFormatRetryCount()).isEqualTo(2);
        assertThat(second.getOutput()).isEqualTo("Format error attempt 2");
    }

    @Test
    public void theCorrectionCarriesTheExemplarBetweenWhatIsWrongAndWhatToDo() {
        String correction = FormatRetry.correction("That was not an action.", "Try again now:");

        assertThat(correction).startsWith("That was not an action. ");
        assertThat(correction).contains("You MUST use this EXACT format:");
        assertThat(correction).contains("ACTION_START\nCOMMAND: read\n");
        assertThat(correction).contains("ACTION_END");
        assertThat(correction).endsWith("Try again now:");
        assertThat(correction.indexOf("ACTION_START"))
                .as("the exemplar sits after the complaint")
                .isGreaterThan(correction.indexOf("That was not an action."));
        assertThat(correction.indexOf("Try again now:"))
                .as("and before the instruction")
                .isGreaterThan(correction.indexOf("ACTION_END"));
    }

    @Test
    public void givingUpSaysHowManyAttemptsWereActuallyMade() {
        ChatContext context = new ChatContext();
        context.setFormatRetryCount(FormatRetry.MAX_ATTEMPTS);

        StepResult result = FormatRetry.giveUp(context, "just some prose");

        assertThat(result.isComplete()).isTrue();
        assertThat(result.isError()).isTrue();
        assertThat(result.getOutput())
                .contains("after " + FormatRetry.MAX_ATTEMPTS + " attempts")
                .contains("just some prose");
    }

    /**
     * A reply that could not be read as an action ran no command, and the model has to be told so.
     *
     * <p><b>The defect</b>: it wrote {@code job start ...}, was answered with a complaint about
     * formatting alone, and went on to wait for the job -- which did not exist, because nothing had
     * started it. It then reasoned about what that absence meant for the rest of its work.</p>
     */
    @Test
    public void thecorrectionSaysThatNothingWasRun() {
        String correction = FormatRetry.correction("That was not an action.", "Try again now:");

        assertThat(correction).contains("Nothing was run for it");
        assertThat(correction.indexOf("Nothing was run for it"))
                .as("said before the format, because it is the part that changes what to do next")
                .isLessThan(correction.indexOf("ACTION_START"));
    }
}
