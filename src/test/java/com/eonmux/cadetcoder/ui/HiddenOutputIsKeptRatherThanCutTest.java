package com.eonmux.cadetcoder.ui;

import org.junit.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Output the console does not show is still there to be opened.
 *
 * <h2>The defect</h2>
 *
 * <p>Hiding a command's output meant cutting it: four lines were printed, the rest was replaced by
 * a count, and the text was gone. There was nothing on screen to open and no way to read what a
 * command had said short of turning the setting on and running it again -- so the count was an
 * announcement of something withheld rather than a way to reach it.</p>
 *
 * <p>The text is sent whole now, between two markers. The live console leaves out what is between
 * them; a result opened by clicking it shows everything it holds.</p>
 */
public class HiddenOutputIsKeptRatherThanCutTest {

    private static final List<String> RESULT = List.of(
            "▸ glob src/test/**/*.java",
            "  Find the test files",
            "✓ [8] ok  (463 lines)",
            CollapsedOutput.OPEN,
            "  FilePathResolverTest.java",
            "  GitIntegrationManagerTest.java",
            CollapsedOutput.CLOSE);

    @Test
    public void theLiveConsoleShowsWhatWasDoneAndNotWhatItPrinted() {
        assertThat(CollapsedOutput.visible(RESULT)).containsExactly(
                "▸ glob src/test/**/*.java",
                "  Find the test files",
                "✓ [8] ok  (463 lines)");
    }

    @Test
    public void anOpenedResultShowsEverythingItHolds() {
        assertThat(CollapsedOutput.expanded(RESULT)).containsExactly(
                "▸ glob src/test/**/*.java",
                "  Find the test files",
                "✓ [8] ok  (463 lines)",
                "  FilePathResolverTest.java",
                "  GitIntegrationManagerTest.java");
    }

    @Test
    public void aMarkerIsRecognisedThroughTheIndentTheFormatterAdds() {
        // The block between the markers is printed through the formatter, which indents what it
        // prints; a marker that only matched at column zero would leave the block on screen.
        assertThat(CollapsedOutput.opens("  " + CollapsedOutput.OPEN)).isTrue();
        assertThat(CollapsedOutput.closes(CollapsedOutput.CLOSE + " ")).isTrue();
        assertThat(CollapsedOutput.isMarker("an ordinary line")).isFalse();
    }

    @Test
    public void aRunLeftOpenEndsWithTheResultRatherThanSwallowingTheRest() {
        // A bounded scrollback can drop the closing marker with the oldest lines it retires. The
        // tail of one result is then hidden, which is the small loss; hiding every result after it
        // would be the large one.
        List<String> truncated = List.of("first", CollapsedOutput.OPEN, "body", "more body");

        assertThat(CollapsedOutput.visible(truncated)).containsExactly("first");
        assertThat(CollapsedOutput.expanded(truncated))
                .containsExactly("first", "body", "more body");
    }

    @Test
    public void whatIsStoredOrForwardedCarriesNoMarkers() {
        String printed = "one\n" + CollapsedOutput.OPEN + "\ntwo\n" + CollapsedOutput.CLOSE + "\n";

        assertThat(CollapsedOutput.strip(printed)).isEqualTo("one\ntwo\n");
        assertThat(CollapsedOutput.strip("nothing to strip")).isEqualTo("nothing to strip");
        assertThat(CollapsedOutput.strip(null)).isNull();
    }

    @Test
    public void textWithNoTrailingNewlineKeepsItsLastLine() {
        assertThat(CollapsedOutput.strip(CollapsedOutput.OPEN + "\nlast")).isEqualTo("last");
    }

    @Test
    public void nothingIsMarkedWhereNothingCanOpenIt() {
        // Off a TUI there is no renderer that knows what the markers mean, and the plain console
        // would print two control lines around output it is not showing.
        String previous = System.getProperty(TuiMode.OVERRIDE_PROPERTY);
        System.clearProperty(TuiMode.OVERRIDE_PROPERTY);
        try {
            assertThat(CollapsedOutput.isSupported()).isFalse();
        } finally {
            if (previous != null) {
                System.setProperty(TuiMode.OVERRIDE_PROPERTY, previous);
            }
        }
    }

    @Test
    public void aWorkerSummarisesRatherThanMarks() {
        // A worker's lines are read back as text by `workers show` and reach the model, so display
        // control must not be written into them.
        String previous = System.getProperty(TuiMode.OVERRIDE_PROPERTY);
        System.setProperty(TuiMode.OVERRIDE_PROPERTY, "true");
        StringBuilder collected = new StringBuilder();
        try {
            OutputCapture.collectInto(collected::append,
                    () -> assertThat(CollapsedOutput.isSupported()).isFalse());
            assertThat(CollapsedOutput.isSupported())
                    .as("outside the worker the shell can expand it again")
                    .isTrue();
        } finally {
            if (previous == null) {
                System.clearProperty(TuiMode.OVERRIDE_PROPERTY);
            } else {
                System.setProperty(TuiMode.OVERRIDE_PROPERTY, previous);
            }
        }
    }
}
