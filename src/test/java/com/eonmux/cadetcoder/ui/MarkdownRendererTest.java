package com.eonmux.cadetcoder.ui;

import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class MarkdownRendererTest {

    private MarkdownRenderer md;

    @Before
    public void setUp() {
        TuiTheme theme = TuiThemeManager.getTheme("matrix");
        md = new MarkdownRenderer(theme, Glyphs.UNICODE);
    }

    private List<Line> render(int width, String... lines) {
        return md.render(Arrays.asList(lines), width);
    }

    private static List<String> raw(List<Line> lines) {
        List<String> out = new ArrayList<>();
        for (Line l : lines) {
            out.add(l.rawContent());
        }
        return out;
    }

    private static boolean anySpanIs(Line line, String content) {
        for (Span s : line.spans()) {
            if (s.content().equals(content)) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void nullAndEmptyAndZeroWidthAreSafe() {
        assertThat(md.render(null, 40)).isEmpty();
        assertThat(md.render(Arrays.asList("x"), 0)).isEmpty();
        assertThat(render(40)).isEmpty();
    }

    @Test
    public void headingStripsHashes() {
        List<Line> out = render(60, "# Title");
        assertThat(out).hasSize(1);
        // The heading bar is on every level now: giving it to h2/h3 but not h1 indented the
        // subordinate heading behind a bar while its parent sat flush at column 0.
        assertThat(out.get(0).rawContent()).endsWith("Title");
        assertThat(out.get(0).rawContent()).doesNotContain("#");
    }

    @Test
    public void boldProducesAStyledSpanAndStripsMarkers() {
        List<Line> out = render(60, "this is **bold** text");
        assertThat(out).hasSize(1);
        assertThat(out.get(0).rawContent()).isEqualTo("this is bold text");
        assertThat(anySpanIs(out.get(0), "bold")).isTrue();
    }

    @Test
    public void italicProducesAStyledSpanAndStripsMarkers() {
        List<Line> out = render(60, "a *word* here");
        assertThat(out.get(0).rawContent()).isEqualTo("a word here");
        assertThat(anySpanIs(out.get(0), "word")).isTrue();
    }

    @Test
    public void inlineCodeStripsBackticks() {
        List<Line> out = render(60, "run `ls -la` now");
        assertThat(out.get(0).rawContent()).isEqualTo("run ls -la now");
    }

    @Test
    public void linkKeepsTextDropsUrlFromDisplay() {
        List<Line> out = render(60, "see [the docs](https://example.com/x) please");
        assertThat(out.get(0).rawContent()).isEqualTo("see the docs please");
        assertThat(out.get(0).rawContent()).doesNotContain("https://example.com");
    }

    @Test
    public void intrawordUnderscoresAndStarsAreNotEmphasis() {
        // The key code/path-safety guarantee: identifiers and expressions survive verbatim.
        assertThat(render(80, "open my_file_name.txt now").get(0).rawContent())
                .isEqualTo("open my_file_name.txt now");
        assertThat(render(80, "compute a*b*c value").get(0).rawContent())
                .isEqualTo("compute a*b*c value");
    }

    @Test
    public void fencedCodeBlockShowsContentVerbatimAndConsumesFences() {
        List<Line> out = render(60, "```java", "int x = 1;", "System.out.println(x);", "```");
        List<String> raws = raw(out);
        // No fence markers leak into the rendered output.
        assertThat(raws).noneMatch(s -> s.contains("```"));
        // Code content is present (with a gutter prefix) and unmodified.
        assertThat(raws).anyMatch(s -> s.contains("int x = 1;"));
        assertThat(raws).anyMatch(s -> s.contains("System.out.println(x);"));
        // A language label appears in the header.
        assertThat(raws).anyMatch(s -> s.contains("java"));
    }

    @Test
    public void unorderedListGetsABulletGlyph() {
        List<Line> out = render(60, "- first item");
        assertThat(out).hasSize(1);
        String r = out.get(0).rawContent();
        assertThat(r).contains("first item");
        assertThat(r).startsWith(Glyphs.UNICODE.bullet());
        assertThat(r).doesNotStartWith("-");
    }

    @Test
    public void orderedListKeepsItsNumber() {
        List<Line> out = render(60, "3. third");
        assertThat(out.get(0).rawContent()).contains("3.").contains("third");
    }

    @Test
    public void thematicBreakBecomesAnEmptyLineRatherThanDrawnCharacters() {
        String narrow = render(20, "---").get(0).rawContent();
        String wide   = render(120, "---").get(0).rawContent();

        // A break means separation, and an empty line already is separation. Drawing it puts
        // characters into every copied transcript that mean nothing once the text leaves the
        // terminal -- and a transcript carries one per command plus two per code block.
        assertThat(narrow).isEmpty();
        assertThat(wide).isEqualTo(narrow);
    }

    @Test
    public void codeBlockLinesCarryNoGutterSoTheyCanBeCopiedVerbatim() {
        List<String> raws = raw(render(60, "```java", "int x = 1;", "```"));

        assertThat(raws).contains("int x = 1;");
    }

    @Test
    public void cliSemanticMarkersArePreservedNotReparsed() {
        assertThat(render(60, "[OK] saved").get(0).rawContent()).isEqualTo("[OK] saved");
        assertThat(render(60, "=== Header ===").get(0).rawContent()).isEqualTo("=== Header ===");
        assertThat(render(60, "[ERR] boom").get(0).rawContent()).isEqualTo("[ERR] boom");
    }

    @Test
    public void longParagraphWrapsWithinWidth() {
        String para = "word".concat(" word".repeat(40));
        List<Line> out = render(20, para);
        assertThat(out.size()).isGreaterThan(1);
        for (Line l : out) {
            assertThat(l.width()).isLessThanOrEqualTo(20);
        }
    }

    @Test
    public void asciiGlyphsUsedWhenConfigured() {
        MarkdownRenderer ascii = new MarkdownRenderer(TuiThemeManager.getTheme("matrix"), Glyphs.ASCII);
        String r = ascii.render(Arrays.asList("- item"), 40).get(0).rawContent();
        assertThat(r).startsWith("-"); // ASCII bullet
        // Rule uses ASCII dashes.
        String rule = ascii.render(Arrays.asList("***"), 10).get(0).rawContent();
        assertThat(rule.chars().allMatch(c -> c == '-')).isTrue();
    }
}
