package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.ui.Glyphs;
import com.eonmux.cadetcoder.ui.TuiThemeManager;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three regions that frame the console: the header, the status bar and the help overlay.
 *
 * <h2>Why this is tested</h2>
 *
 * <p>Each had tests for the strings it composes and none for the drawing, because drawing needed a
 * terminal. What the composition tests cannot see is the arithmetic between a composed string and a
 * row of cells -- and that is where a bar overruns its rectangle, or an overlay is laid out for a
 * terminal larger than the one it is on. TamboUI hands out a frame backed by an in-memory buffer, so
 * every one of them can be drawn and read back.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>That each region draws its content inside the rectangle it was given and nowhere else; that a
 * terminal too small for the help overlay still gets an overlay rather than an exception; that the
 * help overlay names the keys it exists to document; that the bottom row is the keys alone, since
 * where you are is drawn in the console's corner now; and that a missing theme costs colour rather
 * than content, since every style lookup in all three goes through the same null-tolerant helper on
 * the strength of that claim.</p>
 */
public class TheShellsBarsAndOverlayAreDrawnTest {

    private static final int WIDTH  = 70;
    private static final int HEIGHT = 20;

    private static String text(Buffer buffer) {
        StringBuilder out = new StringBuilder();
        for (int y = 0; y < buffer.height(); y++) {
            for (int x = 0; x < buffer.width(); x++) {
                out.append(buffer.get(x, y).symbol());
            }
            out.append('\n');
        }
        return out.toString();
    }

    private static Buffer blank() {
        return Buffer.empty(new Rect(0, 0, WIDTH, HEIGHT));
    }

    // --- header bar ---

    @Test
    public void theHeaderNamesTheApplication() {
        ShellHeaderBar header = new ShellHeaderBar(Glyphs.ASCII, "CadetCoder v1.0");
        Buffer buffer = blank();

        header.render(Frame.forTesting(buffer), new Rect(0, 0, WIDTH, 1),
                      TuiThemeManager.getCurrentTheme(), null);

        assertThat(text(buffer)).contains("CadetCoder v1.0");
    }

    @Test
    public void theHeaderShowsWhatIsRunningWhenSomethingIs() {
        ShellHeaderBar header = new ShellHeaderBar(Glyphs.ASCII, "CadetCoder v1.0");
        Buffer buffer = blank();

        header.render(Frame.forTesting(buffer), new Rect(0, 0, WIDTH, 1),
                      TuiThemeManager.getCurrentTheme(), width -> "/ Thinking");

        assertThat(text(buffer)).contains("Thinking");
    }

    @Test
    public void theHeaderGivesUpItsOwnNameBeforeItOverrunsTheRow() {
        // The running indicator is the half that changes; the application name is on screen in the
        // title bar of every terminal anyone runs this in.
        ShellHeaderBar header = new ShellHeaderBar(Glyphs.ASCII, "CadetCoder v1.0");
        Buffer buffer = blank();

        header.render(Frame.forTesting(buffer), new Rect(0, 0, 20, 1),
                      TuiThemeManager.getCurrentTheme(), width -> "/ Thinking");

        assertThat(text(buffer)).contains("Thinking");
        assertThat(rowsTouchedBelow(buffer, 1)).isFalse();
    }

    @Test
    public void theHeaderDrawsNothingIntoARowThatIsNotThere() {
        ShellHeaderBar header = new ShellHeaderBar(Glyphs.ASCII, "CadetCoder v1.0");
        Buffer buffer = blank();

        header.render(Frame.forTesting(buffer), new Rect(0, 0, 0, 0),
                      TuiThemeManager.getCurrentTheme(), null);

        assertThat(text(buffer).trim()).isEmpty();
    }

    @Test
    public void theHeaderKnowsWhichBranchItIsOn() {
        // Read from the working directory, which for this build is a git repository; what is locked
        // is that asking answers rather than raising, whatever the answer turns out to be.
        ShellHeaderBar header = new ShellHeaderBar(Glyphs.ASCII, "CadetCoder v1.0");

        header.refreshBranch();

        assertThat(header.branch()).isNotNull();
    }

    // --- status bar ---

    @Test
    public void theStatusBarSaysWhereTheConsoleIs() {
        ShellStatusBar status = new ShellStatusBar(Glyphs.ASCII);
        Buffer buffer = blank();

        status.render(Frame.forTesting(buffer), new Rect(0, 0, WIDTH, 1),
                      TuiThemeManager.getCurrentTheme(),
                      new ShellStatusBar.View(null, false, false, null, true, 0, 0));

        assertThat(text(buffer).trim()).isNotEmpty();
    }

    @Test
    public void aflashReplacesWhateverTheStateWouldOtherwiseBe() {
        // It is the only confirmation a copy gets, so it has to win the corner it shares.
        ShellStatusBar status = new ShellStatusBar(Glyphs.ASCII);

        String state = status.state(
                new ShellStatusBar.View("+ copied 42 chars", false, false, null, true, 0, 0));

        assertThat(state).contains("copied 42 chars");
    }

    @Test
    public void theStateSaysWhichResultIsFocused() {
        // Drawn in the console's own top-right corner rather than on this row; see ShellStatusBar.
        ShellStatusBar status = new ShellStatusBar(Glyphs.ASCII);

        String state = status.state(new ShellStatusBar.View(
                null, false, false, new ShellStatusBar.Focus(ShellStatusBar.Of.RESULT, 2, 5, "ls src"), false, 3, 10));

        assertThat(state).contains("2/5");
    }

    @Test
    public void theStatusBarItselfIsKeysAlone() {
        // What changes moment to moment moved to the console's corner, beside the text it is about.
        ShellStatusBar status = new ShellStatusBar(Glyphs.ASCII);
        Buffer buffer = blank();

        status.render(Frame.forTesting(buffer), new Rect(0, 0, WIDTH, 1),
                      TuiThemeManager.getCurrentTheme(),
                      new ShellStatusBar.View("+ copied 42 chars", false, false, null, true, 0, 0));

        assertThat(text(buffer)).contains("F1 Help");
        assertThat(text(buffer)).doesNotContain("copied 42 chars");
    }

    @Test
    public void adividerSeparatesTheKeysFromTheConsoleAboveThem() {
        ShellStatusBar status = new ShellStatusBar(Glyphs.ASCII);
        Buffer buffer = blank();

        status.render(Frame.forTesting(buffer), new Rect(0, 0, WIDTH, 2),
                      TuiThemeManager.getCurrentTheme(),
                      new ShellStatusBar.View(null, false, false, null, true, 0, 0));

        String[] rows = text(buffer).split("\n");
        assertThat(rows[0].strip()).isEqualTo(Glyphs.ASCII.rule(WIDTH));
        assertThat(rows[1]).contains("F1 Help");
        assertThat(rowsTouchedBelow(buffer, 2)).isFalse();
    }

    @Test
    public void aregionTooShortForTheDividerKeepsTheKeys() {
        // A terminal squeezed to a few rows needs the words rather than the edge.
        ShellStatusBar status = new ShellStatusBar(Glyphs.ASCII);
        Buffer buffer = blank();

        status.render(Frame.forTesting(buffer), new Rect(0, 0, WIDTH, 1),
                      TuiThemeManager.getCurrentTheme(),
                      new ShellStatusBar.View(null, false, false, null, true, 0, 0));

        assertThat(text(buffer).split("\n")[0]).contains("F1 Help");
    }

    @Test
    public void theStatusBarStaysOnItsOwnRow() {
        ShellStatusBar status = new ShellStatusBar(Glyphs.ASCII);
        Buffer buffer = blank();

        status.render(Frame.forTesting(buffer), new Rect(0, 0, WIDTH, 1),
                      TuiThemeManager.getCurrentTheme(),
                      new ShellStatusBar.View(null, true, true, null, false, 5, 20));

        assertThat(rowsTouchedBelow(buffer, 1)).isFalse();
    }

    // --- help overlay ---

    @Test
    public void theHelpOverlayNamesTheKeysItExistsToDocument() {
        // The keys are what the panel has to carry: several of them appear nowhere else at all,
        // and they are what the banner sends the reader here for.
        ShellHelp help = new ShellHelp(Glyphs.ASCII, new CommandRegistry());
        Buffer buffer = Buffer.empty(new Rect(0, 0, WIDTH, 60));
        help.open();

        help.render(Frame.forTesting(buffer), new Rect(0, 0, WIDTH, 60),
                    TuiThemeManager.getCurrentTheme());

        String drawn = text(buffer);
        assertThat(drawn).contains("Esc");
        assertThat(drawn).contains("Ctrl+Q");
        assertThat(drawn).contains("F4");
    }

    @Test
    public void aterminalTooSmallForTheOverlayStillGetsOne() {
        // The overlay is how anyone finds out that Esc closes it; refusing to draw at a small size
        // would leave a shell whose modal state has no visible way out.
        ShellHelp help = new ShellHelp(Glyphs.ASCII, new CommandRegistry());
        Buffer buffer = blank();
        help.open();

        help.render(Frame.forTesting(buffer), new Rect(0, 0, 12, 4),
                    TuiThemeManager.getCurrentTheme());

        assertThat(text(buffer).trim()).isNotEmpty();
    }

    @Test
    public void theOverlayIsNotDrawnWhileItIsClosed() {
        ShellHelp help = new ShellHelp(Glyphs.ASCII, new CommandRegistry());
        Buffer buffer = blank();

        help.render(Frame.forTesting(buffer), new Rect(0, 0, WIDTH, HEIGHT),
                    TuiThemeManager.getCurrentTheme());

        assertThat(text(buffer).trim()).isEmpty();
    }

    @Test
    public void scrollingTheOverlayChangesWhatIsDrawn() {
        ShellHelp help = new ShellHelp(Glyphs.ASCII, new CommandRegistry());
        help.open();

        Buffer top = blank();
        help.render(Frame.forTesting(top), new Rect(0, 0, WIDTH, 8),
                    TuiThemeManager.getCurrentTheme());

        help.onKey(dev.tamboui.tui.event.KeyEvent.ofKey(dev.tamboui.tui.event.KeyCode.PAGE_DOWN), 6);
        Buffer lower = blank();
        help.render(Frame.forTesting(lower), new Rect(0, 0, WIDTH, 8),
                    TuiThemeManager.getCurrentTheme());

        assertThat(text(lower)).isNotEqualTo(text(top));
    }

    @Test
    public void amissingThemeCostsColourRatherThanContent() {
        ShellHeaderBar header = new ShellHeaderBar(Glyphs.ASCII, "CadetCoder v1.0");
        ShellStatusBar status = new ShellStatusBar(Glyphs.ASCII);
        ShellHelp      help   = new ShellHelp(Glyphs.ASCII, new CommandRegistry());
        help.open();

        Buffer buffer = blank();
        Frame  frame  = Frame.forTesting(buffer);
        header.render(frame, new Rect(0, 0, WIDTH, 1), null, null);
        status.render(frame, new Rect(0, 1, WIDTH, 1), null,
                      new ShellStatusBar.View(null, false, false, null, true, 0, 0));
        help.render(frame, new Rect(0, 2, WIDTH, HEIGHT - 2), null);

        assertThat(text(buffer)).contains("CadetCoder v1.0");
    }

    /** Whether anything was drawn below the given row; a bar that overran would have been. */
    private static boolean rowsTouchedBelow(Buffer buffer, int rows) {
        for (int y = rows; y < buffer.height(); y++) {
            for (int x = 0; x < buffer.width(); x++) {
                if (!buffer.get(x, y).isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }
}
