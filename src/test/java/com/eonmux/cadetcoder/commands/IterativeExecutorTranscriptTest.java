package com.eonmux.cadetcoder.commands;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a turn contributes to the transcript, and therefore to every later prompt.
 *
 * <p>A step's output and the next prompt frequently carry the same command output — the chat loop
 * builds one string embedding it and another embedding it again — so appending both put every
 * command's result into the prompt twice.</p>
 */
public class IterativeExecutorTranscriptTest {

    private static final String LISTING =
            "▎ File: /tmp/pom.xml\n     1  <project>\n     2    <modelVersion>4.0.0</modelVersion>\n";

    private static final String STEP_OUTPUT =
            "Command executed: read pom.xml\n\nCommand Output:\n" + LISTING;

    @Test
    public void theOutputIsRecordedOnceWhenTheNextPromptAlreadyCarriesIt() {
        String nextPrompt = "I just executed 'read pom.xml'\n\nCommand Output:\n" + LISTING
                            + "\nBased on these results, determine the next step.";

        String entry = StepOutput.condenseForTranscript(STEP_OUTPUT, nextPrompt);

        // The descriptor survives, so the transcript still says what ran; the body does not, because
        // the entry appended immediately after it contains the body verbatim.
        assertThat(entry).isEqualTo("Command executed: read pom.xml");
        assertThat(entry).doesNotContain("modelVersion");
    }

    @Test
    public void aDiagnosticTheNextPromptDoesNotRepeatIsKeptInFull() {
        String output     = "Command executed: read nope.txt\n\nCommand Output:\nFile not found: nope.txt\n";
        String nextPrompt = "The action failed. Choose retry, skip or stop.";

        // Nothing may be dropped on the strength of an assumption: the test is whether the following
        // entry provably contains this one's body.
        assertThat(StepOutput.condenseForTranscript(output, nextPrompt)).isEqualTo(output);
    }

    @Test
    public void aStepWithNoCommandPreambleIsUntouched() {
        String output = "Format error in next_step attempt 1";

        assertThat(StepOutput.condenseForTranscript(output, output + " and more"))
                .isEqualTo(output);
    }

    @Test
    public void missingInputsAreHandledRatherThanThrowing() {
        assertThat(StepOutput.condenseForTranscript(null, "x")).isEmpty();
        assertThat(StepOutput.condenseForTranscript("out", null)).isEqualTo("out");
        assertThat(StepOutput.condenseForTranscript("", "x")).isEmpty();
    }

    @Test
    public void condensingRemovesRealBulkFromTheTranscript() {
        String big        = "x".repeat(40_000);
        String output     = "Command executed: grep foo\n\nCommand Output:\n" + big;
        String nextPrompt = "I just executed 'grep foo'\n\nCommand Output:\n" + big;

        String entry = StepOutput.condenseForTranscript(output, nextPrompt);

        assertThat(entry.length()).isLessThan(100);
    }
}
