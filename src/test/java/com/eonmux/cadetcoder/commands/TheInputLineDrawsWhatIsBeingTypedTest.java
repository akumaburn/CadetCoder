package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ui.Glyphs;
import com.eonmux.cadetcoder.ui.TuiThemeManager;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.widgets.input.TextInputState;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The input region, drawn into a buffer and read back.
 *
 * <h2>Why this is tested</h2>
 *
 * <p>The suggestion drawn after the caret is written straight into the frame buffer, after the input
 * widget has already drawn over the same cells. Where it lands is therefore a consequence of two
 * separate pieces of arithmetic, and the one thing it must never do -- sit on top of characters that
 * are really there -- had no test, because reaching the code meant starting a terminal.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>That what has been typed reaches the screen; that the suggestion is drawn only at the end of
 * the line, since mid-line it would overwrite the text after the caret; that it is absent when there
 * is nothing to suggest; that the caret is placed inside the field rather than left wherever the
 * last widget put it; and that a prompt replaces the heading and the placeholder both, because a
 * command's question is not answered by a hint about typing a slash.</p>
 */
public class TheInputLineDrawsWhatIsBeingTypedTest {

    private static final int WIDTH  = 50;
    private static final int HEIGHT = 4;   // a rule, the heading, and the field

    private final ShellInputLine inputLine = new ShellInputLine(Glyphs.ASCII);

    private static TextInputState typed(String text) {
        TextInputState state = new TextInputState();
        state.insert(text);
        return state;
    }

    private String draw(TextInputState state, ShellInputLine.View view) {
        Buffer buffer = Buffer.empty(new Rect(0, 0, WIDTH, HEIGHT));
        Frame  frame  = Frame.forTesting(buffer);
        inputLine.render(frame, frame.area(), TuiThemeManager.getCurrentTheme(), state, view);
        return text(buffer);
    }

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

    @Test
    public void whatHasBeenTypedIsOnScreen() {
        assertThat(draw(typed("/ls src"), new ShellInputLine.View(false, null, "")))
                .contains("/ls src");
    }

    @Test
    public void theHeadingNamesWhereCommandsWillRun() {
        assertThat(draw(typed(""), new ShellInputLine.View(false, null, ""))).contains("Command");
    }

    @Test
    public void theSuggestionIsDrawnAfterWhatHasBeenTyped() {
        String drawn = draw(typed("/mod"), new ShellInputLine.View(false, null, "els"));

        assertThat(drawn).contains("/models");
    }

    @Test
    public void thereIsNoSuggestionWhenThereIsNothingToSuggest() {
        String drawn = draw(typed("/models"), new ShellInputLine.View(false, null, ""));

        assertThat(drawn).contains("/models");
    }

    @Test
    public void theSuggestionIsNotDrawnFromTheMiddleOfALine() {
        // It is written over cells the input widget has already drawn, so mid-line it would sit on
        // top of the characters after the caret.
        TextInputState state = typed("/models");
        state.moveCursorToStart();

        String drawn = draw(state, new ShellInputLine.View(false, null, " select"));

        assertThat(drawn).doesNotContain("select");
    }

    @Test
    public void aPromptReplacesTheHeadingAndTheHint() {
        // The placeholder explains how to type a command; while a command is asking a question, that
        // is not what the row is for.
        String drawn = draw(typed(""),
                            new ShellInputLine.View(true, "Overwrite src/Main.java?", ""));

        assertThat(drawn).contains("Overwrite src/Main.java?");
        assertThat(drawn).doesNotContain("Type '/'");
    }

    @Test
    public void anIdleLineOffersTheHint() {
        String drawn = draw(typed(""), new ShellInputLine.View(false, null, ""));

        assertThat(drawn).contains("Type '/'");
    }

    @Test
    public void theCaretIsPlacedInsideTheField() {
        Buffer buffer = Buffer.empty(new Rect(0, 0, WIDTH, HEIGHT));
        Frame  frame  = Frame.forTesting(buffer);

        inputLine.render(frame, frame.area(), TuiThemeManager.getCurrentTheme(), typed("/ls"),
                         new ShellInputLine.View(false, null, ""));

        assertThat(frame.cursorPosition()).isPresent();
    }

    @Test
    public void anInputLineWithNoRoomDrawsNothingRatherThanFailing() {
        Buffer buffer = Buffer.empty(new Rect(0, 0, WIDTH, HEIGHT));
        Frame  frame  = Frame.forTesting(buffer);
        TextInputState state = typed("/ls");

        // A terminal mid-resize hands out degenerate rectangles.
        inputLine.render(frame, new Rect(0, 0, 0, 0), TuiThemeManager.getCurrentTheme(), state,
                         new ShellInputLine.View(false, null, "rc"));
        inputLine.render(frame, new Rect(0, 0, WIDTH, 1), TuiThemeManager.getCurrentTheme(), state,
                         new ShellInputLine.View(false, null, "rc"));
        inputLine.render(frame, new Rect(0, 0, 1, HEIGHT), TuiThemeManager.getCurrentTheme(), state,
                         new ShellInputLine.View(false, null, "rc"));
    }

    @Test
    public void aMissingThemeIsNotAMissingInputLine() {
        Buffer buffer = Buffer.empty(new Rect(0, 0, WIDTH, HEIGHT));
        Frame  frame  = Frame.forTesting(buffer);

        inputLine.render(frame, frame.area(), null, typed("/ls src"),
                         new ShellInputLine.View(false, null, ""));

        assertThat(text(buffer)).contains("/ls src");
    }

    @Test
    public void aRuleSeparatesWhatIsBeingTypedFromWhatHasHappened() {
        // The console above ends where this begins, and nothing else on the screen says so: the
        // heading reads as one more line of output without it.
        String[] rows = draw(typed(""), new ShellInputLine.View(false, null, "")).split("\n");

        assertThat(rows[0].strip()).isEqualTo(Glyphs.ASCII.rule(WIDTH));
        assertThat(rows[1]).contains("Command");
    }

    @Test
    public void theRuleIsDrawnAboveAquestionToo() {
        String[] rows = draw(typed(""), new ShellInputLine.View(true, "Run it anyway?", ""))
                .split("\n");

        assertThat(rows[0].strip()).isEqualTo(Glyphs.ASCII.rule(WIDTH));
        assertThat(rows[1]).contains("Run it anyway?");
    }
}
