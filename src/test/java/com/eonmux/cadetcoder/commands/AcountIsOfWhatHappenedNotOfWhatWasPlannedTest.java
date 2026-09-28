package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ai.metrics.TokenEstimator;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Numbers a routine quotes have to describe what it actually did.
 *
 * <p>Each of these was a counter read on the wrong side of the work it counts: before a truncation
 * rather than after, after an increment for work that was not done, or from a list that only ever
 * holds the successes it is being compared against. None of them crashes anything. All of them tell
 * the reader -- often a model, deciding what to do next -- something that is not so.</p>
 */
class AcountIsOfWhatHappenedNotOfWhatWasPlannedTest {

    // ------------------------------------------------------------------ StepOutput

    /**
     * Command output ends in a newline, and the split keeps the empty field after it.
     *
     * <p>Counted as a line, it produced "... 1 more line hidden" for output with nothing left to
     * hide, and put every other count one too high.</p>
     */
    @Test
    void theEmptyFieldAfterAtrailingNewlineIsNotAline() {
        String fourLines = "Command Output:\na\nb\nc\nd\n";

        String shown = StepOutput.abbreviateForConsole(fourLines);

        assertThat(shown)
                .as("four lines and a head of four: there is nothing left to hide")
                .doesNotContain("more line");
    }

    @Test
    void whatIsHiddenIsCountedWithoutThePhantomLine() {
        String sixLines = "Command Output:\na\nb\nc\nd\ne\nf\n";

        String shown = StepOutput.abbreviateForConsole(sixLines);

        assertThat(shown).contains("2 more lines hidden").doesNotContain("3 more lines");
    }

    @Test
    void outputWithNoTrailingNewlineIsStillCountedCorrectly() {
        String sixLines = "Command Output:\na\nb\nc\nd\ne\nf";

        assertThat(StepOutput.abbreviateForConsole(sixLines)).contains("2 more lines hidden");
    }

    // ------------------------------------------------------------------ TranscriptCompactor

    /**
     * The fold marker states how many steps are gone, and the fold may give up more after it is
     * written.
     *
     * <p>Written once, up front, it told the model that work it could not see was in front of it --
     * and disagreed with the summary the same call returns, which IS counted from the finished
     * sizes.</p>
     */
    /**
     * Long enough that the fold has to give up more than the tail rule alone would.
     *
     * <p>Sized from the budget in force rather than from a fixed number of entries. Sixty entries
     * of two thousand characters was "well over any sane budget" only while the budget was small:
     * once an uncatalogued model was assumed to take 64K rather than 8K, the same transcript sat
     * under the trigger, nothing was folded, and a test about counting failed for having nothing
     * to count.</p>
     */
    private static List<String> aLongRun() {
        int          entry      = 2_000;
        int          needed     = TranscriptCompactor.effectiveBudgetTokens() * 3;
        int          entries    = Math.max(60, needed / TokenEstimator.estimate("x".repeat(entry)));
        List<String> transcript = new ArrayList<>();
        for (int i = 0; i < entries; i++) {
            transcript.add("System: entry " + i + " " + "x".repeat(entry));
        }
        return transcript;
    }

    /** The number the marker states, read back out of it. */
    private static int statedInMarker(List<String> transcript) {
        String marker = transcript.stream().filter(line -> line.contains("omitted to stay within"))
                                  .findFirst().orElseThrow();
        return Integer.parseInt(marker.split("\\[")[1].split(" ")[0]);
    }

    @Test
    void thefoldMarkerCountsWhatTheFoldActuallyGaveUp() {
        List<String> transcript = aLongRun();
        int          before     = transcript.size();

        assertThat(new TranscriptCompactor().compactIfNeeded(transcript, "system", true))
                .as("this transcript is well over any sane budget")
                .isNotNull();

        // Everything still present came out of the original, less the one line the marker occupies.
        assertThat(statedInMarker(transcript))
                .as("the marker must not promise steps the fold went on to drop")
                .isEqualTo(before - (transcript.size() - 1));
    }

    @Test
    void thefoldMarkerAgreesWithTheSummaryTheSameCallReturns() {
        List<String> transcript = aLongRun();

        String summary = new TranscriptCompactor().compactIfNeeded(transcript, "system", true);

        assertThat(String.valueOf(statedInMarker(transcript)))
                .as("one fold, one number: the marker and %s", summary)
                .isEqualTo(summary.split(" ")[0]);
    }
}
