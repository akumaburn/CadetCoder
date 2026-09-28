package com.eonmux.cadetcoder.commands;

import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.tui.event.MouseEventKind;

/**
 * What a mouse report means to the shell.
 *
 * <h2>Why a press and a release are not symmetrical</h2>
 *
 * <p>A press cannot know whether it begins a drag or is a click, so it always anchors a selection
 * and the release decides which it was: an empty selection means the pointer never moved, and that
 * is a click on whatever was under the press. Written as one chain inside the event loop this read
 * as a press that started something the release sometimes threw away, and the case the user
 * actually meets -- click a result to focus it -- was the branch furthest from the top.</p>
 *
 * <h2>What select mode changes</h2>
 *
 * <p>Only what a click means. Dragging selects whether or not the mode is on, so the mode's job is
 * to stop a click that never moved from opening the result under it. Somebody who pressed F4 is
 * about to drag, and a mis-click that jumped into a result would throw that away.</p>
 */
final class ShellPointer {

    /** How far one wheel notch scrolls. */
    static final int WHEEL_LINES = 3;

    /** What a mouse report asks for. */
    enum Gesture {
        /** Not ours: let it pass. */
        NONE,
        /** Ours, but nothing to do with it. */
        CONSUME,
        SCROLL_UP,
        SCROLL_DOWN,
        /** Anchor a selection where the button went down. */
        BEGIN_SELECT,
        /** Move the far end of the selection to where the pointer now is. */
        EXTEND_SELECT,
        /** The pointer never moved: focus whatever is under where the button went down. */
        CLICK,
        /** Copy what was selected, and leave it highlighted. */
        COPY_SELECTION,
        /**
         * End the drag with nothing selected and nothing opened.
         *
         * <p>Two reasons reach it. Something else took the input line mid-drag, so the rows the
         * anchor was taken against are no longer the rows on screen. Or the pointer never moved
         * while select mode was on, where a click is a selection of nothing rather than a request to
         * open the result underneath.</p>
         */
        ABANDON_SELECT
    }

    /**
     * The part of the shell's state a mouse report's meaning depends on.
     *
     * @param selectMode      whether the mouse has been given over to selecting
     * @param prompting       whether a running command is waiting for an answer
     * @param focused         whether a single result is filling the console
     * @param selecting       whether a drag is in progress
     * @param emptySelection  whether the drag has so far selected nothing
     */
    record State(boolean selectMode, boolean prompting, boolean focused, boolean selecting,
                 boolean emptySelection) {
    }

    private ShellPointer() {
    }

    /**
     * What a mouse report means.
     *
     * @param m     the report
     * @param state what else is going on
     * @return the gesture
     */
    static Gesture classify(MouseEvent m, State state) {
        MouseEventKind kind = m.kind();
        if (kind == MouseEventKind.SCROLL_UP) {
            return Gesture.SCROLL_UP;
        }
        if (kind == MouseEventKind.SCROLL_DOWN) {
            return Gesture.SCROLL_DOWN;
        }
        if (kind == MouseEventKind.PRESS && m.isLeftButton()) {
            // Drag-to-select and click-to-focus are both about the live transcript: there is nothing
            // to focus while one result already fills the console, and nothing to select while the
            // input line belongs to a command's question.
            return state.prompting() || state.focused() ? Gesture.CONSUME : Gesture.BEGIN_SELECT;
        }
        if (kind == MouseEventKind.DRAG && state.selecting()) {
            return Gesture.EXTEND_SELECT;
        }
        if (kind == MouseEventKind.RELEASE && state.selecting()) {
            if (state.prompting()) {
                return Gesture.ABANDON_SELECT;
            }
            if (!state.emptySelection()) {
                return Gesture.COPY_SELECTION;
            }
            // Not CONSUME: the release has to end the drag, or the next tick goes on scrolling a
            // selection the user has already let go of.
            return state.selectMode() ? Gesture.ABANDON_SELECT : Gesture.CLICK;
        }
        return Gesture.NONE;
    }
}
