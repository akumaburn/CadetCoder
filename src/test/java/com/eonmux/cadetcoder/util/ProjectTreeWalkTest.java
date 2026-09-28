package com.eonmux.cadetcoder.util;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bug this exists to prevent: a walk that starts at {@code "."} and prunes on
 * {@code startsWith(".")} prunes its own start directory and visits nothing.
 */
public class ProjectTreeWalkTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private List<String> walk(Path start) throws Exception {
        List<String> seen = new ArrayList<>();
        Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return ProjectTreeWalk.isPruned(start, dir)
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                seen.add(file.getFileName().toString());
                return FileVisitResult.CONTINUE;
            }
        });
        return seen;
    }

    @Test
    public void aWalkStartingAtDotStillVisitsTheProject() throws Exception {
        assertThat(ProjectTreeWalk.isPruned(Paths.get("."), Paths.get(".")))
                .as("Paths.get(\".\").getFileName() is \".\", which startsWith(\".\") -- pruning it "
                    + "leaves the walk with nothing to visit and no error to show for it")
                .isFalse();
    }

    @Test
    public void theStartDirectoryIsNeverPrunedEvenWhenItsNameWouldBe() {
        Path hidden = Paths.get("/home/someone/.config/myproject");
        assertThat(ProjectTreeWalk.isPruned(hidden, hidden)).isFalse();

        Path buildNamed = Paths.get("/srv/build");
        assertThat(ProjectTreeWalk.isPruned(buildNamed, buildNamed)).isFalse();
    }

    @Test
    public void buildOutputAndHiddenDirectoriesBelowTheStartArePruned() throws Exception {
        folder.newFolder("src");
        folder.newFolder("target");
        folder.newFolder(".git");
        folder.newFolder("node_modules");
        Files.writeString(folder.getRoot().toPath().resolve("src/Kept.java"), "class Kept {}");
        Files.writeString(folder.getRoot().toPath().resolve("target/Skipped.class"), "x");
        Files.writeString(folder.getRoot().toPath().resolve(".git/config"), "x");
        Files.writeString(folder.getRoot().toPath().resolve("node_modules/pkg.json"), "x");
        Files.writeString(folder.getRoot().toPath().resolve("Root.java"), "class Root {}");

        assertThat(walk(folder.getRoot().toPath()))
                .containsExactlyInAnyOrder("Kept.java", "Root.java");
    }

    @Test
    public void relativePathComponentsAreNotMistakenForHiddenDirectories() {
        assertThat(ProjectTreeWalk.isPrunedName(".")).isFalse();
        assertThat(ProjectTreeWalk.isPrunedName("..")).isFalse();
        assertThat(ProjectTreeWalk.isPrunedName(".git")).isTrue();
        assertThat(ProjectTreeWalk.isPrunedName(".idea")).isTrue();
        assertThat(ProjectTreeWalk.isPrunedName("src")).isFalse();
    }
}
