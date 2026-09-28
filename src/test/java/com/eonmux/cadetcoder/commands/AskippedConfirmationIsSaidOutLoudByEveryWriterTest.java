package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ai.CommandApproval;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import com.eonmux.cadetcoder.ui.CapturedRun;
import com.eonmux.cadetcoder.ui.InteractivePrompts;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * The confirmation question every writing command skips when it has nobody to ask.
 *
 * <h2>The defect</h2>
 *
 * <p>{@code edit} was made to say so. {@code multiedit} was not, and it is the same rule written a
 * second time: it shows the edits, asks "Do you want to apply these changes?", and with prompts off
 * went straight to {@code apply_edits} in silence. Files were written under a setting whose whole
 * purpose is to hold a write until somebody agrees to it, and the only record was the change.</p>
 *
 * <p>Every agent run is such a run, because {@code asModelDrivenWork} turns prompts off for the
 * duration of a step. So the gate was skipped on exactly the path that writes with nobody
 * watching.</p>
 *
 * <p>When {@code security.commandApproval} is {@code auto}, the default, the model answers the
 * question in a request of its own and nothing is skipped. What follows holds in manual mode.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>That both commands say it, in the same words, from the same rule. Two copies of one sentence
 * drift, and the one that drifts is the one nobody reads.</p>
 */
class AskippedConfirmationIsSaidOutLoudByEveryWriterTest {

    @AfterEach
    void restorePrompts() {
        System.clearProperty(InteractivePrompts.PROPERTY);
        System.clearProperty(CommandApproval.PROPERTY);
    }

    /**
     * What {@code multiedit} prints when it reaches its confirmation step.
     *
     * <p>Observed by swapping the streams rather than through {@link CapturedRun}. A run whose
     * output {@code CapturedRun} is collecting is a run whose question reaches nobody, so
     * collecting it that way would decide the very thing these cases are about.</p>
     */
    private static String multieditAtPreview() {
        Map<String, Object> context = new HashMap<>();
        context.put("step", "preview_edits");
        context.put("args", new String[]{"a-file.txt"});
        return printedBy(() ->
                new MultiEditCommand().executeStep(new String[]{"a-file.txt"}, context, null));
    }

    /** What {@code edit} prints when it reaches its confirmation step. */
    private static String editAtPreview() {
        Map<String, Object> context = new HashMap<>();
        context.put("step", "preview_changes");
        return printedBy(() ->
                new EditCommand().executeStep(new String[]{"a-file.txt"}, context, null));
    }

    /**
     * Runs a step with the streams swapped, and answers with what it wrote to them.
     *
     * @param step the step to run
     * @return everything it printed
     */
    private static String printedBy(Runnable step) {
        TestOutputCapture streams = new TestOutputCapture();
        streams.startCapture();
        try {
            step.run();
            return streams.getAllOutput();
        } finally {
            streams.stopCapture();
        }
    }

    @Test
    void multieditSaysItAppliedWithoutAskingWhenThereIsNoTerminal() {
        System.setProperty(InteractivePrompts.PROPERTY, "false");

        assertThat(multieditAtPreview())
                .contains("without confirmation")
                .contains("no terminal");
    }

    @Test
    void multieditInsideAnAgentStepInManualModeSaysSo() {
        // In auto mode the model is asked instead; see AquestionInAmodelsStepIsPutToTheModelTest.
        System.setProperty(CommandApproval.PROPERTY, "manual");
        String printed = InteractivePrompts.asModelDrivenWork(
                AskippedConfirmationIsSaidOutLoudByEveryWriterTest::multieditAtPreview);

        assertThat(printed)
                .contains("without confirmation")
                .contains("agent step");
    }

    @Test
    void multieditNamesTheSettingWhenThatIsWhatTurnedThePromptOff() {
        assertThat(withPromptsOffByPreference(
                AskippedConfirmationIsSaidOutLoudByEveryWriterTest::multieditAtPreview))
                .contains("ui.interactivePrompts is off");
    }

    @Test
    void multieditSaysNothingOfTheKindWhenItCanAsk() {
        System.setProperty(InteractivePrompts.PROPERTY, "true");

        assertThat(multieditAtPreview()).doesNotContain("without confirmation");
    }

    /**
     * A run whose output is being collected has nobody to ask either.
     *
     * <p>A worker, a nested dispatch and a pass of {@code loop} all run with
     * {@link com.eonmux.cadetcoder.ui.OutputCapture} installed, so the question goes into the
     * transcript and only the bare input prompt reaches the screen. {@code multiedit} used to show
     * its preview, ask, answer itself no, and report "Edits cancelled by user" to a reader who had
     * never been asked anything -- which is how an edit a model had got right was thrown away.</p>
     */
    @Test
    void multieditSaysSoWhenItsOutputIsBeingCollected() {
        System.setProperty(InteractivePrompts.PROPERTY, "true");
        Map<String, Object> context = new HashMap<>();
        context.put("step", "preview_edits");
        context.put("args", new String[]{"a-file.txt"});

        String printed = CapturedRun.of(() -> {
            new MultiEditCommand().executeStep(new String[]{"a-file.txt"}, context, null);
            return 0;
        }).output();

        assertThat(printed)
                .contains("without confirmation")
                .contains("collected rather than shown");
    }

    @Test
    void bothCommandsGiveTheSameReasonForTheSameCircumstance() {
        // One rule, asked twice. A second copy of the sentence would drift, and the copy that
        // drifts is the one nobody reads.
        System.setProperty(InteractivePrompts.PROPERTY, "false");

        assertThat(noticeIn(multieditAtPreview()))
                .isEqualTo(noticeIn(editAtPreview()));
    }

    /** Runs {@code body} with {@code ui.interactivePrompts} off and a terminal present. */
    private static String withPromptsOffByPreference(Supplier<String> body) {
        Configuration config = new Configuration();
        config.getUi().setInteractivePrompts(false);
        ConfigManager manager = mock(ConfigManager.class);
        when(manager.getConfig()).thenReturn(config);

        try (MockedStatic<ConfigManager> configured = mockStatic(ConfigManager.class)) {
            configured.when(ConfigManager::getInstance).thenReturn(manager);
            return body.get();
        }
    }

    /** The one line about confirmation, out of whatever else the command said. */
    private static String noticeIn(String output) {
        for (String line : output.split("\n")) {
            if (line.contains("without confirmation")) {
                return line.trim();
            }
        }
        return "";
    }
}
