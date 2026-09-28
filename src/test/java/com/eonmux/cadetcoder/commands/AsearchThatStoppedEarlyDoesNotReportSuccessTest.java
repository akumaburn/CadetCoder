package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ExitCode;
import com.eonmux.cadetcoder.InterruptSignal;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.test.TestOutputCapture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * A search stopped part-way through says so, instead of reporting what it happened to have.
 *
 * <p><b>The defect</b>: the walk returned its partial results and nothing carried out of it the fact
 * that it had given up. The caller reported "Found 37 matches" -- or, when the stop landed before
 * the first match, "No matches found" -- for a search that had not looked, and exited zero either
 * way. "No matches found" for a search nobody finished is the worst of the two: it is an answer, and
 * it is wrong.</p>
 */
class AsearchThatStoppedEarlyDoesNotReportSuccessTest {

    @TempDir
    Path directory;

    private String originalWorkingDir;

    private TestOutputCapture output;

    @BeforeEach
    void setUp() {
        // The searched directory is the project, as it is when a user searches their own code.
        originalWorkingDir = System.getProperty("user.dir");
        System.setProperty("user.dir", directory.toAbsolutePath().toString());
        output = new TestOutputCapture();
        output.startCapture();
        InterruptSignal.clear();
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.dir", originalWorkingDir);
        InterruptSignal.clear();
        output.stopCapture();
    }

    /** A file long enough for the search to reach one of its interruption checkpoints. */
    private Path aLongFile(String line, int lines) throws IOException {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < lines; i++) {
            body.append(line).append('\n');
        }
        return Files.writeString(directory.resolve("subject.txt"), body.toString());
    }

    private int grep(String pattern, String... options) {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager manager = mock(ConfigManager.class);
            when(manager.getConfig()).thenReturn(new Configuration());
            configMock.when(ConfigManager::getInstance).thenReturn(manager);

            String[] args = new String[options.length + 3];
            args[0] = pattern;
            args[1] = "-p";
            args[2] = directory.toAbsolutePath().toString();
            System.arraycopy(options, 0, args, 3, options.length);
            return new GrepCommand().execute(args);
        }
    }

    @Test
    void asearchThatWasStoppedIsReportedAsStoppedRatherThanFinished() throws IOException {
        aLongFile("needle here", 500);
        InterruptSignal.request();

        int exitCode = grep("needle");

        assertThat(exitCode).isEqualTo(ExitCode.INTERRUPTED);
        assertThat(output.getAllOutput())
                .contains("stopped before it had looked everywhere")
                .contains("There may be more");
    }

    @Test
    void asearchThatWasStoppedBeforeItsFirstMatchDoesNotSayThereAreNone() throws IOException {
        aLongFile("nothing of interest", 500);
        InterruptSignal.request();

        int exitCode = grep("needle");

        assertThat(exitCode).isEqualTo(ExitCode.INTERRUPTED);
        assertThat(output.getAllOutput())
                .as("it never looked; 'no matches' would be an answer, and a wrong one")
                .doesNotContain("No matches found");
    }

    /**
     * What the stopped search says it found is what it found, not how many lines it printed.
     *
     * <p>The figure in the warning was the size of the result, and a result carries the lines
     * around each match as well as the matches. With {@code -C 2} that is five lines per match, so
     * a run that had found nine of them announced forty-five -- while the per-file line, which
     * counts only the lines the pattern selected, said nine directly above it. Two numbers for one
     * quantity, on consecutive lines, and the larger one was the lie.</p>
     */
    @Test
    void whatAstoppedSearchSaysItFoundCountsMatchesRatherThanLinesShown() throws IOException {
        sparseMatches(500, 10);
        InterruptSignal.request();

        grep("needle", "-C", "2");

        assertThat(output.getAllOutput())
                .contains("9 matches found so far")
                .doesNotContain("45 matches");
    }

    /**
     * A long file with a match every {@code every} lines, so most of what a context run keeps is
     * neighbouring lines rather than matches.
     */
    private Path sparseMatches(int lines, int every) throws IOException {
        StringBuilder body = new StringBuilder();
        for (int i = 1; i <= lines; i++) {
            body.append(i % every == 0 ? "needle here" : "nothing of interest").append('\n');
        }
        return Files.writeString(directory.resolve("subject.txt"), body.toString());
    }

    @Test
    void asearchNobodyStoppedStillReportsWhatItFound() throws IOException {
        aLongFile("needle here", 3);

        int exitCode = grep("needle");

        assertThat(exitCode).isZero();
        assertThat(output.getAllOutput())
                .doesNotContain("stopped before it had looked everywhere");
    }

    @Test
    void asearchNobodyStoppedStillReportsFindingNothing() throws IOException {
        aLongFile("nothing of interest", 3);

        int exitCode = grep("needle");

        assertThat(output.getAllOutput()).contains("No matches found");
        assertThat(exitCode).isNotEqualTo(ExitCode.INTERRUPTED);
    }
}
