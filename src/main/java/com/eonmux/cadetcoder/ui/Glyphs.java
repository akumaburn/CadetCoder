package com.eonmux.cadetcoder.ui;

/**
 * A small set of UI glyphs with a Unicode variant and a plain-ASCII fallback, so the interactive
 * TUI degrades gracefully on terminals (or locales) that cannot render box-drawing, braille or
 * other multibyte symbols.
 *
 * <p>Two ready-made instances are provided ({@link #UNICODE} and {@link #ASCII}); {@link #system()}
 * picks between them by inspecting the JVM's file encoding and the {@code LANG}/{@code LC_*}
 * environment variables. Everything here is a pure {@link String} (no terminal dependency) so it is
 * trivially unit-testable.</p>
 */
public final class Glyphs {

    /** Rich Unicode glyph set (braille spinner, box-drawing rules, round bullets). */
    public static final Glyphs UNICODE = new Glyphs(true);

    /** Plain 7-bit ASCII glyph set for maximum terminal compatibility. */
    public static final Glyphs ASCII = new Glyphs(false);

    private static final String[] SPINNER_UNICODE = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
    private static final String[] SPINNER_ASCII    = {"|", "/", "-", "\\"};

    private final boolean unicode;

    private Glyphs(boolean unicode) {
        this.unicode = unicode;
    }

    /**
     * Choose a glyph set from the runtime environment. Returns {@link #UNICODE} when the JVM file
     * encoding or the locale environment advertises UTF-8/Unicode, otherwise {@link #ASCII}.
     */
    public static Glyphs system() {
        return detectUnicode() ? UNICODE : ASCII;
    }

    /** Whether this glyph set uses Unicode symbols. */
    public boolean isUnicode() {
        return unicode;
    }

    /** Number of animation frames in {@link #spinner(int)}. */
    public int spinnerFrames() {
        return (unicode ? SPINNER_UNICODE : SPINNER_ASCII).length;
    }

    /** A spinner frame for the given (unbounded) tick; wraps around automatically. */
    public String spinner(int frame) {
        String[] frames = unicode ? SPINNER_UNICODE : SPINNER_ASCII;
        return frames[Math.floorMod(frame, frames.length)];
    }

    /** A mid-line separator bullet ({@code ·} or {@code -}). */
    public String bullet() {
        return unicode ? "·" : "-";
    }

    /** The "following live output" indicator ({@code ●} or {@code *}). */
    public String liveDot() {
        return unicode ? "●" : "*";
    }

    /** The "scrolled away from the bottom" indicator ({@code ▲} or {@code ^}). */
    public String scrolledMark() {
        return unicode ? "▲" : "^";
    }

    /** The focused-result marker shown in the console title in focus mode ({@code ▣} or {@code #}). */
    public String focusMark() {
        return unicode ? "▣" : "#";
    }

    /** The marker for a sub-section / sub-agent step ({@code ▸} or {@code >}). */
    public String sectionMark() {
        return unicode ? "▸" : ">";
    }

    /** The "this is the selected one" mark used in listings ({@code ★} or {@code *}). */
    public String star() {
        return unicode ? "★" : "*";
    }

    /** Truncation ellipsis ({@code …} or {@code ...}). */
    public String ellipsis() {
        return unicode ? "…" : "...";
    }

    /** A separating dash for inline labels ({@code —} or {@code -}). */
    public String dash() {
        return unicode ? "—" : "-";
    }

    /** Success status marker ({@code ✓} or {@code +}). */
    public String ok() {
        return unicode ? "✓" : "+";
    }

    /** Error status marker ({@code ✗} or {@code x}). */
    public String error() {
        return unicode ? "✗" : "x";
    }

    /**
     * Marker that opens a console <em>success</em> line.
     *
     * <p>Distinct from {@link #ok()}: that one is a compact status tick rendered inside a TUI title,
     * where a bracketed word would not fit. These four console markers are the ones
     * {@link ThemedOutputFormatter} puts at the start of a line, and on a non-Unicode terminal they
     * fall back to the bracketed words the console used before glyphs -- not to a bare punctuation
     * character, which would be indistinguishable from a Markdown list bullet
     * ({@code MarkdownRenderer}'s unordered-item pattern matches a leading {@code -}, {@code *} or
     * {@code +}).</p>
     *
     * @return {@code "✓"} or {@code "[OK]"}
     */
    public String successMarker() {
        return unicode ? "✓" : "[OK]";
    }

    /** Marker that opens a console warning line ({@code ⚠} or {@code [WARN]}). */
    public String warningMarker() {
        return unicode ? "⚠" : "[WARN]";
    }

    /** Marker that opens a console error line ({@code ✗} or {@code [ERR]}). */
    public String errorMarker() {
        return unicode ? "✗" : "[ERR]";
    }

    /**
     * The quiet marker ({@code ℹ} or {@code [i]}).
     *
     * <p>No longer printed in front of a notice. Information was the default channel by far, so
     * the marker sat in front of most of what the program printed and marked nothing out;
     * {@code ThemedOutputFormatter.printInfo} emits the message alone.</p>
     *
     * <p>What it is still for: a message or a captured block holding a Markdown fence carries it
     * on every line, because a fence that starts its line is live and an odd number of them would
     * pair with the next fence printed. A marker in front makes each of them inert, and this is
     * the marker to use for that, being the one that claims the least.</p>
     */
    public String infoMarker() {
        return unicode ? "ℹ" : "[i]";
    }

    /**
     * Marker that opens a console header line ({@code ▎} or {@code ===}).
     *
     * <p>The Unicode form is the same left bar {@code MarkdownRenderer} puts in front of a rendered
     * Markdown heading, so a header a command prints and a heading the model wrote look alike.</p>
     */
    public String headerMarker() {
        return unicode ? "▎" : "===";
    }

    /** Closing delimiter for a header, empty in the Unicode form ({@code ""} or {@code " ==="}). */
    public String headerCloser() {
        return unicode ? "" : " ===";
    }

    /**
     * Marker opening a new iteration of an agentic run ({@code •} or {@code [*]}).
     *
     * <p>Distinct from {@link #subheaderMarker()} on purpose: a run's turns and the sub-steps inside
     * a turn are different things, and giving them the same marker made a long transcript read as one
     * flat list. Distinct from {@link #bullet()} too, which is the list bullet — sharing it would
     * make every bulleted line the model writes look like the start of a turn.</p>
     */
    public String iterationMarker() {
        return unicode ? "\u2022" : "[*]";
    }

    /** Marker that opens a console sub-header line ({@code ▸} or {@code --}). */
    public String subheaderMarker() {
        return unicode ? "▸" : "--";
    }

    /** Closing delimiter for a sub-header, empty in the Unicode form ({@code ""} or {@code " --"}). */
    public String subheaderCloser() {
        return unicode ? "" : " --";
    }


    /** A horizontal rule of the requested width ({@code ─…} or {@code -…}); empty when width &lt;= 0. */
    public String rule(int width) {
        if (width <= 0) {
            return "";
        }
        return (unicode ? "─" : "-").repeat(width);
    }

    /** Whether rounded box-drawing borders are safe to use (Unicode only). */
    public boolean roundedBorders() {
        return unicode;
    }

    private static boolean detectUnicode() {
        String enc = System.getProperty("file.encoding", "");
        if (enc != null && enc.toLowerCase().contains("utf")) {
            return true;
        }
        for (String var : new String[]{"LC_ALL", "LC_CTYPE", "LANG"}) {
            String v = System.getenv(var);
            if (v != null) {
                String lv = v.toLowerCase();
                if (lv.contains("utf-8") || lv.contains("utf8")) {
                    return true;
                }
            }
        }
        return false;
    }
}
