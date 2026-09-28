package com.eonmux.cadetcoder.commands;

import org.junit.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one row that says what the shell is doing while it is busy.
 *
 * <h2>The defect</h2>
 *
 * <p>Three groups compete for one row -- a mark, what is being done, and what the request has cost
 * so far -- and the widget that draws the row clips whatever does not fit. A clipped
 * {@code ~5,551 tokens} reads as {@code ~5,55}: a smaller number, stated with exactly the same
 * confidence as a right one. The rule that prevents it, that the timing group is dropped whole
 * rather than cut, was written inside the render path and could not be asked about without starting
 * a terminal.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>That the timing group is dropped rather than clipped once the activity would be squeezed below
 * what is worth reading; that the activity is elided instead, since it is prose and reads as prose
 * when cut; that the groups keep their order and separator when both fit; and that an opening
 * activity names the command being run rather than echoing the user's own sentence, which is
 * already on screen twice by the time this row appears.</p>
 */
public class TheBusyLineSaysWhatIsHappeningTest {

    private static final String BULLET   = "-";
    private static final String ELLIPSIS = "...";

    private static String compose(String activity, String timing, int width) {
        return ShellActivityLine.compose("/", activity, timing, width, BULLET, ELLIPSIS);
    }

    @Test
    public void bothGroupsShowWhenBothFit() {
        String line = compose("Reading src/Main.java", "12s - 1,200 tokens", 80);

        assertThat(line).startsWith("/ Reading src/Main.java");
        assertThat(line).endsWith("12s - 1,200 tokens");
        assertThat(line).contains("  " + BULLET + "  ");
    }

    @Test
    public void theTimingGoesRatherThanBeingCut() {
        // The point of the whole exercise: a half-printed number is worse than no number, because
        // nothing about it says it is half-printed.
        String line = compose("Reading src/Main.java", "12s - 1,200 tokens", 30);

        assertThat(line).doesNotContain("12s");
        assertThat(line).doesNotContain("1,200");
    }

    @Test
    public void theActivityIsElidedRatherThanDropped() {
        String line = compose("Reading a file with a very long name indeed", "", 24);

        assertThat(line).startsWith("/ ");
        assertThat(line).contains(ELLIPSIS);
        assertThat(line.length()).isLessThanOrEqualTo(24);
    }

    @Test
    public void theTimingSurvivesWhenTheActivityIsShortEnoughToShareTheRow() {
        String line = compose("Thinking", "3s", 40);

        assertThat(line).contains("Thinking");
        assertThat(line).endsWith("3s");
    }

    @Test
    public void aRowWithNoActivityIsJustTheMark() {
        assertThat(compose("", "", 40)).isEqualTo("/");
    }

    @Test
    public void aRowWithNoActivityStillReportsTheCost() {
        // A request that has said nothing yet is exactly when the elapsed time is the only thing
        // distinguishing "still going" from "hung".
        String line = compose("", "8s", 40);

        assertThat(line).startsWith("/");
        assertThat(line).endsWith("8s");
    }

    @Test
    public void nothingIsWrittenIntoARowWithNoColumns() {
        assertThat(compose("Thinking", "3s", 0)).isEqualTo("/");
        assertThat(compose("Thinking", "3s", -5)).isEqualTo("/");
    }

    @Test
    public void missingValuesAreTreatedAsAbsentRatherThanPrinted() {
        assertThat(ShellActivityLine.compose(null, null, null, 40, null, null)).isEmpty();
    }

    @Test
    public void anOpeningActivityNamesTheCommandNotTheSentence() {
        Set<String> commands = Set.of("ls", "read");

        assertThat(ShellActivityLine.opening("/ls src", commands)).isNotEqualTo("Thinking");
        assertThat(ShellActivityLine.opening("/ls src", commands)).isNotEmpty();
    }

    @Test
    public void aMessageToTheModelIsCalledThinking() {
        // Not the user's own words: they are on screen twice already, as the echo of what was typed
        // and as the label on the result separator.
        assertThat(ShellActivityLine.opening("explain how sessions are stored", Set.of("ls")))
                .isEqualTo("Thinking");
    }

    @Test
    public void aLineThatOnlyLooksLikeACommandIsStillAMessage() {
        // Without the slash it is a sentence that happens to open with a command word, and guessing
        // otherwise would name a file the user never mentioned.
        assertThat(ShellActivityLine.opening("read the design doc and summarise it", Set.of("read")))
                .isEqualTo("Thinking");
    }
}
