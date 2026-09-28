package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.ui.Glyphs;
import com.eonmux.cadetcoder.ui.TuiTheme;

import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.tui.event.MouseEventKind;
import dev.tamboui.widgets.block.Block;

import java.util.ArrayList;
import java.util.List;

/**
 * The shell's help overlay: what it says, how it scrolls, and how it closes.
 *
 * <h2>Why it is one object</h2>
 *
 * <p>The overlay is the shell's only modal region, and being modal means three things have to agree:
 * that it is up, that keys and mouse reports belong to it rather than to the view behind it, and how
 * far down it is scrolled. Those were three fields on the shell, read from the event path and
 * written from the render path, and the disagreement showed: the panel was modal for keys but not
 * for the mouse, so a wheel scroll moved the transcript hidden behind it and a drag started a
 * selection against the hit map of a view nobody could see -- releasing it copied text chosen at
 * random.</p>
 *
 * <h2>Why the scroll bound is set while drawing</h2>
 *
 * <p>How much of the help fits is a fact about the terminal's height and the panel's border, and
 * only the draw knows both. So the draw records the bound and the key path clamps against what the
 * last draw found, rather than each guessing at the other's arithmetic.</p>
 */
final class ShellHelp {

    /**
     * The widest the panel is allowed to be, in columns.
     *
     * <p>Wide enough for a command name and its description on one line. Narrower than that and
     * every second row of the reference wrapped.</p>
     */
    private static final int MAX_WIDTH = 92;

    /** The rows the panel's own top and bottom edges take out of its box. */
    private static final int BORDER_ROWS = 2;

    /** The columns the panel's own left and right edges take out of its box. */
    private static final int BORDER_COLUMNS = 2;


    /** The shortest box that still has a row between its edges to put a line of help on. */
    private static final int MIN_BOX_ROWS = BORDER_ROWS + 1;

    /** How a command is typed where this overlay is read: at the shell's prompt. */
    private static final String PREFIX = String.valueOf(InputRouter.COMMAND_PREFIX);

    /** What a key press means while the overlay is up. */
    enum Action { CLOSE, LINE_UP, LINE_DOWN, PAGE_UP, PAGE_DOWN, TOP, BOTTOM }

    private final Glyphs          glyphs;
    private final CommandRegistry registry;

    private volatile boolean visible;
    private          int     scroll;

    /** Rows the last drawn overlay could not show; 0 when all of it fits. */
    private volatile int maxScroll;

    /**
     * @param glyphs   what draws the borders and marks, degraded for the terminal
     * @param registry where each named command's description comes from
     */
    ShellHelp(Glyphs glyphs, CommandRegistry registry) {
        if (glyphs == null || registry == null) {
            throw new IllegalArgumentException("the help overlay needs glyphs and a command registry");
        }
        this.glyphs   = glyphs;
        this.registry = registry;
    }

    /** Whether the overlay is up, and therefore owns the keyboard and the mouse. */
    boolean isVisible() {
        return visible;
    }

    /** Opens it at the top. */
    void open() {
        visible = true;
        scroll  = 0;
    }

    /**
     * Applies one key press.
     *
     * @param key  what was pressed
     * @param page how many lines a page key moves, from the terminal's height
     */
    void onKey(KeyEvent key, int page) {
        switch (classifyKey(key == null ? null : key.code())) {
            case LINE_UP:   scrollTo(scroll - 1);    break;
            case LINE_DOWN: scrollTo(scroll + 1);    break;
            case PAGE_UP:   scrollTo(scroll - page); break;
            case PAGE_DOWN: scrollTo(scroll + page); break;
            case TOP:       scrollTo(0);             break;
            case BOTTOM:    scrollTo(maxScroll);     break;
            case CLOSE:
            default:
                visible = false;
                scroll  = 0;
        }
    }

    /**
     * Applies one mouse report. Everything but the wheel is swallowed, for the reason in the class
     * comment.
     *
     * @param mouse      the report
     * @param wheelLines lines per wheel notch
     * @return whether the overlay moved, and so needs redrawing
     */
    boolean onMouse(MouseEvent mouse, int wheelLines) {
        int delta = wheelDelta(mouse == null ? null : mouse.kind(), wheelLines);
        if (delta == 0) {
            return false;
        }
        scrollTo(scroll + delta);
        return true;
    }

    private void scrollTo(int line) {
        scroll = Math.max(0, Math.min(line, maxScroll));
    }

    /**
     * Decides what a key does while the overlay is up.
     *
     * <p>Navigation moves it; <b>anything else closes it</b>. "Any key closes" is what makes a panel
     * safe to open without first knowing how to leave it, and it was also why the overlay could not
     * be scrolled at all -- so the keys that already mean "move" are spent on moving, and every
     * other key still closes, which keeps that guarantee for someone who does not know these
     * exist.</p>
     *
     * @param code the key, or null
     * @return what it means here
     */
    static Action classifyKey(KeyCode code) {
        if (code == null) {
            return Action.CLOSE;
        }
        switch (code) {
            case UP:        return Action.LINE_UP;
            case DOWN:      return Action.LINE_DOWN;
            case PAGE_UP:   return Action.PAGE_UP;
            case PAGE_DOWN: return Action.PAGE_DOWN;
            case HOME:      return Action.TOP;
            case END:       return Action.BOTTOM;
            default:        return Action.CLOSE;
        }
    }

    /**
     * How far a mouse report moves the overlay.
     *
     * @param kind       the report
     * @param wheelLines lines per wheel notch
     * @return the scroll delta, {@code 0} when the report is not for the overlay
     */
    static int wheelDelta(MouseEventKind kind, int wheelLines) {
        if (kind == MouseEventKind.SCROLL_UP) {
            return -wheelLines;
        }
        if (kind == MouseEventKind.SCROLL_DOWN) {
            return wheelLines;
        }
        return 0;
    }

    /**
     * Draws the overlay centred over the whole terminal, if it is open.
     *
     * <p>The check is here and not only at the call site because this draws over the whole terminal:
     * a caller that forgets it does not draw the overlay slightly wrong, it hides the console behind
     * a panel nobody asked for, and the class that knows whether the overlay is open is this one.</p>
     *
     * @param frame the frame being built
     * @param area  the terminal
     * @param theme what colours it
     */
    void render(Frame frame, Rect area, TuiTheme theme) {
        if (!visible) {
            return;
        }
        Style windowStyle = ShellWidgets.nonNull(theme == null ? null : theme.getWindowBackground());
        Style borderStyle = ShellWidgets.nonNull(theme == null ? null : theme.getAccent1());
        Style textStyle   = ShellWidgets.nonNull(theme == null ? null : theme.getTextNormal());

        int boxW = boxWidth(area.width());

        // Wrapped before the height is worked out, because a wrapped line occupies two rows and the
        // scroll bound counts rows. The whole reference is in here now, so lines that do not fit
        // are the ordinary case rather than the odd long one.
        List<Line> styled = new ArrayList<>();
        ShellWidgets.styledLines(styled, lines(), theme, Math.max(1, boxW - BORDER_COLUMNS));

        int boxH = boxHeight(area.height(), styled.size());

        // Centred with nested layouts rather than by constructing a Rect by hand.
        List<Rect> rows = Layout.vertical()
                .constraints(Constraint.fill(), Constraint.length(boxH), Constraint.fill())
                .split(area);
        List<Rect> columns = Layout.horizontal()
                .constraints(Constraint.fill(), Constraint.length(boxW), Constraint.fill())
                .split(rows.get(1));
        Rect box = columns.get(1);

        int visibleRows = Math.max(0, boxH - BORDER_ROWS); // the overlay keeps its border
        maxScroll = Math.max(0, styled.size() - visibleRows);
        scrollTo(scroll);

        Block block = ShellWidgets.overlayBlock(title(), borderStyle, windowStyle,
                                                glyphs.roundedBorders());
        frame.renderWidget(ShellWidgets.paragraph(styled, block, textStyle, scroll), box);
        frame.clearCursor();
    }

    /**
     * How many columns the overlay's box takes.
     *
     * <h2>Why the terminal has the last word here too</h2>
     *
     * <p>The box asks for the whole width up to {@link #MAX_WIDTH}. It once also asked for a
     * floor of ten columns, which a terminal narrower than ten does not have to give: this width
     * is what the reference is wrapped to, so a box measured wider than the one drawn wrapped every
     * line into columns that are off screen, and the end of each line could not be read by any
     * amount of scrolling.</p>
     *
     * @param available the columns the terminal gives the overlay
     * @return the box's width, never more than {@code available}
     */
    static int boxWidth(int available) {
        return Math.min(available, MAX_WIDTH);
    }

    /**
     * How many rows the overlay's box takes.
     *
     * <h2>Why the terminal has the last word</h2>
     *
     * <p>The box asks for its two border rows and a row for every line of the reference, and
     * settles for a single line between the borders when the reference is shorter than that. What
     * it may not ask for is more rows than the terminal has. This height is also what {@link
     * #render} counts the visible rows and the scroll bound from, so a box measured taller than the
     * one drawn promised rows that were never on screen, and left behind a bound that stopped short
     * of the end of the reference -- on a terminal of one or two rows, the tail of the help could
     * not be scrolled to at all.</p>
     *
     * @param available the rows the terminal gives the overlay
     * @param lines     how many rows the wrapped reference occupies
     * @return the box's height, never more than {@code available}
     */
    static int boxHeight(int available, int lines) {
        return Math.min(available, Math.max(MIN_BOX_ROWS, lines + BORDER_ROWS));
    }

    /**
     * The overlay's heading, which says how to leave it and -- only when there is more than fits --
     * how to reach the rest. Naming the scroll keys unconditionally would advertise a control that
     * does nothing on a terminal tall enough to show the whole thing.
     */
    private String title() {
        if (maxScroll <= 0) {
            return " Help " + glyphs.dash() + " press any key to close ";
        }
        int shown = scroll <= 0 ? 0 : (int) Math.round(100.0 * scroll / maxScroll);
        return " Help " + glyphs.dash() + " " + shown + "% " + glyphs.dash()
               + " PgUp/PgDn scroll, any other key closes ";
    }

    /**
     * Everything the overlay says, one line per row before it is wrapped and styled.
     *
     * <p>The same sections {@code /help} prints, from {@link HelpContent}. The overlay used to name
     * seven commands in words of its own and send the reader to {@code /help} for the other
     * thirty-five, which meant closing the panel to answer the question it had been opened for.
     * Headings carry the sub-header marker so the console's own styling colours them, without this
     * class knowing which colour that is.</p>
     *
     * @return the lines
     */
    List<String> lines() {
        List<String> h = new ArrayList<>();
        h.add("");
        h.add(glyphs.headerMarker() + " CadetCoder " + glyphs.dash() + " Help" + glyphs.headerCloser());
        for (HelpContent.Section section : HelpContent.sections(registry, PREFIX)) {
            h.add("");
            h.add(glyphs.subheaderMarker() + " " + section.heading() + glyphs.subheaderCloser());
            int width = section.width();
            for (HelpContent.Entry entry : section.entries()) {
                h.add(HelpContent.row(entry, width));
            }
            for (String note : section.notes()) {
                h.add("");
                h.add("  " + note);
            }
        }
        h.add("");
        for (String note : HelpContent.footer(PREFIX)) {
            h.add(note);
        }
        h.add("");
        return h;
    }
}
