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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * The confirmation question {@code edit} skips when it has nobody to ask.
 *
 * <h2>The defect</h2>
 *
 * <p>{@code edit} generates changes, shows them, and asks "Do you want to apply these changes?".
 * With prompts off it went straight to {@code apply_changes} and said nothing. Files were written
 * under a setting whose whole purpose is to hold a write until somebody agrees to it, and the only
 * record was the change itself.</p>
 *
 * <p>Every agent run is such a run. {@code InteractivePrompts.asModelDrivenWork} turns prompts off
 * for the duration of a step, because a question asked inside a captured command never reaches a
 * screen and would block the run. So the gate was skipped on exactly the path that writes without a
 * person watching.</p>
 *
 * <p>When {@code security.commandApproval} is {@code auto}, the default, the model answers the
 * question in a request of its own and nothing is skipped. What follows holds in manual mode.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>That the skip is reported, and that the report says why nobody was asked. Refusing instead was
 * considered and rejected: {@code security.requireConfirmation} defaults to on, so refusing would
 * make {@code edit} unusable for every agent run. The behaviour stays; it stops being silent.</p>
 */
class AskippedConfirmationIsSaidOutLoudTest {

    @AfterEach
    void restorePrompts() {
        System.clearProperty(InteractivePrompts.PROPERTY);
        System.clearProperty(CommandApproval.PROPERTY);
    }

    /**
     * What {@code edit} prints when it reaches the confirmation step.
     *
     * <p>Observed by swapping the streams rather than through {@link CapturedRun}. A run whose
     * output {@code CapturedRun} is collecting is a run whose question reaches nobody, and
     * {@code InteractivePrompts.isOn} says so -- so collecting the output that way would decide
     * the very thing these cases are about.</p>
     */
    private static String atPreviewStep() {
        Map<String, Object> context = new HashMap<>();
        context.put("step", "preview_changes");
        TestOutputCapture streams = new TestOutputCapture();
        streams.startCapture();
        try {
            new EditCommand().executeStep(new String[]{"a-file.txt"}, context, null);
            return streams.getAllOutput();
        } finally {
            streams.stopCapture();
        }
    }

    @Test
    void arunWithNoTerminalSaysItAppliedWithoutAsking() {
        System.setProperty(InteractivePrompts.PROPERTY, "false");

        assertThat(atPreviewStep())
                .contains("without confirmation")
                .contains("no terminal");
    }

    @Test
    void anAgentStepInManualModeSaysSoInItsOwnWords() {
        // In auto mode the model is asked instead; see AquestionInAmodelsStepIsPutToTheModelTest.
        System.setProperty(CommandApproval.PROPERTY, "manual");
        // The case that matters. asModelDrivenWork turns prompts off for the step, and the notice
        // this produces is captured with the rest of the command's output, so the model reads it.
        String printed = InteractivePrompts.asModelDrivenWork(
                AskippedConfirmationIsSaidOutLoudTest::atPreviewStep);

        assertThat(printed)
                .contains("without confirmation")
                .contains("agent step");
    }

    @Test
    void auserWhoTurnedPromptsOffIsToldWhichSettingDidIt() {
        // Prompts off by preference rather than by circumstance: there is a terminal, and the user
        // asked not to be stopped. Naming the setting tells them what to change to get the question
        // back.
        Configuration config = new Configuration();
        config.getUi().setInteractivePrompts(false);
        ConfigManager manager = mock(ConfigManager.class);
        when(manager.getConfig()).thenReturn(config);

        try (MockedStatic<ConfigManager> configured = mockStatic(ConfigManager.class)) {
            configured.when(ConfigManager::getInstance).thenReturn(manager);
            assertThat(atPreviewStep()).contains("ui.interactivePrompts is off");
        }
    }

    @Test
    void theReasonGivenDependsOnWhyNobodyCouldBeAsked() {
        System.setProperty(InteractivePrompts.PROPERTY, "false");

        assertThat(atPreviewStep())
                .as("the reasons are distinguishable, so the notice is worth reading")
                .isNotEqualTo(InteractivePrompts.asModelDrivenWork(
                        AskippedConfirmationIsSaidOutLoudTest::atPreviewStep));
    }

    @Test
    void arunThatCanAskSaysNothingOfTheKind() {
        System.setProperty(InteractivePrompts.PROPERTY, "true");

        assertThat(atPreviewStep()).doesNotContain("without confirmation");
    }

    /**
     * A run whose output is being collected has nobody to ask either.
     *
     * <p>A worker, a nested dispatch and a pass of {@code loop} all run with
     * {@link com.eonmux.cadetcoder.ui.OutputCapture} installed. The text of a question goes into
     * the transcript, so the only thing reaching the screen is the bare input prompt: nobody can
     * answer what they were not shown, an unanswered confirmation is denied, and the whole exchange
     * came down to cancelling the step. {@code multiedit} showed its preview, asked, answered
     * itself no, and reported "Edits cancelled by user" to a reader who had never been asked.</p>
     */
    @Test
    void arunWhoseOutputIsCollectedSaysSoToo() {
        System.setProperty(InteractivePrompts.PROPERTY, "true");

        String printed = CapturedRun.of(() -> {
            Map<String, Object> context = new HashMap<>();
            context.put("step", "preview_changes");
            new EditCommand().executeStep(new String[]{"a-file.txt"}, context, null);
            return 0;
        }).output();

        assertThat(printed)
                .contains("without confirmation")
                .contains("collected rather than shown");
    }
}
