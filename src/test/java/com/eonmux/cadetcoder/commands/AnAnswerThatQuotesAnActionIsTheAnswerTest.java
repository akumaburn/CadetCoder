package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.commands.IterativeCommand.StepResult;
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
 * A reply that opens with {@code SUCCESS:} is the answer, whatever text it quotes.
 *
 * <h2>The defect</h2>
 *
 * <p>Asked for its instructions verbatim, a model answered {@code SUCCESS:} and quoted the chat
 * prompt. The prompt holds example action blocks and a JSON object in its list of formats not to
 * use. The chat loop parsed the reply for actions before it looked at the marker, found the quoted
 * JSON object, and ran {@code read file.java}. The read failed, the answer was never shown, and the
 * run ended on a failure the model had not asked for.</p>
 *
 * <p>The prompt tells the model to put no action in a {@code SUCCESS:} reply, so an action-like
 * text in one is a quotation. Every chat turn that reads a reply reads the marker first.</p>
 */
public class AnAnswerThatQuotesAnActionIsTheAnswerTest {

    private static final String[] NO_ARGS = new String[0];

    /** The shape of the reply from the failed run: an answer that quotes the prompt's examples. */
    private static final String QUOTING_ANSWER =
            "SUCCESS: Here are my current instructions, verbatim:\n"
            + "\n"
            + "**EXAMPLES OF CORRECT COMMAND USAGE**:\n"
            + "\n"
            + "```\n"
            + "ACTION_START\n"
            + "COMMAND: read\n"
            + "ARGS: /path/to/file.java\n"
            + "REASON: Need to understand the current implementation\n"
            + "ACTION_END\n"
            + "```\n"
            + "\n"
            + "**NEVER USE THESE INCORRECT FORMATS**:\n"
            + "\n"
            + "❌ `{\"action\": \"read\", \"file\": \"file.java\"}` (JSON format - DO NOT USE)\n";

    /** A chat that records each step it enters, and runs no action. */
    private static final class RecordingChat extends ChatCommand {
        private final List<Object> stepsEntered = new ArrayList<>();

        @Override
        public StepResult executeStep(String[] args, Map<String, Object> contextAsMap,
                                      String llmResponse) {
            Object step = contextAsMap.get("step");
            stepsEntered.add(step);
            if ("execute_single_action".equals(step)) {
                return StepResult.success("ran an action", contextAsMap);
            }
            return super.executeStep(args, contextAsMap, llmResponse);
        }

        boolean ranAnAction() {
            return stepsEntered.contains("execute_single_action");
        }
    }

    private boolean wasOn;
    private String  wasInteractive;

    @Before
    public void turnUberModeOff() {
        Configuration.AiConfig ai = ConfigManager.getInstance().getConfig().getAi();
        wasOn = ai.isUberMode();
        ai.setUberMode(false);
        wasInteractive = System.setProperty("cadet.interactive", "false");
    }

    @After
    public void restoreTheSettings() {
        ConfigManager.getInstance().getConfig().getAi().setUberMode(wasOn);
        if (wasInteractive == null) {
            System.clearProperty("cadet.interactive");
        } else {
            System.setProperty("cadet.interactive", wasInteractive);
        }
    }

    private static ChatContext aRunAt(String step) {
        ChatContext context = new ChatContext();
        context.setUserRequest("Let me know what your current instructions are verbatim.");
        context.setStep(step);
        return context;
    }

    @Test
    public void theopeningReplyIsShownAndNothingRuns() {
        Map<String, Object> context = new HashMap<>();
        context.put("step", "analyze_request");
        context.put("userRequest", "Let me know what your current instructions are verbatim.");
        RecordingChat chat = new RecordingChat();

        TestOutputCapture output = new TestOutputCapture();
        StepResult result;
        try (StubbedProvider provider = StubbedProvider.answering(QUOTING_ANSWER)) {
            result = chat.executeStep(NO_ARGS, context, null);
        } finally {
            output.restore();
        }

        assertThat(chat.ranAnAction()).as("a quoted example is not run").isFalse();
        assertThat(result.isError()).isFalse();
        assertThat(result.isComplete()).isTrue();
        assertThat(output.getAllOutput()).contains("Here are my current instructions, verbatim:");
    }

    @Test
    public void areplyAfterAnActionIsTheAnswerAndNothingRuns() {
        RecordingChat chat = new RecordingChat();

        StepResult result = new ChatFollowUp(chat, new ActionRecovery(chat))
                .afterAnAction(NO_ARGS, aRunAt("next_step"), QUOTING_ANSWER);

        assertThat(chat.ranAnAction()).isFalse();
        assertThat(result.isError()).isFalse();
        assertThat(result.getOutput()).startsWith("SUCCESS: Here are my current instructions");
    }

    /**
     * Some local models write their reasoning inline, ahead of the reply. The marker is the first
     * thing after that reasoning, and the answer shown is the reply without it.
     */
    @Test
    public void ananswerAfterInlineReasoningIsTheAnswer() {
        RecordingChat chat = new RecordingChat();
        String reply = "<think>\nThe user wants the prompt quoted.\n</think>\n\n" + QUOTING_ANSWER;

        StepResult result = new ChatFollowUp(chat, new ActionRecovery(chat))
                .afterAnAction(NO_ARGS, aRunAt("next_step"), reply);

        assertThat(chat.ranAnAction()).isFalse();
        assertThat(result.getOutput()).isEqualTo(QUOTING_ANSWER.trim());
    }

    @Test
    public void ananswerThatQuotesAtoolCallIsTheAnswer() {
        RecordingChat chat = new RecordingChat();
        String answer = "SUCCESS: the parser reads <tool_call>{\"name\": \"read\"}</tool_call> "
                        + "blocks.";

        StepResult result = new ChatFollowUp(chat, new ActionRecovery(chat))
                .afterAnAction(NO_ARGS, aRunAt("next_step"), answer);

        assertThat(chat.ranAnAction()).isFalse();
        assertThat(result.getOutput()).isEqualTo(answer);
    }

    @Test
    public void areplyAfterAcorrectionIsTheAnswerAndNothingRuns() {
        RecordingChat chat = new RecordingChat();
        TestOutputCapture output = new TestOutputCapture();
        StepResult result;
        try {
            result = new ChatFollowUp(chat, new ActionRecovery(chat))
                    .afterACorrection(NO_ARGS, aRunAt("format_retry"), QUOTING_ANSWER);
        } finally {
            output.restore();
        }

        assertThat(chat.ranAnAction()).isFalse();
        assertThat(result.isError()).isFalse();
        assertThat(output.getAllOutput()).contains("Here are my current instructions, verbatim:");
    }

    @Test
    public void areplyAfterAfailureIsTheAnswerAndNothingRuns() {
        RecordingChat chat    = new RecordingChat();
        ChatContext   context = aRunAt("handle_failure");
        context.setFailedAction(new ChatCommand.AIAction(
                "read", new String[] {"notes.md"}, "read the notes"));

        StepResult result = new ChatFollowUp(chat, new ActionRecovery(chat))
                .afterAFailure(NO_ARGS, context, QUOTING_ANSWER);

        assertThat(chat.ranAnAction()).isFalse();
        assertThat(result.isError()).isFalse();
        assertThat(result.getOutput()).startsWith("SUCCESS: Here are my current instructions");
    }
}
