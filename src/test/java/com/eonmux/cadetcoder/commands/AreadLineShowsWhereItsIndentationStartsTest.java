package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code read} shows where each line's own text starts.
 *
 * <h2>The defect</h2>
 *
 * <p>A line was printed as its number, two spaces, and the line. A line indented by three spaces
 * therefore showed five spaces after its number, and nothing told the separator from the
 * indentation. A model that read a Markdown file wrote {@code OLD:} with four spaces of indentation
 * for lines that have three, and spent five {@code multiedit} calls in a row on the same
 * mismatch. The number is now followed by a bar, and everything after the bar is the line as it
 * is in the file.</p>
 */
class AreadLineShowsWhereItsIndentationStartsTest {

    @TempDir
    Path folder;

    private String originalWorkingDir;

    private TestOutputCapture output;

    @BeforeEach
    void setUp() {
        // The folder is the project, as it is when a user works on their own code.
        originalWorkingDir = System.getProperty("user.dir");
        System.setProperty("user.dir", folder.toAbsolutePath().toString());
        output = new TestOutputCapture();
        output.startCapture();
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.dir", originalWorkingDir);
        output.stopCapture();
    }

    @Test
    void everythingAfterTheBarIsTheLineAsItIs() throws Exception {
        Path file = folder.resolve("guide.md");
        Files.writeString(file, "9. An item\n   of the same one.\n\tand a tab\n");

        assertThat(new ReadCommand().execute(new String[] {file.toString()})).isZero();

        assertThat(output.getAllOutput())
                .contains("     1│9. An item")
                .contains("     2│   of the same one.")
                .contains("     3│\tand a tab");
    }

    @Test
    void alargeFileIsShownTheSameWay() throws Exception {
        Path file = folder.resolve("large.txt");
        Files.writeString(file, "   indented by three\n" + "filler line of text\n".repeat(30_000));

        assertThat(new ReadCommand().execute(new String[] {file.toString(), "-l", "1"})).isZero();

        assertThat(output.getAllOutput()).contains("     1│   indented by three");
    }
}
