package com.eonmux.cadetcoder.commands;

import dev.tamboui.text.CharWidth;

import org.junit.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The arithmetic behind every bar, title and truncated line the shell draws.
 *
 * <h2>Why this is tested</h2>
 *
 * <p>These are the decisions that were lifted out of the render path precisely so they could be
 * exercised without a terminal, and most of them still had no test: a header bar that overflows by
 * one column pushes the git branch off the screen, a status bar that pads one short leaves a ragged
 * edge on a styled run, and a line clipped in the middle of a two-column character puts every
 * character after it in the wrong place for the rest of the frame.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>Nothing ever returns more columns than it was given, a width of zero is answered with nothing
 * rather than with an exception, and the end that identifies a thing is the end that survives: the
 * basename of a path, the tail of a filename, the right-hand group of a bar.</p>
 */
public class TheShellFitsTextIntoColumnsTest {

    private static final String ELLIPSIS = "…";

    /** A CJK ideograph: one character, one code point, two columns. */
    private static final String WIDE = "世";

    /** An emoji: one code point outside the basic plane, two {@code char}s, two columns. */
    private static final String EMOJI = "\uD83D\uDE42";

    // ------------------------------------------------------------------ clipping

    @Test
    public void aLineIsCutToTheColumnsItWasGiven() {
        assertThat(ShellWidgets.clipTo("abcdefgh", 4)).isEqualTo("abcd");
        assertThat(ShellWidgets.clipTo("abc", 10)).isEqualTo("abc");
    }

    @Test
    public void noColumnsMeansNothingIsDrawn() {
        assertThat(ShellWidgets.clipTo("abcdefgh", 0)).isEmpty();
        assertThat(ShellWidgets.clipTo("abcdefgh", -3)).isEmpty();
    }

    // ------------------------------------------------------------------ wrapping

    @Test
    public void aLineIsWrappedOnASpaceWhereThereIsOne() {
        assertThat(ShellWidgets.wrapOne("alpha beta gamma", 11))
                .containsExactly("alpha beta ", "gamma");
    }

    @Test
    public void aWordWiderThanTheColumnIsBrokenRatherThanLost() {
        assertThat(ShellWidgets.wrapOne("supercalifragilistic", 7))
                .containsExactly("superca", "lifragi", "listic");
    }

    @Test
    public void anEmptyLineIsStillALine() {
        assertThat(ShellWidgets.wrapOne("", 10)).containsExactly("");
    }

    @Test
    public void withNoColumnsThereIsNothingToWrapInto() {
        assertThat(ShellWidgets.wrapOne("anything", 0)).isEmpty();
    }

    @Test
    public void aWideLineIsWrappedWhereItsColumnsRunOutAndNotWhereItsCharactersDo() {
        // Forty ideographs are eighty columns. Counted as characters they were one fragment, and
        // the half of it past the fortieth column was drawn over whatever was beside the console.
        List<String> fragments = ShellWidgets.wrapOne(WIDE.repeat(40), 40);

        assertThat(fragments).hasSize(2);
        assertThat(fragments)
                .allSatisfy(piece -> assertThat(CharWidth.of(piece)).isLessThanOrEqualTo(40));
        assertThat(String.join("", fragments)).isEqualTo(WIDE.repeat(40));
    }

    @Test
    public void aWrapNeverCutsThroughACharacter() {
        // An odd column count lands in the middle of an emoji's surrogate pair, and half a pair is
        // not a character any terminal can draw.
        List<String> fragments = ShellWidgets.wrapOne(EMOJI.repeat(30), 11);

        assertThat(fragments).allSatisfy(piece -> {
            assertThat(isSplit(piece)).as("<%s> is whole", piece).isFalse();
            assertThat(CharWidth.of(piece)).isLessThanOrEqualTo(11);
        });
        assertThat(String.join("", fragments)).isEqualTo(EMOJI.repeat(30));
    }

    @Test
    public void everyFragmentTogetherIsTheOriginalLine() {
        String line = "the quick brown fox jumps over the lazy dog";

        assertThat(String.join("", ShellWidgets.wrapOne(line, 13))).isEqualTo(line);
    }

    // ------------------------------------------------------------------ bars

    @Test
    public void aBarPutsItsTwoGroupsAtOppositeEnds() {
        assertThat(ShellWidgets.composeBar("left", "right", 20))
                .isEqualTo("left           right")
                .hasSize(20);
    }

    @Test
    public void aBarTooNarrowForBothGroupsGivesTheRoomToTheRightHandGroup() {
        // The right-hand group is the position counter, and it is drawn nowhere else on the frame.
        // The left-hand group is the region's name, which the reader can see is the console.
        assertThat(ShellWidgets.composeBar("leftmost", "rightmost", 10))
                .hasSize(10)
                .endsWith("rightmost");
    }

    @Test
    public void aBarWeighsItsGroupsInColumnsAndNotInCharacters() {
        // Ten ideographs are twenty columns, so nothing is left for the counter -- but counted as
        // characters they left three columns of padding, and the bar came out half again as wide
        // as the row, with the counter on the part that was clipped away.
        String bar = ShellWidgets.composeBar(WIDE.repeat(10), " (3/7) ", 20);

        assertThat(CharWidth.of(bar)).isEqualTo(20);
        assertThat(bar).endsWith(" (3/7) ");
    }

    @Test
    public void aBarOfNoWidthIsEmpty() {
        assertThat(ShellWidgets.composeBar("left", "right", 0)).isEmpty();
    }

    @Test
    public void paddingFillsTheWidthAndNeverExceedsIt() {
        assertThat(ShellWidgets.padRightTo("ab", 5)).isEqualTo("ab   ");
        assertThat(ShellWidgets.padRightTo("abcdef", 3))
                .as("a styled run that is already too long is not made longer")
                .isEqualTo("abcdef");
        assertThat(ShellWidgets.padRightTo("ab", 0)).isEmpty();
    }

    @Test
    public void paddingCountsTheColumnsTheTextAlreadyFills() {
        // Three ideographs already fill six of the ten columns; padding for three left the styled
        // run three columns longer than the rectangle it was drawn into.
        assertThat(CharWidth.of(ShellWidgets.padRightTo(WIDE.repeat(3), 10))).isEqualTo(10);
    }

    // ------------------------------------------------------------------ eliding

    @Test
    public void aShortActivityIsLeftAlone() {
        assertThat(ShellWidgets.elide("reading pom.xml", 40, ELLIPSIS)).isEqualTo("reading pom.xml");
    }

    @Test
    public void aNewlineIsFlattenedBeforeAnythingIsMeasured() {
        assertThat(ShellWidgets.elide("  reading\npom.xml  ", 40, ELLIPSIS))
                .isEqualTo("reading pom.xml");
    }

    @Test
    public void aPathIsFoldedToItsBasenameFirst() {
        String folded = ShellWidgets.elide("reading src/main/java/com/Foo.java", 20, ELLIPSIS);

        assertThat(folded).isEqualTo("reading " + ELLIPSIS + "/Foo.java");
        assertThat(folded.length()).isLessThanOrEqualTo(20);
    }

    @Test
    public void aBasenameStillTooLongKeepsItsEnd() {
        String elided = ShellWidgets.elide("src/main/AVeryLongFileNameIndeed.java", 12, ELLIPSIS);

        assertThat(elided).startsWith(ELLIPSIS).endsWith(".java");
        assertThat(elided.length()).isLessThanOrEqualTo(12);
    }

    @Test
    public void aSentenceWithNoPathIsCutAtAWordWhereItCan() {
        String elided = ShellWidgets.elide("thinking about what to do next", 20, ELLIPSIS);

        assertThat(elided).endsWith(ELLIPSIS);
        assertThat(elided.length()).isLessThanOrEqualTo(20);
    }

    @Test
    public void thereIsNothingToSayInNoColumns() {
        assertThat(ShellWidgets.elide("anything", 0, ELLIPSIS)).isEmpty();
        assertThat(ShellWidgets.elide(null, 20, ELLIPSIS)).isEmpty();
        assertThat(ShellWidgets.elide("   ", 20, ELLIPSIS)).isEmpty();
    }

    @Test
    public void aWidthShorterThanTheEllipsisIsAnsweredWithWhatFits() {
        assertThat(ShellWidgets.elide("something long here", 1, ELLIPSIS)).hasSize(1);
    }

    @Test
    public void aWideActivityIsCutWhereItsColumnsRunOut() {
        // Twenty ideographs are twenty characters and forty columns, so measured by characters the
        // activity looked as though it already fitted and was handed back whole.
        assertThat(CharWidth.of(ShellWidgets.elide(WIDE.repeat(20), 20, ELLIPSIS)))
                .isLessThanOrEqualTo(20);
    }

    @Test
    public void anElidedActivityIsNeverCutThroughACharacter() {
        String elided = ShellWidgets.elide(EMOJI.repeat(20), 16, ELLIPSIS);

        assertThat(isSplit(elided)).as("<%s> is whole", elided).isFalse();
        assertThat(CharWidth.of(elided)).isLessThanOrEqualTo(16);
    }

    // ------------------------------------------------------------------ paths

    @Test
    public void aPathUnderHomeIsWrittenWithATilde() {
        assertThat(ShellWidgets.shortPath("/home/me/work/project", "/home/me", 40))
                .isEqualTo("~/work/project");
    }

    @Test
    public void aPathElsewhereIsLeftAsItIs() {
        assertThat(ShellWidgets.shortPath("/opt/project", "/home/me", 40)).isEqualTo("/opt/project");
    }

    @Test
    public void aPathTooLongLosesItsFrontAndKeepsItsTail() {
        String shortened = ShellWidgets.shortPath("/a/very/deep/tree/of/directories/project",
                                                  "/home/me", 15);

        assertThat(shortened).hasSize(15).startsWith(ELLIPSIS).endsWith("project");
    }

    @Test
    public void thereIsNoPathToShorten() {
        assertThat(ShellWidgets.shortPath(null, "/home/me", 20)).isEmpty();
        assertThat(ShellWidgets.shortPath("", "/home/me", 20)).isEmpty();
    }

    @Test
    public void aMissingHomeChangesNothing() {
        assertThat(ShellWidgets.shortPath("/opt/project", null, 40)).isEqualTo("/opt/project");
        assertThat(ShellWidgets.shortPath("/opt/project", "", 40)).isEqualTo("/opt/project");
    }

    // ------------------------------------------------------------------ the values that may be absent

    @Test
    public void aStyleThatWasNeverSetIsStillAStyle() {
        assertThat(ShellWidgets.nonNull(null)).isNotNull();
    }

    @Test
    public void textThatWasNeverSetIsStillMeasurable() {
        assertThat(ShellWidgets.nz(null)).isEmpty();
        assertThat(ShellWidgets.nz("here")).isEqualTo("here");
    }

    /** Whether the text ends or begins in half of a surrogate pair, which is half of a character. */
    private static boolean isSplit(String text) {
        for (int i = 0; i < text.length(); i++) {
            char at = text.charAt(i);
            if (Character.isHighSurrogate(at)
                && (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1)))) {
                return true;
            }
            if (Character.isLowSurrogate(at)
                && (i == 0 || !Character.isHighSurrogate(text.charAt(i - 1)))) {
                return true;
            }
        }
        return false;
    }
}
