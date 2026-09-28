package com.eonmux.cadetcoder.ui;

import com.eonmux.cadetcoder.ui.OutputLineStyler.Kind;
import dev.tamboui.style.Style;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class OutputLineStylerTest {

    @Test
    public void classifiesFormatterPrefixes() {
        assertThat(OutputLineStyler.classify("[OK] saved")).isEqualTo(Kind.SUCCESS);
        assertThat(OutputLineStyler.classify("[WARN] careful")).isEqualTo(Kind.WARNING);
        assertThat(OutputLineStyler.classify("[ERR] boom")).isEqualTo(Kind.ERROR);
        assertThat(OutputLineStyler.classify("[ERROR] boom")).isEqualTo(Kind.ERROR);
        assertThat(OutputLineStyler.classify("ℹ heads up")).isEqualTo(Kind.INFO);
    }

    @Test
    public void classifiesShellAndStructuralLines() {
        assertThat(OutputLineStyler.classify("> chat hello")).isEqualTo(Kind.USER);
        assertThat(OutputLineStyler.classify("*** interrupted ***")).isEqualTo(Kind.SYSTEM);
        assertThat(OutputLineStyler.classify("```")).isEqualTo(Kind.CODE);
        assertThat(OutputLineStyler.classify("=== Welcome ===")).isEqualTo(Kind.HEADER);
        assertThat(OutputLineStyler.classify("-- Section --")).isEqualTo(Kind.SUBHEADER);
        assertThat(OutputLineStyler.classify("======")).isEqualTo(Kind.HEADER);
        assertThat(OutputLineStyler.classify("════════")).isEqualTo(Kind.HEADER);
    }

    @Test
    public void leadingWhitespaceIsTolerated() {
        assertThat(OutputLineStyler.classify("   [OK] indented")).isEqualTo(Kind.SUCCESS);
    }

    @Test
    public void unrecognisedIsNormal() {
        assertThat(OutputLineStyler.classify("just some output")).isEqualTo(Kind.NORMAL);
        assertThat(OutputLineStyler.classify("")).isEqualTo(Kind.NORMAL);
        assertThat(OutputLineStyler.classify("   ")).isEqualTo(Kind.NORMAL);
        assertThat(OutputLineStyler.classify(null)).isEqualTo(Kind.NORMAL);
        // A short run of '=' is not treated as a rule.
        assertThat(OutputLineStyler.classify("==")).isEqualTo(Kind.NORMAL);
    }

    @Test
    public void styleForNullThemeIsEmptyNotNull() {
        assertThat(OutputLineStyler.styleFor(Kind.ERROR, null)).isEqualTo(Style.EMPTY);
        assertThat(OutputLineStyler.styleFor("[ERR] x", null)).isEqualTo(Style.EMPTY);
    }

    @Test
    public void styleForRealThemeIsNonNullForEveryKind() {
        TuiTheme theme = TuiThemeManager.getTheme("matrix");
        assertThat(theme).isNotNull();
        for (Kind k : Kind.values()) {
            assertThat(OutputLineStyler.styleFor(k, theme)).isNotNull();
        }
    }

    @Test
    public void classifiesTheGlyphMarkerVocabularyToo() {
        // The console emits glyphs on a Unicode terminal and the bracketed words on one that cannot
        // render them. Classification must recognise BOTH regardless of the terminal it is running
        // on: output captured on one machine is routinely rendered on another.
        assertThat(OutputLineStyler.classify("✓ saved")).isEqualTo(Kind.SUCCESS);
        assertThat(OutputLineStyler.classify("⚠ careful")).isEqualTo(Kind.WARNING);
        assertThat(OutputLineStyler.classify("✗ boom")).isEqualTo(Kind.ERROR);
        assertThat(OutputLineStyler.classify("ℹ heads up")).isEqualTo(Kind.INFO);
        assertThat(OutputLineStyler.classify("[i] heads up")).isEqualTo(Kind.INFO);
        assertThat(OutputLineStyler.classify("▎ Welcome")).isEqualTo(Kind.HEADER);
        assertThat(OutputLineStyler.classify("▸ Thinking")).isEqualTo(Kind.SUBHEADER);
    }

    @Test
    public void everyMarkerTheGlyphSetCanEmitIsClassified() {
        // Guards the coupling directly: whatever Glyphs decides to emit, this classifier has to
        // recognise, or the shell renders that line as plain body text and -- for a sub-header --
        // silently stops opening a section for it.
        for (Glyphs g : new Glyphs[]{Glyphs.UNICODE, Glyphs.ASCII}) {
            assertThat(OutputLineStyler.classify(g.successMarker() + " done")).isEqualTo(Kind.SUCCESS);
            assertThat(OutputLineStyler.classify(g.warningMarker() + " careful")).isEqualTo(Kind.WARNING);
            assertThat(OutputLineStyler.classify(g.errorMarker() + " boom")).isEqualTo(Kind.ERROR);
            assertThat(OutputLineStyler.classify(g.infoMarker() + " note")).isEqualTo(Kind.INFO);
            assertThat(OutputLineStyler.classify(g.headerMarker() + " Title" + g.headerCloser()))
                    .isEqualTo(Kind.HEADER);
            assertThat(OutputLineStyler.classify(g.subheaderMarker() + " Step" + g.subheaderCloser()))
                    .isEqualTo(Kind.SUBHEADER);
        }
    }

    @Test
    public void aMarkdownListItemIsNotMistakenForAStatusLine() {
        // The ASCII markers are bracketed words precisely so that ordinary prose cannot collide with
        // them. A model writing a bulleted list must keep rendering as a list.
        assertThat(OutputLineStyler.classify("- a list item")).isEqualTo(Kind.NORMAL);
        assertThat(OutputLineStyler.classify("* a list item")).isEqualTo(Kind.NORMAL);
        assertThat(OutputLineStyler.classify("+ a list item")).isEqualTo(Kind.NORMAL);
    }
}
