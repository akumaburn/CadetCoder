package com.eonmux.cadetcoder.harness.cadet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A workspace has more files than an observation can carry, and the agent still has to be able to
 * tell that something changed.
 *
 * <h2>The defect these lock out</h2>
 *
 * <p>An observation that lists the first few hundred paths and stops is a world with a blind spot:
 * a build that rewrote a file past the end of the listing produces the identical observation, so a
 * model predicting "nothing changed" is confirmed by a world that did change. The listing is
 * therefore a courtesy and the digest is the fact -- the digest covers every file, including the
 * ones no listing shows.</p>
 *
 * <h2>Why size and modification time rather than content</h2>
 *
 * <p>Hashing every byte of a repository on every step costs more than the step. Size and
 * modification time never miss a real edit; they can report a same-size rewrite within a clock tick
 * as no change at all, which is the direction that costs an extra observation rather than a wrong
 * prediction that goes unnoticed. These lock the digest's sensitivity, not its formula.</p>
 */
class TheWorkspaceIsSummarisedSoNoChangeGoesUnnoticedTest {

    /** More files than the listing shows, so the overflow is exercised rather than described. */
    private static final int MORE_THAN_FITS = WorkspaceTree.PATHS_SHOWN + 12;

    @Test
    void everyFileUnderTheRootIsListedRelativeToItAndInAStableOrder(@TempDir Path root)
            throws IOException {
        write(root.resolve("pom.xml"), "<project/>");
        write(root.resolve("src/main/java/A.java"), "class A {}");
        write(root.resolve("src/main/java/B.java"), "class B {}");

        WorkspaceTree tree = WorkspaceTree.of(root);

        assertThat(tree.paths())
                .containsExactly("pom.xml", "src/main/java/A.java", "src/main/java/B.java");
        assertThat(tree.total()).isEqualTo(3);
        assertThat(tree.truncated()).isFalse();
    }

    @Test
    void directoriesThatHoldBuildOutputOrNothingTheAgentWroteAreNotPartOfTheWorkspace(
            @TempDir Path root) throws IOException {
        write(root.resolve("pom.xml"), "<project/>");
        write(root.resolve("target/classes/A.class"), "compiled");
        write(root.resolve(".git/HEAD"), "ref: refs/heads/master");
        write(root.resolve("node_modules/left-pad/index.js"), "module.exports = 1;");

        assertThat(WorkspaceTree.of(root).paths()).containsExactly("pom.xml");
    }

    @Test
    void theDigestChangesWhenAFileDoes(@TempDir Path root) throws IOException {
        write(root.resolve("notes.txt"), "before");
        String before = WorkspaceTree.of(root).digest();

        write(root.resolve("notes.txt"), "after the edit");

        assertThat(WorkspaceTree.of(root).digest()).isNotEqualTo(before);
    }

    @Test
    void theDigestIsUnchangedWhenNothingIs(@TempDir Path root) throws IOException {
        write(root.resolve("notes.txt"), "steady");

        assertThat(WorkspaceTree.of(root).digest()).isEqualTo(WorkspaceTree.of(root).digest());
    }

    @Test
    void aFileAppearingAnywhereChangesTheDigestEvenPastTheEndOfTheListing(@TempDir Path root)
            throws IOException {
        for (int at = 0; at < MORE_THAN_FITS; at++) {
            write(root.resolve(String.format("f%04d.txt", at)), "x");
        }
        WorkspaceTree before = WorkspaceTree.of(root);
        assertThat(before.paths()).doesNotContain("zzz-past-the-listing.txt");

        write(root.resolve("zzz-past-the-listing.txt"), "y");
        WorkspaceTree after = WorkspaceTree.of(root);

        assertThat(after.paths()).doesNotContain("zzz-past-the-listing.txt");
        assertThat(after.digest()).isNotEqualTo(before.digest());
    }

    @Test
    void aTreeTooLargeToShowSaysHowManyFilesItActuallyHas(@TempDir Path root) throws IOException {
        for (int at = 0; at < MORE_THAN_FITS; at++) {
            write(root.resolve(String.format("f%04d.txt", at)), "x");
        }

        WorkspaceTree tree = WorkspaceTree.of(root);

        assertThat(tree.paths()).hasSize(WorkspaceTree.PATHS_SHOWN);
        assertThat(tree.total()).isEqualTo(MORE_THAN_FITS);
        assertThat(tree.truncated()).isTrue();
    }

    @Test
    void aWorkspaceThatIsNotThereIsRefusedRatherThanReportedEmpty(@TempDir Path root) {
        assertThatThrownBy(() -> WorkspaceTree.of(root.resolve("nowhere")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workspace");
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}
