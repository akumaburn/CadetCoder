package com.eonmux.cadetcoder.ui;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contracts for {@link ThemePanels}, the console's painted backgrounds.
 */
public class ThemePanelsTest {

    private static final String BG = "\u001B[48;2;35;40;51m"; // #232830, the modern surface
    private static final String RESET = "\u001B[0m";

    @Test
    public void anEmptyBackgroundPaintsNothing() {
        String block = "✓ done";
        assertThat(ThemePanels.paint(block, null, false, 0)).isSameAs(block);
        assertThat(ThemePanels.paint(block, "", false, 0)).isSameAs(block);
    }

    @Test
    public void aNullBlockStaysNull() {
        assertThat(ThemePanels.paint(null, BG, false, 0)).isNull();
        assertThat(ThemePanels.paint("", BG, false, 0)).isEmpty();
    }

    @Test
    public void aChipPadsEveryLineToTheWidestLinePlusTwo() {
        String painted = ThemePanels.paint("⚠ short\n⚠ a longer line", BG, false, 0);

        String[] lines = painted.split("\n", -1);

        // Both lines open with the background; the visible text is untouched by the escapes.
        // (The stripped text carries the padding -- painted spaces are still spaces -- so the
        // content is compared without its trailing pad.)
        assertThat(lines[0]).startsWith(BG + "⚠ short").endsWith(RESET);
        assertThat(AnsiStripper.strip(lines[0]).stripTrailing()).isEqualTo("⚠ short");
        assertThat(AnsiStripper.strip(lines[1]).stripTrailing()).isEqualTo("⚠ a longer line");
        // A solid rectangle: the shorter line carries background-carrying spaces to the common
        // extent, and that extent is the block's own -- two columns past the widest line.
        assertThat(AnsiStripper.strip(lines[0]).length()).isEqualTo(AnsiStripper.strip(lines[1]).length());
        assertThat(AnsiStripper.strip(lines[1]).length()).isEqualTo("⚠ a longer line".length() + 2);
    }

    @Test
    public void aFullWidthPanelPadsToTheTerminalWidth() {
        String painted = ThemePanels.paint("▎ Update check", BG, true, 40);

        assertThat(AnsiStripper.strip(painted)).hasSize(40);
        assertThat(painted).startsWith(BG + "▎ Update check");
    }

    @Test
    public void aFullWidthPanelFallsBackToTheBlockExtentWhenTheWidthIsUnknown() {
        // A run whose width nobody announced -- output piped to a file -- still gets its
        // rectangle, just one that hugs the text rather than a strip across an unknown terminal.
        String painted = ThemePanels.paint("▎ Update check", BG, true, TerminalWidth.UNKNOWN);

        assertThat(AnsiStripper.strip(painted)).hasSize("▎ Update check".length() + 2);
    }

    @Test
    public void aResetInsideTheLineDoesNotEndTheBackgroundMidLine() {
        // colourize closes its colour with a reset; if the paint did not restore the background
        // after every interior reset, the padding would render in the terminal's own colours and
        // the rectangle would fall apart.
        String colourised = "\u001B[38;2;229;192;123m⚠ careful" + RESET;
        String painted = ThemePanels.paint(colourised, BG, false, 0);

        assertThat(painted)
                .startsWith(BG)
                .contains(RESET + BG)   // the interior reset re-opens the background
                .endsWith("  " + RESET);
        // The visible text is the colourised text plus the chip's two padded columns.
        assertThat(AnsiStripper.strip(painted).stripTrailing()).isEqualTo("⚠ careful");
    }

    @Test
    public void everyLineOfThePanelCarriesTheBackground() {
        // One escape at the top of a multi-line block bleeds background onto whatever prints
        // after the block only in terminals that keep SGR state across newlines -- and loses the
        // rectangle on every terminal that does not. Each line carries its own.
        String painted = ThemePanels.paint("one\ntwo", BG, false, 0);

        String[] lines = painted.split("\n", -1);
        assertThat(lines).hasSize(2);
        assertThat(lines[0]).startsWith(BG).endsWith(RESET);
        assertThat(lines[1]).startsWith(BG).endsWith(RESET);
    }
}
