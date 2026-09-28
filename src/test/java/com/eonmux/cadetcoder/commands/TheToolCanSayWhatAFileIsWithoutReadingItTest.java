package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.test.TestOutputCapture;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * The {@code stat} command.
 *
 * <h2>What was missing</h2>
 *
 * <p>Deciding what to do with a file often needs facts about the file rather than its contents. Is
 * it there. How big is it. How many lines, so a read can be budgeted. When was it last written, so
 * a build can be judged stale. Is it text at all. Every one of those cost a {@code read} of the
 * whole file, or an {@code ls} of its directory and a guess.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>That the facts are right, that a file the security rules refuse is refused here too, and that
 * a missing file is reported as missing rather than as an error with no name in it.</p>
 */
public class TheToolCanSayWhatAFileIsWithoutReadingItTest {

    @Rule
    public TemporaryFolder project = new TemporaryFolder();

    private TestOutputCapture output;
    private String            wasWorkingDir;

    @Before
    public void setUp() {
        wasWorkingDir = System.getProperty("user.dir");
        System.setProperty("user.dir", project.getRoot().getAbsolutePath());
        output = new TestOutputCapture();
        output.startCapture();
    }

    @After
    public void tearDown() {
        output.stopCapture();
        System.setProperty("user.dir", wasWorkingDir);
    }

    private void write(String name, String contents) throws IOException {
        Path file = project.getRoot().toPath().resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, contents);
    }

    private int stat(String... args) {
        try (MockedStatic<ConfigManager> configured = mockStatic(ConfigManager.class)) {
            ConfigManager manager = mock(ConfigManager.class);
            Configuration config  = new Configuration();
            config.getUi().setColorEnabled(false);
            config.getSecurity().setAllowOutsideProject(true);
            when(manager.getConfig()).thenReturn(config);
            configured.when(ConfigManager::getInstance).thenReturn(manager);
            return new StatCommand().execute(args);
        }
    }

    private String shown() {
        return output.getAllOutput();
    }

    @Test
    public void thesizeAndTheLineCountAreReported() throws Exception {
        write("notes.txt", "one\ntwo\nthree\n");

        int exit = stat("notes.txt");

        assertThat(exit).isZero();
        assertThat(shown()).contains("14 bytes");
        assertThat(shown()).contains("3 lines");
    }

    @Test
    public void thekindOfThingItIsIsReported() throws Exception {
        write("notes.txt", "text\n");
        Files.createDirectories(project.getRoot().toPath().resolve("somedir"));

        stat("notes.txt");
        assertThat(shown()).containsIgnoringCase("file");

        stat("somedir");
        assertThat(shown()).containsIgnoringCase("directory");
    }

    @Test
    public void whenItWasLastWrittenIsReported() throws Exception {
        write("notes.txt", "text\n");

        stat("notes.txt");

        assertThat(shown()).containsIgnoringCase("modified");
    }

    @Test
    public void whetherItIsTextIsReportedBecauseItDecidesWhetherToReadIt() throws Exception {
        write("notes.txt", "plain text\n");
        Files.write(project.getRoot().toPath().resolve("blob.bin"),
                    new byte[]{0, 1, 2, 0, 3, 0, 4, 0});

        stat("notes.txt");
        assertThat(shown()).containsIgnoringCase("text");

        output.stopCapture();
        output = new TestOutputCapture();
        output.startCapture();

        stat("blob.bin");
        assertThat(shown()).containsIgnoringCase("binary");
    }

    @Test
    public void adirectoryReportsHowManyEntriesItHolds() throws Exception {
        write("somedir/one.txt", "a\n");
        write("somedir/two.txt", "b\n");

        stat("somedir");

        assertThat(shown()).contains("2 entries");
    }

    @Test
    public void adirectoryIsNotCountedInLines() throws Exception {
        write("somedir/one.txt", "a\n");

        stat("somedir");

        assertThat(shown()).doesNotContain("lines");
    }

    @Test
    public void adirectoryIsNotMeasuredInBytes() throws Exception {
        // The file system reports a size for a directory -- the inode's own size -- and it says
        // nothing about what is in there. Printing it beside an entry count invites it to be read
        // as the size of the contents, which it is not.
        write("somedir/one.txt", "a\n");

        stat("somedir");

        assertThat(shown()).doesNotContain("bytes");
        assertThat(shown()).contains("1 entry");
    }

    @Test
    public void oneEntryAndOneLineAreSaidInTheSingular() throws Exception {
        write("somedir/one.txt", "just the one line\n");

        stat("somedir");
        assertThat(shown()).contains("1 entry").doesNotContain("1 entries");

        stat("somedir/one.txt");
        assertThat(shown()).contains("1 line,").doesNotContain("1 lines");
    }

    @Test
    public void afileThatIsNotThereIsSaidToBeMissingByName() {
        int exit = stat("nothing-here.txt");

        assertThat(exit).isNotZero();
        assertThat(shown()).contains("nothing-here.txt");
    }

    @Test
    public void severalFilesInOneCallAreAllReported() throws Exception {
        write("one.txt", "a\n");
        write("two.txt", "b\n");

        assertThat(stat("one.txt", "two.txt")).isZero();
        assertThat(shown()).contains("one.txt");
        assertThat(shown()).contains("two.txt");
    }

    @Test
    public void onefileMissingDoesNotStopTheRest() throws Exception {
        write("one.txt", "a\n");

        int exit = stat("nothing-here.txt", "one.txt");

        assertThat(exit).as("something was asked for and not found").isNotZero();
        assertThat(shown()).contains("one.txt");
    }

    @Test
    public void afileTheSecurityRulesRefuseIsRefusedHereToo() throws Exception {
        write(".env", "ai.apiKey=sk-abcdefghijklmnopqrstuvwxyz012345\n");

        int exit = stat(".env");

        assertThat(exit).isNotZero();
        assertThat(shown()).doesNotContain("sk-abcdefghijklmnopqrstuvwxyz012345");
    }

    @Test
    public void nothingAtAllIsReportedWithTheUsage() {
        assertThat(stat()).isNotZero();
        assertThat(shown()).containsIgnoringCase("stat");
    }

    @Test
    public void anEmptyFileIsZeroBytesAndZeroLines() throws Exception {
        write("empty.txt", "");

        stat("empty.txt");

        assertThat(shown()).contains("0 bytes");
        assertThat(shown()).contains("0 lines");
    }

    @Test
    public void afileWithNoFinalNewlineStillCountsItsLastLine() throws Exception {
        write("notes.txt", "one\ntwo");

        stat("notes.txt");

        assertThat(shown()).contains("2 lines");
    }
}
