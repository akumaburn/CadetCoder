package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ExitCode;
import com.eonmux.cadetcoder.InterruptSignal;
import com.eonmux.cadetcoder.test.TestOutputCapture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The commands that were still reporting a stop or a refusal as a failure.
 *
 * <p>Three of them said 1 where every other command says 130: webfetch for a fetch the user
 * declined, explain for a run that had been taken back, and execute for a list of alternatives
 * nobody picked from. A caller reading exit codes -- a script, or the loop -- cannot tell
 * "you stopped me" from "I could not do it" when they arrive as the same number, and the two call
 * for opposite responses: one is retried, the other is not.</p>
 */
class ArefusalIsNotAfailureAnywhereTest {

    @TempDir
    Path directory;

    private TestOutputCapture output;

    @BeforeEach
    void setUp() {
        output = new TestOutputCapture();
        output.startCapture();
        InterruptSignal.clear();
    }

    @AfterEach
    void tearDown() {
        InterruptSignal.clear();
        output.stopCapture();
    }

    @Test
    void anExplanationThatWasTakenBackReportsTheInterruptionCode() throws IOException {
        Path file = Files.writeString(directory.resolve("subject.txt"), "some text\n");
        InterruptSignal.request();

        assertThat(new ExplainCommand().execute(new String[] {file.toString()}))
                .isEqualTo(ExitCode.INTERRUPTED);
    }

    @Test
    void anExplanationStepThatWasTakenBackIsNotAnError() {
        InterruptSignal.request();
        Map<String, Object> context = new HashMap<>();

        IterativeCommand.StepResult stopped =
                new ExplainCommand().executeStep(new String[] {"anything.txt"}, context, null);

        assertThat(stopped.isInterrupted()).isTrue();
        assertThat(stopped.isError()).isFalse();
    }

    @Test
    void anExplanationNobodyStoppedIsNotReportedAsStopped() throws IOException {
        Path file = Files.writeString(directory.resolve("subject.txt"), "some text\n");

        assertThat(new ExplainCommand().execute(new String[] {file.toString()}))
                .as("whatever became of it, it was not taken back")
                .isNotEqualTo(ExitCode.INTERRUPTED);
    }
}
