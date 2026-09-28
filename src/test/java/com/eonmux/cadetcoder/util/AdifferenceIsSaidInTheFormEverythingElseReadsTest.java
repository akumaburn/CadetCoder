package com.eonmux.cadetcoder.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two texts, and what changed between them.
 *
 * <h2>What was missing</h2>
 *
 * <p>Nothing in this tool could say what two texts differ by. A model asked to review a change read
 * both files in full and worked it out, which costs the whole of both files in the prompt and gets
 * the answer wrong on a long file. A model asked to make a change described it in prose, and the
 * only machine-readable form of a change was this tool's own SEARCH/REPLACE block.</p>
 *
 * <h2>Why unified format</h2>
 *
 * <p>It is what {@code git diff} prints, what {@code patch} reads, and what a model has seen more
 * of than any other change format. The output here is meant to go straight into {@code patch}, so
 * the two are one interface rather than two.</p>
 *
 * <h2>Why the whole diff ends its lines the same way</h2>
 *
 * <p>The hunks come from JGit, which writes them with bare newlines on every platform. A header
 * written with the platform's own separator therefore gave a diff whose first two lines ended
 * differently from the rest of it on Windows, which no reader of the format expects and which a
 * run on Linux never shows.</p>
 */
class AdifferenceIsSaidInTheFormEverythingElseReadsTest {

    @Test
    void identicalTextsDifferByNothing() {
        assertThat(UnifiedDiff.between("a.txt", "one\ntwo\n", "b.txt", "one\ntwo\n", 3)).isEmpty();
    }

    @Test
    void achangedLineIsShownAsARemovalAndAnAddition() {
        String diff = UnifiedDiff.between("a.txt", "one\ntwo\nthree\n",
                                          "b.txt", "one\nTWO\nthree\n", 3);

        assertThat(diff).contains("--- a.txt");
        assertThat(diff).contains("+++ b.txt");
        assertThat(diff).contains("-two");
        assertThat(diff).contains("+TWO");
        assertThat(diff).contains(" one");
        assertThat(diff).contains(" three");
    }

    @Test
    void thehunkHeaderSaysWhereTheChangeIs() {
        String diff = UnifiedDiff.between("a.txt", "1\n2\n3\n4\n5\n6\n7\n8\n9\n10\n",
                                          "b.txt", "1\n2\n3\n4\n5\n6\n7\n8\n9\nTEN\n", 2);

        assertThat(diff).contains("@@ -8,3 +8,3 @@");
    }

    @Test
    void thecontextAskedForIsTheContextGiven() {
        String wide   = UnifiedDiff.between("a.txt", "1\n2\n3\n4\n5\n", "b.txt", "1\n2\nX\n4\n5\n", 2);
        String narrow = UnifiedDiff.between("a.txt", "1\n2\n3\n4\n5\n", "b.txt", "1\n2\nX\n4\n5\n", 0);

        assertThat(wide).contains(" 1");
        assertThat(narrow).doesNotContain("\n 1");
        assertThat(narrow).contains("-3");
        assertThat(narrow).contains("+X");
    }

    @Test
    void anAddedFileIsAllAdditions() {
        String diff = UnifiedDiff.between("a.txt", "", "b.txt", "one\ntwo\n", 3);

        assertThat(diff).contains("+one");
        assertThat(diff).contains("+two");
        assertThat(diff).doesNotContain("-one");
    }

    @Test
    void anEmptiedFileIsAllRemovals() {
        String diff = UnifiedDiff.between("a.txt", "one\ntwo\n", "b.txt", "", 3);

        assertThat(diff).contains("-one");
        assertThat(diff).contains("-two");
        assertThat(diff).doesNotContain("+one");
    }

    @Test
    void amissingFinalNewlineIsMarkedTheWayGitMarksIt() {
        String diff = UnifiedDiff.between("a.txt", "one\ntwo", "b.txt", "one\nTWO", 3);

        assertThat(diff).contains("\\ No newline at end of file");
    }

    @Test
    void anullTextIsReadAsAnEmptyOne() {
        assertThat(UnifiedDiff.between("a.txt", null, "b.txt", null, 3)).isEmpty();
        assertThat(UnifiedDiff.between("a.txt", null, "b.txt", "one\n", 3)).contains("+one");
    }

    @Test
    void thesummaryCountsTheLinesEitherWay() {
        UnifiedDiff.Tally tally = UnifiedDiff.tally("one\ntwo\nthree\n", "one\nTWO\nthree\nfour\n");

        assertThat(tally.added()).isEqualTo(2);
        assertThat(tally.removed()).isEqualTo(1);
        assertThat(tally.changed()).isTrue();
    }

    @Test
    void thesummaryOfNoChangeIsNoChange() {
        UnifiedDiff.Tally tally = UnifiedDiff.tally("one\n", "one\n");

        assertThat(tally.added()).isZero();
        assertThat(tally.removed()).isZero();
        assertThat(tally.changed()).isFalse();
    }

    @Test
    void awindowsLineEndingIsNotAChangeOfItsOwnContent() {
        // Carriage returns are part of the line, so a file converted between line endings shows
        // every line as changed. That is the truth about the bytes, and saying otherwise would make
        // the output unusable as a patch.
        String diff = UnifiedDiff.between("a.txt", "one\r\n", "b.txt", "one\n", 3);

        assertThat(diff).isNotEmpty();
    }

    @Test
    void theheaderLinesEndTheWayTheHunksDo() {
        String diff = UnifiedDiff.between("a.txt", "one\ntwo\nthree\n",
                                          "b.txt", "one\nTWO\nthree\n", 3);

        assertThat(diff)
                .as("JGit writes the body with bare newlines whatever the platform separator is")
                .startsWith("--- a.txt\n+++ b.txt\n@@");
        assertThat(diff).doesNotContain("\r");
    }
}
