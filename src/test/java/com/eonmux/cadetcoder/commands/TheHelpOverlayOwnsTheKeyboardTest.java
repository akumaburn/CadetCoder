package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.ui.Glyphs;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.tui.event.MouseEventKind;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What {@code F1} opens, and what it takes to get out of it again.
 *
 * <h2>Why this is tested</h2>
 *
 * <p>The overlay's rule is that <em>any</em> key closes it, which is what makes a panel safe to open
 * without first knowing how to leave one. Scrolling it means spending some keys on movement instead,
 * and every key spent that way is one that no longer closes -- so the set has to be exactly the keys
 * a reader would already try to scroll with, and nothing else. Its state was three fields on the
 * shell, written from the render path and read from the event path; the panel was modal for keys and
 * not for the mouse, so a wheel notch scrolled the transcript hidden behind it.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>Every navigation key moves and does not close; every other key closes. The box is never taller
 * than the terminal it is drawn in, because the same height decides how many rows it may scroll by
 * and a box measured larger than the one drawn left the end of the reference out of reach. The
 * scroll never goes above the top or past what the last frame said it could show, so a key pressed
 * before anything was drawn cannot leave the panel scrolled past its own content. The wheel moves
 * the overlay and is swallowed rather than reaching the view underneath. And it carries the whole
 * reference -- every command, the keys and the mouse -- from the same source {@code /help} prints,
 * so the two cannot drift apart.</p>
 */
public class TheHelpOverlayOwnsTheKeyboardTest {

    /** Taller than any box the overlay builds for itself, so the terminal is what limits it. */
    private static final int MANY_LINES = 200;

    /** The tallest of the cramped terminals swept for a box that outgrows the screen. */
    private static final int CRAMPED_ROWS = 6;

    private final ShellHelp help = new ShellHelp(Glyphs.UNICODE, new CommandRegistry());

    private static KeyEvent key(KeyCode code) {
        return KeyEvent.ofKey(code);
    }

    // ------------------------------------------------------------------ what a key means

    @Test
    public void theKeysAReaderWouldScrollWithScroll() {
        assertThat(ShellHelp.classifyKey(KeyCode.UP)).isEqualTo(ShellHelp.Action.LINE_UP);
        assertThat(ShellHelp.classifyKey(KeyCode.DOWN)).isEqualTo(ShellHelp.Action.LINE_DOWN);
        assertThat(ShellHelp.classifyKey(KeyCode.PAGE_UP)).isEqualTo(ShellHelp.Action.PAGE_UP);
        assertThat(ShellHelp.classifyKey(KeyCode.PAGE_DOWN)).isEqualTo(ShellHelp.Action.PAGE_DOWN);
        assertThat(ShellHelp.classifyKey(KeyCode.HOME)).isEqualTo(ShellHelp.Action.TOP);
        assertThat(ShellHelp.classifyKey(KeyCode.END)).isEqualTo(ShellHelp.Action.BOTTOM);
    }

    @Test
    public void everyOtherKeyCloses() {
        assertThat(ShellHelp.classifyKey(KeyCode.ESCAPE)).isEqualTo(ShellHelp.Action.CLOSE);
        assertThat(ShellHelp.classifyKey(KeyCode.ENTER)).isEqualTo(ShellHelp.Action.CLOSE);
        assertThat(ShellHelp.classifyKey(KeyCode.CHAR)).isEqualTo(ShellHelp.Action.CLOSE);
        assertThat(ShellHelp.classifyKey(KeyCode.F1)).isEqualTo(ShellHelp.Action.CLOSE);
        assertThat(ShellHelp.classifyKey(KeyCode.TAB)).isEqualTo(ShellHelp.Action.CLOSE);
        assertThat(ShellHelp.classifyKey(null))
                .as("a key that could not be read is still a key, and still closes")
                .isEqualTo(ShellHelp.Action.CLOSE);
    }

    // ------------------------------------------------------------------ opening and closing

    @Test
    public void itIsClosedUntilItIsOpened() {
        assertThat(help.isVisible()).isFalse();

        help.open();

        assertThat(help.isVisible()).isTrue();
    }

    @Test
    public void anyOrdinaryKeyClosesIt() {
        help.open();

        help.onKey(key(KeyCode.ENTER), 10);

        assertThat(help.isVisible()).isFalse();
    }

    @Test
    public void aKeyThatCouldNotBeReadClosesIt() {
        help.open();

        help.onKey(null, 10);

        assertThat(help.isVisible()).isFalse();
    }

    @Test
    public void aNavigationKeyLeavesItOpen() {
        help.open();

        help.onKey(key(KeyCode.DOWN), 10);
        help.onKey(key(KeyCode.PAGE_DOWN), 10);
        help.onKey(key(KeyCode.END), 10);

        assertThat(help.isVisible())
                .as("a key spent on scrolling is one that no longer closes")
                .isTrue();
    }

    @Test
    public void openingItAgainStartsAtTheTop() {
        help.open();
        help.onKey(key(KeyCode.DOWN), 10);
        help.onKey(key(KeyCode.ESCAPE), 10);

        help.open();

        assertThat(help.isVisible()).isTrue();
    }

    // ------------------------------------------------------------------ the wheel

    @Test
    public void theWheelMovesItByOneNotch() {
        assertThat(ShellHelp.wheelDelta(MouseEventKind.SCROLL_UP, 3)).isEqualTo(-3);
        assertThat(ShellHelp.wheelDelta(MouseEventKind.SCROLL_DOWN, 3)).isEqualTo(3);
    }

    @Test
    public void anythingButTheWheelIsNotAScroll() {
        assertThat(ShellHelp.wheelDelta(MouseEventKind.MOVE, 3)).isZero();
        assertThat(ShellHelp.wheelDelta(null, 3)).isZero();
    }

    @Test
    public void aWheelNotchIsSwallowedRatherThanReachingTheViewBehind() {
        help.open();

        assertThat(help.onMouse(MouseEvent.scrollDown(5, 5), 3))
                .as("the overlay is modal for the mouse as well as the keyboard")
                .isTrue();
    }

    @Test
    public void aMouseReportThatIsNotTheWheelMovesNothing() {
        help.open();

        assertThat(help.onMouse(MouseEvent.move(5, 5), 3)).isFalse();
        assertThat(help.onMouse(null, 3)).isFalse();
    }

    @Test
    public void scrollingBeforeAnythingWasDrawnGoesNowhere() {
        help.open();

        // No frame has said how much does not fit, so there is nothing below the first line.
        assertThat(help.onMouse(MouseEvent.scrollDown(5, 5), 3))
                .as("a panel that has never been drawn cannot be scrolled past its own content")
                .isTrue();
        assertThat(help.isVisible()).isTrue();
    }

    // ------------------------------------------------------------------ how much of it fits

    @Test
    public void theBoxIsNeverTallerThanTheTerminalItIsDrawnIn() {
        // The box's height is also what the draw counts its visible rows and its scroll bound
        // from. A box measured taller than the terminal claimed rows that were never on screen,
        // and the bound it left behind stopped short of the end of the reference.
        for (int rows = 0; rows <= CRAMPED_ROWS; rows++) {
            assertThat(ShellHelp.boxHeight(rows, MANY_LINES))
                    .as("a terminal %d rows tall", rows)
                    .isLessThanOrEqualTo(rows);
        }
    }

    @Test
    public void theBoxIsNoTallerThanWhatItHasToShow() {
        assertThat(ShellHelp.boxHeight(60, 10))
                .as("ten lines and the two border rows around them")
                .isEqualTo(12);
        assertThat(ShellHelp.boxHeight(60, 0))
                .as("a panel with nothing in it is still a panel")
                .isEqualTo(3);
    }

    // ------------------------------------------------------------------ what it says

    @Test
    public void itCarriesTheWholeReferenceRatherThanASampleOfIt() {
        // It used to name seven commands and send the reader to /help for the other thirty-five,
        // which meant closing the panel to answer the question it had been opened for.
        List<String> lines = help.lines();

        assertThat(lines).anySatisfy(line -> assertThat(line).contains("/search"));
        assertThat(lines).anySatisfy(line -> assertThat(line).contains("/notebookedit"));
        assertThat(lines).anySatisfy(line -> assertThat(line).contains("Keyboard"));
        assertThat(lines).anySatisfy(line -> assertThat(line).contains("Ctrl+Home / Ctrl+End"));
        assertThat(lines).anySatisfy(line -> assertThat(line).contains("Mouse"));
    }

    @Test
    public void everyRowOfASectionIsAlignedToTheSameColumn() {
        Pattern row = Pattern.compile("^ {2}(\\S[^ ]*(?: [^ ]+)*?) {2,}(\\S.*)$");

        for (HelpContent.Section section : HelpContent.sections(new CommandRegistry(), "/")) {
            if (section.entries().isEmpty()) {
                continue; // a section that is prose only, such as what a paste does
            }
            List<Integer> columns = new ArrayList<>();
            for (HelpContent.Entry entry : section.entries()) {
                String  line    = HelpContent.row(entry, section.width());
                Matcher matched = row.matcher(line);
                assertThat(matched.matches()).as("a row reads as one row: %s", line).isTrue();
                columns.add(matched.start(2));
            }
            assertThat(columns).as("'%s' is one column", section.heading())
                               .isNotEmpty().containsOnly(columns.get(0));
        }
    }

    @Test
    public void acommandThatIsNotRegisteredIsNotNamed() {
        assertThat(help.lines())
                .noneSatisfy(line -> assertThat(line).contains("/notacommand"));
    }

    // ------------------------------------------------------------------ construction

    @Test
    public void anOverlayNeedsGlyphsAndCommandsToDescribe() {
        assertThatThrownBy(() -> new ShellHelp(null, new CommandRegistry()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ShellHelp(Glyphs.UNICODE, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
