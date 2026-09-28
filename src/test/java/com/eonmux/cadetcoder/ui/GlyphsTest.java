package com.eonmux.cadetcoder.ui;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class GlyphsTest {

    @Test
    public void unicodeAndAsciiVariantsDiffer() {
        assertThat(Glyphs.UNICODE.isUnicode()).isTrue();
        assertThat(Glyphs.ASCII.isUnicode()).isFalse();
        assertThat(Glyphs.UNICODE.bullet()).isNotEqualTo(Glyphs.ASCII.bullet());
        assertThat(Glyphs.UNICODE.roundedBorders()).isTrue();
        assertThat(Glyphs.ASCII.roundedBorders()).isFalse();
    }

    @Test
    public void asciiGlyphsAreSevenBit() {
        Glyphs g = Glyphs.ASCII;
        String all = g.bullet() + g.liveDot() + g.scrolledMark() + g.focusMark()
                + g.sectionMark() + g.ellipsis() + g.ok() + g.error() + g.spinner(0) + g.rule(3);
        for (int i = 0; i < all.length(); i++) {
            assertThat((int) all.charAt(i)).isLessThan(128);
        }
    }

    @Test
    public void spinnerWrapsAndIsStableForBothSets() {
        for (Glyphs g : new Glyphs[]{Glyphs.UNICODE, Glyphs.ASCII}) {
            assertThat(g.spinnerFrames()).isGreaterThan(0);
            // Wrapping: frame and frame+frames() map to the same glyph; negatives are tolerated.
            assertThat(g.spinner(0)).isEqualTo(g.spinner(g.spinnerFrames()));
            assertThat(g.spinner(-1)).isEqualTo(g.spinner(g.spinnerFrames() - 1));
        }
    }

    @Test
    public void ruleRepeatsToWidthAndClampsToEmpty() {
        assertThat(Glyphs.ASCII.rule(4)).isEqualTo("----");
        assertThat(Glyphs.UNICODE.rule(2)).isEqualTo("──");
        assertThat(Glyphs.UNICODE.rule(0)).isEmpty();
        assertThat(Glyphs.ASCII.rule(-5)).isEmpty();
    }

    @Test
    public void systemReturnsANonNullSet() {
        assertThat(Glyphs.system()).isNotNull();
    }

    @Test
    public void consoleMarkersAreOneColumnWideOnAUnicodeTerminal() {
        Glyphs g = Glyphs.UNICODE;

        // The point of the glyph markers: every kind of line puts its message in the same column, so
        // a run of mixed output reads as a column of text with a column of status beside it. The
        // bracketed words they replaced were 4, 5, 6 and 1 characters wide, so nothing ever lined up.
        assertThat(g.successMarker()).hasSize(1);
        assertThat(g.warningMarker()).hasSize(1);
        assertThat(g.errorMarker()).hasSize(1);
        assertThat(g.infoMarker()).hasSize(1);
        assertThat(g.headerMarker()).hasSize(1);
        assertThat(g.subheaderMarker()).hasSize(1);
    }

    @Test
    public void headerAndSubheaderClosersAreEmptyOnlyInTheUnicodeForm() {
        assertThat(Glyphs.UNICODE.headerCloser()).isEmpty();
        assertThat(Glyphs.UNICODE.subheaderCloser()).isEmpty();

        // The ASCII forms are the delimited shapes the console used before glyphs, so a terminal
        // that cannot render the glyphs still gets something that reads as a heading.
        assertThat(Glyphs.ASCII.headerMarker() + " x" + Glyphs.ASCII.headerCloser())
                .isEqualTo("=== x ===");
        assertThat(Glyphs.ASCII.subheaderMarker() + " x" + Glyphs.ASCII.subheaderCloser())
                .isEqualTo("-- x --");
    }

    @Test
    public void asciiConsoleMarkersCannotBeMistakenForAMarkdownListBullet() {
        // MarkdownRenderer treats a leading "-", "*" or "+" followed by whitespace as a list item.
        // An ASCII fallback of "+" or "-" would therefore make every success line in a rendered
        // result turn into a bullet -- and, worse, make a model-written list item classify as a
        // status line. The bracketed words cannot collide.
        Glyphs g = Glyphs.ASCII;
        for (String marker : new String[]{g.successMarker(), g.warningMarker(), g.errorMarker(),
                                          g.infoMarker(), g.headerMarker(), g.subheaderMarker()}) {
            assertThat(marker).doesNotMatch("^[-*+]$");
        }
    }

    @Test
    public void asciiConsoleMarkersAreSevenBitToo() {
        Glyphs g = Glyphs.ASCII;
        String all = g.successMarker() + g.warningMarker() + g.errorMarker() + g.infoMarker()
                + g.headerMarker() + g.headerCloser() + g.subheaderMarker() + g.subheaderCloser();
        for (int i = 0; i < all.length(); i++) {
            assertThat((int) all.charAt(i)).isLessThan(128);
        }
    }
}
