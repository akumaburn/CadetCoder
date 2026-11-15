package com.eonmux.cadetcoder.ui;

/**
 * Paints a rendered block of console output with a background colour, so a line reads as a
 * rectangular area of the console rather than bare text.
 *
 * <p>This is the console counterpart of what the interactive shell does through
 * {@link OutputLineStyler}: the model is the opencode theme's, where areas are separated by
 * painting their background a small step away from the base. Two widths cover the kinds the
 * formatter paints:</p>
 *
 * <ul>
 *   <li>{@code FULL} -- every line is padded to the terminal's width, so a banner reads as a
 *   strip across the console and the edge of a section stays visible while scrolling. This is
 *   the padding a terminal shows as colour; a copy of the text carries the spaces along, the
 *   same trade the one-row activity bars already make.</li>
 *   <li>{@code CHIP} -- every line is padded to the longest line of the block plus two columns,
 *   so a notice reads as a solid block around exactly its own text.</li>
 * </ul>
 *
 * <p>An empty background paints nothing: a theme that declares no background (see
 * {@link ColorTheme}'s background fields) keeps today's bare-text look. The class is pure and
 * side-effect free so it is unit-testable without a terminal.</p>
 */
final class ThemePanels {

    /** The escape every colourised line ends with, and which therefore ends a background too. */
    private static final String RESET = "\u001B[0m";

    private ThemePanels() {
    }

    /**
     * Paints every line of {@code block} with {@code background}.
     *
     * @param block     the already-rendered, already-colourised text; may be {@code null}
     * @param background the background SGR escape, or {@code null}/empty for no painting
     * @param fullWidth pad to the terminal width ({@code true}) or to the block's own extent
     * @param columns   the terminal width in columns, or {@link TerminalWidth#UNKNOWN}
     * @return the painted block, or {@code block} itself when there is nothing to paint
     */
    static String paint(String block, String background, boolean fullWidth, int columns) {
        if (block == null || block.isEmpty() || background == null || background.isEmpty()) {
            return block;
        }

        String[] lines = block.split("\n", -1);

        // The width every line of the block is set to. A full-width panel on a terminal whose
        // width nobody announced falls back to the block's own extent, which is still a
        // rectangle -- just one that hugs the text.
        int widest = 0;
        for (String line : lines) {
            widest = Math.max(widest, AnsiStripper.strip(line).length());
        }
        int target = fullWidth && columns > TerminalWidth.UNKNOWN
                     ? columns
                     : widest + 2;

        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append('\n');
            }
            paintLine(out, lines[i], background, target);
        }
        return out.toString();
    }

    /**
     * Appends one line, opened with the background, closed with a reset, and padded with
     * background-carrying spaces to {@code target} columns.
     *
     * <p>A reset inside the line -- the one {@code colorize} appends, or any a richer line
     * carries -- would end the background mid-line, so every reset inside is followed by the
     * background again: the colour survives to the padding and the line reads as one area.</p>
     */
    private static void paintLine(StringBuilder out, String line, String background, int target) {
        out.append(background);
        out.append(line.replace(RESET, RESET + background));
        int visible = AnsiStripper.strip(line).length();
        int pad     = Math.max(0, target - visible);
        out.append(" ".repeat(pad));
        out.append(RESET);
    }
}
