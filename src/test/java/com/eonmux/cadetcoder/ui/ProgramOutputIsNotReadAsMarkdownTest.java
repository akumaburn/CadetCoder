package com.eonmux.cadetcoder.ui;

import dev.tamboui.text.Line;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a program printed reaches the shell as it was printed.
 *
 * <p>The shell renders a result as Markdown, because a model writes its answers in Markdown. A
 * program does not. In the output of {@code git diff}, a line that starts with {@code -} or
 * {@code +} is a removed or an added line. Read as Markdown, both became list items behind the same
 * bullet, and the diff no longer said which line was which. The same reading turns a shell comment
 * into a heading and {@code **} in a glob into bold text.</p>
 */
public class ProgramOutputIsNotReadAsMarkdownTest {

    private static final String BULLET = Glyphs.UNICODE.bullet() + " ";

    private MarkdownRenderer md;
    private String           previousTuiMode;

    @Before
    public void setUp() {
        md = new MarkdownRenderer(TuiThemeManager.getTheme("matrix"), Glyphs.UNICODE);
        previousTuiMode = System.getProperty(TuiMode.OVERRIDE_PROPERTY);
    }

    @After
    public void restoreTuiMode() {
        if (previousTuiMode == null) {
            System.clearProperty(TuiMode.OVERRIDE_PROPERTY);
        } else {
            System.setProperty(TuiMode.OVERRIDE_PROPERTY, previousTuiMode);
        }
    }

    private List<String> rendered(String... lines) {
        List<String> out = new ArrayList<>();
        for (Line line : md.render(Arrays.asList(lines), 100)) {
            out.add(line.rawContent());
        }
        return out;
    }

    @Test
    public void aDiffKeepsItsMinusAndPlusSigns() {
        assertThat(rendered(ProgramOutput.OPEN,
                            "-        return stock.get(item);",
                            "+        return stock.getOrDefault(item, 0);",
                            ProgramOutput.CLOSE))
                .containsExactly("-        return stock.get(item);",
                                 "+        return stock.getOrDefault(item, 0);");
    }

    @Test
    public void noOtherMarkdownIsReadIntoProgramOutput() {
        assertThat(rendered(ProgramOutput.OPEN,
                            "# a shell comment",
                            "* not a bullet",
                            "1. not a numbered item",
                            "**/*.java matched `3` files",
                            "---",
                            ProgramOutput.CLOSE))
                .containsExactly("# a shell comment",
                                 "* not a bullet",
                                 "1. not a numbered item",
                                 "**/*.java matched `3` files",
                                 "---");
    }

    @Test
    public void aFenceInProgramOutputDoesNotOpenACodeBlock() {
        // An unmatched fence in a program's output would otherwise pair with the next fence the
        // model writes and turn everything between them into code.
        assertThat(rendered(ProgramOutput.OPEN, "```", "still output", ProgramOutput.CLOSE,
                            "- a model's list item"))
                .containsExactly("```", "still output", BULLET + "a model's list item");
    }

    @Test
    public void aCodeBlockLeftOpenEndsWhereProgramOutputBegins() {
        // A model's answer can end inside an unclosed fence. The markers after it must still be
        // read as markers, and never shown as code.
        assertThat(rendered("```", "model code", ProgramOutput.OPEN, "- kept as printed",
                            ProgramOutput.CLOSE))
                .containsExactly("", "code", "model code", "", "- kept as printed");
    }

    @Test
    public void markdownOutsideProgramOutputIsStillRendered() {
        assertThat(rendered("- removed from the list", ProgramOutput.OPEN, "- kept as printed",
                            ProgramOutput.CLOSE, "- rendered again"))
                .containsExactly(BULLET + "removed from the list", "- kept as printed",
                                 BULLET + "rendered again");
    }

    @Test
    public void theToolsOwnStatusLinesKeepTheirStyleInsideProgramOutput() {
        List<Line> inside  = md.render(List.of(ProgramOutput.OPEN, "✓ Command completed successfully",
                                               ProgramOutput.CLOSE), 100);
        List<Line> outside = md.render(List.of("✓ Command completed successfully"), 100);

        assertThat(inside).hasSize(1);
        assertThat(inside.get(0).spans().get(0).style())
                .isEqualTo(outside.get(0).spans().get(0).style());
    }

    @Test
    public void aLongProgramLineIsWrappedWithoutLosingAnyOfIt() {
        String longLine = "x".repeat(250);

        List<String> out = new ArrayList<>();
        for (Line line : md.render(List.of(ProgramOutput.OPEN, longLine, ProgramOutput.CLOSE), 100)) {
            out.add(line.rawContent());
        }

        assertThat(String.join("", out)).isEqualTo(longLine);
    }

    @Test
    public void aRunLeftOpenEndsWithTheLines() {
        // A bounded scrollback can drop a closing marker; the run then covers only what is left.
        assertThat(rendered(ProgramOutput.OPEN, "- still output")).containsExactly("- still output");
    }

    @Test
    public void aCollapsedResultHidesItsProgramOutputAndAnOpenedOneKeepsTheMarkers() {
        List<String> result = List.of("✓ [1] ok  (3 lines)", CollapsedOutput.OPEN,
                                      ProgramOutput.OPEN, "- a", ProgramOutput.CLOSE,
                                      CollapsedOutput.CLOSE);

        assertThat(CollapsedOutput.visible(result)).containsExactly("✓ [1] ok  (3 lines)");
        assertThat(CollapsedOutput.expanded(result))
                .containsExactly("✓ [1] ok  (3 lines)", ProgramOutput.OPEN, "- a", ProgramOutput.CLOSE);
    }

    @Test
    public void whatIsStoredOrForwardedCarriesNoMarkers() {
        String printed = "one\n" + ProgramOutput.OPEN + "\n- two\n" + ProgramOutput.CLOSE + "\n";

        assertThat(CollapsedOutput.strip(printed)).isEqualTo("one\n- two\n");
    }

    @Test
    public void programOutputIsMarkedWhereTheShellDrawsTheScreen() {
        System.setProperty(TuiMode.OVERRIDE_PROPERTY, "true");

        String printed = printedBy(() -> ProgramOutput.print("- removed\n+ added\n"));

        assertThat(printed.split("\n", -1))
                .containsExactly(ProgramOutput.OPEN, "- removed", "+ added", ProgramOutput.CLOSE, "");
    }

    @Test
    public void outputWithoutAFinalNewlineStillClosesOnALineOfItsOwn() {
        System.setProperty(TuiMode.OVERRIDE_PROPERTY, "true");

        String printed = printedBy(() -> ProgramOutput.print("last line"));

        assertThat(printed.split("\n", -1))
                .containsExactly(ProgramOutput.OPEN, "last line", ProgramOutput.CLOSE, "");
    }

    @Test
    public void nothingIsMarkedOnAPlainConsole() {
        System.clearProperty(TuiMode.OVERRIDE_PROPERTY);

        assertThat(printedBy(() -> ProgramOutput.print("- removed\n"))).isEqualTo("- removed\n");
    }

    @Test
    public void nothingIsMarkedInOutputThatIsCollectedForTheModel() {
        System.setProperty(TuiMode.OVERRIDE_PROPERTY, "true");
        StringBuilder collected = new StringBuilder();

        OutputCapture.collectInto(collected::append, () -> ProgramOutput.print("- removed\n"));

        assertThat(collected.toString()).doesNotContain(ProgramOutput.OPEN)
                                        .contains("- removed");
    }

    @Test
    public void aStepsOutputIsMarkedAndNeedsNoMarkerOnEachLine() {
        // Without the markers, a block holding a fence carries the info marker on every line so the
        // fence cannot pair with a later one. Inside the markers nothing is read as a fence, so the
        // lines keep their plain indent.
        System.setProperty(TuiMode.OVERRIDE_PROPERTY, "true");

        String printed = printedBy(() -> ThemedOutputFormatter.printOutputBlock("```\n- item"));

        assertThat(printed.split("\n", -1))
                .containsExactly(ProgramOutput.OPEN, "  ```", "  - item", ProgramOutput.CLOSE, "");
    }

    private static String printedBy(Runnable printing) {
        PrintStream           original = System.out;
        ByteArrayOutputStream bytes    = new ByteArrayOutputStream();
        System.setOut(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        try {
            printing.run();
        } finally {
            System.setOut(original);
        }
        return AnsiStripper.strip(bytes.toString(StandardCharsets.UTF_8)).replace("\r\n", "\n");
    }
}
