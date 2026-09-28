package com.eonmux.cadetcoder.git;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The files a stage-all would add are the ones git would add.
 *
 * <p>{@code commit -a} warns about secret-looking files before it stages everything, and it chose
 * what to check by walking the working tree. Ignored build output was checked with the rest, so a
 * commit warned of secrets in {@code build/classes} files that git never staged.</p>
 */
class WhatStageAllAddsIsWhatGitWouldAddTest {

    @TempDir
    Path repo;

    @Test
    void changedAndNewFilesAreListedAndIgnoredOnesAreNot() throws Exception {
        try (Git git = Git.init().setDirectory(repo.toFile()).call()) {
            Files.writeString(repo.resolve(".gitignore"), "build/\n");
            Files.writeString(repo.resolve("kept.txt"), "one\n");
            Files.writeString(repo.resolve("unchanged.txt"), "same\n");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("start").setSign(false).call();
        }
        Files.writeString(repo.resolve("kept.txt"), "two\n");
        Files.writeString(repo.resolve("new.txt"), "new\n");
        Files.createDirectories(repo.resolve("build/classes"));
        Files.writeString(repo.resolve("build/classes/Client.class"), "password=hunter2\n");

        assertThat(new GitIntegration(repo.toFile()).pathsStageAllWouldAdd())
                .containsExactlyInAnyOrder(repo.resolve("kept.txt"), repo.resolve("new.txt"));
    }
}
