package com.eonmux.cadetcoder.ui;

import com.eonmux.cadetcoder.test.TestOutputCapture;

import dev.tamboui.text.Line;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The layout rules an agent run's transcript is set by.
 *
 * <p><b>What it looked like</b>: one unbroken column of lines, almost all of them opening with the
 * information marker, with the final answer drawn across the full 200 columns of a wide terminal.
 * Four different kinds of thing wore the same marker -- the run's telemetry, the reason a step was
 * taken, the step's outcome, and the whole of whatever the step printed -- so nothing could be found
 * by scanning, and a turn ran into the next one with no space between them.</p>
 *
 * <p>Each test here pins one of the decisions that fixed it.</p>
 */
public class AtranscriptIsSetToBeReadTest {

    private TestOutputCapture output;

    @Before
    public void setUp() {
        output = new TestOutputCapture();
        output.startCapture();
    }

    @After
    public void tearDown() {
        output.stopCapture();
    }

    private static List<String> render(int width, String... lines) {
        return new MarkdownRenderer(null, Glyphs.UNICODE).render(List.of(lines), width)
                .stream().map(Line::rawContent).collect(Collectors.toList());
    }

    // ---------------------------------------------------------------- the measure

    @Test
    public void proseIsNotDrawnAcrossAWideTerminal() {
        assertThat(ProseMeasure.fit(200)).isEqualTo(ProseMeasure.READABLE_COLUMNS);
    }

    @Test
    public void aNarrowTerminalKeepsItsOwnWidth() {
        // The cap is a maximum. On a narrow screen the screen still decides.
        assertThat(ProseMeasure.fit(60)).isEqualTo(60);
    }

    @Test
    public void noWidthAtAllIsStillNoWidth() {
        // Piped or captured output has no width, and a break inserted there lands in the middle of
        // something another program is reading.
        assertThat(ProseMeasure.fit(TerminalWidth.UNKNOWN)).isEqualTo(TerminalWidth.UNKNOWN);
        assertThat(ProseMeasure.fit(-5)).isEqualTo(-5);
    }

    @Test
    public void theMeasureSitsAboveTheRangeRunningTextReadsBestIn() {
        // Wide enough that a path or a model identifier inside a sentence is not what forces the
        // break, and narrow enough to still be one measure rather than the whole terminal.
        assertThat(ProseMeasure.READABLE_COLUMNS).isBetween(90, 110);
    }

    @Test
    public void aParagraphIsBrokenAtTheMeasureRatherThanAtTheEdge() {
        String sentence = "The project structure confirms the README description, and the section "
                          + "already covers the getting-started flow that a new reader needs before "
                          + "anything else on the page.";

        List<String> out = render(200, sentence);

        assertThat(out.size()).isGreaterThan(1);
        assertThat(out).allSatisfy(line ->
                assertThat(line.length()).isLessThanOrEqualTo(ProseMeasure.READABLE_COLUMNS));
    }

    @Test
    public void aCodeBlockKeepsTheWholeTerminal() {
        // Code is not prose. Its line breaks are part of what it says, and a second measure would
        // wrap lines that the width was already able to hold.
        String code = "x".repeat(140);

        List<String> out = render(200, "```java", code, "```");

        assertThat(out).contains(code);
    }

    // ---------------------------------------------------------------- marked lines

    @Test
    public void aWrappedMarkedLineHangsUnderItsOwnText() {
        String reason = "Read the main README to understand the project's purpose, structure, and "
                        + "the getting-started content that is already there.";

        List<String> out = render(60, OutputLineStyler.INFO_GLYPH + " " + reason);

        assertThat(out.size()).isGreaterThan(1);
        assertThat(out.get(0)).startsWith(OutputLineStyler.INFO_GLYPH + " Read");
        assertThat(out.get(1))
                .as("column zero is where the next message's marker goes")
                .startsWith("  ");
    }

    @Test
    public void aHangingContinuationStillFitsTheMeasure() {
        List<String> out = render(40, OutputLineStyler.INFO_GLYPH + " " + "word ".repeat(30).trim());

        assertThat(out).allSatisfy(line -> assertThat(line.length()).isLessThanOrEqualTo(40));
    }

    @Test
    public void aLineThatOpensWithNoMarkerIsLeftWhereItIs() {
        // The hang is measured from the marker. A long first word is not one, and indenting under
        // it would put an arbitrary gutter down the side of ordinary text.
        List<String> out = render(40, "> " + "alpha bravo charlie delta echo foxtrot golf hotel");

        assertThat(out.get(0)).startsWith("> alpha");
        assertThat(out.get(1)).startsWith("  ");
    }

    // ---------------------------------------------------------------- nested output

    @Test
    public void whatACommandPrintedKeepsItsOwnMarkers() {
        // It arrives already formatted by the command that printed it. A second marker in front of
        // its first line rendered as two markers for one line.
        ThemedOutputFormatter.printOutputBlock(OutputLineStyler.HEADER_GLYPH + " File: README.md");

        List<String> lines = AnsiStripper.strip(output.getStdout()).lines().collect(Collectors.toList());

        assertThat(lines).containsExactly("  " + OutputLineStyler.HEADER_GLYPH + " File: README.md");
        assertThat(OutputLineStyler.classify(lines.get(0)))
                .as("the command's own heading still reads as a heading")
                .isEqualTo(OutputLineStyler.Kind.HEADER);
    }

    @Test
    public void whatACommandPrintedIsSetInFromTheMargin() {
        ThemedOutputFormatter.printOutputBlock("first\nsecond");

        List<String> lines = AnsiStripper.strip(output.getStdout()).lines().collect(Collectors.toList());

        assertThat(lines).containsExactly("  first", "  second");
    }

    @Test
    public void theIndentDoesNotSurviveOnAnEmptyLine() {
        ThemedOutputFormatter.printOutputBlock("first\n\nthird");

        List<String> lines = AnsiStripper.strip(output.getStdout()).lines().collect(Collectors.toList());

        assertThat(lines).containsExactly("  first", "", "  third");
    }

    @Test
    public void capturedOutputCarryingAfenceIsMadeInert() {
        // An indent does not stop a fence counting, because a renderer strips leading space before
        // it looks. An odd number of them would pair with the next fence printed and swallow
        // everything in between. A marker at the start of the line cannot open a block.
        ThemedOutputFormatter.printOutputBlock("```java\nint x = 1;");

        List<String> lines = AnsiStripper.strip(output.getStdout()).lines().collect(Collectors.toList());

        assertThat(lines).allSatisfy(line ->
                assertThat(line).startsWith(OutputLineStyler.INFO_GLYPH));
    }

    // ---------------------------------------------------------------- the answer

    @Test
    public void theAnswerIsSetToTheSameMeasureAsEverythingElse() {
        String saved = System.getProperty(TerminalWidth.PROPERTY);
        System.setProperty(TerminalWidth.PROPERTY, "200");
        try {
            ThemedOutputFormatter.printProse("word ".repeat(60).trim());

            List<String> lines =
                    AnsiStripper.strip(output.getStdout()).lines().collect(Collectors.toList());

            assertThat(lines.size()).isGreaterThan(1);
            assertThat(lines).allSatisfy(line ->
                    assertThat(line.length()).isLessThanOrEqualTo(ProseMeasure.READABLE_COLUMNS));
        } finally {
            if (saved == null) {
                System.clearProperty(TerminalWidth.PROPERTY);
            } else {
                System.setProperty(TerminalWidth.PROPERTY, saved);
            }
        }
    }

    @Test
    public void anAnswerCarryingCodeIsLeftExactlyAsItWas() {
        // A break inside a line of code is a change to the code.
        String saved = System.getProperty(TerminalWidth.PROPERTY);
        System.setProperty(TerminalWidth.PROPERTY, "40");
        try {
            String answer = "Use this:\n```java\nint total = alpha + bravo + charlie + delta;\n```";

            ThemedOutputFormatter.printProse(answer);

            assertThat(AnsiStripper.strip(output.getStdout()))
                    .contains("int total = alpha + bravo + charlie + delta;");
        } finally {
            if (saved == null) {
                System.clearProperty(TerminalWidth.PROPERTY);
            } else {
                System.setProperty(TerminalWidth.PROPERTY, saved);
            }
        }
    }

    @Test
    public void proseCarriesNoMarkerOfItsOwn() {
        ThemedOutputFormatter.printProse("The section already covers what a new reader needs.");

        List<String> lines = AnsiStripper.strip(output.getStdout()).lines().collect(Collectors.toList());

        assertThat(lines).containsExactly("The section already covers what a new reader needs.");
    }

    // ---------------------------------------------------------------- turn boundaries

    @Test
    public void aTurnOpensWithSpaceAboveIt() {
        ThemedOutputFormatter.printIteration("Iteration 3");

        List<String> lines = AnsiStripper.strip(output.getStdout()).lines().collect(Collectors.toList());

        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).isEmpty();
        assertThat(OutputLineStyler.classify(lines.get(1)))
                .isEqualTo(OutputLineStyler.Kind.ITERATION);
    }
}
