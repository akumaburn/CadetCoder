package com.eonmux.cadetcoder.ui;

import org.junit.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deciding how wide the console is, and breaking a message to fit it.
 *
 * <h2>Why this is tested</h2>
 *
 * <p>A message longer than the terminal is wrapped by the terminal, which breaks it wherever the
 * edge falls -- inside a word, and back at column zero, so the second half of a sentence lines up
 * with the marker of the next one. Both halves of the fix are decisions with no output in them: what
 * the width is, and where a line may be broken. They are tested here rather than through printed
 * output, because output that has been captured has no width and must not be re-wrapped at all.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>Nobody saying how wide the terminal is means "do not wrap", not "assume eighty": a pipe, a
 * redirect and a captured run all have no width, and a break inserted there lands in the middle of
 * something another program is reading. A width that is told explicitly wins over one inherited from
 * the environment, including when what it says is zero. And a word too long to fit is left to
 * overflow, because the words that reach that length are paths and URLs.</p>
 */
public class AMessageFitsTheWidthItWasToldTest {

    // ------------------------------------------------------------------ how wide it is

    @Test
    public void withNobodySayingAnythingThereIsNoWidth() {
        assertThat(TerminalWidth.resolve(null, null)).isEqualTo(TerminalWidth.UNKNOWN);
    }

    @Test
    public void theEnvironmentIsUsedWhenItSaysSomething() {
        assertThat(TerminalWidth.resolve(null, "100")).isEqualTo(100);
    }

    @Test
    public void beingToldExplicitlyBeatsTheEnvironment() {
        assertThat(TerminalWidth.resolve("60", "100")).isEqualTo(60);
    }

    @Test
    public void beingToldZeroTurnsWrappingOffRatherThanFallingBack() {
        assertThat(TerminalWidth.resolve("0", "100"))
                .as("a script that cannot unset COLUMNS says 0 to mean 'leave my output alone'")
                .isEqualTo(TerminalWidth.UNKNOWN);
    }

    @Test
    public void somethingThatIsNotANumberIsNotAnAnswer() {
        assertThat(TerminalWidth.resolve("wide", "100")).isEqualTo(100);
        assertThat(TerminalWidth.resolve("wide", "wider")).isEqualTo(TerminalWidth.UNKNOWN);
        assertThat(TerminalWidth.resolve("", "  ")).isEqualTo(TerminalWidth.UNKNOWN);
    }

    @Test
    public void aWidthTooNarrowToReadIsNoWidth() {
        assertThat(TerminalWidth.resolve(null, "10")).isEqualTo(TerminalWidth.UNKNOWN);
        assertThat(TerminalWidth.resolve(null, "-1")).isEqualTo(TerminalWidth.UNKNOWN);
    }

    @Test
    public void anAbsurdWidthIsClampedRatherThanBelieved() {
        assertThat(TerminalWidth.resolve(null, "99999")).isEqualTo(TerminalWidth.WIDEST);
    }

    // ------------------------------------------------------------------ where it breaks

    @Test
    public void withNoWidthTheLineIsLeftExactlyAsItWas() {
        String line = "a message considerably longer than any terminal would care to show at once";

        assertThat(ProseWrap.wrap(line, TerminalWidth.UNKNOWN)).containsExactly(line);
        assertThat(ProseWrap.wrap(line, -5)).containsExactly(line);
    }

    @Test
    public void aLineThatFitsIsOnePiece() {
        assertThat(ProseWrap.wrap("short enough", 40)).containsExactly("short enough");
    }

    @Test
    public void aLineIsBrokenOnASpaceAndNeverInsideAWord() {
        List<String> pieces = ProseWrap.wrap("one two three four five six", 12);

        assertThat(pieces).containsExactly("one two",  "three four", "five six");
        assertThat(pieces).allSatisfy(piece -> assertThat(piece.length()).isLessThanOrEqualTo(12));
    }

    @Test
    public void aWordTooLongToFitOverflowsRatherThanBeingCut() {
        List<String> pieces = ProseWrap.wrap("see /a/very/long/path/that/will/not/fit.java now", 20);

        assertThat(pieces).containsExactly("see", "/a/very/long/path/that/will/not/fit.java", "now");
    }

    @Test
    public void anIndentedLineKeepsItsIndentOnEveryPiece() {
        List<String> pieces = ProseWrap.wrap("    alpha beta gamma delta", 14);

        assertThat(pieces).containsExactly("    alpha beta", "    gamma", "    delta");
    }

    @Test
    public void anIndentWiderThanTheWidthIsLeftAlone() {
        String line = "        deeply indented text";

        assertThat(ProseWrap.wrap(line, 6)).containsExactly(line);
    }

    @Test
    public void anEmptyLineStaysOneEmptyPiece() {
        assertThat(ProseWrap.wrap("", 40)).containsExactly("");
        assertThat(ProseWrap.wrap(null, 40)).containsExactly("");
    }

    @Test
    public void runsOfWhitespaceInsideALineCollapseWhenItIsBroken() {
        assertThat(ProseWrap.wrap("alpha    beta gamma", 12)).containsExactly("alpha beta", "gamma");
    }
}
