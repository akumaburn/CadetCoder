package com.eonmux.cadetcoder.ui;

/**
 * How wide a line of the tool's own prose may be drawn.
 *
 * <h2>Why a cap, when the terminal is wider</h2>
 *
 * <p>A wide terminal is room to put things side by side. It is not a reason to draw one sentence
 * across all of it. Prose set to 200 columns is hard to read, because the eye has to travel the
 * whole width and then find the start of the next line, and it loses the row on the way back. The
 * readable range for running text is roughly 45 to 90 characters. This console also carries paths,
 * model identifiers and command lines inside its sentences, so the cap sits a little above that
 * range rather than inside it.</p>
 *
 * <h2>What the cap does not apply to</h2>
 *
 * <p>Code, tables and captured command output keep the full width. Their line breaks are part of
 * what they say, and a narrower measure would either re-flow them or wrap them twice. The cap is
 * for text that may be broken anywhere a space falls.</p>
 */
public final class ProseMeasure {

    /**
     * Widest a line of prose is drawn, whatever the terminal offers.
     *
     * <p>Above the 45-to-90 range that running text reads best in, because a sentence here can
     * carry a path or a model identifier that should not be broken across two rows.</p>
     */
    public static final int READABLE_COLUMNS = 96;

    private ProseMeasure() {
    }

    /**
     * The measure to set prose in, given the room there is for it.
     *
     * @param available the columns available, or {@link TerminalWidth#UNKNOWN} when nobody has said
     * @return the columns to wrap at; {@code available} unchanged when it is narrower than the cap,
     *         and unchanged when it is not a width at all
     */
    public static int fit(int available) {
        if (available <= 0) {
            return available;
        }
        return Math.min(available, READABLE_COLUMNS);
    }
}
