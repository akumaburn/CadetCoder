package com.eonmux.cadetcoder.commands;

import org.junit.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Focus mode: which piece of background work Tab shows, where its scroll starts, and how the
 * console gets back to the live transcript.
 *
 * <h2>Why this is tested</h2>
 *
 * <p>Tab is how background work is watched at all. A worker's output is collected rather than
 * streamed, so while it runs the transcript says nothing about it; a job's output belongs to
 * another process entirely. Both {@code workers show <n>} and {@code job output <id>} need a free
 * prompt, which during a blocking run means interrupting the very thing you wanted to watch. None
 * of that behaviour had a test, because it lived on a class that cannot be constructed without a
 * terminal.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>Tab does nothing when nothing is running, rather than quietly focusing something else and
 * making one key mean two things depending on history; the first press lands on the first pane as
 * the registries list them; the walk runs through every worker and then every job; cycling wraps in
 * both directions; work that has since vanished does not strand the cycle; a focused pane starts at
 * its <em>newest</em> line, because it is still being written, while a result clicked in the
 * transcript starts at its first, because it is finished; and leaving focus resumes following the
 * live tail.</p>
 */
public class TabCyclesTheWorkersAndJobsTest {

    private static final List<BackgroundPane> THREE_WORKERS =
            List.of(BackgroundPane.worker(1), BackgroundPane.worker(2), BackgroundPane.worker(3));

    private static final List<BackgroundPane> TWO_WORKERS_TWO_JOBS =
            List.of(BackgroundPane.worker(1), BackgroundPane.worker(2),
                    BackgroundPane.job("j1"), BackgroundPane.job("j2"));

    private final ShellConsoleView console = new ShellConsoleView();

    @Test
    public void withNoWorkersTabRefusesAndChangesNothing() {
        assertThat(console.enterOrMoveFocus(1, List.of())).isFalse();
        assertThat(console.enterOrMoveFocus(1, null)).isFalse();

        assertThat(console.isFocused()).isFalse();
        assertThat(console.focusedPane()).isNull();
    }

    @Test
    public void theFirstPressLandsOnTheFirstWorker() {
        assertThat(console.enterOrMoveFocus(1, THREE_WORKERS)).isTrue();

        assertThat(console.isFocused()).isTrue();
        assertThat(console.focusedPane()).isEqualTo(BackgroundPane.worker(1));
    }

    /** The whole point of one walk: a job is reached by carrying on past the last worker. */
    @Test
    public void thewalkCarriesOnFromTheLastWorkerIntoTheJobs() {
        console.enterOrMoveFocus(1, TWO_WORKERS_TWO_JOBS);
        console.enterOrMoveFocus(1, TWO_WORKERS_TWO_JOBS);
        assertThat(console.focusedPane()).isEqualTo(BackgroundPane.worker(2));

        console.enterOrMoveFocus(1, TWO_WORKERS_TWO_JOBS);
        assertThat(console.focusedPane()).isEqualTo(BackgroundPane.job("j1"));

        console.enterOrMoveFocus(1, TWO_WORKERS_TWO_JOBS);
        assertThat(console.focusedPane()).isEqualTo(BackgroundPane.job("j2"));

        console.enterOrMoveFocus(1, TWO_WORKERS_TWO_JOBS);
        assertThat(console.focusedPane()).isEqualTo(BackgroundPane.worker(1));
    }

    @Test
    public void thewalkGoesBackwardsFromTheFirstWorkerIntoTheJobs() {
        console.enterOrMoveFocus(1, TWO_WORKERS_TWO_JOBS);

        console.enterOrMoveFocus(-1, TWO_WORKERS_TWO_JOBS);

        assertThat(console.focusedPane()).isEqualTo(BackgroundPane.job("j2"));
    }

    @Test
    public void theFirstPressLandsOnTheFirstWorkerGoingBackwardsToo() {
        // There is nowhere to go "back" from before the first press, so direction cannot matter.
        assertThat(console.enterOrMoveFocus(-1, THREE_WORKERS)).isTrue();

        assertThat(console.focusedPane()).isEqualTo(BackgroundPane.worker(1));
    }

    @Test
    public void cyclingForwardWrapsRoundToTheFirst() {
        console.enterOrMoveFocus(1, THREE_WORKERS);

        console.enterOrMoveFocus(1, THREE_WORKERS);
        assertThat(console.focusedPane()).isEqualTo(BackgroundPane.worker(2));

        console.enterOrMoveFocus(1, THREE_WORKERS);
        assertThat(console.focusedPane()).isEqualTo(BackgroundPane.worker(3));

        console.enterOrMoveFocus(1, THREE_WORKERS);
        assertThat(console.focusedPane()).isEqualTo(BackgroundPane.worker(1));
    }

    @Test
    public void cyclingBackwardWrapsRoundToTheLast() {
        console.enterOrMoveFocus(1, THREE_WORKERS);

        console.enterOrMoveFocus(-1, THREE_WORKERS);

        assertThat(console.focusedPane())
                .as("a negative step must wrap, not fall off the front of the list")
                .isEqualTo(BackgroundPane.worker(3));
    }

    @Test
    public void aWorkerThatHasLeftTheRunDoesNotStrandTheCycle() {
        console.enterOrMoveFocus(1, List.of(BackgroundPane.worker(7), BackgroundPane.worker(8)));
        assertThat(console.focusedPane()).isEqualTo(BackgroundPane.worker(7));

        // A new run replaced the old one; 7 is no longer one of its workers.
        console.enterOrMoveFocus(1, THREE_WORKERS);

        assertThat(console.focusedPane()).isEqualTo(BackgroundPane.worker(1));
    }

    @Test
    public void aFocusedWorkerStartsAtItsNewestLine() {
        console.enterOrMoveFocus(1, THREE_WORKERS);

        assertThat(console.isFocusFollowingTail())
                .as("a worker still writing is worth watching where it is writing")
                .isTrue();
        assertThat(console.resolveFocusScroll(100, 10)).isEqualTo(90);
    }

    @Test
    public void focusingAWorkerForgetsAnyFocusedResult() {
        console.publish(List.of("a", "b"), new int[] {5, 5}, 0, 0, 20, 2, 0);
        console.focusSegmentAt(0, 0);
        assertThat(console.focusedSegment()).isEqualTo(5);

        console.enterOrMoveFocus(1, THREE_WORKERS);

        assertThat(console.focusedSegment()).isEqualTo(ShellConsoleView.NOTHING);
        assertThat(console.focusedPane()).isEqualTo(BackgroundPane.worker(1));
    }

    @Test
    public void aClickedResultStartsAtItsFirstLine() {
        console.publish(List.of("a", "b"), new int[] {5, 5}, 0, 0, 20, 2, 0);

        console.focusSegmentAt(0, 1);

        assertThat(console.isFocusFollowingTail())
                .as("a finished result is read from the beginning")
                .isFalse();
        assertThat(console.resolveFocusScroll(100, 10)).isZero();
    }

    @Test
    public void leavingFocusResumesFollowingTheLiveTail() {
        console.setViewportHeight(10);
        console.jumpToTop();
        console.enterOrMoveFocus(1, THREE_WORKERS);

        assertThat(console.exitFocus()).isTrue();

        assertThat(console.isFocused()).isFalse();
        assertThat(console.focusedPane()).isNull();
        assertThat(console.isFollowingTail())
                .as("coming back to the live view should show what has happened since")
                .isTrue();
    }

    @Test
    public void leavingFocusWhenNotFocusedChangesNothing() {
        console.jumpToTop();

        assertThat(console.exitFocus()).isFalse();
        assertThat(console.isFollowingTail())
                .as("Esc in the live view must not undo a deliberate scroll back")
                .isFalse();
    }

    @Test
    public void scrollingTheFocusedRegionStopsItFollowing() {
        console.enterOrMoveFocus(1, THREE_WORKERS);
        console.resolveFocusScroll(100, 10);

        console.focusScrollBy(-5);

        assertThat(console.isFocusFollowingTail()).isFalse();
        assertThat(console.resolveFocusScroll(100, 10)).isEqualTo(85);
    }

    @Test
    public void theFocusedRegionStopsAtItsFirstLine() {
        console.enterOrMoveFocus(1, THREE_WORKERS);
        console.resolveFocusScroll(100, 10);

        console.focusScrollBy(-1000);

        assertThat(console.resolveFocusScroll(100, 10)).isZero();
    }

    @Test
    public void theFocusedRegionIsClampedToWhatItActuallyRenderedTo() {
        console.enterOrMoveFocus(1, THREE_WORKERS);
        console.resolveFocusScroll(500, 10);
        console.focusScrollBy(-10);
        assertThat(console.resolveFocusScroll(500, 10)).isEqualTo(480);

        // The worker's output is re-rendered narrower, or a shorter result is focused instead.
        assertThat(console.resolveFocusScroll(15, 10)).isEqualTo(5);
    }

    @Test
    public void goingToTheTopAndTheTailOfTheFocusedRegion() {
        console.enterOrMoveFocus(1, THREE_WORKERS);

        console.focusTop();
        assertThat(console.isFocusFollowingTail()).isFalse();
        assertThat(console.resolveFocusScroll(100, 10)).isZero();

        console.focusTail();
        assertThat(console.isFocusFollowingTail()).isTrue();
        assertThat(console.resolveFocusScroll(100, 10)).isEqualTo(90);
    }

    @Test
    public void aFocusedRegionShorterThanTheWindowDoesNotScroll() {
        console.enterOrMoveFocus(1, THREE_WORKERS);

        assertThat(console.resolveFocusScroll(3, 10)).isZero();
    }

    @Test
    public void aFocusedSegmentThatVanishedGivesUpFocus() {
        console.publish(List.of("a"), new int[] {5}, 0, 0, 20, 1, 0);
        console.focusSegmentAt(0, 0);

        console.loseFocus();

        assertThat(console.isFocused()).isFalse();
    }

    @Test
    public void workThatIsGoneLeavesFocusWithNothingToShow() {
        console.enterOrMoveFocus(1, THREE_WORKERS);

        console.forgetPane();

        assertThat(console.focusedPane()).isNull();
    }

    @Test
    public void pagingScrollsByOneScreenLessTheOverlapRow() {
        console.setViewportHeight(10);
        console.resolveScroll(100, 10);

        console.scrollLines(-console.page());

        assertThat(console.resolveScroll(100, 10)).isEqualTo(81);
    }

    @Test
    public void clearingTheConsoleForgetsWhatWasFocused() {
        console.enterOrMoveFocus(1, THREE_WORKERS);
        console.jumpToTop();

        console.reset();

        assertThat(console.isFocused()).isFalse();
        assertThat(console.focusedPane()).isNull();
        assertThat(console.focusedSegment()).isEqualTo(ShellConsoleView.NOTHING);
        assertThat(console.isFollowingTail()).isTrue();
    }
}
