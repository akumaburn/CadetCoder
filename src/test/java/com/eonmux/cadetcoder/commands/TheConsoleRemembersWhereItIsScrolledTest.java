package com.eonmux.cadetcoder.commands;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scrolling the shell's console: what a page is, where the ends are, and when the transcript goes
 * back to following its newest line.
 *
 * <h2>Why this is tested</h2>
 *
 * <p>This is the shell's most reachable behaviour -- every paging key and every wheel notch goes
 * through it -- and it had never been exercised once. It could not be: the arithmetic lived on a
 * class whose constructor takes over the process's {@code System.out} and expects a real terminal,
 * which left {@code InteractiveShell} at 3.8% line coverage with its scrolling entirely inside the
 * uncovered part. None of it needs a terminal, so it no longer has one.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>A page overlaps the previous screen by a row and is never zero, however small the terminal;
 * scrolling stops the transcript following its tail, and reaching the bottom again resumes it, which
 * is what makes paging down to the end behave like never having scrolled; the top is the first line
 * and no further; a scroll offset left over from a longer transcript is clamped rather than showing
 * a blank screen; and the figures the status bar reports are the ones the last frame actually
 * used.</p>
 */
public class TheConsoleRemembersWhereItIsScrolledTest {

    private final ShellConsoleView console = new ShellConsoleView();

    @Test
    public void aPageOverlapsThePreviousScreenByOneRow() {
        console.setViewportHeight(24);

        assertThat(console.page())
                .as("a row of overlap is what lets you carry your place across a page turn")
                .isEqualTo(23);
    }

    @Test
    public void aPageIsNeverZeroHoweverSmallTheTerminal() {
        console.setViewportHeight(1);
        assertThat(console.page()).isEqualTo(1);

        console.setViewportHeight(0);
        assertThat(console.page()).as("a zero-row viewport must not make paging a no-op").isEqualTo(1);

        console.setViewportHeight(-5);
        assertThat(console.page()).isEqualTo(1);
    }

    @Test
    public void aFreshConsoleFollowsTheNewestLine() {
        assertThat(console.isFollowingTail()).isTrue();
    }

    @Test
    public void scrollingUpStopsFollowingTheTail() {
        console.scrollLines(-3);

        assertThat(console.isFollowingTail()).isFalse();
    }

    @Test
    public void scrollingUpStopsAtTheFirstLine() {
        console.setViewportHeight(10);
        console.resolveScroll(100, 10); // 90 lines above the viewport, following the tail

        console.scrollLines(-1000);

        assertThat(console.resolveScroll(100, 10)).isZero();
    }

    @Test
    public void followingTheTailShowsTheEndOfTheTranscript() {
        assertThat(console.resolveScroll(100, 10))
                .as("100 lines in a 10-row window hides the first 90")
                .isEqualTo(90);
    }

    @Test
    public void aTranscriptShorterThanTheWindowDoesNotScroll() {
        assertThat(console.resolveScroll(4, 10)).isZero();
        assertThat(console.maxScroll()).isZero();
    }

    @Test
    public void aWindowOfNoRowsIsTreatedAsOne() {
        // Math.max(1, viewportRows) inside the resolution: a zero-height frame must not report a
        // scroll one line past the end of the transcript.
        assertThat(console.resolveScroll(10, 0)).isEqualTo(9);
    }

    @Test
    public void scrollingBackToTheBottomResumesFollowing() {
        console.setViewportHeight(10);
        console.resolveScroll(100, 10);

        console.scrollLines(-20);
        assertThat(console.isFollowingTail()).isFalse();
        assertThat(console.resolveScroll(100, 10)).isEqualTo(70);

        console.scrollLines(20);

        assertThat(console.resolveScroll(100, 10)).isEqualTo(90);
        assertThat(console.isFollowingTail())
                .as("paging down to the end must behave like never having scrolled")
                .isTrue();
    }

    @Test
    public void scrollingPastTheEndIsClampedByTheFrameAndResumesFollowing() {
        console.setViewportHeight(10);
        console.resolveScroll(100, 10);
        console.scrollLines(-50);

        console.scrollLines(10_000); // the draw is what knows where the end is

        assertThat(console.resolveScroll(100, 10)).isEqualTo(90);
        assertThat(console.isFollowingTail()).isTrue();
    }

    @Test
    public void anOffsetLeftOverFromALongerTranscriptIsClamped() {
        console.setViewportHeight(10);
        console.resolveScroll(500, 10);
        console.scrollLines(-100);
        assertThat(console.resolveScroll(500, 10)).isEqualTo(390);

        // The console was cleared and refilled with far less.
        assertThat(console.resolveScroll(12, 10))
                .as("a stale offset must not scroll past the end of what is there now")
                .isEqualTo(2);
    }

    @Test
    public void jumpingToTheTopStopsFollowing() {
        console.setViewportHeight(10);
        console.resolveScroll(100, 10);

        console.jumpToTop();

        assertThat(console.isFollowingTail()).isFalse();
        assertThat(console.resolveScroll(100, 10)).isZero();
    }

    @Test
    public void askingToFollowTheTailGoesBackToTheEnd() {
        console.setViewportHeight(10);
        console.jumpToTop();
        console.resolveScroll(100, 10);

        console.followTail();

        assertThat(console.resolveScroll(100, 10)).isEqualTo(90);
    }

    @Test
    public void freezingHoldsThePositionWithoutMovingIt() {
        console.setViewportHeight(10);
        console.resolveScroll(100, 10);
        console.scrollLines(-30);
        int before = console.resolveScroll(100, 10);

        console.freezeWhereItIs();

        assertThat(console.isFollowingTail()).isFalse();
        assertThat(console.resolveScroll(100, 10))
                .as("a drag must not move the anchor's row out from under it")
                .isEqualTo(before);
    }

    @Test
    public void theFiguresTheStatusBarShowsAreTheOnesTheLastFrameUsed() {
        console.setViewportHeight(10);
        console.resolveScroll(100, 10);
        console.scrollLines(-25);
        console.resolveScroll(100, 10);

        assertThat(console.scroll()).isEqualTo(65);
        assertThat(console.maxScroll()).isEqualTo(90);
    }

    @Test
    public void scrollingBeforeAnythingHasBeenDrawnHasNowhereToGo() {
        // The offset only means something once a frame has established where the bottom is: until
        // then it is zero, and zero is already the top.
        console.setViewportHeight(10);

        console.scrollLines(-25);

        assertThat(console.resolveScroll(100, 10)).isZero();
    }
}
