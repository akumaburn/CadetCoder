package com.eonmux.cadetcoder.commands;

import org.junit.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Turning a terminal cell back into the character that was drawn in it.
 *
 * <h2>The defect</h2>
 *
 * <p>A click on an empty console landed on row zero. The clamp read
 * {@code Math.min(row, Math.max(0, lines.size() - 1))}, and the floor of zero says "the last row of
 * an empty document is row zero" rather than "there is no row". So a click on the blank console
 * after {@code Ctrl+L} began a selection over no text, and beginning a selection stops the console
 * following the tail -- output kept arriving and kept scrolling past unseen, with nothing on screen
 * to say why. That reads as a hang.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>A cell is resolved against the frame that was actually drawn, not against the transcript as it
 * stands now: the scroll offset of that frame, its position on screen, and its bounds. Outside those
 * bounds there is no character, and saying so is the point -- the alternative is a selection anchored
 * to a row nobody clicked.</p>
 */
public class AClickLandsOnWhatWasDrawnTest {

    private static final List<String> FOUR_LINES = List.of("one", "two", "three", "four");

    /** Two results: the first owns the first two lines, the second owns the rest. */
    private static final int[] TWO_RESULTS = {7, 7, 9, 9};

    private final ShellConsoleView console = new ShellConsoleView();

    @Test
    public void beforeAnythingHasBeenDrawnThereIsNothingToClickOn() {
        assertThat(console.documentRowAt(0)).isEqualTo(ShellConsoleView.NOTHING);
        assertThat(console.focusSegmentAt(0, 0)).isFalse();
        assertThat(console.documentLines()).isEmpty();
    }

    @Test
    public void aClickOnTheTopRowOfTheConsoleLandsOnTheTopLineDrawn() {
        console.publish(FOUR_LINES, TWO_RESULTS, 3, 2, 40, 4, 0);

        assertThat(console.documentRowAt(3)).isZero();
        assertThat(console.documentRowAt(4)).isEqualTo(1);
        assertThat(console.documentRowAt(6)).isEqualTo(3);
    }

    @Test
    public void aClickIsResolvedAgainstTheScrollOfTheFrameThatWasDrawn() {
        console.publish(FOUR_LINES, TWO_RESULTS, 0, 0, 40, 2, 2);

        assertThat(console.documentRowAt(0))
                .as("the top row of a frame scrolled by two shows the third line")
                .isEqualTo(2);
        assertThat(console.documentRowAt(1)).isEqualTo(3);
    }

    @Test
    public void aClickAboveOrBelowTheConsoleLandsOnNothing() {
        console.publish(FOUR_LINES, TWO_RESULTS, 3, 2, 40, 4, 0);

        assertThat(console.documentRowAt(2)).isEqualTo(ShellConsoleView.NOTHING);
        assertThat(console.documentRowAt(7)).isEqualTo(ShellConsoleView.NOTHING);
    }

    @Test
    public void aClickPastTheEndOfAShortTranscriptLandsOnItsLastLine() {
        // A window taller than the transcript: the rows below the text are blank.
        console.publish(FOUR_LINES, TWO_RESULTS, 0, 0, 40, 20, 0);

        assertThat(console.documentRowAt(19))
                .as("dragging into the blank space below should select to the end, not off it")
                .isEqualTo(3);
    }

    @Test
    public void aClickOnAnEmptyConsoleLandsOnNothing() {
        console.publish(List.of(), new int[0], 0, 0, 40, 20, 0);

        assertThat(console.documentRowAt(0))
                .as("row zero of an empty document is not a row")
                .isEqualTo(ShellConsoleView.NOTHING);
        assertThat(console.focusSegmentAt(0, 0)).isFalse();
    }

    @Test
    public void theColumnIsMeasuredFromTheLeftEdgeOfTheConsole() {
        console.publish(FOUR_LINES, TWO_RESULTS, 0, 5, 40, 4, 0);

        assertThat(console.documentColumnAt(5)).isZero();
        assertThat(console.documentColumnAt(9)).isEqualTo(4);
    }

    @Test
    public void aColumnLeftOfTheConsoleIsTheStartOfTheLine() {
        console.publish(FOUR_LINES, TWO_RESULTS, 0, 5, 40, 4, 0);

        assertThat(console.documentColumnAt(0))
                .as("dragging out of the left gutter should select from the line's start")
                .isZero();
    }

    @Test
    public void aFrameCannotHaveHiddenLessThanNoLines() {
        console.publish(FOUR_LINES, TWO_RESULTS, 0, 0, 40, 4, -3);

        assertThat(console.documentRowAt(0))
                .as("a negative scroll is floored, not read as a row behind the first")
                .isZero();
        assertThat(console.focusSegmentAt(0, 0)).isTrue();
        assertThat(console.focusedSegment()).isEqualTo(7);
    }

    @Test
    public void theTextOfTheLastFrameIsWhatGetsCopied() {
        console.publish(FOUR_LINES, TWO_RESULTS, 0, 0, 40, 4, 0);

        assertThat(console.documentLines()).containsExactly("one", "two", "three", "four");
    }

    @Test
    public void aFrameWithNoLinesLeavesNothingToCopy() {
        console.publish(FOUR_LINES, TWO_RESULTS, 0, 0, 40, 4, 0);

        console.publish(null, null, 0, 0, 40, 4, 0);

        assertThat(console.documentLines()).isEmpty();
    }

    @Test
    public void clickingALineOfAResultFocusesThatResult() {
        console.publish(FOUR_LINES, TWO_RESULTS, 3, 2, 40, 4, 0);

        assertThat(console.focusSegmentAt(2, 3)).isTrue();
        assertThat(console.focusedSegment()).isEqualTo(7);

        assertThat(console.focusSegmentAt(2, 5)).isTrue();
        assertThat(console.focusedSegment()).isEqualTo(9);
    }

    @Test
    public void clickingALineThatBelongsToNoResultFocusesNothing() {
        console.publish(FOUR_LINES, new int[] {7, ShellConsoleView.NOTHING, 9, 9}, 0, 0, 40, 4, 0);

        assertThat(console.focusSegmentAt(0, 1)).isFalse();
        assertThat(console.isFocused()).isFalse();
    }

    @Test
    public void clickingOutsideTheConsoleFocusesNothing() {
        console.publish(FOUR_LINES, TWO_RESULTS, 3, 2, 10, 4, 0);

        assertThat(console.focusSegmentAt(1, 3)).as("left of the console").isFalse();
        assertThat(console.focusSegmentAt(12, 3)).as("right of the console").isFalse();
        assertThat(console.focusSegmentAt(2, 2)).as("above the console").isFalse();
        assertThat(console.focusSegmentAt(2, 7)).as("below the console").isFalse();
        assertThat(console.isFocused()).isFalse();
    }

    @Test
    public void clickingIsResolvedAgainstTheScrollOfTheFrameThatWasDrawn() {
        console.publish(FOUR_LINES, TWO_RESULTS, 0, 0, 40, 2, 2);

        assertThat(console.focusSegmentAt(0, 0)).isTrue();
        assertThat(console.focusedSegment())
                .as("the top row of a frame scrolled by two belongs to the second result")
                .isEqualTo(9);
    }

    @Test
    public void clickingBelowTheEndOfAShortTranscriptFocusesNothing() {
        console.publish(FOUR_LINES, TWO_RESULTS, 0, 0, 40, 20, 0);

        assertThat(console.focusSegmentAt(0, 8))
                .as("the blank rows under a short transcript belong to no result")
                .isFalse();
    }

    @Test
    public void clickingAResultTakesFocusFromAWorker() {
        console.enterOrMoveFocus(1, List.of(BackgroundPane.worker(4), BackgroundPane.worker(5)));
        console.publish(FOUR_LINES, TWO_RESULTS, 0, 0, 40, 4, 0);

        assertThat(console.focusSegmentAt(0, 0)).isTrue();

        assertThat(console.focusedPane()).isNull();
        assertThat(console.focusedSegment()).isEqualTo(7);
    }
}
