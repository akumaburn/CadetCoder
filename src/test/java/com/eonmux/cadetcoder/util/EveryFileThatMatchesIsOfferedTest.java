package com.eonmux.cadetcoder.util;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Scanner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A path that was not found offers every file that matches it, and any of them can be chosen.
 *
 * <h2>The defect</h2>
 *
 * <p>The list stopped at ten and ended "... and 2 more matches", and a number past ten was refused.
 * The file the user meant could be the eleventh, and then there was no way to choose it.</p>
 */
public class EveryFileThatMatchesIsOfferedTest {

    private static final int MATCHES = 12;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Path projectWithMatches() throws Exception {
        Path project = folder.getRoot().toPath();
        for (int i = 1; i <= MATCHES; i++) {
            Path dir = Files.createDirectories(project.resolve("module" + i));
            Files.createFile(dir.resolve("Notes.txt"));
        }
        return project;
    }

    @Test
    public void everyMatchIsListed() throws Exception {
        FilePathResolver.ResolvedPath resolved =
                FilePathResolver.resolve("Notes.txt", projectWithMatches(), false);

        assertThat(resolved.getAlternatives()).hasSize(MATCHES);
        assertThat(resolved.getErrorMessage()).contains(" " + MATCHES + ". ")
                                              .doesNotContain("more matches");
    }

    @Test
    public void thelastMatchCanBeChosen() throws Exception {
        FilePathResolver.ResolvedPath resolved =
                FilePathResolver.resolve("Notes.txt", projectWithMatches(), false);

        Path chosen = resolved.selectFromAlternatives(new Scanner(MATCHES + "\n"));

        assertThat(chosen).isEqualTo(resolved.getAlternatives().get(MATCHES - 1));
    }
}
