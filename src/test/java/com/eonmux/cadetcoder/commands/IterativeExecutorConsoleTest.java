package com.eonmux.cadetcoder.commands;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a run puts on the console, as opposed to what it sends the model.
 *
 * <p>A step's output is built for the model and is then also the source of the console rendering.
 * The two audiences want different things, and these are the two places the executor translates
 * between them.</p>
 */
public class IterativeExecutorConsoleTest {

    @Test
    public void theStepPreambleBuiltForTheModelIsNotShownToTheUser() {
        String stepOutput = "Command executed: read pom.xml\n"
                + "\n"
                + "Command Output:\n"
                + "▎ File: /tmp/pom.xml\n"
                + "     1\t<project>\n";

        String shown = StepOutput.stripStepPreamble(stepOutput);

        // The command was announced a moment earlier and named again in its ordered record, so all
        // three preamble lines are things the reader has just been told, repeated once per command.
        assertThat(shown).doesNotContain("Command executed:");
        assertThat(shown).doesNotContain("Command Output:");
        assertThat(shown).startsWith("▎ File: /tmp/pom.xml");
    }

    @Test
    public void outputWithNoPreambleIsLeftAlone() {
        assertThat(StepOutput.stripStepPreamble("File not found: nope.txt"))
                .isEqualTo("File not found: nope.txt");
    }

    @Test
    public void aCommandThatPrintedNothingShowsTheBoilerplateNoMoreThanAnyOtherDoes() {
        // A step whose command said nothing -- a write that worked, a grep with no matches -- is
        // the one case where the preamble IS the whole message, and the one where returning the
        // input unchanged put every line this method exists to remove back on the screen.
        String onlyPreamble = "Command executed: bash true\n\nCommand Output:\n";

        assertThat(StepOutput.stripStepPreamble(onlyPreamble)).isEmpty();
    }

    @Test
    public void nothingIsPrintedForAStepThatProducedNoOutput() {
        com.eonmux.cadetcoder.test.TestOutputCapture console =
                new com.eonmux.cadetcoder.test.TestOutputCapture();
        try {
            StepOutput.printForConsole("Command executed: bash true\n\nCommand Output:\n");
        } finally {
            console.restore();
        }

        assertThat(console.getAllOutput())
                .as("the announcement above it already named the command; there is nothing to add")
                .doesNotContain("Command executed:")
                .doesNotContain("Command Output:");
    }

    @Test
    public void nullOutputIsRenderedAsNothingRatherThanThrowing() {
        assertThat(StepOutput.stripStepPreamble(null)).isEmpty();
    }

    @Test
    public void theMarkerIsRemovedWhereItAppearsRatherThanCuttingTheTextAtIt() {
        // This branch is the ONLY place a completing step reaches the console, so anything dropped
        // here is dropped everywhere. A model asked to finish with a "SUCCESS:" summary routinely
        // writes its actual answer first; cutting at the marker threw that answer away.
        String answer = StepOutput.extractAnswer(
                "Analysis of Foo.java:\n- reads config\n- writes cache\nSUCCESS: analysis complete");

        assertThat(answer).isEqualTo(
                "Analysis of Foo.java:\n- reads config\n- writes cache\nanalysis complete");
    }

    @Test
    public void aFencedAnswerIsNotCutInHalfLeavingAnUnbalancedFence() {
        // The shell renders a result as Markdown, so an unbalanced fence swallows everything printed
        // after it -- including the next command's output.
        String answer = StepOutput.extractAnswer(
                "Here is the fix:\n```\nSUCCESS: not a marker\n```\nand that is all.");

        assertThat(answer).startsWith("Here is the fix:");
        assertThat(answer).endsWith("and that is all.");
        assertThat(answer.split("```", -1).length - 1).isEqualTo(2);
    }

    @Test
    public void aProtocolFramingLineAroundTheMarkerIsKeptRatherThanGuessedAt() {
        // "Response: TASK_COMPLETE" is framing rather than answer, but the only safe rule is to
        // remove the marker token and keep everything else -- guessing at which lines are protocol
        // is how the answer got thrown away in the first place.
        assertThat(StepOutput.extractAnswer("Response: TASK_COMPLETE\nSUCCESS: Looked at it."))
                .isEqualTo("Response: TASK_COMPLETE\nLooked at it.");
    }

    @Test
    public void aLeadingSuccessMarkerStillWorks() {
        assertThat(StepOutput.extractAnswer("SUCCESS: all done")).isEqualTo("all done");
    }

    @Test
    public void outputWithNoMarkerIsTheAnswerItself() {
        assertThat(StepOutput.extractAnswer("  the analysis  ")).isEqualTo("the analysis");
    }

    @Test
    public void nothingCompletesToAnEmptyAnswerRatherThanNull() {
        assertThat(StepOutput.extractAnswer(null)).isEmpty();
    }

    @Test
    public void theWordSuccessInsideASentenceIsNotMistakenForTheMarker() {
        String prose = "The build reported SUCCESS: nothing to do here is part of the sentence";

        // Only a line START counts, so prose that happens to contain the word is left intact.
        assertThat(StepOutput.extractAnswer(prose)).isEqualTo(prose);
    }
}
