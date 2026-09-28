package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.commands.IterativeCommand.StepResult;

import org.junit.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An action the safety screen refuses is answered with the refusal, not with a format lesson.
 *
 * <h2>The defect</h2>
 *
 * <p>Pass 1 of a loop ended with "AI failed to provide proper ACTION format after 2 attempts"
 * printed above this, which is a perfectly formed block:</p>
 *
 * <pre>
 * ACTION_START
 * COMMAND: bash
 * ARGS: git rm --cached .commandcode/taste/taste.md
 * ...
 * ACTION_END
 * </pre>
 *
 * <p>The screen refused it for the word {@code rm}, which the screen no longer does for an argument
 * (see {@code AshellLineIsJudgedByTheProgramsItRunsTest}); these tests use a line that starts
 * {@code rm}, which the screen still refuses. Nothing told the model so. It was sent the format exemplar and told its format was wrong, twice; it sent the
 * same block back, twice; and the run gave up on a model that had followed the format every time.
 * Told the reason, a model can do the same thing another way -- {@code git restore --staged} here.
 * Not told, it cannot.</p>
 *
 * <h2>Why a refusal has a ceiling of its own</h2>
 *
 * <p>A refusal is not a format failure, so it does not spend the format budget. A model that keeps
 * proposing refused actions is still stuck, though, and it must not be able to go on forever;
 * refusals in a row are counted separately and end the run past their own limit.</p>
 */
public class AmodelToldItsActionWasRefusedCanChooseAnotherTest {

    /** A chat that records the re-entry instead of running anything. */
    private static final class RecordingChat extends ChatCommand {
        private Map<String, Object> reEntered;

        @Override
        public StepResult executeStep(String[] args, Map<String, Object> contextAsMap,
                                      String llmResponse) {
            reEntered = contextAsMap;
            return StepResult.success("re-entered", contextAsMap);
        }
    }

    private static final String REFUSED =
            "ACTION_START\n"
            + "COMMAND: bash\n"
            + "ARGS: rm .commandcode/taste/taste.md\n"
            + "REASON: Delete the tooling file that stage-all committed\n"
            + "ACTION_END";

    private static final String ALLOWED = REFUSED.replace("ARGS: rm ", "ARGS: git rm --cached ");

    private static ChatContext running() {
        ChatContext context = new ChatContext();
        context.setUserRequest("record the rejected knobs and commit");
        context.setStep("next_step");
        return context;
    }

    private static ChatFollowUp followUp(RecordingChat chat) {
        return new ChatFollowUp(chat, new ActionRecovery(chat));
    }

    @Test
    public void therefusalAndItsReasonAreWhatTheModelIsTold() {
        RecordingChat chat    = new RecordingChat();
        ChatContext   context = running();

        StepResult told = followUp(chat).afterAnAction(new String[0], context, REFUSED);

        assertThat(chat.reEntered).as("nothing refused is run").isNull();
        assertThat(told.isComplete()).as("the run goes on").isFalse();
        assertThat(told.getAudience()).isEqualTo(StepResult.Audience.MODEL);
        assertThat(told.getNextPrompt())
                .contains("refused")
                .contains("rm")
                .doesNotContain("EXACT format");
        assertThat(told.getContext().get("step")).isEqualTo("next_step");
        assertThat(told.getContext().get("formatRetryCount"))
                .as("a refusal is not a format failure")
                .isEqualTo(0);
    }

    @Test
    public void arefusalAfterTwoFormatCorrectionsDoesNotEndTheRun() {
        RecordingChat chat    = new RecordingChat();
        ChatContext   context = running();
        context.setStep("format_retry");
        context.setFormatRetryCount(FormatRetry.MAX_ATTEMPTS);

        StepResult told = followUp(chat).afterACorrection(new String[0], context, REFUSED);

        assertThat(told.isComplete())
                .as("the model followed the format; the run must not give up on its format")
                .isFalse();
        assertThat(told.getOutput()).doesNotContain("proper ACTION format");
        assertThat(told.getNextPrompt()).contains("refused");
        assertThat(told.getContext().get("formatRetryCount")).isEqualTo(0);
    }

    @Test
    public void theactionItChoosesInsteadIsRun() throws Exception {
        RecordingChat chat    = new RecordingChat();
        ChatContext   context = running();
        context.setRefusedActionCount(2);

        followUp(chat).afterAnAction(new String[0], context, ALLOWED);

        assertThat(chat.reEntered).isNotNull();
        assertThat(chat.reEntered.get("step")).isEqualTo("execute_single_action");
        assertThat(chat.reEntered.get("refusedActionCount"))
                .as("an action that runs ends the run of refusals")
                .isEqualTo(0);
    }

    @Test
    public void refusalsInArowEndTheRunPastTheirLimit() {
        RecordingChat chat    = new RecordingChat();
        ChatContext   context = running();
        context.setRefusedActionCount(RefusedAction.MAX_IN_A_ROW);

        StepResult ended = followUp(chat).afterAnAction(new String[0], context, REFUSED);

        assertThat(ended.isComplete()).isTrue();
        assertThat(ended.isError()).isTrue();
        assertThat(ended.getOutput())
                .contains("refused")
                .contains("rm")
                .doesNotContain("format");
    }
}
