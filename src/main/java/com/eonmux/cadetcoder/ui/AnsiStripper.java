package com.eonmux.cadetcoder.ui;

import java.util.regex.Pattern;

/**
 * Removes ANSI terminal escape sequences from text so it can be stored and rendered as clean,
 * plain content in the interactive TUI.
 *
 * <p>Only genuine <em>escape-prefixed</em> sequences are removed. This is deliberate: a naive
 * stripper that matched a bare {@code "[..m"} or {@code "[..<letter>"} (without the leading ESC)
 * would corrupt ordinary text that merely looks like a CSI sequence — for example
 * {@code "[OK]" -> "K]"} or {@code "[Thread-7] INFO ..." -> "hread-7] INFO ..."}, and it would
 * strip the {@code "[..m"} body of a colour code while leaving the orphan ESC byte behind (which
 * then renders as a stray control glyph). The console's markers are what {@link OutputLineStyler}
 * keys off, so all of them must survive: the single-column glyphs {@code ✓ ⚠ ✗ ℹ ▎ ▸} and the
 * bracketed fallbacks a non-Unicode terminal gets instead ({@code [OK]}, {@code [WARN]},
 * {@code [ERR]}, {@code [i]}, {@code === … ===}, {@code -- … --}). All three patterns below are
 * anchored on a literal ESC byte, so none of them can touch a marker.</p>
 *
 * <p>Pure and side-effect free, so it is trivially unit-testable without a terminal. Escape bytes
 * are expressed with the regex {@code \\x1b} (ESC) and {@code \\x07} (BEL) metacharacters rather
 * than literal control bytes, so the source stays readable.</p>
 */
public final class AnsiStripper {

    /** CSI sequence: {@code ESC [ <params> <intermediates> <final>} (colours, cursor moves, erase). */
    private static final Pattern CSI = Pattern.compile("\\x1b\\[[0-9;?]*[ -/]*[@-~]");

    /** OSC sequence: {@code ESC ] ... <BEL | ST>}, where ST is {@code ESC \\} (hyperlinks, titles). */
    private static final Pattern OSC = Pattern.compile("\\x1b\\][\\s\\S]*?(?:\\x07|\\x1b\\\\)");

    /** Any stray escape byte left by an unterminated or otherwise unmatched sequence. */
    private static final Pattern STRAY_ESC = Pattern.compile("\\x1b");

    private AnsiStripper() {
    }

    /**
     * Strip ANSI escape sequences from {@code text}. Returns {@code ""} for {@code null} input.
     * Plain text (including bracketed markers like {@code [OK]}) is returned unchanged.
     */
    public static String strip(String text) {
        if (text == null) {
            return "";
        }
        String out = CSI.matcher(text).replaceAll("");
        out = OSC.matcher(out).replaceAll("");
        // A lone ESC must never leak into the rendered transcript as a control glyph.
        out = STRAY_ESC.matcher(out).replaceAll("");
        return out;
    }
}
