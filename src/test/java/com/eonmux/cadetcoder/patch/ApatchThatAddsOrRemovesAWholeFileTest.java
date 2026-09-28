package com.eonmux.cadetcoder.patch;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A patch whose subject is a whole file rather than lines inside one.
 *
 * <h2>What was missing</h2>
 *
 * <p>{@code git diff} writes {@code /dev/null} on the side of a file that does not exist, so a new
 * file reads {@code --- /dev/null} and a removed one reads {@code +++ /dev/null}. Any patch that
 * adds or deletes a file arrives in that shape, and a change to a project usually does at least one
 * of the two. The parser read the path correctly and nothing recorded WHICH of the three things the
 * patch was doing, so a creation and a deletion were both read as an edit to an existing file.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>That the kind of change is read off the headers, that the path is taken from whichever side is
 * a real file, and that an ordinary edit is still an ordinary edit.</p>
 */
class ApatchThatAddsOrRemovesAWholeFileTest {

    @Test
    void anEditOfAnExistingFileIsAModification() {
        String patch = """
                --- a/notes.txt
                +++ b/notes.txt
                @@ -1 +1 @@
                -before
                +after
                """;

        PatchedFile file = PatchParse.read(patch).get(0);

        assertThat(file.kind()).isEqualTo(PatchKind.MODIFY);
        assertThat(file.path()).isEqualTo("notes.txt");
    }

    @Test
    void anewFileIsACreation() {
        String patch = """
                --- /dev/null
                +++ b/added.txt
                @@ -0,0 +1,2 @@
                +one
                +two
                """;

        PatchedFile file = PatchParse.read(patch).get(0);

        assertThat(file.kind()).isEqualTo(PatchKind.CREATE);
        assertThat(file.path()).isEqualTo("added.txt");
        assertThat(file.hunks().get(0).after()).containsExactly("one", "two");
        assertThat(file.hunks().get(0).before()).isEmpty();
    }

    @Test
    void aremovedFileIsADeletion() {
        String patch = """
                --- a/gone.txt
                +++ /dev/null
                @@ -1,2 +0,0 @@
                -one
                -two
                """;

        PatchedFile file = PatchParse.read(patch).get(0);

        assertThat(file.kind()).isEqualTo(PatchKind.DELETE);
        assertThat(file.path()).isEqualTo("gone.txt");
        assertThat(file.hunks().get(0).before()).containsExactly("one", "two");
        assertThat(file.hunks().get(0).after()).isEmpty();
    }

    @Test
    void thegitHeaderThatSpellsOutTheModeIsReadTheSameWay() {
        // What `git diff` actually emits for a new file. The /dev/null side is what decides;
        // the "new file mode" line agrees with it and is not needed.
        String patch = """
                diff --git a/added.txt b/added.txt
                new file mode 100644
                index 0000000..3b18e51
                --- /dev/null
                +++ b/added.txt
                @@ -0,0 +1 @@
                +hello
                """;

        assertThat(PatchParse.read(patch).get(0).kind()).isEqualTo(PatchKind.CREATE);
    }

    @Test
    void acreationAndADeletionInOnePatchAreReadAsBoth() {
        String patch = """
                --- /dev/null
                +++ b/added.txt
                @@ -0,0 +1 @@
                +new
                --- a/gone.txt
                +++ /dev/null
                @@ -1 +0,0 @@
                -old
                """;

        assertThat(PatchParse.read(patch))
                .extracting(PatchedFile::path, PatchedFile::kind)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("added.txt", PatchKind.CREATE),
                                 org.assertj.core.groups.Tuple.tuple("gone.txt", PatchKind.DELETE));
    }

    @Test
    void acreationAppliesToNoTextAtAll() {
        PatchApply.Result applied = PatchApply.to("",
                List.of(new PatchedHunk(0, 1, List.of(), List.of("one", "two"))));

        assertThat(applied.applied()).isTrue();
        assertThat(applied.text()).isEqualTo("one\ntwo\n");
    }

    @Test
    void adeletionLeavesNoText() {
        PatchApply.Result applied = PatchApply.to("one\ntwo\n",
                List.of(new PatchedHunk(1, 0, List.of("one", "two"), List.of())));

        assertThat(applied.applied()).isTrue();
        assertThat(applied.text()).isEmpty();
    }
}
