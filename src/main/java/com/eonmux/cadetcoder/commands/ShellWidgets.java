package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ui.OutputLineStyler;
import com.eonmux.cadetcoder.ui.TuiTheme;

import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Overflow;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.CharWidth;
import dev.tamboui.text.Line;
import dev.tamboui.text.Text;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.paragraph.Paragraph;

import java.util.ArrayList;
import java.util.List;

/**
 * The pieces every region of the shell is drawn from: its chrome, its bars, and the measurements
 * that decide what fits.
 *
 * <h2>Why these are here and not on the shell</h2>
 *
 * <p>Each is a decision about columns and characters and nothing else, and each was a private member
 * of a class whose constructor takes over the process's output routing -- so a question as small as
 * "where does the caret go" could not be asked without starting a terminal UI. They are also shared:
 * the console, the two bars, the input line and the help overlay all draw from the same handful, and
 * a second copy of any of them is a second answer to the same question.</p>
 *
 * <h2>Why width is measured and not counted</h2>
 *
 * <p>A column is a display measurement and a string index is a count of characters. They agree only
 * for text made entirely of one-column characters, and a shell shows whatever the user typed and
 * whatever a file contains. Cutting by index splits surrogate pairs and miscounts every wide
 * character, which drew half a glyph over the text beside it.</p>
 */
final class ShellWidgets {

    /**
     * The most columns one glyph can occupy.
     *
     * <p>Also the narrowest cut that is certain to take something off a line, which is what stops a
     * wrap into a single column from looping forever on a two-column character.</p>
     */
    private static final int WIDEST_GLYPH_COLUMNS = 2;

    private ShellWidgets() {
    }

    /**
     * Chrome for the console: a titled rule above the content and nothing down the sides.
     *
     * <p>A full box puts a vertical border character at the start and end of every row. The shell's
     * own drag-selection reads the text area and skips them, but the terminal's does not: anyone who
     * selects with their own terminal, over SSH or out of habit, copies whatever occupies the
     * screen, so every copied line arrived wrapped in box-drawing characters. The top rule carries
     * the title and separates the region; the sides only cost the reader.</p>
     *
     * @param windowStyle the region's background
     * @return the block
     */
    static Block consoleBlock(Style windowStyle) {
        return Block.builder()
                .borders(Borders.NONE)
                .style(windowStyle)
                .build();
    }

    /**
     * Chrome for a modal overlay, which keeps a full box: it floats over the transcript, so it needs
     * edges to read as a panel, and it is not something anyone copies out.
     *
     * @param title       the panel's heading, already spaced
     * @param borderStyle the edge style
     * @param windowStyle the panel's background
     * @param rounded     whether this terminal can draw rounded corners
     * @return the block
     */
    static Block overlayBlock(String title, Style borderStyle, Style windowStyle, boolean rounded) {
        return Block.builder()
                .title(title)
                .borders(Borders.ALL)
                .borderType(rounded ? BorderType.ROUNDED : BorderType.PLAIN)
                .borderStyle(borderStyle)
                .style(windowStyle)
                .build();
    }

    /**
     * Splits a region into its one-row top -- a heading, or a divider -- and the rest.
     *
     * @param rect the whole region
     * @return the first row, then everything below it
     */
    static List<Rect> titledRegion(Rect rect) {
        return Layout.vertical().constraints(Constraint.length(1), Constraint.fill()).split(rect);
    }

    /**
     * Splits a region into a divider, a heading row, and the content beneath them.
     *
     * @param rect the whole region
     * @return the divider row, the heading row, then the content
     */
    static List<Rect> dividedRegion(Rect rect) {
        return Layout.vertical()
                .constraints(Constraint.length(1), Constraint.length(1), Constraint.fill())
                .split(rect);
    }

    /**
     * Draws a region's heading: its name on one row, with no border and no rule.
     *
     * <p>Takes the place of the widget border a titled block would draw. A border spans the
     * terminal on every edge it is given, and a selection copies those characters along with the
     * text.</p>
     *
     * @param frame the frame being built
     * @param rect  the single row the heading occupies
     * @param title the region's name, already spaced
     * @param style the style to draw it in
     */
    static void sectionTitle(Frame frame, Rect rect, String title, Style style) {
        if (rect.width() <= 0 || rect.height() <= 0) {
            return;
        }
        frame.renderWidget(bar(clipTo(title.stripTrailing(), rect.width()), style), rect);
    }

    /**
     * A scrollable body of styled lines inside a block.
     *
     * @param lines  what to draw, one per row before wrapping
     * @param block  the chrome around it
     * @param style  the default style
     * @param scroll how many rows are hidden above the top
     * @return the widget
     */
    static Paragraph paragraph(List<Line> lines, Block block, Style style, int scroll) {
        return Paragraph.builder()
                .text(Text.from(lines))
                .block(block)
                .style(style)
                .overflow(Overflow.CLIP)
                .scroll(scroll)
                .build();
    }

    /**
     * A single-line, full-width styled bar. The padding spaces carry the bar's background.
     *
     * @param content what the bar says, already fitted to its width
     * @param style   the bar's style
     * @return the widget
     */
    static Paragraph bar(String content, Style style) {
        return Paragraph.builder()
                .text(Text.from(Line.styled(content, style)))
                .style(style)
                .overflow(Overflow.CLIP)
                .build();
    }

    /**
     * Wraps each source line to {@code width} and applies its semantic style to every fragment.
     *
     * @param out    where the styled lines are appended
     * @param source the raw lines
     * @param theme  what decides a line's style
     * @param width  the columns available
     */
    static void styledLines(List<Line> out, List<String> source, TuiTheme theme, int width) {
        for (String src : source) {
            Style style = OutputLineStyler.styleFor(src, theme);
            for (String fragment : wrapOne(src, width)) {
                out.add(Line.styled(fragment, style));
            }
        }
    }

    /**
     * Truncates to the available columns, by display width.
     *
     * @param text  what to fit
     * @param width the columns available
     * @return the text, cut to fit
     */
    static String clipTo(String text, int width) {
        return width <= 0 ? "" : CharWidth.substringByWidth(text, width);
    }

    /**
     * Word-aware wrap of a single output line, measured in display columns.
     *
     * <h2>Why the fragments are cut by width and not by index</h2>
     *
     * <p>Forty ideographs are forty characters and eighty columns, so a wrap that counted characters
     * handed the paragraph a single fragment twice as wide as the console, and the half of it past
     * the edge was drawn over the region beside it. An index is also free to land between the two
     * halves of a surrogate pair, which is not a character and cannot be drawn at all. Both ends of
     * every fragment are chosen by {@link CharWidth} instead, which counts columns and stops on
     * character boundaries.</p>
     *
     * @param line  the line
     * @param width the columns available
     * @return the fragments, in order; one empty fragment for an empty line
     */
    static List<String> wrapOne(String line, int width) {
        List<String> out = new ArrayList<>();
        if (width <= 0) {
            return out;
        }
        if (line.isEmpty()) {
            out.add("");
            return out;
        }
        int i = 0;
        while (i < line.length()) {
            int end = i + headLength(line.substring(i), width);
            if (end < line.length()) {
                int space = line.lastIndexOf(' ', end - 1);
                if (space > i) {
                    end = space + 1;
                }
            }
            out.add(line.substring(i, end));
            i = end;
        }
        return out;
    }

    /**
     * How many characters of {@code text} fill at most {@code width} columns without one of them
     * being cut in half.
     *
     * <h2>Why the answer is never zero</h2>
     *
     * <p>A single column cannot hold a two-column glyph, and a line that opens with a joiner opens
     * with something that is half of a cluster and belongs to no character of its own. In both cases
     * nothing fits, and a wrap that believed that answer would take nothing off the line and never
     * finish. The first whole character goes out instead, to be clipped where it is drawn rather
     * than to hang the frame.</p>
     *
     * @param text  what is left of the line
     * @param width the columns available
     * @return the length of the leading fragment, always at least one character
     */
    private static int headLength(String text, int width) {
        String head = CharWidth.substringByWidth(text, width);
        if (head.isEmpty()) {
            head = CharWidth.substringByWidth(text, WIDEST_GLYPH_COLUMNS);
        }
        return head.isEmpty() ? Character.charCount(text.codePointAt(0)) : head.length();
    }

    /**
     * A bar with one group at each end and space between them.
     *
     * <h2>Why the right-hand group is the one that survives</h2>
     *
     * <p>Both groups are measured in columns, because a title of wide characters overran a row whose
     * character count said it still had space to spare -- and the padding was worked out from that
     * same count, so the bar came out wider still and the drawing clipped the end of it away. The
     * end it clipped was the right, and the right is where the position counter goes: the one thing
     * on the frame that says which of several results is being looked at. The left-hand group is the
     * region's name, which the reader can see for themselves. So the right is laid out first, and
     * the left is given whatever columns are left over.</p>
     *
     * @param left  the left-hand group
     * @param right the right-hand group
     * @param width the bar's width in columns
     * @return the composed bar, exactly {@code width} columns wide
     */
    static String composeBar(String left, String right, int width) {
        if (width <= 0) {
            return "";
        }
        String tail     = clipTo(right, width);
        int    tailWide = CharWidth.of(tail);
        String head     = clipTo(left, width - tailWide);
        return head + " ".repeat(width - tailWide - CharWidth.of(head)) + tail;
    }

    /**
     * Pads with spaces so a styled run covers the whole width.
     *
     * <p>What the run already covers is counted in columns. Counted in characters, a run holding a
     * wide character was padded as though it were half its size, and the styled background it
     * carries ran on past the rectangle it was drawn into.</p>
     *
     * @param text  what to pad
     * @param width the columns to fill
     * @return the padded text, unchanged when it already reaches
     */
    static String padRightTo(String text, int width) {
        if (width <= 0) {
            return "";
        }
        int filled = CharWidth.of(text);
        return filled >= width ? text : text + " ".repeat(width - filled);
    }

    /**
     * Which screen column the caret sits in on the input line.
     *
     * <h2>Why this is not the caret's index</h2>
     *
     * <p>A column is a display measurement and a caret position is a count of characters, and the
     * two coincide only for text made entirely of one-column characters. Anything else -- a CJK
     * character, an emoji, anything outside the basic plane -- is one caret step and two columns, so
     * taking the index as a column put the cursor to the left of where the text actually was, and
     * drew the ghost completion there with it. The field scrolls once the text outstrips it, which
     * moves the column again.</p>
     *
     * <p>This is the calculation {@code TextInput} makes to decide what to draw, so the caret and
     * the text agree by construction rather than by both being approximately right.</p>
     *
     * @param text  what is in the field
     * @param caret the caret's position in {@code text}, as a character index
     * @param left  the field's leftmost column
     * @param width the field's width in columns
     * @return the column the caret belongs in
     */
    static int cursorColumn(String text, int caret, int left, int width) {
        if (width <= 0) {
            return left;
        }
        String typed  = text == null ? "" : text;
        int    index  = Math.max(0, Math.min(caret, typed.length()));
        int    before = CharWidth.of(typed.substring(0, index));
        // The field scrolls to keep the caret visible once the text before it fills the width.
        int scroll = before >= width ? before - width + 1 : 0;
        return left + (before - scroll);
    }

    /**
     * Fits text into {@code width} columns, keeping the ends that identify it.
     *
     * <p>A right-hand ellipsis is the wrong operation for a path: every file in a project shares its
     * leading directories, so {@code read src/main/java/com/e...} identifies nothing. The
     * informative ends are the verb and the basename, so the middle goes first. Prose has no such
     * shape, and is cut on a word boundary instead of mid-word.</p>
     *
     * <p>Every one of those decisions is taken in columns. Taken in characters, an activity made of
     * wide characters was twice its measured size, so one that had already overrun the line was
     * handed back untouched -- and every cut that was made could fall between the halves of a
     * surrogate pair.</p>
     *
     * @param text     what to fit; newlines are flattened to spaces
     * @param width    the columns available
     * @param ellipsis what marks the cut, in whatever this terminal can draw
     * @return the fitted text
     */
    static String elide(String text, int width, String ellipsis) {
        String flat = text == null ? "" : text.replace('\n', ' ').trim();
        if (width <= 0 || flat.isEmpty()) {
            return "";
        }
        if (CharWidth.of(flat) <= width) {
            return flat;
        }
        String mark  = nz(ellipsis);
        int    slash = flat.lastIndexOf('/');
        if (slash > 0 && slash < flat.length() - 1) {
            int    space    = flat.indexOf(' ');
            String verb     = space > 0 ? flat.substring(0, space + 1) : "";
            String basename = flat.substring(slash + 1);
            String folded   = verb + mark + "/" + basename;
            if (CharWidth.of(folded) <= width) {
                return folded;
            }
            // Still too long: keep the END of the basename. A suffix disambiguates a filename; a
            // prefix does not.
            int keep = width - CharWidth.of(mark);
            if (keep > 0 && keep < CharWidth.of(basename)) {
                return mark + CharWidth.substringByWidthFromEnd(basename, keep);
            }
        }
        int budget = width - CharWidth.of(mark);
        if (budget <= 0) {
            return clipTo(mark, width);
        }
        String head  = clipTo(flat, budget);
        int    space = flat.lastIndexOf(' ', head.length());
        String kept  = space > 0 && CharWidth.of(flat.substring(0, space)) > budget / 2
                       ? flat.substring(0, space)
                       : head;
        return kept + mark;
    }

    /**
     * A filesystem path, shortened for a title bar.
     *
     * <p>Home-relative where it can be, and elided from the LEFT when it is still too long: the tail
     * of a path is what identifies it, and truncating the other way leaves every project under the
     * same source root looking identical.</p>
     *
     * @param absolute the path
     * @param home     the user's home directory, replaced by {@code ~}
     * @param max      the most characters to use
     * @return the shortened path, or empty when there is none
     */
    static String shortPath(String absolute, String home, int max) {
        if (absolute == null || absolute.isEmpty()) {
            return "";
        }
        String path = absolute;
        if (home != null && !home.isEmpty() && path.startsWith(home)) {
            path = "~" + path.substring(home.length());
        }
        if (max > 1 && path.length() > max) {
            path = "\u2026" + path.substring(path.length() - (max - 1));
        }
        return path;
    }

    /** A style that can be handed to a widget, for a theme lookup that may not have one. */
    static Style nonNull(Style style) {
        return style == null ? Style.EMPTY : style;
    }

    /** Text that can be measured, for a value that may not be set. */
    static String nz(String text) {
        return text == null ? "" : text;
    }

    /** The widest a command may be in a result heading, leaving room for the status mark. */
    private static final int COMMAND_COLUMNS = 24;

    /** The widest a title may be in a region heading, leaving room for the position counter. */
    private static final int TITLE_COLUMNS = 40;

    /**
     * A command, cut to the width a result heading gives it.
     *
     * @param command  what was run, or {@code null}
     * @param ellipsis the mark for text that was cut
     * @return the label, empty when there is no command
     */
    static String shortenCommand(String command, String ellipsis) {
        return command == null ? "" : fitWithin(command, COMMAND_COLUMNS, ellipsis);
    }

    /**
     * A region's title, cut to the width its heading gives it.
     *
     * <p>The trailing colon goes because these titles are written to introduce something -- a
     * command's prompt, a section's heading -- and read as a label once they are on a heading of
     * their own.</p>
     *
     * @param text     the title, or {@code null} when nothing named it
     * @param ellipsis the mark for text that was cut
     * @return the title
     */
    static String safeTitle(String text, String ellipsis) {
        if (text == null) {
            return "Input";
        }
        String trimmed = text.trim();
        if (trimmed.endsWith(":")) {
            // Trimmed again afterwards: a question written "Which file ?:" would otherwise leave
            // the space that stood before the colon sitting at the end of a heading.
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        return fitWithin(trimmed, TITLE_COLUMNS, ellipsis);
    }

    /**
     * Text cut to a budget of columns, the last of them spent on the ellipsis.
     *
     * <p>By display width, like everything else here. Counting characters instead let a heading of
     * wide characters come out twice the columns it was budgeted, and the part
     * {@link #sectionTitle} then clipped was the tail -- which is where the position counter is, the
     * one thing on the heading that says which of the results this is.</p>
     *
     * @param text     what to fit, already trimmed
     * @param columns  the budget
     * @param ellipsis the mark for text that was cut
     * @return the text, at most {@code columns} wide
     */
    private static String fitWithin(String text, int columns, String ellipsis) {
        if (CharWidth.of(text) <= columns) {
            return text;
        }
        String mark = nz(ellipsis);
        return CharWidth.substringByWidth(text, Math.max(0, columns - CharWidth.of(mark))) + mark;
    }
}
