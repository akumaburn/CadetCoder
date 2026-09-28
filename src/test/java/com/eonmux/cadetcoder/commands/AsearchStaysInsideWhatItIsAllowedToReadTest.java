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
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Where a search may look is decided by the resolved file, not by the name it was reached under.
 *
 * <h2>The defect</h2>
 *
 * <p>{@code GrepSearch} asked {@code SecurityValidator} only whether a file was on the credential
 * denylist, and it asked about the path as the walk had spelled it. {@code isFileAccessAllowed} --
 * the rule {@code read}, {@code write} and {@code ls} go through, and the one that knows about
 * protected system locations and the project boundary -- was never consulted at all.</p>
 *
 * <p>A symbolic link is a file name in one place and content in another, and the walk does not
 * follow links when it descends but {@code Files.lines} follows one when it reads. So a link
 * sitting in the search root pointed wherever it liked, and its target was opened, matched, and
 * printed line by line under the link's own harmless-looking name. The denylist could not help:
 * the name it was shown was the link.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>That the question is asked about the path with every link in it followed, so the answer is
 * about the bytes that will actually be read -- and that the refusal is said out loud, because a
 * file omitted in silence reads exactly like a pattern that is not there.</p>
 */
public class AsearchStaysInsideWhatItIsAllowedToReadTest {

    /** A world-readable file in a protected system location, present on any POSIX host. */
    private static final Path SYSTEM_FILE = Paths.get("/etc/passwd");

    @Rule
    public TemporaryFolder project = new ProjectFolder();

    @Rule
    public TemporaryFolder elsewhere = new TemporaryFolder();

    private TestOutputCapture output;

    @Before
    public void startCapture() {
        output = new TestOutputCapture();
        output.startCapture();
    }

    @After
    public void stopCapture() {
        output.stopCapture();
    }

    private Path write(TemporaryFolder folder, String name, String contents) throws IOException {
        Path file = folder.getRoot().toPath().resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, contents);
        return file;
    }

    /** Links a name in the search root to a target outside it, or skips where links are refused. */
    private void link(String name, Path target) throws IOException {
        try {
            Files.createSymbolicLink(project.getRoot().toPath().resolve(name), target);
        } catch (UnsupportedOperationException | FileSystemException e) {
            Assume.assumeNoException("this file system does not create symbolic links", e);
        }
    }

    /** Runs one search over the temporary project and returns what the user would see. */
    private String grep(String pattern) {
        try (MockedStatic<ConfigManager> configured = mockStatic(ConfigManager.class)) {
            ConfigManager manager = mock(ConfigManager.class);
            Configuration config  = new Configuration();
            config.getUi().setColorEnabled(false);
            when(manager.getConfig()).thenReturn(config);
            configured.when(ConfigManager::getInstance).thenReturn(manager);

            new GrepCommand().execute(new String[] {
                    pattern, "-p", project.getRoot().getAbsolutePath()});
        }
        return output.getAllOutput();
    }

    @Test
    public void alinkToAcredentialFileOutsideTheRootIsNotRead() throws Exception {
        write(elsewhere, ".aws/credentials", "aws_secret_access_key = HUNTER2SECRET\n");
        write(project, "notes.txt", "the key is configured elsewhere\n");
        link("harmless.txt", elsewhere.getRoot().toPath().resolve(".aws/credentials"));

        String shown = grep("key");

        assertThat(shown)
                .as("the denylist was shown the link's name, which says nothing about the target")
                .doesNotContain("HUNTER2SECRET");
        assertThat(shown).contains("the key is configured elsewhere");
    }

    @Test
    public void alinkIntoAprotectedSystemLocationIsNotRead() throws Exception {
        Assume.assumeTrue("no readable " + SYSTEM_FILE + " on this host",
                          Files.isReadable(SYSTEM_FILE));
        write(project, "notes.txt", "the root account is managed by the platform\n");
        link("accounts.txt", SYSTEM_FILE);

        String shown = grep("roo[t]");

        assertThat(shown)
                .as("/etc is a protected location whichever name the walk reached it under")
                .doesNotContain(":0:0:");
        assertThat(shown).contains("account is managed by the platform");
    }

    @Test
    public void therefusalIsReportedRatherThanSilent() throws Exception {
        write(elsewhere, ".aws/credentials", "aws_secret_access_key = HUNTER2SECRET\n");
        write(project, "notes.txt", "the key is configured elsewhere\n");
        link("harmless.txt", elsewhere.getRoot().toPath().resolve(".aws/credentials"));

        assertThat(grep("key"))
                .as("an omission the user cannot see reads as an honest miss")
                .contains("credential file")
                .contains("skipped")
                .contains("harmless.txt");
    }

    /** The gate is about where a file resolves to, so an ordinary one is unaffected by it. */
    @Test
    public void anordinaryFileInTheSearchRootIsStillRead() throws Exception {
        write(project, "src/notes.txt", "the needlemarker is here\n");

        String shown = grep("needlemarker");

        assertThat(shown).contains("the needlemarker is here");
        assertThat(shown).doesNotContain("skipped");
    }

    /** A link that stays inside the search root is reading what the caller asked about. */
    @Test
    public void alinkToAfileInsideTheSearchRootIsStillRead() throws Exception {
        write(project, "src/notes.txt", "the needlemarker is here\n");
        link("shortcut.txt", project.getRoot().toPath().resolve("src/notes.txt"));

        assertThat(grep("needlemarker"))
                .as("both names lead to content the search was pointed at")
                .contains("shortcut.txt");
    }
}
