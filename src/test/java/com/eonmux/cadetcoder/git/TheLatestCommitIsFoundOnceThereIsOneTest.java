package com.eonmux.cadetcoder.git;

import com.eonmux.cadetcoder.test.TestOutputCapture;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The newest commit of a real repository, read back after it was made.
 *
 * <h2>The defect</h2>
 *
 * <p>The log was asked for its iterator twice: once to ask whether there was a commit, and again to
 * take it. A log is a walk, and the second iterator starts where the first one left it -- past the
 * only commit a walk limited to one returns. So every repository with a commit was reported as
 * having none. {@code commit} printed "No commits found in the repository." straight after
 * "Committed changes", and an agent that read both spent its next steps checking whether its
 * commit existed. {@code undo} and {@code push}, which ask the same question, were told the same
 * thing.</p>
 *
 * <p>The mocked test beside this one could not see it, because its mock hands back the same
 * iterator however often it is asked. This one uses a repository on disk.</p>
 */
class TheLatestCommitIsFoundOnceThereIsOneTest {

    @TempDir
    Path project;

    private TestOutputCapture output;

    @BeforeEach
    void startCapturing() {
        output = new TestOutputCapture();
        output.startCapture();
    }

    @AfterEach
    void stopCapturing() {
        output.stopCapture();
    }

    @Test
    void thecommitJustMadeIsTheOneReported() throws Exception {
        RevCommit made;
        try (Git git = Git.init().setDirectory(project.toFile()).call()) {
            Files.writeString(project.resolve("notes.txt"), "one\n");
            git.add().addFilepattern("notes.txt").call();
            made = git.commit().setMessage("first").setAuthor("t", "t@example.test")
                      .setCommitter("t", "t@example.test").setSign(false).call();
        }

        String reported = new GitIntegration(project.toFile()).getLatestCommitHash();

        assertThat(reported).isEqualTo(made.getName());
        assertThat(output.getOutput()).doesNotContain("No commits found");
    }

    @Test
    void arepositoryWithNoCommitsStillSaysSo() throws Exception {
        Git.init().setDirectory(project.toFile()).call().close();

        String reported = new GitIntegration(project.toFile()).getLatestCommitHash();

        assertThat(reported).isNull();
        assertThat(output.getOutput()).contains("No commits found in the repository");
    }
}
