package com.eonmux.cadetcoder.git;

import com.eonmux.cadetcoder.test.TestOutputCapture;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a commit nobody chose the contents of actually records.
 *
 * <p><b>The defect</b>: auto-commit called {@link GitIntegration#commit(String)}, which commits the
 * index. Nothing put anything in the index -- there is no person here to have chosen what to
 * include, which is the whole reason the scheduler exists -- so every tick and every save wrote a
 * commit that recorded nothing, while the edits it was there to preserve stayed in the working tree.
 * A history of empty entries, and no backup of the one thing it was for. The record has to be made
 * by the same act as the thing it records.</p>
 */
public class AcommitNobodyChoseStillRecordsWhatChangedTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Path              project;
    private Git               git;
    private TestOutputCapture output;

    @Before
    public void setUp() throws Exception {
        project = folder.getRoot().toPath();
        output  = new TestOutputCapture();

        git = Git.init().setDirectory(project.toFile()).call();
        Files.writeString(project.resolve("kept.txt"), "first\n");
        git.add().addFilepattern(".").call();
        git.commit().setMessage("the work so far").call();
    }

    @After
    public void tearDown() {
        output.restore();
        if (git != null) {
            git.close();
        }
    }

    private static int commits(Git git) throws Exception {
        int seen = 0;
        for (Object ignored : git.log().call()) {
            seen++;
        }
        return seen;
    }

    @Test
    public void whatWasEditedSinceTheLastCommitIsInTheNextOne() throws Exception {
        Files.writeString(project.resolve("kept.txt"), "second\n");
        Files.writeString(project.resolve("added.txt"), "new\n");

        assertThat(new GitIntegration(project.toFile()).commitEverything("auto: saved")).isTrue();

        Status after = git.status().call();
        assertThat(after.isClean())
                .as("nothing is left behind in the working tree for the next tick to miss again")
                .isTrue();
        assertThat(commits(git)).isEqualTo(2);
    }

    /** An add records what is on disk, so a file that is gone is only staged by an update pass. */
    @Test
    public void afileThatWasDeletedIsRecordedAsDeleted() throws Exception {
        Files.delete(project.resolve("kept.txt"));

        assertThat(new GitIntegration(project.toFile()).commitEverything("auto: saved")).isTrue();

        assertThat(git.status().call().isClean()).isTrue();
        assertThat(commits(git)).isEqualTo(2);
    }

    /**
     * A tree that has not changed produces no commit at all.
     *
     * <p>An empty commit is a claim that work happened. On a timer that is a claim made every
     * interval for as long as the session is open, and a history it is made in is one nobody can
     * read back.</p>
     */
    @Test
    public void atreeThatHasNotChangedIsNotCommittedOverAndOver() throws Exception {
        assertThat(new GitIntegration(project.toFile()).commitEverything("auto: saved")).isFalse();
        assertThat(new GitIntegration(project.toFile()).commitEverything("auto: saved")).isFalse();

        assertThat(commits(git)).isEqualTo(1);
    }
}
