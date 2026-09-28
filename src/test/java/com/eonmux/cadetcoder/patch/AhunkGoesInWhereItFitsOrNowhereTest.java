package com.eonmux.cadetcoder.patch;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Putting a hunk into a file.
 *
 * <h2>Why the line number in the header is a hint</h2>
 *
 * <p>A patch is written against one version of a file and applied to another. An edit above the
 * hunk moves every later line, so the numbers in the header are already wrong by the time the patch
 * arrives. What locates a hunk is the lines it expects to find. The header says where to look
 * first, and the search widens from there, which is what {@code git apply} does.</p>
 *
 * <h2>Why nothing is applied in part</h2>
 *
 * <p>A file with three of four hunks in it is a file in a state nobody wrote and nobody asked for,
 * and the caller cannot tell from the result which three. Every hunk is placed against the text as
 * it was, or the file is left alone.</p>
 *
 * <h2>Why where a hunk lands is checked as well as whether it fits</h2>
 *
 * <p>Each hunk is located on its own, so two of them can resolve to spans that overlap or that run
 * up the file instead of down it. Rebuilding the text from placements like that cuts lines no hunk
 * asked to touch, and the result came back reported as applied. A hunk found a long way from the
 * line it names is the same kind of accident caught earlier, so the search is bounded and any
 * distance it did travel is said out loud.</p>
 */
class AhunkGoesInWhereItFitsOrNowhereTest {

    private static PatchedHunk hunk(int oldStart, List<String> before, List<String> after) {
        return new PatchedHunk(oldStart, oldStart, before, after);
    }

    @Test
    void ahunkAtTheLineItNamesIsApplied() {
        PatchApply.Result applied = PatchApply.to("one\ntwo\nthree\n",
                List.of(hunk(1, List.of("one", "two", "three"), List.of("one", "TWO", "three"))));

        assertThat(applied.applied()).isTrue();
        assertThat(applied.text()).isEqualTo("one\nTWO\nthree\n");
    }

    @Test
    void ahunkWhoseLinesMovedIsStillFound() {
        // Two lines were added above the hunk since the patch was written.
        PatchApply.Result applied = PatchApply.to("added\nalso added\none\ntwo\nthree\n",
                List.of(hunk(1, List.of("one", "two", "three"), List.of("one", "TWO", "three"))));

        assertThat(applied.applied()).isTrue();
        assertThat(applied.text()).isEqualTo("added\nalso added\none\nTWO\nthree\n");
    }

    @Test
    void ahunkWhoseLinesAreNotThereIsRefused() {
        PatchApply.Result applied = PatchApply.to("something else entirely\n",
                List.of(hunk(1, List.of("one", "two", "three"), List.of("one", "TWO", "three"))));

        assertThat(applied.applied()).isFalse();
        assertThat(applied.reason()).contains("1");
        assertThat(applied.text()).isNull();
    }

    @Test
    void severalHunksAreAppliedInOnePass() {
        PatchApply.Result applied = PatchApply.to("a\nb\nc\nd\ne\nf\n",
                List.of(hunk(1, List.of("a"), List.of("A")),
                        hunk(5, List.of("e"), List.of("E"))));

        assertThat(applied.applied()).isTrue();
        assertThat(applied.text()).isEqualTo("A\nb\nc\nd\nE\nf\n");
    }

    @Test
    void oneHunkThatDoesNotFitStopsTheWholeFile() {
        PatchApply.Result applied = PatchApply.to("a\nb\nc\n",
                List.of(hunk(1, List.of("a"), List.of("A")),
                        hunk(2, List.of("nothing like this"), List.of("x"))));

        assertThat(applied.applied()).isFalse();
        assertThat(applied.text())
                .as("a file with some of a patch in it is a state nobody asked for")
                .isNull();
    }

    @Test
    void ahunkThatAddsLinesLengthensTheFile() {
        PatchApply.Result applied = PatchApply.to("a\nb\n",
                List.of(hunk(1, List.of("a"), List.of("a", "new"))));

        assertThat(applied.text()).isEqualTo("a\nnew\nb\n");
    }

    @Test
    void ahunkThatOnlyRemovesShortensTheFile() {
        PatchApply.Result applied = PatchApply.to("a\nb\nc\n",
                List.of(hunk(2, List.of("b"), List.of())));

        assertThat(applied.text()).isEqualTo("a\nc\n");
    }

    @Test
    void alaterHunkIsPlacedAgainstTheTextAsItWasRatherThanAsItBecame() {
        // Both hunks say line 1, because both were written against the original. Applying the first
        // makes the file longer, and a reader that searched the new text from line 1 would find the
        // second hunk's lines at the wrong place or not at all.
        PatchApply.Result applied = PatchApply.to("x\ny\nx\n",
                List.of(hunk(1, List.of("x"), List.of("added", "x")),
                        hunk(3, List.of("x"), List.of("X"))));

        assertThat(applied.applied()).isTrue();
        assertThat(applied.text()).isEqualTo("added\nx\ny\nX\n");
    }

    @Test
    void anEmptyFileTakesAPatchThatOnlyAdds() {
        PatchApply.Result applied = PatchApply.to("", List.of(hunk(1, List.of(), List.of("one"))));

        assertThat(applied.applied()).isTrue();
        assertThat(applied.text()).isEqualTo("one\n");
    }

    @Test
    void afileWithNoFinalNewlineKeepsNotHavingOne() {
        PatchApply.Result applied =
                PatchApply.to("one\ntwo", List.of(hunk(1, List.of("one"), List.of("ONE"))));

        assertThat(applied.text()).isEqualTo("ONE\ntwo");
    }

    @Test
    void nohunksAtAllChangesNothing() {
        PatchApply.Result applied = PatchApply.to("one\n", List.of());

        assertThat(applied.applied()).isTrue();
        assertThat(applied.text()).isEqualTo("one\n");
    }

    @Test
    void thereasonNamesTheHunkThatWouldNotFit() {
        PatchApply.Result applied = PatchApply.to("a\nb\nc\n",
                List.of(hunk(1, List.of("a"), List.of("A")),
                        hunk(2, List.of("nothing like this"), List.of("x"))));

        assertThat(applied.reason())
                .contains("2")
                .contains("nothing like this");
    }

    @Test
    void apatchAlreadyInTheFileIsSaidToBeAlreadyThere() {
        // The common case after a retry. Reporting it as a plain failure sends the caller looking
        // for a conflict that is not there.
        PatchApply.Result applied = PatchApply.to("one\nTWO\nthree\n",
                List.of(hunk(1, List.of("one", "two", "three"), List.of("one", "TWO", "three"))));

        assertThat(applied.applied()).isFalse();
        assertThat(applied.alreadyApplied()).isTrue();
        assertThat(applied.reason()).contains("already");
    }

    @Test
    void apureInsertionGoesAfterTheLineItsHeaderNames() {
        // "@@ -3,0 +4,1 @@" names the line the new line goes AFTER, not the line it displaces.
        PatchApply.Result applied = PatchApply.to("a\nb\nc\nd\n",
                List.of(new PatchedHunk(3, 4, List.of(), List.of("new"))));

        assertThat(applied.applied()).isTrue();
        assertThat(applied.text()).isEqualTo("a\nb\nc\nnew\nd\n");
    }

    @Test
    void anInsertionAtTheTopOfTheFileStaysAtTheTop() {
        // "@@ -0,0" is the one case where the line named is not a line at all.
        PatchApply.Result applied = PatchApply.to("a\nb\n",
                List.of(new PatchedHunk(0, 1, List.of(), List.of("first"))));

        assertThat(applied.applied()).isTrue();
        assertThat(applied.text()).isEqualTo("first\na\nb\n");
    }

    @Test
    void twoHunksThatWouldChangeTheSameLinesAreRefused() {
        PatchApply.Result applied = PatchApply.to("a\nb\nc\n",
                List.of(new PatchedHunk(1, 1, List.of("a", "b"), List.of("A", "b")),
                        new PatchedHunk(2, 2, List.of("b", "c"), List.of("b", "C"))));

        assertThat(applied.applied()).isFalse();
        assertThat(applied.reason()).contains("2").contains("1");
        assertThat(applied.text())
                .as("rebuilding from overlapping spans drops lines no hunk asked to touch")
                .isNull();
    }

    @Test
    void twoHunksThatResolveUpTheFileRatherThanDownItAreRefused() {
        // The patch says the "a" hunk comes first, and the file has "a" below "b". Quietly
        // swapping them applies a patch in an order nobody wrote.
        PatchApply.Result applied = PatchApply.to("b\na\n",
                List.of(new PatchedHunk(1, 1, List.of("a"), List.of("A")),
                        new PatchedHunk(2, 2, List.of("b"), List.of("B"))));

        assertThat(applied.applied()).isFalse();
        assertThat(applied.reason()).contains("2");
    }

    @Test
    void ahunkAppliedAwayFromItsStatedLineSaysHowFar() {
        PatchApply.Result applied = PatchApply.to("added\nalso added\none\ntwo\n",
                List.of(new PatchedHunk(1, 1, List.of("one"), List.of("ONE"))));

        assertThat(applied.applied()).isTrue();
        assertThat(applied.offsets()).hasSize(1);
        assertThat(applied.offsets().get(0)).contains("Hunk 1").contains("2");
    }

    @Test
    void ahunkThatLandsWhereItSaidReportsNoOffset() {
        PatchApply.Result applied = PatchApply.to("one\ntwo\n",
                List.of(new PatchedHunk(1, 1, List.of("one"), List.of("ONE"))));

        assertThat(applied.offsets()).isEmpty();
    }

    @Test
    void ahunkWhoseLinesAreOnlyFoundFarAwayIsRefusedRatherThanAppliedThere() {
        StringBuilder text = new StringBuilder();
        for (int line = 0; line < 4000; line++) {
            text.append("filler ").append(line).append('\n');
        }
        text.append("needle\n");

        PatchApply.Result applied = PatchApply.to(text.toString(),
                List.of(new PatchedHunk(1, 1, List.of("needle"), List.of("NEEDLE"))));

        assertThat(applied.applied())
                .as("a run of lines thousands of lines from where the patch says is not that run")
                .isFalse();
        assertThat(applied.text()).isNull();
    }

    @Test
    void apatchAppliesToAfileWrittenWithWindowsLineEndings() {
        PatchApply.Result applied = PatchApply.to("one\r\ntwo\r\nthree\r\n",
                List.of(hunk(1, List.of("one", "two", "three"), List.of("one", "TWO", "three"))));

        assertThat(applied.applied()).isTrue();
        assertThat(applied.text()).isEqualTo("one\r\nTWO\r\nthree\r\n");
    }

    @Test
    void alineAddedToAwindowsFileIsWrittenTheWindowsWay() {
        PatchApply.Result applied = PatchApply.to("one\r\ntwo\r\n",
                List.of(hunk(1, List.of("one"), List.of("one", "middle"))));

        assertThat(applied.text()).isEqualTo("one\r\nmiddle\r\ntwo\r\n");
    }

    @Test
    void apatchThatTakesTheFinalNewlineOffTakesItOff() {
        PatchApply.Result applied = PatchApply.to("one\ntwo\n",
                List.of(new PatchedHunk(2, 2, List.of("two"), List.of("two"), false, true)));

        assertThat(applied.applied()).isTrue();
        assertThat(applied.text()).isEqualTo("one\ntwo");
    }

    @Test
    void apatchThatPutsTheFinalNewlineBackPutsItBack() {
        PatchApply.Result applied = PatchApply.to("one\ntwo",
                List.of(new PatchedHunk(2, 2, List.of("two"), List.of("two"), true, false)));

        assertThat(applied.applied()).isTrue();
        assertThat(applied.text()).isEqualTo("one\ntwo\n");
    }

    @Test
    void afileCreatedWithoutAfinalNewlineIsCreatedWithoutOne() {
        PatchApply.Result applied = PatchApply.to("",
                List.of(new PatchedHunk(0, 1, List.of(), List.of("only"), false, true)));

        assertThat(applied.text()).isEqualTo("only");
    }

    @Test
    void ahunkAwayFromTheEndSaysNothingAboutTheFinalNewline() {
        PatchApply.Result applied = PatchApply.to("one\ntwo",
                List.of(hunk(1, List.of("one"), List.of("ONE"))));

        assertThat(applied.text())
                .as("a hunk that does not reach the end of the file cannot move the end of it")
                .isEqualTo("ONE\ntwo");
    }
}
