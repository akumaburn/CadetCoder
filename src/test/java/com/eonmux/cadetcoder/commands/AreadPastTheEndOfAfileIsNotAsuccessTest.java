package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import com.eonmux.cadetcoder.test.ProjectFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An offset with nothing behind it is reported, whichever path the read takes.
 *
 * <h2>The defect</h2>
 *
 * <p>The streaming path, used for files over 500 KB, decided "past the end" by comparing its skip
 * counter with the requested offset. That counter stops for two different reasons -- it reached the
 * offset, or the file ran out -- and the two coincide exactly at the first line past the end. So a
 * 10,000-line file read from offset 10,001 printed a header, no content, and exited 0, while offset
 * 10,002 was answered correctly. The hole was one line wide, and it is the line a paging reader
 * lands on the moment it has read everything: the reader is told "success, nothing here" and cannot
 * tell it from an empty page in the middle.</p>
 *
 * <p>The small-file path has always asked the question of the content rather than of the counter,
 * which is what makes the disagreement visible.</p>
 */
public class AreadPastTheEndOfAfileIsNotAsuccessTest {

    /** Over the threshold at which ReadCommand switches to streaming. */
    private static final int STREAMING_BYTES = 600 * 1024;

    @Rule
    public TemporaryFolder tempFolder = new ProjectFolder();

    private ReadCommand       read;
    private TestOutputCapture output;

    @Before
    public void setUp() {
        read   = new ReadCommand();
        output = new TestOutputCapture();
        output.startCapture();
    }

    @After
    public void tearDown() {
        output.stopCapture();
    }

    /** @return a file of {@code lines} lines, padded past the streaming threshold if asked */
    private Path fileOf(int lines, boolean large) throws IOException {
        StringBuilder text    = new StringBuilder();
        String        padding = large ? "x".repeat(Math.max(1, STREAMING_BYTES / lines)) : "x";
        for (int i = 1; i <= lines; i++) {
            text.append("line ").append(i).append(' ').append(padding).append('\n');
        }
        Path file = tempFolder.newFile("subject.txt").toPath();
        Files.writeString(file, text.toString());
        return file;
    }

    @Test
    public void thefirstOffsetPastTheEndIsRefusedOnAstreamedFile() throws IOException {
        Path file = fileOf(400, true);
        assertThat(Files.size(file)).as("this must take the streaming path").isGreaterThan(500 * 1024);

        int exitCode = read.execute(new String[] {file.toString(), "--offset", "401"});

        assertThat(exitCode).as("nothing was read, so nothing succeeded").isEqualTo(1);
        assertThat(output.getAllOutput()).contains("beyond end of file");
    }

    @Test
    public void anoffsetWellPastTheEndIsStillRefused() throws IOException {
        Path file = fileOf(400, true);

        assertThat(read.execute(new String[] {file.toString(), "--offset", "402"})).isEqualTo(1);
    }

    /** The two paths have to answer the same question the same way. */
    @Test
    public void bothPathsAnswerThefirstOffsetPastTheEndAlike() throws IOException {
        Path small = tempFolder.newFile("small.txt").toPath();
        Files.writeString(small, "a\nb\nc\n");

        int onSmall = read.execute(new String[] {small.toString(), "--offset", "4"});

        Path large = fileOf(400, true);
        int  onLarge = new ReadCommand().execute(new String[] {large.toString(), "--offset", "401"});

        assertThat(onLarge).as("the same question, the same answer").isEqualTo(onSmall);
    }

    @Test
    public void thelastRealLineIsStillReadable() throws IOException {
        Path file = fileOf(400, true);

        int exitCode = read.execute(new String[] {file.toString(), "--offset", "400"});

        assertThat(exitCode).isZero();
        assertThat(output.getAllOutput()).contains("line 400");
    }
}
