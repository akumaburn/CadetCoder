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
 * {@code \|} in a {@code grep} pattern means "or", as it does in GNU grep.
 *
 * <h2>The defect</h2>
 *
 * <p>Java's expressions read {@code \|} as a literal bar. A model searched for
 * {@code tv.args\|tv.heap} more than ten times in one run, in the form GNU grep takes, and was
 * told each time that nothing matched while three files held {@code tv.args}. A false "no
 * matches" is worse than an error, because it is acted on. A literal bar is written {@code [|]}.</p>
 */
class AbackslashBarIsAlternationAsInGrepTest {

    @TempDir
    Path folder;

    private String originalWorkingDir;

    private TestOutputCapture output;

    @BeforeEach
    void setUp() throws Exception {
        // The folder is the project, as it is when a user works on their own code.
        originalWorkingDir = System.getProperty("user.dir");
        System.setProperty("user.dir", folder.toAbsolutePath().toString());
        Files.writeString(folder.resolve("build.gradle.kts"),
                          "val args = project.findProperty(\"tv.args\")\nval heap = \"tv.heap\"\nx | y\n");
        output = new TestOutputCapture();
        output.startCapture();
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.dir", originalWorkingDir);
        output.stopCapture();
    }

    private String grep(String pattern) {
        new GrepCommand().execute(new String[] {pattern, "--path=" + folder});
        return output.getAllOutput();
    }

    @Test
    void eitherSideOfTheBarIsFound() {
        assertThat(grep("tv.args\\|tv.heap"))
                .contains("val args")
                .contains("val heap")
                .contains("Found 2 matches");
    }

    @Test
    void aplainBarStillMeansOr() {
        assertThat(grep("tv.args|tv.heap")).contains("val heap");
    }

    @Test
    void abarInBracketsIsAliteralBar() {
        assertThat(grep("x [|] y")).contains("x | y");
    }

    @Test
    void anEscapedBackslashBeforeAbarIsLeftAlone() {
        // "\\|" is a literal backslash followed by "or": the bar is not the one escaped.
        assertThat(grep("nothing\\\\|val heap")).contains("val heap");
    }
}
