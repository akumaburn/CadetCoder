package com.eonmux.cadetcoder.commands;

import java.util.List;

/**
 * Where the shell's console currently is: how far the transcript is scrolled, what is focused, and
 * what the last drawn frame put on which row.
 *
 * <h2>Why this is an object and not fields on the shell</h2>
 *
 * <p>These sixteen values are read from the event path and written from the render path, and the
 * arithmetic that moves them is the shell's most reachable behaviour -- every key, every wheel
 * notch, every click goes through it. All of it lived inside a class whose constructor takes over
 * the process's {@code System.out} and expects a real terminal, so none of it could be asked a
 * question: at 3.8% line coverage the shell's scrolling and focus had never once been exercised. The
 * arithmetic does not need a terminal, so it does not have one here -- there is no TUI type in this
 * file, and the render path hands in plain numbers.</p>
 *
 * <h2>Why the render publishes a hit map</h2>
 *
 * <p>A click arrives as a terminal cell, and what is under it depends on how the last frame was laid
 * out and how far it was scrolled -- facts only the draw knows. So the draw records them, and the
 * click maps backwards through what was actually shown rather than through what the event path
 * guesses is shown now.</p>
 */
final class ShellConsoleView {

    /** No row, no segment, no worker. */
    static final int NOTHING = -1;

    /**
     * How much of the console one drag tick may scroll at full pull, as a divisor of its height.
     *
     * <p>A third. Proportionate rather than a fixed count, because the same number of lines is a
     * crawl on a tall terminal and a jump on a short one. A drag scrolls once per render tick, which
     * is every 100 milliseconds, so a thirty-row console moves a hundred lines a second at full
     * pull.</p>
     */
    private static final int DRAG_SCROLL_DIVISOR = 3;

    // ------------------------------------------------------------------ live transcript

    private          int     scrollOffset;
    private volatile boolean followBottom   = true;

    /**
     * Whether the position is being held where it is, overriding the resume-at-the-bottom rule.
     *
     * <p>Set only by {@link #freezeWhereItIs()}, which is called when a drag starts. Without it the
     * freeze could not survive one frame: a view frozen while at the bottom satisfies the
     * resume-follow test on the very next draw.</p>
     */
    private volatile boolean held;
    private volatile int     viewportHeight = 20;
    private volatile int     lastScroll;
    private volatile int     lastMaxScroll;

    // ------------------------------------------------------------------ focus mode

    private volatile boolean        focused;
    private volatile int            focusedSegment = NOTHING;
    private volatile BackgroundPane focusedPane;
    private          int            focusScroll;
    private volatile boolean        focusFollowTail;

    /** Which line of the overview is picked out, as an index into the rows it lists. */
    private volatile int overviewRow;

    // ------------------------------------------------------------------ what the last frame drew

    private volatile int[]        rowOwners;
    private volatile int          hitTop;
    private volatile int          hitLeft;
    private volatile int          hitWidth;
    private volatile int          hitHeight;
    private volatile int          hitScroll;
    private volatile List<String> documentLines = List.of();

    // ================================================================== live transcript

    /** How far the paging keys move, in lines: a screenful less one row of overlap. */
    int page() {
        return Math.max(1, viewportHeight - 1);
    }

    /**
     * Records how tall the console was in the frame just drawn, which is what a page is measured in.
     *
     * @param rows the content rows, before the heading
     */
    void setViewportHeight(int rows) {
        viewportHeight = Math.max(1, rows);
    }

    /**
     * Scrolls the live transcript, by a wheel notch or by a page.
     *
     * <p>Scrolling down is deliberately not clamped here: only the draw knows how many lines there
     * are, and it clamps -- and re-engages auto-follow when the bottom is reached, which is what
     * makes paging down to the end behave like never having scrolled at all.</p>
     *
     * @param deltaLines negative for up, positive for down
     */
    void scrollLines(int deltaLines) {
        followBottom = false;
        held         = false;
        scrollOffset = Math.max(0, scrollOffset + deltaLines);
    }

    /** Jumps to the oldest line and stops following. */
    void jumpToTop() {
        followBottom = false;
        held         = false;
        scrollOffset = 0;
    }

    /** Follows the newest line again, releasing any freeze. */
    void followTail() {
        followBottom = true;
        held         = false;
    }

    /**
     * Stops following the tail without moving, so streamed output cannot scroll the transcript out
     * from under a drag: the anchor's screen row must keep meaning the same document row, or the
     * release copies text the user never covered.
     */
    void freezeWhereItIs() {
        followBottom = false;
        // While following, every draw leaves scrollOffset sitting AT the bottom. Clearing the flag
        // alone therefore lasted exactly one frame: the next resolveScroll took the not-following
        // branch, found scrollOffset already equal to maxScroll, and re-armed follow -- in the one
        // situation the freeze exists for, a drag in the live tail-following view. Held instead
        // until the user chooses to follow again.
        held = true;
    }

    /**
     * Scrolls under a drag, without letting go of the position the drag froze.
     *
     * <p>Separate from {@link #scrollLines(int)}, which clears the hold. That is right for a wheel
     * notch, where the user is done with wherever they were, and wrong here: the drag is still
     * going, and a view that resumed following its newest line would move every row out from under
     * the anchor.</p>
     *
     * <p>The hit map is moved with the view rather than left for the next frame to correct. The far
     * end of the selection is resolved in the same event as the scroll, so a stale map would leave
     * the selection trailing the view by a tick for as long as the drag lasted, and finish that much
     * short of where the pointer was.</p>
     *
     * @param deltaLines negative for older lines, positive for newer ones
     * @return whether the view actually moved
     */
    boolean scrollWhileHeld(int deltaLines) {
        followBottom = false;
        held         = true;
        int moved = Math.max(0, Math.min(scrollOffset + deltaLines, lastMaxScroll));
        if (moved == scrollOffset) {
            return false;
        }
        scrollOffset = moved;
        hitScroll    = moved;
        return true;
    }

    /**
     * Stops holding the position, without deciding where it should go.
     *
     * <p>A view frozen while at the bottom resumes following on the next draw; one frozen after a
     * deliberate scroll-back stays where it is. Which of the two it was is already recorded in the
     * scroll offset, so nothing here has to remember it.</p>
     */
    void releaseHold() {
        held = false;
    }

    /** Whether the transcript is following its newest line. */
    boolean isFollowingTail() {
        return followBottom;
    }

    /** How far the last drawn frame was scrolled. */
    int scroll() {
        return lastScroll;
    }

    /** How far it could have been scrolled. */
    int maxScroll() {
        return lastMaxScroll;
    }

    /**
     * Settles where the live transcript is scrolled to, for a frame of this size.
     *
     * @param totalLines  how many lines the transcript rendered to
     * @param viewportRows how many rows are available to show them
     * @return the number of lines hidden above the top
     */
    int resolveScroll(int totalLines, int viewportRows) {
        int maxScroll = Math.max(0, totalLines - Math.max(1, viewportRows));
        int scroll;
        if (followBottom) {
            scroll = maxScroll;
        } else {
            scroll = Math.max(0, Math.min(scrollOffset, maxScroll));
            // Reaching the bottom resumes auto-follow -- unless the position is being HELD, which
            // means it was frozen while sitting at the bottom and re-arming here would undo it
            // immediately. A held view follows again only when something asks it to.
            if (scroll >= maxScroll && !held) {
                followBottom = true;
            }
        }
        scrollOffset  = scroll;
        lastScroll    = scroll;
        lastMaxScroll = maxScroll;
        return scroll;
    }

    // ================================================================== focus mode

    /** Whether one result or worker fills the console instead of the whole transcript. */
    boolean isFocused() {
        return focused;
    }

    /** The transcript segment being shown, or {@link #NOTHING}. */
    int focusedSegment() {
        return focusedSegment;
    }

    /** The piece of background work being shown, or {@code null} when a result is. */
    BackgroundPane focusedPane() {
        return focusedPane;
    }

    /** Which line of the overview is picked out. */
    int overviewRow() {
        return overviewRow;
    }

    /**
     * Shows the list of everything running instead of the transcript.
     *
     * <p>Opened on its own key rather than by cycling, and always at the first line: it is a place
     * to look from, and a list that remembered where you last were would answer a different
     * question each time it was opened.</p>
     *
     * @return whether anything changed
     */
    boolean openOverview() {
        boolean wasShowing = focused && focusedPane != null && focusedPane.isOverview();
        focused         = true;
        focusedPane     = BackgroundPane.overview();
        focusedSegment  = NOTHING;
        focusScroll     = 0;
        focusFollowTail = false;
        overviewRow     = 0;
        return !wasShowing;
    }

    /**
     * Moves the overview's picked-out line.
     *
     * <p>Stops at each end rather than wrapping. A list of running work is read top to bottom, and
     * a selection that jumps from the last line to the first looks like it did not move at all.</p>
     *
     * @param direction -1 for up, 1 for down
     * @param rows      how many lines the overview has
     * @return whether the line moved
     */
    boolean moveOverviewRow(int direction, int rows) {
        if (rows <= 0) {
            overviewRow = 0;
            return false;
        }
        int moved = Math.max(0, Math.min(rows - 1, overviewRow + direction));
        if (moved == overviewRow) {
            return false;
        }
        overviewRow = moved;
        return true;
    }

    /** Whether the focused region is following its newest line. */
    boolean isFocusFollowingTail() {
        return focusFollowTail;
    }

    /**
     * Focuses a piece of background work, or moves to the next or previous one.
     *
     * <p>Refuses when there is none: there is nothing to cycle, and silently focusing something
     * else would make one key mean two things depending on history. Work that is still going is
     * worth watching at its newest line, so focus starts at the tail -- the opposite of a finished
     * result, where the top is where you start reading.</p>
     *
     * <p>The overview is not a stop on this walk, so cycling from it goes to the first pane rather
     * than to whatever happens to come after a list.</p>
     *
     * @param direction negative for the previous pane, positive for the next
     * @param panes     every worker then every job, in order
     * @return whether there was anything to focus
     */
    boolean enterOrMoveFocus(int direction, List<BackgroundPane> panes) {
        if (panes == null || panes.isEmpty()) {
            return false;
        }
        int at = focused && focusedPane != null ? panes.indexOf(focusedPane) : -1;
        focusedPane     = at < 0 ? panes.get(0) : panes.get(Math.floorMod(at + direction,
                                                                         panes.size()));
        focused         = true;
        focusedSegment  = NOTHING;
        focusScroll     = 0;
        focusFollowTail = true;
        return true;
    }

    /**
     * Shows one named piece of background work, whichever was being shown before.
     *
     * @param pane what to show
     */
    void focusOn(BackgroundPane pane) {
        if (pane == null) {
            return;
        }
        focused         = true;
        focusedPane     = pane;
        focusedSegment  = NOTHING;
        focusScroll     = 0;
        focusFollowTail = true;
    }

    /**
     * Returns to the live transcript.
     *
     * @return whether anything changed
     */
    boolean exitFocus() {
        if (!focused) {
            return false;
        }
        focused      = false;
        focusedPane  = null;
        followBottom = true;
        held         = false;
        return true;
    }

    /** The focused segment is no longer in the transcript, so there is nothing to show. */
    void loseFocus() {
        focused = false;
    }

    /** The focused work is no longer there, so there is nothing to show. */
    void forgetPane() {
        focusedPane = null;
    }

    /**
     * Scrolls the focused region by lines.
     *
     * @param deltaLines negative for up, positive for down
     */
    void focusScrollBy(int deltaLines) {
        focusFollowTail = false;
        focusScroll     = Math.max(0, focusScroll + deltaLines); // the draw clamps to content height
    }

    /** Jumps the focused region to its first line and stops following. */
    void focusTop() {
        focusScroll     = 0;
        focusFollowTail = false;
    }

    /** Follows the focused region's newest line again. */
    void focusTail() {
        focusFollowTail = true;
    }

    /**
     * Settles where the focused region is scrolled to, for a frame of this size.
     *
     * <p>One arithmetic for both focused regions: a result and a worker had a copy each, identical
     * line for line, so a change to how focus scrolls had to be made twice to be made at all.</p>
     *
     * @param totalLines   how many lines the region rendered to
     * @param viewportRows how many rows are available to show them
     * @return the number of lines hidden above the top
     */
    int resolveFocusScroll(int totalLines, int viewportRows) {
        int maxScroll = Math.max(0, totalLines - Math.max(1, viewportRows));
        focusScroll = focusFollowTail ? maxScroll : Math.max(0, Math.min(focusScroll, maxScroll));
        return focusScroll;
    }

    /** Forgets what was focused, for a console that has been emptied. */
    void reset() {
        focused        = false;
        focusedSegment = NOTHING;
        focusedPane    = null;
        followBottom   = true;
        held           = false;
    }

    // ================================================================== what the last frame drew

    /**
     * Records what the live transcript just drew, so a click or a drag can be mapped back to it.
     *
     * <p>The scroll is floored at zero here rather than in each reader: a frame that hid a negative
     * number of lines is not a frame, and a row resolved from one would index behind the start of
     * the document -- differently in each reader, since only one of them bounds-checks.</p>
     *
     * @param lines     the raw text of every rendered line, in order
     * @param owners    the transcript segment owning each of those lines
     * @param top       the content area's first terminal row
     * @param left      its first terminal column
     * @param width     its width in columns
     * @param height    its height in rows
     * @param scroll    how many lines were hidden above the top
     */
    void publish(List<String> lines, int[] owners, int top, int left, int width, int height,
                 int scroll) {
        documentLines = lines == null ? List.of() : lines;
        rowOwners     = owners;
        hitTop        = top;
        hitLeft       = left;
        hitWidth      = width;
        hitHeight     = height;
        hitScroll     = Math.max(0, scroll);
    }

    /** The raw text of every line the last live frame drew. */
    List<String> documentLines() {
        return documentLines;
    }

    /**
     * The document row under a terminal row, clamped to the content.
     *
     * <p>An empty console has no rows, so a click in one lands on nothing. It used to land on row
     * zero: {@code Math.max(0, size() - 1)} floors an empty document's last row at zero rather than
     * at "there is none", so a click on the blank console after {@code Ctrl+L} began a selection
     * over no text and, with it, froze auto-follow. Nothing on screen said why, and the next output
     * to arrive scrolled past unseen -- which reads exactly like a hang.</p>
     *
     * @param y the terminal row
     * @return the row, or {@link #NOTHING} when the cell is outside the console or it is empty
     */
    int documentRowAt(int y) {
        if (rowOwners == null || documentLines.isEmpty() || y < hitTop || y >= hitTop + hitHeight) {
            return NOTHING;
        }
        return Math.min(hitScroll + (y - hitTop), documentLines.size() - 1);
    }

    /**
     * The document row a drag at this terminal row is pointing at, clamped into the visible window.
     *
     * <p>Where {@link #documentRowAt(int)} answers "nothing" outside the console, this answers with
     * the nearest row on screen. A drag is allowed to leave the console -- the terminal keeps
     * reporting it anywhere in its window while the button is down -- and the far end of the
     * selection has to keep moving when it does, or pulling past the edge selects nothing further
     * however far the view scrolls.</p>
     *
     * @param y the terminal row
     * @return the row, or {@link #NOTHING} when there is no document
     */
    int documentRowNear(int y) {
        if (rowOwners == null || documentLines.isEmpty()) {
            return NOTHING;
        }
        int offset = Math.max(0, Math.min(y - hitTop, Math.max(0, hitHeight - 1)));
        return Math.min(hitScroll + offset, documentLines.size() - 1);
    }

    /**
     * How far a drag at this terminal row asks the transcript to move.
     *
     * <p>The first and last rows of the console are the trigger, and the pull grows with the
     * distance past them. That distance is the only thing the user can vary: the terminal reports a
     * drag anywhere in its window, so the pointer can sit several rows below the console over the
     * input line and the status bar, but no further.</p>
     *
     * @param y the terminal row
     * @return lines to scroll, negative for older lines; {@code 0} to stay where it is
     */
    int dragScrollLines(int y) {
        if (rowOwners == null || documentLines.isEmpty() || hitHeight <= 0) {
            return 0;
        }
        int last = hitTop + hitHeight - 1;
        if (y <= hitTop) {
            return -Math.min(1 + (hitTop - y), maxDragScrollLines());
        }
        if (y >= last) {
            return Math.min(1 + (y - last), maxDragScrollLines());
        }
        return 0;
    }

    /**
     * The most lines one drag tick may scroll.
     *
     * @return the cap, never below one line
     */
    int maxDragScrollLines() {
        return Math.max(1, viewportHeight / DRAG_SCROLL_DIVISOR);
    }

    /**
     * The document column under a terminal column.
     *
     * @param x the terminal column
     * @return the column, never negative
     */
    int documentColumnAt(int x) {
        int column = x - hitLeft;
        return column < 0 ? 0 : column;
    }

    /**
     * Focuses the result under a terminal cell, if there is one.
     *
     * <p>Clicking a result focuses that result rather than a worker, and starts at the top: a
     * finished result is read from its beginning.</p>
     *
     * @param x the terminal column
     * @param y the terminal row
     * @return whether a result was focused
     */
    boolean focusSegmentAt(int x, int y) {
        int[] owners = rowOwners;
        if (owners == null) {
            return false;
        }
        if (x < hitLeft || x >= hitLeft + hitWidth || y < hitTop || y >= hitTop + hitHeight) {
            return false;
        }
        int row = hitScroll + (y - hitTop);
        if (row >= owners.length) {
            return false;
        }
        int segment = owners[row];
        if (segment < 0) {
            return false;
        }
        focused         = true;
        focusedSegment  = segment;
        focusedPane     = null;
        focusScroll     = 0;
        focusFollowTail = false;
        return true;
    }
}
