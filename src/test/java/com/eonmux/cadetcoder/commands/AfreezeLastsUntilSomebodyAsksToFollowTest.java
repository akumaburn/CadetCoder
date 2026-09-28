package com.eonmux.cadetcoder.commands;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A view frozen for a drag stays frozen until something asks it to follow again.
 *
 * <p><b>The defect</b>: freezing cleared the follow flag and nothing else. While following, every
 * frame leaves the scroll sitting exactly at the bottom -- so the very next frame took the
 * not-following branch, saw it was already at the bottom, and turned following back on. The freeze
 * lasted one frame, in precisely the situation it exists for: a drag in the live tail view, where
 * new output then scrolled the text out from under the selection.</p>
 */
class AfreezeLastsUntilSomebodyAsksToFollowTest {

    private static ShellConsoleView following() {
        ShellConsoleView view = new ShellConsoleView();
        view.followTail();
        view.resolveScroll(100, 10);
        return view;
    }

    @Test
    void aviewThatWasNeverFrozenFollowsItsNewestLine() {
        ShellConsoleView view = following();

        view.resolveScroll(120, 10);

        assertThat(view.isFollowingTail()).isTrue();
        assertThat(view.scroll()).isEqualTo(110);
    }

    @Test
    void afreezeSurvivesTheNextFrame() {
        ShellConsoleView view = following();

        view.freezeWhereItIs();
        view.resolveScroll(100, 10);

        assertThat(view.isFollowingTail())
                .as("the drag is still going on; nothing asked to follow again")
                .isFalse();
    }

    @Test
    void afrozenViewDoesNotChaseNewOutput() {
        ShellConsoleView view = following();
        int wasAt = view.scroll();

        view.freezeWhereItIs();
        view.resolveScroll(400, 10);

        assertThat(view.isFollowingTail()).isFalse();
        assertThat(view.scroll())
                .as("the anchor's screen row still means the document row it did")
                .isEqualTo(wasAt);
    }

    @Test
    void releasingTheHoldLetsAviewAtTheBottomFollowAgain() {
        ShellConsoleView view = following();
        view.freezeWhereItIs();
        view.resolveScroll(100, 10);

        view.releaseHold();
        view.resolveScroll(100, 10);

        assertThat(view.isFollowingTail()).isTrue();
    }

    @Test
    void releasingTheHoldAfterScrollingBackLeavesItWhereItIs() {
        ShellConsoleView view = following();
        view.scrollLines(-40);
        view.freezeWhereItIs();
        view.resolveScroll(100, 10);

        view.releaseHold();
        view.resolveScroll(100, 10);

        assertThat(view.isFollowingTail())
                .as("it was not at the bottom, so there is nothing to resume")
                .isFalse();
        assertThat(view.scroll()).isEqualTo(50);
    }
}
