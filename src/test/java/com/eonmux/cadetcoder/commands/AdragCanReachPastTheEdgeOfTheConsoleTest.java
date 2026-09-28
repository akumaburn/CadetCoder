package com.eonmux.cadetcoder.commands;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Selecting more than one screenful by dragging to the top or bottom edge.
 *
 * <h2>The defect</h2>
 *
 * <p>A drag could only ever cover the rows on screen when the button went down. Two things stopped
 * it. The pointer row was resolved by {@code documentRowAt}, which answers "nothing" for a row
 * outside the console, so pulling past the edge moved the far end of the selection no further. The
 * view was also frozen for the length of the drag, so nothing scrolled the rest of the transcript
 * into reach.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>That a pointer on the first or last row of the console asks the transcript to move, and asks
 * harder the further past the edge it goes; that the far end of the selection resolves to the
 * nearest visible row instead of nothing, so a drag into the status bar still selects; that a drag's
 * scroll keeps the hold the drag put on the view, because releasing it would let the tail snap back
 * and remap every row under the anchor; and that the scroll and the far end move together, so the
 * selection does not trail the view by a tick.</p>
 */
public class AdragCanReachPastTheEdgeOfTheConsoleTest {

    private static final int TOP    = 5;   // first terminal row of the console
    private static final int LEFT   = 2;
    private static final int HEIGHT = 10;  // rows on screen
    private static final int WIDTH  = 40;

    private final ShellConsoleView console = new ShellConsoleView();

    /**
     * A console showing {@code HEIGHT} rows of a {@code lines}-line document, scrolled to
     * {@code scroll}.
     *
     * <p>Settled and then published, in that order, because that is what the renderer does. Publish
     * alone leaves the view never having resolved a frame, and the drag arithmetic reads figures
     * that only resolving a frame produces.</p>
     */
    private void drawn(int lines, int scroll) {
        List<String> document = new ArrayList<>(lines);
        int[]        owners   = new int[lines];
        for (int i = 0; i < lines; i++) {
            document.add("line " + i);
            owners[i] = 1;
        }
        console.setViewportHeight(HEIGHT);
        console.jumpToTop();
        console.scrollLines(scroll);
        int resolved = console.resolveScroll(lines, HEIGHT);
        console.publish(document, owners, TOP, LEFT, WIDTH, HEIGHT, resolved);
    }

    // ------------------------------------------------------------------ which way, and how fast

    @Test
    public void thePointerOnTheFirstRowAsksForOlderLines() {
        drawn(100, 40);

        assertThat(console.dragScrollLines(TOP)).isNegative();
    }

    @Test
    public void thePointerOnTheLastRowAsksForNewerLines() {
        drawn(100, 40);

        assertThat(console.dragScrollLines(TOP + HEIGHT - 1)).isPositive();
    }

    @Test
    public void thePointerInTheMiddleAsksForNothing() {
        drawn(100, 40);

        for (int y = TOP + 1; y < TOP + HEIGHT - 1; y++) {
            assertThat(console.dragScrollLines(y)).as("row " + y).isZero();
        }
    }

    @Test
    public void pullingFurtherPastTheEdgeScrollsFaster() {
        drawn(100, 40);

        // A terminal reports a drag anywhere in its window, so the pointer can sit several rows
        // below the console, over the input line and the status bar. That distance is the only
        // thing the user can vary to say "faster".
        int atEdge = console.dragScrollLines(TOP + HEIGHT - 1);
        int below  = console.dragScrollLines(TOP + HEIGHT + 2);

        assertThat(below).isGreaterThan(atEdge);
    }

    @Test
    public void theSpeedIsCappedSoTheViewCanStillBeStopped() {
        drawn(100, 40);

        assertThat(console.dragScrollLines(TOP + HEIGHT + 500))
                .isEqualTo(console.maxDragScrollLines());
        assertThat(console.dragScrollLines(TOP - 500))
                .isEqualTo(-console.maxDragScrollLines());
    }

    @Test
    public void theCapIsProportionateToTheConsole() {
        // A fixed count of lines is a crawl on a tall terminal and a jump on a short one.
        drawn(100, 40);
        assertThat(console.maxDragScrollLines()).isEqualTo(HEIGHT / 3);

        console.setViewportHeight(60);
        assertThat(console.maxDragScrollLines()).isEqualTo(20);
    }

    @Test
    public void theCapIsNeverZeroHoweverShortTheConsole() {
        drawn(100, 40);
        console.setViewportHeight(1);

        assertThat(console.maxDragScrollLines()).isEqualTo(1);
    }

    @Test
    public void anEmptyConsoleIsNotScrolledByADrag() {
        // Nothing to select and nothing to reveal, and a drag over a console cleared with Ctrl+L
        // would otherwise scroll a document that is not there.
        assertThat(console.dragScrollLines(TOP)).isZero();
    }

    // ------------------------------------------------------------------ where the far end lands

    @Test
    public void aRowInsideTheConsoleIsTheRowUnderThePointer() {
        drawn(100, 40);

        assertThat(console.documentRowNear(TOP + 3)).isEqualTo(43);
    }

    @Test
    public void aRowAboveTheConsoleIsTheFirstVisibleRow() {
        drawn(100, 40);

        assertThat(console.documentRowNear(TOP - 4)).isEqualTo(40);
    }

    @Test
    public void aRowBelowTheConsoleIsTheLastVisibleRow() {
        drawn(100, 40);

        assertThat(console.documentRowNear(TOP + HEIGHT + 4)).isEqualTo(49);
    }

    @Test
    public void aRowPastTheEndOfTheDocumentIsItsLastLine() {
        drawn(6, 0);

        assertThat(console.documentRowNear(TOP + HEIGHT - 1)).isEqualTo(5);
    }

    @Test
    public void anEmptyConsoleHasNoRowToLandOn() {
        assertThat(console.documentRowNear(TOP)).isEqualTo(ShellConsoleView.NOTHING);
    }

    // ------------------------------------------------------------------ scrolling under a drag

    @Test
    public void adragScrollKeepsTheHoldItPutOnTheView() {
        drawn(100, 90);
        console.freezeWhereItIs();

        console.scrollWhileHeld(2);

        // The ordinary scroll paths clear the hold, which is right for a wheel notch and wrong here:
        // the drag is still going, and a view that resumed following its tail would move every row
        // out from under the anchor.
        console.resolveScroll(100, HEIGHT);
        assertThat(console.isFollowingTail())
                .as("a drag that scrolls to the bottom must not re-arm auto-follow")
                .isFalse();
    }

    @Test
    public void adragScrollMovesTheView() {
        drawn(100, 40);
        console.freezeWhereItIs();

        console.scrollWhileHeld(-3);

        assertThat(console.resolveScroll(100, HEIGHT)).isEqualTo(37);
    }

    @Test
    public void adragScrollStopsAtTheEndsRatherThanRunningOn() {
        drawn(100, 0);
        console.freezeWhereItIs();

        console.scrollWhileHeld(-50);

        // Held at the top edge for several seconds, an unclamped offset would run far negative and
        // then need as many ticks to come back before the view moved at all.
        assertThat(console.resolveScroll(100, HEIGHT)).isZero();
    }

    @Test
    public void theFarEndSeesTheScrollImmediately() {
        drawn(100, 40);
        console.freezeWhereItIs();

        console.scrollWhileHeld(5);

        // Resolved against the old position, the far end would sit five rows behind the view for as
        // long as the drag kept scrolling, and finish five rows short.
        assertThat(console.documentRowNear(TOP)).isEqualTo(45);
    }
}
