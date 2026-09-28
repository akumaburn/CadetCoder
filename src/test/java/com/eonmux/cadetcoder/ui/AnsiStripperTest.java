package com.eonmux.cadetcoder.ui;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link AnsiStripper}. The central guarantee is that ordinary text which merely looks
 * like an escape sequence (notably the {@code [OK]} / {@code [Thread-N]} markers) is preserved,
 * while genuine ESC-prefixed sequences are removed without leaving an orphan ESC behind.
 */
public class AnsiStripperTest {

    /** ESC (0x1B) and BEL (0x07) built without embedding control bytes in the source. */
    private static final String ESC = String.valueOf((char) 0x1B);
    private static final String BEL = String.valueOf((char) 0x07);

    @Test
    public void nullBecomesEmpty() {
        assertThat(AnsiStripper.strip(null)).isEmpty();
    }

    @Test
    public void plainTextIsUnchanged() {
        assertThat(AnsiStripper.strip("plain text 123")).isEqualTo("plain text 123");
        assertThat(AnsiStripper.strip("")).isEqualTo("");
    }

    @Test
    public void bracketMarkersArePreserved() {
        // The exact regression: the old stripper turned "[OK]" -> "K]" and "[Thread-27]" -> "hread-27]".
        assertThat(AnsiStripper.strip("[OK] Using connector: opencode-go"))
                .isEqualTo("[OK] Using connector: opencode-go");
        assertThat(AnsiStripper.strip("[Thread-27] INFO some.Logger - hello"))
                .isEqualTo("[Thread-27] INFO some.Logger - hello");
        assertThat(AnsiStripper.strip("[ERR] boom")).isEqualTo("[ERR] boom");
        assertThat(AnsiStripper.strip("[WARN] careful")).isEqualTo("[WARN] careful");
    }

    @Test
    public void stripsSgrColourCodes() {
        assertThat(AnsiStripper.strip(ESC + "[36mhello" + ESC + "[0m")).isEqualTo("hello");
        assertThat(AnsiStripper.strip("a" + ESC + "[1;31mB" + ESC + "[0mc")).isEqualTo("aBc");
    }

    @Test
    public void stripsCursorAndEraseCodes() {
        assertThat(AnsiStripper.strip("x" + ESC + "[2Ky")).isEqualTo("xy");
        assertThat(AnsiStripper.strip(ESC + "[1;1Hhome")).isEqualTo("home");
    }

    @Test
    public void stripsOscHyperlink() {
        // OSC-8 hyperlink: ESC ] 8 ; ; URL ST  text  ESC ] 8 ; ; ST
        String link = ESC + "]8;;https://example.com" + ESC + "\\" + "docs" + ESC + "]8;;" + ESC + "\\";
        assertThat(AnsiStripper.strip(link)).isEqualTo("docs");
    }

    @Test
    public void stripsBelTerminatedOsc() {
        assertThat(AnsiStripper.strip("before" + ESC + "]52;c;Zm9v" + BEL + "after"))
                .isEqualTo("beforeafter");
    }

    @Test
    public void removesStrayEscapeByte() {
        // An orphan ESC (e.g. left by an upstream partial strip) must not survive as a control glyph.
        assertThat(AnsiStripper.strip("x" + ESC + "y")).isEqualTo("xy");
        assertThat(AnsiStripper.strip(ESC + "`code`" + ESC)).isEqualTo("`code`");
    }

    @Test
    public void mixedRealAndLiteralBrackets() {
        // A coloured "[OK]" line: colour codes removed, the literal marker preserved intact.
        assertThat(AnsiStripper.strip(ESC + "[32m[OK] done" + ESC + "[0m")).isEqualTo("[OK] done");
    }
}
