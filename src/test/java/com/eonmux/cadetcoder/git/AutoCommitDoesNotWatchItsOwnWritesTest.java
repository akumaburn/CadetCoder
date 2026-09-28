package com.eonmux.cadetcoder.git;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The auto-commit watcher watches the project, and nothing that changes because it committed.
 *
 * <h2>The defect</h2>
 *
 * <p>The walk that registered directories with the watch service skipped registering {@code .git}
 * and {@code .cadet} but returned {@code CONTINUE} for them, so it descended and registered every
 * subdirectory underneath -- {@code .git/objects}, {@code .git/refs}, and the rest, none of which is
 * named {@code .git}. Committing writes there, that write arrived as a change, and the change
 * scheduled the next commit: the scheduler drove itself, on the largest part of the tree.</p>
 *
 * <p>The event loop's guard could not have stopped it either. It tested
 * {@code event.context().toString().contains(".git")}, and a watch event's context is the file name
 * relative to the directory that was registered -- never {@code .git/...} for a file inside
 * {@code .git}. The only names it ever matched were {@code .gitignore} and {@code .gitattributes},
 * which are the project's own tracked files, so the one thing the filter did was discard the
 * changes it was least entitled to.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>{@code .git} and {@code .cadet} are not the project's, whether they are met at the root or
 * anywhere below it; every other directory is, including one whose name merely begins with
 * {@code .git}; and the root of a walk, which has no file name of its own, is watched.</p>
 */
public class AutoCommitDoesNotWatchItsOwnWritesTest {

    @Test
    public void gitAndCadetAreNotTheProjects() {
        assertThat(AutoCommitScheduler.isTheProjects(Paths.get(".git"))).isFalse();
        assertThat(AutoCommitScheduler.isTheProjects(Paths.get(".cadet"))).isFalse();
        assertThat(AutoCommitScheduler.isTheProjects(Paths.get("/home/someone/project/.git")))
                .isFalse();
    }

    @Test
    public void aFileWhoseNameMerelyBeginsWithGitIsTheProjects() {
        // The name test this replaced discarded exactly these, and nothing else.
        assertThat(AutoCommitScheduler.isTheProjects(Paths.get(".gitignore"))).isTrue();
        assertThat(AutoCommitScheduler.isTheProjects(Paths.get(".gitattributes"))).isTrue();
        assertThat(AutoCommitScheduler.isTheProjects(Paths.get(".github"))).isTrue();
    }

    @Test
    public void ordinarySourceDirectoriesAreTheProjects() {
        assertThat(AutoCommitScheduler.isTheProjects(Paths.get("src/main/java"))).isTrue();
        assertThat(AutoCommitScheduler.isTheProjects(Paths.get("target"))).isTrue();
    }

    @Test
    public void aPathWithNoNameOfItsOwnIsStillWatched() {
        Path root = Paths.get("/");

        assertThat(root.getFileName()).as("a filesystem root has no file name").isNull();
        assertThat(AutoCommitScheduler.isTheProjects(root)).isTrue();
    }
}
