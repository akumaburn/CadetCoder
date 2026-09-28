package com.eonmux.cadetcoder.git;

import com.eonmux.cadetcoder.test.TestOutputCapture;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Git says what happened, not what was attempted.
 *
 * <p><b>The defects</b>: a push whose refs the remote refused printed "Pushed commits to the remote
 * repository" and exited 0, because the result of the push was thrown away. Staging everything
 * staged only what was on disk, so a deleted file was never recorded as deleted. And a commit with
 * nothing staged wrote an empty entry rather than saying there was nothing to record.</p>
 */
public class WhatGitWasAskedToDoIsWhatItReportsTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private TestOutputCapture output;

    @Before
    public void setUp() {
        output = new TestOutputCapture();
    }

    @After
    public void tearDown() {
        output.restore();
    }

    private static int commits(Git git) throws Exception {
        int seen = 0;
        for (Object ignored : git.log().call()) {
            seen++;
        }
        return seen;
    }

    /** A repository with one commit in it, and the handle that made it. */
    private Git repositoryWithOneCommit(Path where) throws Exception {
        Git git = Git.init().setDirectory(where.toFile()).call();
        Files.writeString(where.resolve("kept.txt"), "first\n");
        git.add().addFilepattern(".").call();
        git.commit().setMessage("the work so far").call();
        return git;
    }

    /** Staging everything includes the files that are gone, or the commit preserves them. */
    @Test
    public void stagingEverythingStagesAfileThatIsNoLongerThere() throws Exception {
        Path project = folder.getRoot().toPath();
        try (Git git = repositoryWithOneCommit(project)) {
            Files.delete(project.resolve("kept.txt"));

            new GitIntegration(project.toFile()).stageAllChanges();

            Status status = git.status().call();
            assertThat(status.getRemoved())
                    .as("a deletion nobody staged is a deletion the commit does not record")
                    .contains("kept.txt");
        }
    }

    /** A commit with nothing staged records nothing, and says so. */
    @Test
    public void acommitWithNothingStagedIsNotWrittenAtAll() throws Exception {
        Path project = folder.getRoot().toPath();
        try (Git git = repositoryWithOneCommit(project)) {
            int before = commits(git);

            boolean committed = new GitIntegration(project.toFile()).commit("nothing to say");

            assertThat(committed).isFalse();
            assertThat(commits(git))
                    .as("an entry that records no change is a lie the history keeps")
                    .isEqualTo(before);
        }
    }

    /** A push the remote refused is reported as refused. */
    @Test
    public void apushTheRemoteRefusedIsNotReportedAsAsuccess() throws Exception {
        File bare = folder.newFolder("remote.git");
        Git.init().setBare(true).setDirectory(bare).call().close();
        String remote = bare.toURI().toString();

        File aheadDir  = folder.newFolder("ahead");
        File behindDir = folder.newFolder("behind");

        try (Git seed = Git.cloneRepository().setURI(remote).setDirectory(aheadDir).call()) {
            Files.writeString(aheadDir.toPath().resolve("one.txt"), "one\n");
            seed.add().addFilepattern(".").call();
            seed.commit().setMessage("one").call();
            seed.push().call();
        }

        // Cloned while the remote had one commit, so what it pushes next cannot fast-forward once
        // the remote has moved on.
        try (Git behind = Git.cloneRepository().setURI(remote).setDirectory(behindDir).call()) {
            try (Git ahead = Git.open(aheadDir)) {
                Files.writeString(aheadDir.toPath().resolve("two.txt"), "two\n");
                ahead.add().addFilepattern(".").call();
                ahead.commit().setMessage("two").call();
                ahead.push().call();
            }

            Files.writeString(behindDir.toPath().resolve("other.txt"), "other\n");
            behind.add().addFilepattern(".").call();
            behind.commit().setMessage("mine").call();
        }

        List<String> refused = new GitIntegration(behindDir).push();

        assertThat(refused)
                .as("the remote has nothing of this, so nobody may be told it does")
                .isNotEmpty();
        assertThat(output.getOutput()).doesNotContain("Pushed commits to the remote repository");
    }
}
