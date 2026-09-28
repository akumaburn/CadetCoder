package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ui.Glyphs;
import com.eonmux.cadetcoder.ui.TuiTheme;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.CharWidth;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.input.TextInput;
import dev.tamboui.widgets.input.TextInputState;

import java.util.List;

/**
 * The bottom region: the line being typed, its heading, and the suggested rest of a command name.
 *
 * <h2>Why the heading changes rather than the field</h2>
 *
 * <p>The same row serves two different questions -- "what do you want to run" and whatever a running
 * command has stopped to ask -- and the field itself cannot say which, because it holds the answer
 * either way. So the heading carries it: the working directory while the shell is idle, and the
 * command's own question, in the accent colour, while one is waiting.</p>
 */
final class ShellInputLine {

    private final Glyphs glyphs;

    /**
     * @param glyphs the marks this terminal can draw
     */
    ShellInputLine(Glyphs glyphs) {
        this.glyphs = glyphs;
    }

    /**
     * What the input line is for this frame.
     *
     * @param prompting  whether a command is waiting for an answer
     * @param promptText what it asked, when it is
     * @param ghostText  the predicted rest of the command name, empty when there is no prediction
     */
    record View(boolean prompting, String promptText, String ghostText) {
    }

    /**
     * Draws the input region.
     *
     * @param frame the frame being built
     * @param rect  the region the input line occupies
     * @param theme the active theme
     * @param state what has been typed and where the caret is
     * @param view  what the line is for this frame
     */
    void render(Frame frame, Rect rect, TuiTheme theme, TextInputState state, View view) {
        Style fieldStyle  = ShellWidgets.nonNull(theme == null ? null : theme.getFieldActive());
        Style windowStyle = ShellWidgets.nonNull(theme == null ? null : theme.getWindowBackground());
        Style accentStyle = ShellWidgets.nonNull(theme == null ? null : theme.getAccent1());

        boolean prompt = view.prompting();
        String  where  = ShellWidgets.shortPath(System.getProperty("user.dir"),
                                                System.getProperty("user.home"),
                                                Math.max(0, rect.width() / 2));
        Style borderStyle = prompt ? accentStyle : fieldStyle;

        // A rule, a heading row and a borderless field, matching the console: a boxed input put a
        // border character at both ends of the line being typed and two more full-width rules
        // around it. The rule above the heading is the one edge worth drawing -- it separates what
        // you are about to type from everything that has already happened, and it sits outside the
        // transcript, so nothing copied out of the console carries it.
        List<Rect> region = ShellWidgets.dividedRegion(rect);
        ShellWidgets.sectionTitle(frame, region.get(0),
                                  glyphs.rule(region.get(0).width()), borderStyle);
        ShellWidgets.sectionTitle(frame, region.get(1),
                                  heading(prompt, view.promptText(), where, glyphs), borderStyle);
        Rect fieldRect = region.get(2);

        Block inputBlock = Block.builder()
                .borders(Borders.NONE)
                .style(windowStyle)
                .build();
        TextInput input = TextInput.builder()
                .block(inputBlock)
                .style(fieldStyle)
                .placeholder(prompt ? "" : "Type '/' for a command " + glyphs.dash()
                                     + " Right accepts the suggestion, F1 for help")
                .build();
        frame.renderStatefulWidget(input, fieldRect, state);

        // Place the terminal cursor inside the input field
        Rect inputInner = inputBlock.inner(fieldRect);
        if (!inputInner.isEmpty()) {
            int cursorX = ShellWidgets.cursorColumn(state.text(), state.cursorPosition(),
                                                    inputInner.left(), inputInner.width());
            renderCompletionHint(frame, inputInner, cursorX, theme, state, view.ghostText());
            frame.setCursorPosition(cursorX, inputInner.top());
        }
    }

    /**
     * What the heading above the input line says.
     *
     * @param prompting  whether a command is waiting for an answer
     * @param promptText what it asked, when it is
     * @param where      the working directory, already shortened, or empty when there is none
     * @param glyphs     the marks this terminal can draw
     * @return the heading, already spaced for the bar it is drawn on
     */
    static String heading(boolean prompting, String promptText, String where, Glyphs glyphs) {
        if (prompting) {
            return " ? " + ShellWidgets.safeTitle(promptText, glyphs.ellipsis()) + " ";
        }
        if (where == null || where.isEmpty()) {
            return "  Command ";
        }
        return "  Command  " + glyphs.dash() + "  " + where + " ";
    }

    /**
     * Draws the predicted rest of the command name after what has been typed.
     *
     * <p>Written into the frame buffer after the input widget has drawn, in the dim style, so it
     * reads as a suggestion rather than as text that is already there. {@code Right} at the end of
     * the line accepts it.</p>
     */
    private static void renderCompletionHint(Frame frame, Rect inner, int cursorX, TuiTheme theme,
                                             TextInputState state, String ghostText) {
        // Only ever drawn at the end of the line: shown mid-line it would sit on top of the
        // characters after the cursor.
        if (state.cursorPosition() < state.length()) {
            return;
        }
        String hint = ShellWidgets.nz(ghostText);
        if (hint.isEmpty()) {
            return;
        }
        Buffer buffer = frame.buffer();
        if (buffer == null) {
            return;
        }
        int available = inner.left() + inner.width() - cursorX;
        if (available <= 0) {
            return;
        }
        // By display width, not by character count: cutting by index can split a surrogate pair
        // and leaves a wide character half-drawn over the text beside it.
        hint = CharWidth.substringByWidth(hint, available);
        if (hint.isEmpty()) {
            return;
        }
        buffer.setString(cursorX, inner.top(), hint,
                         ShellWidgets.nonNull(theme == null ? null : theme.getDim()));
    }
}
