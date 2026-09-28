package com.eonmux.cadetcoder.ui;

/**
 * How many columns the console output may use, when that is knowable at all.
 *
 * <h2>Why it is told rather than measured</h2>
 *
 * <p>Asking the terminal its size means building a JLine system terminal, which on a 315 ms startup
 * costs about 60 ms of it -- on every invocation, including the ones whose output is three lines
 * long -- and briefly takes hold of a device this process otherwise never touches. What it buys is
 * breaking a handful of messages on a space instead of wherever the terminal happens to wrap them.
 * That is not a trade worth making on every run, so the width is taken from whoever already knows
 * it: {@code COLUMNS=100 cadet help}, an exported {@code COLUMNS}, or
 * {@code -Dcadet.terminalWidth=100} for a script that is rendering to something other than a
 * terminal.</p>
 *
 * <h2>Why unknown means "do not wrap"</h2>
 *
 * <p>Output that is piped, redirected or captured has no width, and re-wrapping it there would put
 * line breaks into the middle of something another program is parsing. The absence of an answer is
 * therefore an answer: leave the text exactly as the caller wrote it.</p>
 */
public final class TerminalWidth {

    /** No width is known, so nothing should be re-wrapped. */
    public static final int UNKNOWN = 0;

    /** The property a script sets when it is rendering to a known width. */
    static final String PROPERTY = "cadet.terminalWidth";

    /** The environment variable a shell sets, and that {@code COLUMNS=100 cadet ...} passes on. */
    static final String VARIABLE = "COLUMNS";

    /**
     * Below this, wrapping does more damage than overflowing: a column count this small breaks
     * ordinary words apart and leaves a message no easier to read than the terminal's own wrap.
     */
    static final int NARROWEST = 20;

    /** Above this, a width is a mistake rather than a terminal, and clamping it costs nothing. */
    static final int WIDEST = 1000;

    private TerminalWidth() {
    }

    /**
     * The columns available for the tool's own messages.
     *
     * @return the width, or {@link #UNKNOWN} when nobody has said
     */
    public static int columns() {
        return resolve(System.getProperty(PROPERTY), System.getenv(VARIABLE));
    }

    /**
     * The same decision, over values a test can supply.
     *
     * <p>The property wins outright when it parses, {@code 0} included: a script that says "no
     * width" is overriding an exported {@code COLUMNS} it cannot unset, and falling through to the
     * variable would ignore the only instruction it was given.</p>
     *
     * @param property the {@code cadet.terminalWidth} value, or {@code null}
     * @param variable the {@code COLUMNS} value, or {@code null}
     * @return the width to use, or {@link #UNKNOWN}
     */
    static int resolve(String property, String variable) {
        Integer told = number(property);
        if (told == null) {
            told = number(variable);
        }
        if (told == null || told < NARROWEST) {
            return UNKNOWN;
        }
        return Math.min(told, WIDEST);
    }

    /** @return the value as a number, or {@code null} when it is absent or not one */
    private static Integer number(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }
}
