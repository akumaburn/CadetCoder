package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.commands.IterativeCommand.StepResult;

import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A model that answers a failed action with a corrected one is allowed to run it.
 *
 * <h2>The defect</h2>
 *
 * <p>When an action failed the model was asked to reply with "retry", "skip" or "stop", and the
 * reply was read as one of those three words or as nothing at all. A model that had read the error
 * and written a different command -- which is the useful answer, and the one the failure prompt now
 * asks for first -- fell through to the default branch. The run reported "Action failed -
 * continuing (response not recognized as retry/skip/stop)", threw the correction away and asked
 * what to do next, so a turn was spent discarding the fix it had just been given.</p>
 *
 * <p>The reply is parsed with the same engine every other turn uses. An action in it re-enters the
 * machine at {@code execute_single_action}, with the retry count cleared, because a different
 * command is a first attempt rather than another go at the one that failed.</p>
 */
public class AcorrectedActionIsRunRatherThanSkippedTest {

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

    private static final String CORRECTION =
            "The pattern was wrong. Let me count them with grep instead.\n"
            + "ACTION_START\n"
            + "COMMAND: grep\n"
            + "ARGS: \"<testcase\" --path=target/surefire-reports --count\n"
            + "REASON: count the tests that actually ran\n"
            + "ACTION_END";

    private static ChatContext failedRun() {
        ChatContext context = new ChatContext();
        context.setUserRequest("check the README's test count");
        context.setStep("handle_failure");
        context.setActionRetryCount(2);
        context.setFailedAction(new ChatCommand.AIAction(
                "bash", new String[] {"awk 'match($0, /tests=\"[0-9]+\"/)' r.xml"}, "count tests"));
        return context;
    }

    private static String commandOf(Object action) throws Exception {
        Field field = ChatCommand.AIAction.class.getDeclaredField("command");
        field.setAccessible(true);
        return (String) field.get(action);
    }

    @Test
    public void thecorrectedCommandIsWhatTheMachineGoesOnToRun() throws Exception {
        RecordingChat  chat    = new RecordingChat();
        ChatContext    context = failedRun();

        StepResult result = new ChatFollowUp(chat, new ActionRecovery(chat))
                .afterAFailure(new String[] {"check the README"}, context, CORRECTION);

        assertThat(chat.reEntered).as("the machine was re-entered rather than moved on").isNotNull();
        assertThat(chat.reEntered.get("step")).isEqualTo("execute_single_action");
        assertThat(commandOf(chat.reEntered.get("currentAction"))).isEqualTo("grep");
        assertThat(result.getOutput()).doesNotContain("not recognized");
    }

    @Test
    public void adifferentCommandStartsItsOwnRetryCount() throws Exception {
        RecordingChat chat    = new RecordingChat();
        ChatContext   context = failedRun();

        new ChatFollowUp(chat, new ActionRecovery(chat))
                .afterAFailure(new String[] {"check the README"}, context, CORRECTION);

        assertThat(chat.reEntered.get("actionRetryCount")).isEqualTo(0);
    }

    @Test
    public void theThreeWordsStillMeanWhatTheyMeant() {
        RecordingChat chat    = new RecordingChat();
        ChatContext   context = failedRun();

        StepResult stopped = new ChatFollowUp(chat, new ActionRecovery(chat))
                .afterAFailure(new String[] {"check the README"}, context, "stop");

        assertThat(chat.reEntered).as("'stop' runs nothing").isNull();
        assertThat(stopped.isError()).isFalse();
        assertThat(stopped.getOutput()).isEqualTo("Execution stopped by user");
    }

    @Test
    public void areplyThatIsNeitherIsStillTheOneThatMovesOn() {
        RecordingChat chat    = new RecordingChat();
        ChatContext   context = failedRun();

        StepResult moved = new ChatFollowUp(chat, new ActionRecovery(chat))
                .afterAFailure(new String[] {"check the README"}, context,
                               "I am not sure what happened there.");

        assertThat(chat.reEntered).isNull();
        assertThat(moved.getOutput()).contains("neither an action nor retry/skip/stop");
    }
}
