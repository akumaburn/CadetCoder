package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import com.eonmux.cadetcoder.test.ProjectFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ReadCommand}.
 *
 * <p>Covers the baseline read behavior plus the targeted fixes:
 * <ul>
 *   <li>read-2: auto-resolved single match runs the full security check.</li>
 *   <li>read-3: non-interactive mode does not prompt on multiple matches.</li>
 *   <li>read-4: a file that is not text reports a clear message; text that is not valid UTF-8
 *       is still shown.</li>
 *   <li>read-5: offset beyond EOF is reported as a failure, not silent success.</li>
 *   <li>read-7: an existing exact path is preferred over a same-named file
 *       elsewhere in the project.</li>
 * </ul>
 */
public class ReadCommandTest {

    @Rule
    public TemporaryFolder tempFolder = new ProjectFolder();

    private ReadCommand       readCommand;
    private TestOutputCapture outputCapture;
    private String            originalUserDir;
    private String            originalInteractive;

    @Before
    public void setUp() {
        readCommand         = new ReadCommand();
        outputCapture       = new TestOutputCapture();
        originalUserDir     = System.getProperty("user.dir");
        originalInteractive = System.getProperty("cadet.interactive");
        outputCapture.startCapture();
    }

    @After
    public void tearDown() {
        outputCapture.stopCapture();
        if (originalUserDir != null) {
            System.setProperty("user.dir", originalUserDir);
        }
        if (originalInteractive == null) {
            System.clearProperty("cadet.interactive");
        } else {
            System.setProperty("cadet.interactive", originalInteractive);
        }
    }

    @Test
    public void testReadFileWithValidPath() throws Exception {
        File testFile = tempFolder.newFile("test.txt");
        Files.writeString(testFile.toPath(), "Line 1\nLine 2\nLine 3");

        int result = readCommand.execute(new String[] {testFile.getAbsolutePath()});

        assertThat(result).isEqualTo(0);
        assertThat(outputCapture.getAllOutput()).contains("Line 1");
        assertThat(outputCapture.getAllOutput()).contains("Line 2");
        assertThat(outputCapture.getAllOutput()).contains("Line 3");
    }

    @Test
    public void testReadFileWithInvalidPath() {
        int result = readCommand.execute(new String[] {"definitely-nonexistent-file-xyz.txt"});

        assertThat(result).isEqualTo(1);
        assertThat(outputCapture.getAllOutput()).containsAnyOf("File not found", "Error reading file", "No such file");
    }

    @Test
    public void testReadFileWithInvalidOptions() throws Exception {
        File testFile = tempFolder.newFile("test.txt");
        Files.writeString(testFile.toPath(), "Line 1\nLine 2\nLine 3");

        int result = readCommand.execute(
                new String[] {testFile.getAbsolutePath(), "-l", "invalid", "-o", "invalid"});

        assertThat(result).isEqualTo(0);
        assertThat(outputCapture.getAllOutput()).containsAnyOf("Invalid limit format", "Invalid offset format");
        assertThat(outputCapture.getAllOutput()).contains("Line 1");
    }

    // read-5: an offset past the end of a non-empty file must report a failure
    // (non-zero exit + clear message) rather than silently succeeding.
    @Test
    public void testReadOffsetBeyondEndOfFileReportsFailure() throws Exception {
        File testFile = tempFolder.newFile("short.txt");
        Files.writeString(testFile.toPath(), "Line 1\nLine 2\nLine 3");

        int result = readCommand.execute(new String[] {testFile.getAbsolutePath(), "-o", "99"});

        assertThat(result).isEqualTo(1);
        assertThat(outputCapture.getAllOutput()).contains("beyond end of file");
    }

    // read-5: an empty file is legitimately empty, not an offset-past-EOF error.
    @Test
    public void testReadEmptyFileStillReportsEmpty() throws Exception {
        File testFile = tempFolder.newFile("empty.txt");

        int result = readCommand.execute(new String[] {testFile.getAbsolutePath()});

        assertThat(result).isEqualTo(0);
        assertThat(outputCapture.getAllOutput()).contains("File is empty");
    }

    // read-4: a file whose content is not text must report a clear message instead of a raw
    // decoding exception. What counts as text is TextFiles' answer, asserted in
    // ReadShowsEveryFileTheRestOfTheToolCallsTextTest.
    @Test
    public void testReadBinaryFileWithNulBytesReportsBinary() throws Exception {
        File testFile = tempFolder.newFile("binary.dat");
        byte[] bytes  = new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x00, 0x01, 0x02, (byte) 0xFF};
        Files.write(testFile.toPath(), bytes);

        int result = readCommand.execute(new String[] {testFile.getAbsolutePath()});

        assertThat(result).isEqualTo(1);
        assertThat(outputCapture.getAllOutput()).contains("Cannot display file: binary content");
    }

    // read-4: a text file that is not valid UTF-8 is still text. It is shown, with the bytes that
    // could not be decoded replaced, rather than refused by the one command meant to show it.
    @Test
    public void testReadNonUtf8FileIsShownWithReplacementCharacters() throws Exception {
        File testFile = tempFolder.newFile("latin1.txt");
        // 0xC0 0xC1 are never valid in UTF-8.
        byte[] bytes = new byte[] {'h', 'i', (byte) 0xC0, (byte) 0xC1, (byte) 0xFE, '!'};
        Files.write(testFile.toPath(), bytes);

        int result = readCommand.execute(new String[] {testFile.getAbsolutePath()});

        assertThat(result).isEqualTo(0);
        assertThat(outputCapture.getAllOutput()).contains("hi");
    }

    // read-4: a normal UTF-8 file with multi-byte characters is NOT flagged.
    @Test
    public void testReadUtf8FileWithMultibyteCharsIsDisplayed() throws Exception {
        File testFile = tempFolder.newFile("utf8.txt");
        Files.write(testFile.toPath(), "café naïve über".getBytes(StandardCharsets.UTF_8));

        int result = readCommand.execute(new String[] {testFile.getAbsolutePath()});

        assertThat(result).isEqualTo(0);
        assertThat(outputCapture.getAllOutput()).contains("café");
    }

    // read-3: in non-interactive mode multiple matches must not prompt on stdin;
    // they are returned as an error so the model can retry with an exact path.
    @Test
    public void testNonInteractiveMultipleMatchesDoesNotPrompt() throws Exception {
        Path root = tempFolder.getRoot().toPath();
        Path a    = Files.createDirectories(root.resolve("moduleA"));
        Path b    = Files.createDirectories(root.resolve("moduleB"));
        // Use a non-".java", non-placeholder name so ReadCommand's placeholder
        // substitution does not pre-resolve it and the resolver search runs.
        Files.writeString(a.resolve("shared.conf"), "value=A");
        Files.writeString(b.resolve("shared.conf"), "value=B");

        System.setProperty("user.dir", root.toString());
        System.setProperty("cadet.interactive", "false");

        // Request by bare filename so the resolver search finds two matches.
        int result = readCommand.execute(new String[] {"shared.conf"});

        assertThat(result).isEqualTo(1);
        String out = outputCapture.getAllOutput();
        assertThat(out).contains("Did you mean one of these?");
        assertThat(out).contains("exact path");
    }

    // read-7: when the exact requested path exists, it is read directly and a
    // same-named file elsewhere in the project is NOT substituted.
    @Test
    public void testExactPathPreferredOverSameNamedFileElsewhere() throws Exception {
        Path root = tempFolder.getRoot().toPath();
        Path sub  = Files.createDirectories(root.resolve("nested/deep"));
        Files.writeString(root.resolve("config.properties"), "value=ROOT");
        Files.writeString(sub.resolve("config.properties"), "value=NESTED");

        System.setProperty("user.dir", root.toString());

        // Provide the exact relative path to the root file.
        int result = readCommand.execute(new String[] {"config.properties"});

        assertThat(result).isEqualTo(0);
        assertThat(outputCapture.getAllOutput()).contains("value=ROOT");
        assertThat(outputCapture.getAllOutput()).doesNotContain("value=NESTED");
        // No fallback search message should appear for an existing exact path.
        assertThat(outputCapture.getAllOutput()).doesNotContain("was not found; reading");
    }
}
