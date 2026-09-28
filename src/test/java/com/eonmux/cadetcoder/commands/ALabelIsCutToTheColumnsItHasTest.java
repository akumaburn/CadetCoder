package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ui.Glyphs;

import dev.tamboui.text.CharWidth;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The labels on the shell's headings, cut to the room they are given.
 *
 * <h2>The defect</h2>
 *
 * <p>Both were cut by counting characters, on headings the rest of the class measures in columns.
 * For anything outside the Latin alphabet those are different numbers: a title of thirty
 * double-width characters counts as thirty and occupies sixty, so it passed a budget of forty and
 * then overflowed the heading. What the heading's own clip then removed was the tail -- which is
 * where the position counter is, the one part of a focused heading that says <em>which</em> of the
 * results is being looked at.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>That both are measured in display columns, including the ellipsis they end with; that a label
 * short enough is returned untouched, so the common case adds nothing; that a missing title reads
 * as {@code Input} rather than as the word "null"; and that a trailing colon is dropped, because
 * these are written to introduce something and read as labels once they are on a heading.</p>
 */
public class ALabelIsCutToTheColumnsItHasTest {

    private static final String ELLIPSIS = "...";

    /** Twenty-four columns of double-width characters in twelve characters. */
    private static final String WIDE = "廣廣廣廣廣廣廣廣廣廣廣廣";

    @Test
    public void aShortCommandIsLeftAlone() {
        assertThat(ShellWidgets.shortenCommand("chat describe", ELLIPSIS))
                .isEqualTo("chat describe");
    }

    @Test
    public void aCommandWithNothingInItIsEmpty() {
        assertThat(ShellWidgets.shortenCommand(null, ELLIPSIS)).isEmpty();
    }

    @Test
    public void aLongCommandEndsInTheEllipsis() {
        String shortened = ShellWidgets.shortenCommand(
                "agent refactor the whole of the parsing package", ELLIPSIS);

        assertThat(shortened).endsWith(ELLIPSIS);
        assertThat(shortened).startsWith("agent ");
        assertThat(CharWidth.of(shortened)).isLessThanOrEqualTo(24);
    }

    @Test
    public void aMissingTitleReadsAsInput() {
        // The heading is drawn either way, so the alternative is a heading that says "null".
        assertThat(ShellWidgets.safeTitle(null, ELLIPSIS)).isEqualTo("Input");
    }

    @Test
    public void aTitleLosesItsTrailingColonAndItsSurroundingSpace() {
        assertThat(ShellWidgets.safeTitle("  Which file? :  ", ELLIPSIS)).isEqualTo("Which file?");
    }

    @Test
    public void aTitleShortEnoughIsLeftAlone() {
        assertThat(ShellWidgets.safeTitle("Resumed conversation", ELLIPSIS))
                .isEqualTo("Resumed conversation");
    }

    @Test
    public void aLongTitleIsCutToItsBudget() {
        String cut = ShellWidgets.safeTitle(
                "a title that runs on well past the forty columns it is given", ELLIPSIS);

        assertThat(cut).endsWith(ELLIPSIS);
        assertThat(CharWidth.of(cut)).isLessThanOrEqualTo(40);
    }

    @Test
    public void aCommandOfWideCharactersIsMeasuredInColumns() {
        // Twelve characters, twenty-four columns: counting them said it fitted a budget of
        // twenty-four, and the heading then ran a full line over.
        String shortened = ShellWidgets.shortenCommand(WIDE + WIDE, ELLIPSIS);

        assertThat(CharWidth.of(shortened)).isLessThanOrEqualTo(24);
        assertThat(shortened).endsWith(ELLIPSIS);
    }

    @Test
    public void aTitleOfWideCharactersIsMeasuredInColumns() {
        String cut = ShellWidgets.safeTitle(WIDE + WIDE + WIDE, ELLIPSIS);

        assertThat(CharWidth.of(cut)).isLessThanOrEqualTo(40);
        assertThat(cut).endsWith(ELLIPSIS);
    }

    @Test
    public void theEllipsisIsCountedInTheBudgetItIsAddedTo() {
        // A wide ellipsis takes a column the text no longer has; spending it on the ellipsis is the
        // whole point of subtracting it first.
        String wideEllipsis = "……";
        String cut = ShellWidgets.safeTitle(
                "a title that runs on well past the forty columns it is given", wideEllipsis);

        assertThat(CharWidth.of(cut)).isLessThanOrEqualTo(40);
    }

    @Test
    public void theInputHeadingNamesWhereCommandsWillRun() {
        assertThat(ShellInputLine.heading(false, null, "~/work/cadet", Glyphs.ASCII))
                .contains("Command")
                .contains("~/work/cadet");
    }

    @Test
    public void theInputHeadingDropsTheDirectoryWhenThereIsNoneToShow() {
        // Rather than a heading trailing a separator with nothing after it.
        assertThat(ShellInputLine.heading(false, null, "", Glyphs.ASCII)).isEqualTo("  Command ");
        assertThat(ShellInputLine.heading(false, null, null, Glyphs.ASCII)).isEqualTo("  Command ");
    }

    @Test
    public void theInputHeadingBecomesTheQuestionWhileOneIsWaiting() {
        // The field itself cannot say which of the two things it is for: it holds the answer either
        // way. So the heading carries it.
        String heading = ShellInputLine.heading(true, "Overwrite src/Main.java?", "~/work",
                                                Glyphs.ASCII);

        assertThat(heading).contains("Overwrite src/Main.java?");
        assertThat(heading).doesNotContain("Command");
        assertThat(heading).doesNotContain("~/work");
    }
}
