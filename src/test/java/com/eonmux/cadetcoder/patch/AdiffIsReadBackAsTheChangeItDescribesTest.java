package com.eonmux.cadetcoder.patch;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reading a unified diff back into the change it describes.
 *
 * <h2>What is locked here</h2>
 *
 * <p>That every shape a real diff comes in is read: the {@code diff --git} header a model copies
 * from {@code git diff}, the bare {@code ---}/{@code +++} pair, an {@code a/} and {@code b/} prefix
 * that has to come off, several files in one patch, and several hunks in one file. Also that a
 * malformed patch is refused with a reason rather than half-applied.</p>
 *
 * <h2>Why the prefix is only taken off when the header carries it</h2>
 *
 * <p>git writes {@code a/} on the old side and {@code b/} on the new one, so a pair carrying the
 * prefix carries one of each. Taking two characters off whatever begins {@code a/} or {@code b/}
 * resolves a patch against a project with a real top-level directory named {@code a} to a file one
 * directory above the one it names.</p>
 *
 * <h2>Why the missing-newline marker is kept</h2>
 *
 * <p>Whether the last line of a file has a newline after it is part of what the file is, and a
 * patch that only adds or removes that newline says so nowhere else.</p>
 */
class AdiffIsReadBackAsTheChangeItDescribesTest {

    private static final String ONE_FILE = """
            --- a/src/Main.java
            +++ b/src/Main.java
            @@ -1,3 +1,3 @@
             one
            -two
            +TWO
             three
            """;

    @Test
    void thefileANdItsHunkAreRead() {
        List<PatchedFile> files = PatchParse.read(ONE_FILE);

        assertThat(files).hasSize(1);
        assertThat(files.get(0).path()).isEqualTo("src/Main.java");
        assertThat(files.get(0).hunks()).hasSize(1);
    }

    @Test
    void thepathPrefixesGitAddsAreRemoved() {
        assertThat(PatchParse.read(ONE_FILE).get(0).path()).doesNotStartWith("a/");
    }

    @Test
    void ahunkKnowsWhereItStartsAndWhatItExpectsToFind() {
        PatchedHunk hunk = PatchParse.read(ONE_FILE).get(0).hunks().get(0);

        assertThat(hunk.oldStart()).isEqualTo(1);
        assertThat(hunk.before()).containsExactly("one", "two", "three");
        assertThat(hunk.after()).containsExactly("one", "TWO", "three");
    }

    @Test
    void agitStyleHeaderIsReadAsWell() {
        String patch = """
                diff --git a/notes.txt b/notes.txt
                index 1234567..89abcde 100644
                --- a/notes.txt
                +++ b/notes.txt
                @@ -1 +1 @@
                -before
                +after
                """;

        assertThat(PatchParse.read(patch).get(0).path()).isEqualTo("notes.txt");
    }

    @Test
    void aheaderWithNoPrefixIsReadAsWell() {
        String patch = """
                --- notes.txt
                +++ notes.txt
                @@ -1 +1 @@
                -before
                +after
                """;

        assertThat(PatchParse.read(patch).get(0).path()).isEqualTo("notes.txt");
    }

    @Test
    void severalFilesInOnePatchAreReadAsSeveralFiles() {
        String patch = """
                --- a/one.txt
                +++ b/one.txt
                @@ -1 +1 @@
                -a
                +A
                --- a/two.txt
                +++ b/two.txt
                @@ -1 +1 @@
                -b
                +B
                """;

        assertThat(PatchParse.read(patch)).extracting(PatchedFile::path)
                                          .containsExactly("one.txt", "two.txt");
    }

    @Test
    void severalHunksInOneFileAreReadAsSeveralHunks() {
        String patch = """
                --- a/long.txt
                +++ b/long.txt
                @@ -1 +1 @@
                -a
                +A
                @@ -9 +9 @@
                -i
                +I
                """;

        assertThat(PatchParse.read(patch).get(0).hunks()).hasSize(2);
    }

    @Test
    void atrailingTimestampOnTheHeaderIsNotPartOfThePath() {
        String patch = """
                --- notes.txt\t2026-09-15 12:00:00.000000000 -0300
                +++ notes.txt\t2026-09-15 12:01:00.000000000 -0300
                @@ -1 +1 @@
                -before
                +after
                """;

        assertThat(PatchParse.read(patch).get(0).path()).isEqualTo("notes.txt");
    }

    @Test
    void alineWithNoMarkerEndsTheHunkRatherThanJoiningIt() {
        // Some tools emit a bare empty line for an empty context line. A line that is genuinely
        // outside the hunk has to stop it, or prose after a patch is read as content.
        String patch = """
                --- a/notes.txt
                +++ b/notes.txt
                @@ -1,2 +1,2 @@
                 kept
                -before
                +after
                That is the change I made.
                """;

        PatchedHunk hunk = PatchParse.read(patch).get(0).hunks().get(0);

        assertThat(hunk.before()).containsExactly("kept", "before");
        assertThat(hunk.after()).containsExactly("kept", "after");
    }

    @Test
    void anEmptyContextLineIsReadAsAnEmptyLine() {
        String patch = "--- a/notes.txt\n+++ b/notes.txt\n@@ -1,3 +1,3 @@\n a\n\n-b\n+B\n";

        PatchedHunk hunk = PatchParse.read(patch).get(0).hunks().get(0);

        assertThat(hunk.before()).containsExactly("a", "", "b");
        assertThat(hunk.after()).containsExactly("a", "", "B");
    }

    @Test
    void anoNewlineMarkerIsNotAContentLine() {
        String patch = """
                --- a/notes.txt
                +++ b/notes.txt
                @@ -1 +1 @@
                -before
                \\ No newline at end of file
                +after
                """;

        PatchedHunk hunk = PatchParse.read(patch).get(0).hunks().get(0);

        assertThat(hunk.before()).containsExactly("before");
        assertThat(hunk.after()).containsExactly("after");
    }

    @Test
    void textThatIsNotAPatchReadsAsNoFiles() {
        assertThat(PatchParse.read("I changed the second line to TWO.")).isEmpty();
        assertThat(PatchParse.read("")).isEmpty();
        assertThat(PatchParse.read(null)).isEmpty();
    }

    @Test
    void ahunkHeaderThatNamesNoNumbersIsRefusedWithAReason() {
        String patch = """
                --- a/notes.txt
                +++ b/notes.txt
                @@ what changed @@
                -before
                +after
                """;

        assertThat(PatchParse.read(patch))
                .as("a header nothing can be located from is not a hunk")
                .isEmpty();
    }

    @Test
    void arealTopLevelDirectoryNamedAisNotApathPrefix() {
        // What `git diff --no-prefix` writes for a project that has a directory called "a".
        String patch = """
                --- a/notes.txt
                +++ a/notes.txt
                @@ -1 +1 @@
                -before
                +after
                """;

        assertThat(PatchParse.read(patch).get(0).path()).isEqualTo("a/notes.txt");
    }

    @Test
    void thenoNewlineMarkerSaysWhichSideOfTheHunkLacksOne() {
        String patch = """
                --- a/notes.txt
                +++ b/notes.txt
                @@ -1 +1 @@
                -before
                \\ No newline at end of file
                +after
                """;

        PatchedHunk hunk = PatchParse.read(patch).get(0).hunks().get(0);

        assertThat(hunk.beforeEndsWithoutNewline()).isTrue();
        assertThat(hunk.afterEndsWithoutNewline()).isFalse();
    }

    @Test
    void anoNewlineMarkerOnTheLastLineOfAhunkIsStillRead() {
        // The marker that says the patch REMOVES the final newline comes after the last line of
        // the hunk, where a reader that stops as soon as the header's counts are met never sees it.
        String patch = """
                --- a/notes.txt
                +++ b/notes.txt
                @@ -1 +1 @@
                -before
                +after
                \\ No newline at end of file
                """;

        PatchedHunk hunk = PatchParse.read(patch).get(0).hunks().get(0);

        assertThat(hunk.beforeEndsWithoutNewline()).isFalse();
        assertThat(hunk.afterEndsWithoutNewline()).isTrue();
    }

    @Test
    void anoNewlineMarkerAfterAcontextLineDescribesBothSides() {
        String patch = """
                --- a/notes.txt
                +++ b/notes.txt
                @@ -1,2 +1,2 @@
                -one
                +ONE
                 two
                \\ No newline at end of file
                """;

        PatchedHunk hunk = PatchParse.read(patch).get(0).hunks().get(0);

        assertThat(hunk.beforeEndsWithoutNewline()).isTrue();
        assertThat(hunk.afterEndsWithoutNewline()).isTrue();
    }

    @Test
    void ahunkShorterThanItsHeaderPromisesIsRefused() {
        String patch = """
                --- a/notes.txt
                +++ b/notes.txt
                @@ -1,3 +1,3 @@
                 one
                -two
                +TWO
                """;

        assertThatThrownBy(() -> PatchParse.read(patch))
                .as("a hunk that replaces fewer lines than it says replaces the wrong lines")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("3");
    }

    @Test
    void amarkerThatIsNotAhunkLineIsRefusedRatherThanEndingTheHunkEarly() {
        String patch = """
                --- a/notes.txt
                +++ b/notes.txt
                @@ -1,3 +1,3 @@
                 one
                ?two
                +TWO
                 three
                """;

        assertThatThrownBy(() -> PatchParse.read(patch))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
