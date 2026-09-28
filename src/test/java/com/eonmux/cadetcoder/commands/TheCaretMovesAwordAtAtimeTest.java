package com.eonmux.cadetcoder.commands;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where a word-wise move puts the caret.
 *
 * <h2>Why this is tested</h2>
 *
 * <p>The field the shell types into offers one caret and four ways to move it: one character either
 * way, and the two ends. Everything typed at this prompt that is worth crossing quickly -- a path,
 * a flag, a pasted command line -- is longer than one character and shorter than a line, so the
 * useful move is the one the field does not have. Where it lands is a function of the text and the
 * caret, so it is answerable here rather than at a terminal.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>That a move crosses the separators next to the caret and then the word beyond them, so a
 * repeated press walks a path segment by segment; that neither direction leaves the line; and that
 * a caret given a nonsensical index is clamped rather than throwing, because the field is free to
 * report one this has not seen.</p>
 */
public class TheCaretMovesAwordAtAtimeTest {

    @Test
    public void apressGoesBackOverTheWordBehindTheCaret() {
        String line = "read Foo";

        assertThat(InputEditing.previousWord(line, line.length())).isEqualTo(5);
    }

    @Test
    public void apressGoesForwardOverTheWordAheadOfTheCaret() {
        assertThat(InputEditing.nextWord("read Foo", 0)).isEqualTo(4);
    }

    @Test
    public void apathIsWalkedSegmentBySegment() {
        // The case the key exists for: one jump per directory rather than one for the whole path.
        String line = "read src/main/java/Foo.java";
        int    at   = line.length();

        at = InputEditing.previousWord(line, at);
        assertThat(line.substring(at)).isEqualTo("java");
        at = InputEditing.previousWord(line, at);
        assertThat(line.substring(at)).isEqualTo("Foo.java");
        at = InputEditing.previousWord(line, at);
        assertThat(line.substring(at)).isEqualTo("java/Foo.java");
    }

    @Test
    public void separatorsNextToTheCaretAreCrossedWithTheWordBehindThem() {
        String line = "grep --count   ";

        assertThat(InputEditing.previousWord(line, line.length())).isEqualTo(7);
    }

    @Test
    public void neitherDirectionLeavesTheLine() {
        assertThat(InputEditing.previousWord("read Foo", 0)).isZero();
        assertThat(InputEditing.nextWord("read Foo", 8)).isEqualTo(8);
    }

    @Test
    public void acaretOutsideTheLineIsBroughtBackToIt() {
        // The field owns the caret, so this takes whatever index it reports rather than assuming
        // the two agree about the length of the line.
        assertThat(InputEditing.previousWord("read", 99)).isZero();
        assertThat(InputEditing.nextWord("read", -5)).isEqualTo(4);
        assertThat(InputEditing.previousWord(null, 3)).isZero();
        assertThat(InputEditing.nextWord(null, 3)).isZero();
    }

    @Test
    public void anEmptyLineHasNowhereToGo() {
        assertThat(InputEditing.previousWord("", 0)).isZero();
        assertThat(InputEditing.nextWord("", 0)).isZero();
    }
}
