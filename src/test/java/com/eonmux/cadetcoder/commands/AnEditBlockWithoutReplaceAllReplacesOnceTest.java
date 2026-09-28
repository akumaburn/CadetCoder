package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.test.TestOutputCapture;
import com.eonmux.cadetcoder.ui.InteractivePrompts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An edit block that leaves out {@code REPLACE_ALL:} replaces the first match, as
 * {@code REPLACE_ALL: false} does.
 *
 * <h2>The defect</h2>
 *
 * <p>The line was required, and a model that left it out was told the whole block could not be
 * read. {@code false} is the only reading a missing line can have, so it is taken as that.</p>
 */
class AnEditBlockWithoutReplaceAllReplacesOnceTest {

    @TempDir
    Path folder;

    private String originalWorkingDir;

    private TestOutputCapture output;

    @BeforeEach
    void setUp() {
        // The folder is the project, as it is when a user works on their own code.
        originalWorkingDir = System.getProperty("user.dir");
        System.setProperty("user.dir", folder.toAbsolutePath().toString());
        System.setProperty(InteractivePrompts.PROPERTY, "false");
        output = new TestOutputCapture();
        output.startCapture();
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.dir", originalWorkingDir);
        output.stopCapture();
        System.clearProperty(InteractivePrompts.PROPERTY);
    }

    @Test
    void theFirstMatchIsReplaced() throws Exception {
        Path file = folder.resolve("values.txt");
        Files.writeString(file, "value = 30\nvalue = 30\n");

        int exit = new MultiEditCommand().execute(new String[] {
                file.toString(), "EDIT_START\nOLD: value = 30\nNEW: value = 45\nEDIT_END"});

        assertThat(exit).isZero();
        assertThat(Files.readString(file)).isEqualTo("value = 45\nvalue = 30\n");
    }

    @Test
    void blocksWithAndWithoutItCanBeMixed() throws Exception {
        Path file = folder.resolve("values.txt");
        Files.writeString(file, "a = 1\nb = 2\nb = 2\n");

        int exit = new MultiEditCommand().execute(new String[] {
                file.toString(),
                "EDIT_START\nOLD: a = 1\nNEW: a = 10\nEDIT_END\n"
                + "EDIT_START\nOLD: b = 2\nNEW: b = 20\nREPLACE_ALL: true\nEDIT_END"});

        assertThat(exit).isZero();
        assertThat(Files.readString(file)).isEqualTo("a = 10\nb = 20\nb = 20\n");
    }
}
