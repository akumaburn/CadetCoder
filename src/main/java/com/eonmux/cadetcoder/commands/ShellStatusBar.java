package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ui.Glyphs;
import com.eonmux.cadetcoder.ui.TuiTheme;

import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;

import java.util.List;

/**
 * The shell's bottom region: a divider, and under it the keys that will move you.
 *
 * <h2>Why it is given a view rather than the shell</h2>
 *
 * <p>What this line says is a function of about seven values, and it used to read them straight off
 * the shell's fields -- so nothing could be asked of it without starting a terminal UI, and the one
 * question worth asking is exactly the one that had gone wrong: while a result was focused by
 * clicking it, the hints named "Tab/S-Tab results", and Tab cycles workers whichever kind of region
 * is focused. Naming the state it depends on makes that answerable.</p>
 *
 * <h2>Why this line is keys alone</h2>
 *
 * <p>What changes moment to moment -- live, scrolled, focused -- used to open this line, a row
 * below the console and at the opposite end of the screen from the text it describes. It is a
 * statement about the console, so it is drawn in the console's own top-right corner now; this class
 * still works out what it says, and {@code InteractiveShell} hands it to the console renderer. What
 * is left here is the list of keys, which is reference material and the same shape every frame, on
 * its own row under a divider.</p>
 */
final class ShellStatusBar {

    /** What kind of thing the console is showing instead of the transcript. */
    enum Of {

        /** One result of the transcript. */
        RESULT,

        /** One worker of an agent run. */
        WORKER,

        /** One background job. */
        JOB,

        /** The list of every worker and every job. */
        OVERVIEW
    }

    /**
     * What the console is focused on, or {@code null} in the live view.
     *
     * @param of    what kind of thing it is
     * @param index which one, counting from one; {@code 0} when it is no longer in the list
     * @param count how many there are
     * @param title the result's command, already shortened; empty for anything else
     */
    record Focus(Of of, int index, int count, String title) {
    }

    /**
     * Everything the line says is a function of these.
     *
     * @param flash        a transient notice that outranks everything else, or null
     * @param prompting    whether a command is waiting on an answer from the person at the terminal
     * @param selectMode   whether the mouse has been given over to selecting
     * @param focus        what is focused, or null in the live view
     * @param followBottom whether the transcript is following its tail
     * @param scroll       how far back it is scrolled
     * @param maxScroll    how far back it could be
     */
    record View(String flash, boolean prompting, boolean selectMode, Focus focus,
                boolean followBottom, int scroll, int maxScroll) {
    }

    private final Glyphs glyphs;

    /**
     * @param glyphs what draws the marks, degraded for the terminal
     */
    ShellStatusBar(Glyphs glyphs) {
        if (glyphs == null) {
            throw new IllegalArgumentException("the status bar needs glyphs to draw with");
        }
        this.glyphs = glyphs;
    }

    /**
     * Draws the region: a divider, then the keys.
     *
     * <p>The divider separates the keys from the console above them, the way the input line below
     * is separated from both. Without it the keys read as one more line of the transcript, which is
     * the one thing they are not. A region only one row high keeps the keys and drops the divider,
     * because a terminal that short needs the words rather than the edge.</p>
     *
     * @param frame the frame being built
     * @param rect  the region it occupies: two rows, or one when there is no room
     * @param theme what colours it
     * @param view  what to say
     */
    void render(Frame frame, Rect rect, TuiTheme theme, View view) {
        if (rect.width() <= 0 || rect.height() <= 0) {
            return;
        }
        Style style = ShellWidgets.nonNull(theme == null ? null : theme.getStatus());
        Rect  keys  = rect;
        if (rect.height() > 1) {
            List<Rect> region = ShellWidgets.titledRegion(rect);
            ShellWidgets.sectionTitle(frame, region.get(0),
                                      glyphs.rule(region.get(0).width()), style);
            keys = region.get(1);
        }
        if (keys.width() <= 0 || keys.height() <= 0) {
            return;
        }
        String bar = ShellWidgets.composeBar(" " + hints(view), " ", keys.width());
        frame.renderWidget(ShellWidgets.bar(bar, style), keys);
    }

    /**
     * Where you are in the console, for the corner of the console itself.
     *
     * @param view what the console is showing
     * @return the text
     */
    String state(View view) {
        if (view.flash() != null && !view.flash().isEmpty()) {
            return view.flash();
        }
        if (view.selectMode()) {
            return glyphs.scrolledMark() + " select mode";
        }
        Focus focus = view.focus();
        if (focus != null && focus.of() == Of.OVERVIEW) {
            return glyphs.focusMark() + " background work";
        }
        if (focus != null && focus.of() == Of.WORKER) {
            return glyphs.focusMark() + " worker " + position(focus);
        }
        if (focus != null && focus.of() == Of.JOB) {
            return glyphs.focusMark() + " job " + position(focus);
        }
        if (focus != null) {
            String title = focus.title() == null ? "" : focus.title();
            return glyphs.focusMark() + " " + position(focus)
                   + (title.isEmpty() ? "" : ": " + title);
        }
        // No "running" state here: that is the top line's job. This one says where you are in the
        // view -- following the tail, scrolled back, focused on one result -- which is a different
        // question, and one still worth answering while a request is in flight.
        if (!view.followBottom()) {
            int percent = view.maxScroll() <= 0
                    ? 100
                    : (int) Math.round(100.0 * view.scroll() / view.maxScroll());
            return glyphs.scrolledMark() + " " + percent + "% " + glyphs.dash() + " Ctrl+End to follow";
        }
        return glyphs.liveDot() + " live";
    }

    /**
     * Which keys apply here.
     *
     * @param view what the console is showing
     * @return the text
     */
    String hints(View view) {
        String separator = "  " + glyphs.bullet() + "  ";
        if (view.prompting()) {
            return "Enter submit" + separator + "Esc cancel";
        }
        if (view.selectMode()) {
            return "Drag to select" + separator + "hold at an edge to reach further"
                   + separator + "F4/Esc leave";
        }
        if (view.focus() != null && view.focus().of() == Of.OVERVIEW) {
            return "Up/Down pick" + separator + "Enter open" + separator + "Tab/S-Tab panes"
                   + separator + "Esc live";
        }
        if (view.focus() != null) {
            // Tab cycles workers and jobs whichever kind of region is focused, so saying "results"
            // here -- which it did whenever a result had been focused by clicking it -- named a key
            // that does something else.
            return "Tab/S-Tab panes" + separator + "PgUp/PgDn scroll" + separator
                   + "Ctrl+End follow" + separator + "Esc live";
        }
        return "F1 Help" + separator + "F5 Running" + separator + "Tab panes" + separator
               + "F4 Select" + separator + "F2 Interrupt" + separator + "Ctrl+Q Quit";
    }

    /** "3/7", or empty when the list it counted is empty. */
    private static String position(Focus focus) {
        return focus.count() == 0 ? "" : Math.max(1, focus.index()) + "/" + focus.count();
    }
}
