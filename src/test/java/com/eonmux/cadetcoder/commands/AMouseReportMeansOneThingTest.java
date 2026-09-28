package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.commands.ShellPointer.Gesture;
import com.eonmux.cadetcoder.commands.ShellPointer.State;

import dev.tamboui.tui.event.MouseButton;
import dev.tamboui.tui.event.MouseEvent;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the shell does with a mouse report.
 *
 * <h2>The defect</h2>
 *
 * <p>Press, drag and release were one chain inside the event loop, each branch deciding and acting
 * at once against five mutable fields. The case a user meets first -- click a result to open it --
 * was the last branch of the last condition, reachable only through a release that found the
 * selection empty, and none of it could be asked a question without a terminal.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>That a press always anchors and the release decides what it was, so a click is a drag that
 * never moved; that select mode gives up the mouse completely, rather than acting on reports that
 * arrive after the handover and starting an invisible selection under the terminal's own; that a
 * press is swallowed rather than acted on where there is nothing to select -- while a command is
 * asking a question, or while one result already fills the console; and that a prompt opening
 * mid-drag abandons the selection instead of copying whatever happened to be under it.</p>
 */
public class AMouseReportMeansOneThingTest {

    private static final State LIVE      = new State(false, false, false, false, true);
    private static final State DRAGGING  = new State(false, false, false, true, false);
    private static final State NO_DRAG   = new State(false, false, false, true, true);

    private static MouseEvent press()   { return MouseEvent.press(MouseButton.LEFT, 4, 9); }
    private static MouseEvent drag()    { return MouseEvent.drag(MouseButton.LEFT, 6, 11); }
    private static MouseEvent release() { return MouseEvent.release(MouseButton.LEFT, 6, 11); }

    @Test
    public void theWheelScrollsWhicheverWayItWasTurned() {
        assertThat(ShellPointer.classify(MouseEvent.scrollUp(2, 2), LIVE))
                .isEqualTo(Gesture.SCROLL_UP);
        assertThat(ShellPointer.classify(MouseEvent.scrollDown(2, 2), LIVE))
                .isEqualTo(Gesture.SCROLL_DOWN);
    }

    @Test
    public void aPressAnchorsASelection() {
        assertThat(ShellPointer.classify(press(), LIVE)).isEqualTo(Gesture.BEGIN_SELECT);
    }

    @Test
    public void aDragMovesTheFarEnd() {
        assertThat(ShellPointer.classify(drag(), DRAGGING)).isEqualTo(Gesture.EXTEND_SELECT);
    }

    @Test
    public void aDragWithNothingAnchoredIsNotOurs() {
        // Without an anchor there is no far end to move, and a report can arrive from a button that
        // went down before the shell was looking.
        assertThat(ShellPointer.classify(drag(), LIVE)).isEqualTo(Gesture.NONE);
    }

    @Test
    public void aReleaseAfterARealDragCopies() {
        assertThat(ShellPointer.classify(release(), DRAGGING)).isEqualTo(Gesture.COPY_SELECTION);
    }

    @Test
    public void aReleaseThatNeverMovedIsAClick() {
        // This is the whole reason a press anchors unconditionally: at press time there is no way to
        // know which of the two it will turn out to be.
        assertThat(ShellPointer.classify(release(), NO_DRAG)).isEqualTo(Gesture.CLICK);
    }

    @Test
    public void aReleaseWithNothingAnchoredIsNotOurs() {
        assertThat(ShellPointer.classify(release(), LIVE)).isEqualTo(Gesture.NONE);
    }

    @Test
    public void aPromptOpeningMidDragAbandonsTheSelection() {
        // Rather than copying: the rows the anchor was taken against belong to the view that has
        // just been replaced, so whatever is under them now is not what was highlighted.
        State prompted = new State(false, true, false, true, false);

        assertThat(ShellPointer.classify(release(), prompted)).isEqualTo(Gesture.ABANDON_SELECT);
    }

    @Test
    public void selectModeStillSelects() {
        State selecting = new State(true, false, false, false, true);
        State dragging  = new State(true, false, false, true, false);

        assertThat(ShellPointer.classify(press(), selecting)).isEqualTo(Gesture.BEGIN_SELECT);
        assertThat(ShellPointer.classify(drag(), dragging)).isEqualTo(Gesture.EXTEND_SELECT);
        assertThat(ShellPointer.classify(release(), dragging)).isEqualTo(Gesture.COPY_SELECTION);
    }

    @Test
    public void selectModeDoesNotOpenAResultUnderAMisclick() {
        // The whole point of the mode is that the mouse is doing one job. A click that never moved
        // is a selection of nothing, and jumping into a result instead would throw away the drag the
        // user was about to start.
        //
        // It still ENDS the drag. A release that only swallowed itself would leave the shell
        // believing a button was down, and a drag held at an edge scrolls on every tick for as long
        // as that is believed.
        State missed = new State(true, false, false, true, true);

        assertThat(ShellPointer.classify(release(), missed)).isEqualTo(Gesture.ABANDON_SELECT);
    }

    @Test
    public void theWheelStillScrollsInSelectMode() {
        State selecting = new State(true, false, false, false, true);

        assertThat(ShellPointer.classify(MouseEvent.scrollUp(2, 2), selecting))
                .isEqualTo(Gesture.SCROLL_UP);
    }

    @Test
    public void aPressIsSwallowedWhereThereIsNothingToSelect() {
        State prompting = new State(false, true, false, false, true);
        State focused   = new State(false, false, true, false, true);

        // Swallowed rather than ignored: the click landed on this application's window, and letting
        // it through would be reported twice.
        assertThat(ShellPointer.classify(press(), prompting)).isEqualTo(Gesture.CONSUME);
        assertThat(ShellPointer.classify(press(), focused)).isEqualTo(Gesture.CONSUME);
    }

    @Test
    public void theWheelStillWorksWhileAResultIsFocused() {
        // Focus mode is for reading one result, so scrolling it is the one thing the mouse must
        // still do there.
        State focused = new State(false, false, true, false, true);

        assertThat(ShellPointer.classify(MouseEvent.scrollDown(2, 2), focused))
                .isEqualTo(Gesture.SCROLL_DOWN);
    }

    @Test
    public void aButtonThatIsNotTheLeftOneIsNotOurs() {
        assertThat(ShellPointer.classify(MouseEvent.press(MouseButton.RIGHT, 4, 9), LIVE))
                .isEqualTo(Gesture.NONE);
        assertThat(ShellPointer.classify(MouseEvent.press(MouseButton.MIDDLE, 4, 9), LIVE))
                .isEqualTo(Gesture.NONE);
    }

    @Test
    public void movingWithNoButtonDownIsNotOurs() {
        assertThat(ShellPointer.classify(MouseEvent.move(3, 3), LIVE)).isEqualTo(Gesture.NONE);
        assertThat(ShellPointer.classify(MouseEvent.move(3, 3), DRAGGING)).isEqualTo(Gesture.NONE);
    }
}
