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
 * The {@code diff} command.
 *
 * <h2>What was missing</h2>
 *
 * <p>An agent that wanted to know what two files differ by had to read both in full and work it
 * out. That costs the whole of both files in the prompt, and the answer is unreliable on anything
 * long. A review of a change, a check that an edit did what was intended, and a summary of work
 * done all begin with this question.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>That the output is a unified diff {@code patch} can read back, that a file the security rules
 * refuse is refused here too, and that the exit code separates "they differ" from "something went
 * wrong". The second matters because a caller acts on those differently.</p>
 */
public class TheToolCanSayWhatTwoFilesDifferByTest {

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

    private Path write(String name, String contents) throws IOException {
        Path file = project.getRoot().toPath().resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, contents);
        return file;
    }

    /** Runs one diff and returns its exit code; {@link #shown()} has the output. */
    private int diff(String... args) {
        try (MockedStatic<ConfigManager> configured = mockStatic(ConfigManager.class)) {
            ConfigManager manager = mock(ConfigManager.class);
            Configuration config  = new Configuration();
            config.getUi().setColorEnabled(false);
            config.getSecurity().setAllowOutsideProject(true);
            when(manager.getConfig()).thenReturn(config);
            configured.when(ConfigManager::getInstance).thenReturn(manager);
            return new DiffCommand().execute(args);
        }
    }

    private String shown() {
        return output.getAllOutput();
    }

    @Test
    public void twoFilesThatDifferAreReportedAsAUnifiedDiff() throws Exception {
        write("before.txt", "one\ntwo\nthree\n");
        write("after.txt", "one\nTWO\nthree\n");

        int exit = diff("before.txt", "after.txt");

        assertThat(shown()).contains("--- before.txt");
        assertThat(shown()).contains("+++ after.txt");
        assertThat(shown()).contains("-two");
        assertThat(shown()).contains("+TWO");
        assertThat(exit).as("a difference is the answer, not a failure").isZero();
    }

    @Test
    public void twoFilesThatMatchAreSaidToMatch() throws Exception {
        write("before.txt", "same\n");
        write("after.txt", "same\n");

        assertThat(diff("before.txt", "after.txt")).isZero();
        assertThat(shown()).contains("No difference");
    }

    @Test
    public void thecontextAskedForIsTheContextGiven() throws Exception {
        write("before.txt", "1\n2\n3\n4\n5\n6\n7\n");
        write("after.txt", "1\n2\n3\nX\n5\n6\n7\n");

        diff("before.txt", "after.txt", "--context=0");

        assertThat(shown()).contains("-4");
        assertThat(shown()).doesNotContain(" 3");
    }

    @Test
    public void thesummaryFormSaysHowMuchChangedWithoutSayingWhat() throws Exception {
        write("before.txt", "one\ntwo\n");
        write("after.txt", "one\nTWO\nthree\n");

        diff("before.txt", "after.txt", "--stat");

        assertThat(shown()).contains("2 added");
        assertThat(shown()).contains("1 removed");
        assertThat(shown()).doesNotContain("+TWO");
    }

    @Test
    public void amissingFileIsReportedRatherThanTreatedAsEmpty() throws Exception {
        write("before.txt", "one\n");

        int exit = diff("before.txt", "nothing-here.txt");

        assertThat(exit).isNotZero();
        assertThat(shown()).contains("nothing-here.txt");
    }

    @Test
    public void afileTheSecurityRulesRefuseIsRefusedHereToo() throws Exception {
        write("before.txt", "one\n");
        write(".env", "ai.apiKey=sk-abcdefghijklmnopqrstuvwxyz012345\n");

        int exit = diff("before.txt", ".env");

        assertThat(exit).isNotZero();
        assertThat(shown()).doesNotContain("sk-abcdefghijklmnopqrstuvwxyz012345");
    }

    @Test
    public void tooFewFilesIsReportedWithTheUsage() {
        int exit = diff("only-one.txt");

        assertThat(exit).isNotZero();
        assertThat(shown()).containsIgnoringCase("diff");
    }

    @Test
    public void whatDiffPrintsIsWhatPatchReadsBack() throws Exception {
        write("before.txt", "one\ntwo\nthree\n");
        write("after.txt", "one\nTWO\nthree\n");

        diff("before.txt", "after.txt");

        assertThat(com.eonmux.cadetcoder.patch.PatchParse.read(shown()))
                .as("the two commands are one interface")
                .isNotEmpty();
    }

    @Test
    public void adirectoryIsNotAFileToCompare() throws Exception {
        write("before.txt", "one\n");
        Files.createDirectories(project.getRoot().toPath().resolve("somedir"));

        assertThat(diff("before.txt", "somedir")).isNotZero();
    }
}
