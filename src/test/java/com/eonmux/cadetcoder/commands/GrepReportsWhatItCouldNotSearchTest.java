package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import com.eonmux.cadetcoder.test.ProjectFolder;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * A search that cannot read something has to say so.
 *
 * <p>Two silences, one summary line that was written for both and fired for neither.</p>
 *
 * <p>The worse one is a directory. The tree walk used a bare {@code SimpleFileVisitor}, whose
 * {@code visitFileFailed} and {@code postVisitDirectory} rethrow the failure they are handed, so a
 * single unreadable subdirectory -- one owned by root, one on a mount that hiccuped -- aborted the
 * whole walk and threw away every match found before it. The user got an I/O error where they had
 * asked a question about their project.</p>
 *
 * <p>The other is a file. {@code shouldProcessFile} dropped anything {@code Files.isReadable} said
 * no to, before anything counted it, so the file was omitted from the results in silence -- and an
 * omission the user cannot see is indistinguishable from the pattern genuinely not being
 * there.</p>
 */
public class GrepReportsWhatItCouldNotSearchTest {

    @Rule
    public TemporaryFolder projectFolder = new ProjectFolder();

    private GrepCommand       grep;
    private TestOutputCapture output;

    /** Paths made unreadable by a test, restored so the folder rule can delete them. */
    private final List<Path> locked = new ArrayList<>();

    @Before
    public void setUp() {
        grep   = new GrepCommand();
        output = new TestOutputCapture();
        output.startCapture();
    }

    @After
    public void tearDown() {
        for (Path path : locked) {
            try {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
            } catch (IOException ignored) {
                // Best effort: the folder rule reports what it cannot delete.
            }
        }
        output.stopCapture();
    }

    private Path write(String name, String contents) throws IOException {
        Path file = projectFolder.getRoot().toPath().resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, contents);
        return file;
    }

    /** Removes every permission from a path, or skips the test where that is not possible. */
    private void lock(Path path) throws IOException {
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("---------"));
        locked.add(path);
        Assume.assumeFalse("running as a user who can read anything", Files.isReadable(path));
    }

    /**
     * Runs a search over the temporary project and returns everything the user would see.
     *
     * @param pattern the search pattern
     * @return the command's combined output
     */
    private String search(String pattern) {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager manager = mock(ConfigManager.class);
            Configuration config  = new Configuration();
            config.getUi().setColorEnabled(false);
            when(manager.getConfig()).thenReturn(config);
            configMock.when(ConfigManager::getInstance).thenReturn(manager);

            grep.execute(new String[] {pattern, "-p", projectFolder.getRoot().getAbsolutePath()});
        }
        return output.getAllOutput();
    }

    /**
     * Runs a search restricted to one glob and returns everything the user would see.
     *
     * @param pattern the search pattern
     * @param include the {@code --include} glob
     * @return the command's combined output
     */
    private String search(String pattern, String include) {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager manager = mock(ConfigManager.class);
            Configuration config  = new Configuration();
            config.getUi().setColorEnabled(false);
            when(manager.getConfig()).thenReturn(config);
            configMock.when(ConfigManager::getInstance).thenReturn(manager);

            grep.execute(new String[] {pattern, "-p", projectFolder.getRoot().getAbsolutePath(),
                                       "--include", include});
        }
        return output.getAllOutput();
    }

    @Test
    public void oneUnreadableDirectoryDoesNotDiscardTheWholeSearch() throws Exception {
        write("src/notes.txt", "the needlemarker is here\n");
        write("vault/hidden.txt", "the needlemarker is in here too\n");
        lock(projectFolder.getRoot().toPath().resolve("vault"));

        String out = search("needlemarker");

        assertThat(out)
                .as("a match found before the unreadable directory must survive it")
                .contains("needlemarker");
        assertThat(out)
                .as("the walk must degrade per directory, not per search")
                .doesNotContain("No matches found");
    }

    @Test
    public void anUnreadableDirectoryIsReported() throws Exception {
        write("src/notes.txt", "the needlemarker is here\n");
        write("vault/hidden.txt", "the needlemarker is in here too\n");
        lock(projectFolder.getRoot().toPath().resolve("vault"));

        assertThat(search("needlemarker"))
                .as("part of the tree was not searched; a silent omission reads as an honest miss")
                .contains("could not be read")
                .contains("vault");
    }

    @Test
    public void anUnreadableFileIsReportedNotSilentlyOmitted() throws Exception {
        write("src/notes.txt", "the needlemarker is here\n");
        Path unreadable = write("src/locked.conf", "the needlemarker is in here too\n");
        lock(unreadable);

        String out = search("needlemarker");

        assertThat(out).contains("needlemarker");
        assertThat(out)
                .as("the file was in scope and was not searched")
                .contains("1 file could not be read and was skipped")
                .contains("locked.conf");
    }

    /**
     * A hidden or excluded subtree is an omission like any other, and is named like one.
     *
     * <p>This is the omission a caller is least likely to guess at, because nothing about
     * {@code grep workflow --include "**}{@code /*.yml"} suggests that {@code .github} was never
     * entered. The answer came back as "No matches found", which is the same sentence the run would
     * print if the project genuinely had no workflows.</p>
     */
    @Test
    public void aprunedDirectoryIsNamedRatherThanSilentlySkipped() throws Exception {
        write("src/notes.txt", "the needlemarker is here\n");
        write(".github/workflows/ci.yml", "the needlemarker is in the workflow\n");

        String out = search("needlemarker", "**/*.yml");

        assertThat(out)
                .as("the subtree that held the only .yml file was skipped without a word")
                .contains("hidden or excluded")
                .contains(".github");
    }

    /**
     * A glob that spells a pruned directory's name out is the caller asking for that directory.
     *
     * <p>Pruning exists so an ordinary search does not wade through {@code .git} and {@code target}
     * on the way to the project's own files. It was never meant to overrule a caller who names the
     * directory, and a {@code --include} that carries the name as a literal path segment -- rather
     * than reaching it through a wildcard -- has named it.</p>
     */
    @Test
    public void anincludeGlobThatNamesAprunedDirectoryReachesIntoIt() throws Exception {
        write("src/notes.txt", "the needlemarker is here\n");
        write(".github/workflows/ci.yml", "the needlemarker is in the workflow\n");

        assertThat(search("needlemarker", ".github/**"))
                .contains("the needlemarker is in the workflow");
    }

    /** With nothing pruned there is nothing to report, so an ordinary search stays quiet. */
    @Test
    public void asearchWithNothingPrunedSaysNothingAboutPruning() throws Exception {
        write("src/notes.txt", "the needlemarker is here\n");

        assertThat(search("needlemarker")).doesNotContain("hidden or excluded");
    }

    /** A file the caller's own globs ruled out was never in scope, so it is not a complaint. */
    @Test
    public void aFileTheGlobsExcludedIsNotReportedAsUnsearchable() throws Exception {
        write("src/notes.txt", "the needlemarker is here\n");
        lock(write("src/locked.conf", "the needlemarker is in here too\n"));

        assertThat(search("needlemarker", "**/*.txt"))
                .as("--include named the files the user wanted; the rest are not omissions")
                .doesNotContain("could not be read");
    }
}
