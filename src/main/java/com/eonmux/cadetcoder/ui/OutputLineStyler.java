package com.eonmux.cadetcoder.ui;

import dev.tamboui.style.Style;

/**
 * Classifies a line of (ANSI-stripped) shell output into a semantic {@link Kind} and maps it to a
 * {@link Style} from the active {@link TuiTheme}, so the interactive TUI can colour command output
 * by meaning (errors red, success green, the user's own command echo emphasised, and so on).
 *
 * <p>Classification keys off the stable textual prefixes emitted by
 * {@link ThemedOutputFormatter}, the shell's own command echo ({@code > cmd}) and system notices
 * ({@code *** ... ***}), plus code fences. Anything unrecognised renders as normal text. This is
 * pure and side-effect free so it is cheap to call once per visible line per frame and is
 * unit-testable without a terminal.</p>
 *
 * <p><b>Both marker vocabularies are recognised.</b> The formatter emits single-column glyphs on a
 * Unicode terminal ({@code ✓ ⚠ ✗ ℹ ▎ ▸}) and falls back to the bracketed words on a terminal that
 * cannot render them ({@code [OK] [WARN] [ERR] [i] === … === -- … --}), so this classifier accepts
 * either. Accepting both also means output captured on one machine and rendered on another still
 * colours correctly, and that the loggers' own {@code ✓}/{@code ✗} lines — which never went through
 * the formatter — are finally classified instead of rendering as plain body text.</p>
 */
public final class OutputLineStyler {

    /** Semantic category of an output line. */
    public enum Kind {
        USER, ERROR, WARNING, SUCCESS, INFO, HEADER, SUBHEADER, SYSTEM, CODE, NORMAL,

        /**
         * The marker opening one iteration of an agent loop.
         *
         * <p>Styled as a sub-header, and deliberately NOT one. The shell opens a navigable region
         * for every sub-header a command prints, which is right for a result and wrong for an
         * iteration: a long run emits dozens, so keyboard navigation filled up with "Iteration 7"
         * stops and the results worth returning to were buried among them. Its own kind, so it can
         * look like a heading without becoming somewhere to land.</p>
         */
        ITERATION
    }

    /**
     * The Unicode markers, spelled out here rather than read from {@link Glyphs}.
     *
     * <p>{@code Glyphs.system()} answers what <em>this</em> terminal can render; classification must
     * recognise a marker regardless of which terminal produced the line, so it matches both
     * vocabularies unconditionally. Keep these in step with {@code Glyphs}' marker methods and with
     * {@code ShellTranscript.cleanSubheader}, which strips the sub-header marker to title a section.</p>
     */
    public static final String SUCCESS_GLYPH   = "✓";
    public static final String WARNING_GLYPH   = "⚠";
    public static final String ERROR_GLYPH     = "✗";
    public static final String INFO_GLYPH      = "ℹ";
    public static final String HEADER_GLYPH    = "▎";
    public static final String SUBHEADER_GLYPH = "▸";

    /**
     * Opens a new iteration of an agentic run.
     *
     * <p>Spelled differently from {@link #SUBHEADER_GLYPH} so a turn boundary is distinguishable at
     * a glance from a step inside a turn — and, because it classifies as {@link Kind#ITERATION}
     * rather than {@link Kind#SUBHEADER}, it does not open a navigable section. See that constant
     * for why.</p>
     */
    public static final String ITERATION_GLYPH = "\u2022";

    /** The ASCII spelling of {@link #ITERATION_GLYPH}, in the same bracketed family as {@code [OK]}. */
    public static final String ITERATION_GLYPH_ASCII = "[*]";

    private OutputLineStyler() {
    }

    /** Classify a raw output line. Never returns {@code null}; blank/{@code null} input is NORMAL. */
    public static Kind classify(String line) {
        if (line == null) {
            return Kind.NORMAL;
        }
        String t = line.stripLeading();
        if (t.isEmpty()) {
            return Kind.NORMAL;
        }
        if (t.startsWith("[ERR]") || t.startsWith("[ERROR]") || t.startsWith(ERROR_GLYPH)) {
            return Kind.ERROR;
        }
        if (t.startsWith("[WARN]") || t.startsWith(WARNING_GLYPH)) {
            return Kind.WARNING;
        }
        if (t.startsWith("[OK]") || t.startsWith(SUCCESS_GLYPH)) {
            return Kind.SUCCESS;
        }
        if (t.startsWith(INFO_GLYPH) || t.startsWith("[i]")) {
            return Kind.INFO;
        }
        if (t.startsWith("> ")) {
            return Kind.USER;
        }
        if (t.startsWith("*** ")) {
            return Kind.SYSTEM;
        }
        if (t.startsWith("```")) {
            return Kind.CODE;
        }
        if (t.startsWith("=== ") && t.endsWith(" ===")) {
            return Kind.HEADER;
        }
        // The space is NOT required, matching the four status glyphs above and matching
        // ShellTranscript.cleanSubheader, which strips a bare marker. Requiring it here meant a
        // sub-header whose text was empty opened a section in the transcript while rendering as
        // ordinary body text.
        if (t.startsWith(HEADER_GLYPH)) {
            return Kind.HEADER;
        }
        if (isRule(t)) {
            return Kind.HEADER;
        }
        if (t.startsWith("-- ") && t.endsWith(" --")) {
            return Kind.SUBHEADER;
        }
        if (t.startsWith(SUBHEADER_GLYPH)) {
            return Kind.SUBHEADER;
        }
        if (t.startsWith(ITERATION_GLYPH) || t.startsWith(ITERATION_GLYPH_ASCII)) {
            return Kind.ITERATION;
        }
        return Kind.NORMAL;
    }

    /** Map a {@link Kind} to a theme {@link Style}; falls back to {@link Style#EMPTY} when no theme. */
    public static Style styleFor(Kind kind, TuiTheme theme) {
        if (theme == null) {
            return Style.EMPTY;
        }
        switch (kind) {
            case USER:
                return theme.getCommand();
            case ERROR:
                return theme.getError();
            case WARNING:
                return theme.getWarning();
            case SUCCESS:
                return theme.getSuccess();
            case INFO:
                return theme.getInfo();
            case HEADER:
                return theme.getAccent1();
            case SUBHEADER:
            case ITERATION:
                return theme.getAccent2();
            case SYSTEM:
                return theme.getDim();
            case CODE:
                return theme.getString();
            case NORMAL:
            default:
                return theme.getTextNormal();
        }
    }

    /** Convenience: classify {@code line} and return its style under {@code theme}. */
    public static Style styleFor(String line, TuiTheme theme) {
        return styleFor(classify(line), theme);
    }

    /** A horizontal rule: a run (length >= 4) of '=' or box-drawing line characters only. */
    private static boolean isRule(String t) {
        if (t.length() < 4) {
            return false;
        }
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c != '=' && c != '═' && c != '─') {
                return false;
            }
        }
        return true;
    }
}
