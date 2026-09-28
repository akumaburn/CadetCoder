package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.test.TestOutputCapture;
import com.eonmux.cadetcoder.util.TextFiles;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import com.eonmux.cadetcoder.test.ProjectFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether {@code read} can show a file is decided by the same rule as everywhere else.
 *
 * <h2>The defect</h2>
 *
 * <p>{@code read} kept its own text test -- a NUL byte in the first 8KB, or a strict UTF-8 decode
 * failure -- while {@code grep}, {@code index} and the files a prompt names all asked
 * {@link TextFiles}. The two disagreed on real files. A source file saved in Latin-1 was searched by
 * {@code grep} and indexed as project context, and {@code read} answered "Cannot display file:
 * binary or non-UTF-8 content" -- so the one command whose whole job is to show a file was the only
 * one that could not show it.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>A file the rest of the tool treats as text is displayed, with the bytes that are not valid
 * UTF-8 shown as U+FFFD exactly as {@code grep} shows them; a file it treats as binary is still
 * refused with a clear message. Both the whole-file path and the streaming path used for large
 * files answer the same way, because how big a file is is not a fact about its encoding.</p>
 */
public class ReadShowsEveryFileTheRestOfTheToolCallsTextTest {

    /** Past this many bytes {@code read} streams the file instead of holding it whole. */
    private static final int STREAMS_BEYOND_BYTES = 500 * 1024;

    /** What a byte that is not valid UTF-8 is shown as. */
    private static final String REPLACEMENT = "�";

    @Rule
    public TemporaryFolder folder = new ProjectFolder();

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

    private Path file(String name, byte[] bytes) throws IOException {
        Path path = folder.getRoot().toPath().resolve(name);
        Files.createDirectories(path.getParent());
        Files.write(path, bytes);
        return path;
    }

    /** Two pieces of ASCII with one byte between them that is Latin-1 rather than UTF-8. */
    private static byte[] latin1Line(String before, String after) {
        byte[] head = before.getBytes(StandardCharsets.US_ASCII);
        byte[] tail = after.getBytes(StandardCharsets.US_ASCII);
        byte[] line = new byte[head.length + 1 + tail.length];
        System.arraycopy(head, 0, line, 0, head.length);
        line[head.length] = (byte) 0xE9;
        System.arraycopy(tail, 0, line, head.length + 1, tail.length);
        return line;
    }

    private static byte[] joined(byte[] first, byte[] second) {
        byte[] both = new byte[first.length + second.length];
        System.arraycopy(first, 0, both, 0, first.length);
        System.arraycopy(second, 0, both, first.length, second.length);
        return both;
    }

    private int display(Path path) {
        return read.execute(new String[] {path.toString()});
    }

    @Test
    public void aSourceFileSavedInLatin1IsShownRatherThanRefused() throws Exception {
        Path source = file("Caf.java", latin1Line("class Caf { // caf", " au lait\n}\n"));

        assertThat(display(source)).isZero();
        assertThat(output.getAllOutput()).contains("class Caf");
    }

    @Test
    public void theBytesThatCouldNotBeDecodedAreShownAsReplacementCharacters() throws Exception {
        Path source = file("Caf.java", latin1Line("class Caf { // caf", " au lait\n}\n"));

        assertThat(display(source)).isZero();
        assertThat(output.getAllOutput()).contains(REPLACEMENT);
    }

    @Test
    public void aLargeFileSavedInLatin1IsShownTheSameWayAsASmallOne() throws Exception {
        StringBuilder padding = new StringBuilder();
        while (padding.length() <= STREAMS_BEYOND_BYTES) {
            padding.append("// padding so this file is read by streaming rather than whole\n");
        }
        Path source = file("BigCaf.java",
                           joined(latin1Line("class Caf { // caf", " au lait\n"),
                                  padding.toString().getBytes(StandardCharsets.US_ASCII)));

        assertThat(display(source)).isZero();
        assertThat(output.getAllOutput()).contains("class Caf");
        assertThat(output.getAllOutput()).contains(REPLACEMENT);
    }

    @Test
    public void compiledOutputIsStillRefusedWithAClearMessage() throws Exception {
        byte[] compiled = new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE,
                                      0, 0, 0, 61, 0, 12, 10, 0, 2, 0, 3};

        assertThat(display(file("Caf.class", compiled))).isEqualTo(1);
        assertThat(output.getAllOutput()).contains("Cannot display file: binary content");
    }

    @Test
    public void aFileWhoseNameSaysNothingIsStillRefusedWhenItsBytesAreBinary() throws Exception {
        byte[] executable = new byte[64];
        executable[0] = 0x7F;
        executable[1] = 'E';
        executable[2] = 'L';
        executable[3] = 'F';

        assertThat(display(file("build/a.out", executable))).isEqualTo(1);
        assertThat(output.getAllOutput()).contains("Cannot display file: binary content");
    }

    /**
     * The two answers are one answer.
     *
     * <p>Asserted over the same files rather than over two lists of rules, so a change to what
     * counts as text cannot move one of them without the other.</p>
     */
    @Test
    public void whatReadShowsIsWhatTheSharedRuleCallsText() throws Exception {
        Path[] candidates = {
                file("plain.txt", "just text\n".getBytes(StandardCharsets.UTF_8)),
                file("Mixed.java", latin1Line("class Mixed { // caf", "\n}\n")),
                file("Makefile", "all:\n  @echo hello\n".getBytes(StandardCharsets.UTF_8)),
                file("archive.zip", "PK not really".getBytes(StandardCharsets.US_ASCII)),
                file("Empty.class", new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE}),
                file("notes.conf", "key = value\nother = thing\n".getBytes(StandardCharsets.UTF_8))
        };

        for (Path candidate : candidates) {
            boolean text  = TextFiles.isTextFile(candidate);
            boolean shown = display(candidate) == 0;
            assertThat(shown)
                    .as("%s: read %s it, the shared rule calls it %s", candidate.getFileName(),
                        shown ? "showed" : "refused", text ? "text" : "binary")
                    .isEqualTo(text);
        }
    }
}
