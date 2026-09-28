package com.eonmux.cadetcoder.util;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The one rule that says whether a file holds text.
 *
 * <p>It was written out inside {@code GrepCommand} and nowhere else, so {@code ContextEngine}
 * indexed the compiled output {@code grep} was skipping and served it back as project context. The
 * extension lists are asserted against here so that adding one to the rule is enough.</p>
 */
public class OneTextFileRuleTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Path file(String name, byte[] bytes) throws IOException {
        Path path = folder.getRoot().toPath().resolve(name);
        Files.createDirectories(path.getParent());
        Files.write(path, bytes);
        return path;
    }

    private Path text(String name, String contents) throws IOException {
        return file(name, contents.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void noExtensionIsBothTextAndBinary() {
        assertThat(TextFiles.textExtensions())
                .as("an extension on both lists makes the answer depend on which check runs first")
                .doesNotContainAnyElementsOf(TextFiles.binaryExtensions());
    }

    @Test
    public void theNameSettlesItForEveryListedExtension() throws Exception {
        for (String extension : TextFiles.binaryExtensions()) {
            assertThat(TextFiles.isTextFile(text("plain." + extension, "this reads as text\n")))
                    .as(".%s is compiled or packaged output whatever its first bytes look like",
                        extension)
                    .isFalse();
        }
        for (String extension : TextFiles.textExtensions()) {
            assertThat(TextFiles.isTextFile(file("odd." + extension, new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0})))
                    .as(".%s is source or configuration and is searched even when it decodes badly",
                        extension)
                    .isTrue();
        }
    }

    @Test
    public void theBytesSettleItWhenTheNameSaysNothing() throws Exception {
        assertThat(TextFiles.isTextFile(text("Makefile", "all:\n\t@echo hello\n"))).isTrue();
        assertThat(TextFiles.isTextFile(text("LICENSE", "Permission is hereby granted...\n"))).isTrue();
        assertThat(TextFiles.isTextFile(text("notes.conf", "key = value\nother = thing\n"))).isTrue();

        byte[] executable = new byte[64];
        executable[0] = 0x7F;
        executable[1] = 'E';
        executable[2] = 'L';
        executable[3] = 'F';
        assertThat(TextFiles.isTextFile(file("build/a.out", executable)))
                .as("more than one NUL byte in the sample is not text")
                .isFalse();
    }

    /** Three bytes of byte-order mark must not, on their own, condemn a short file. */
    @Test
    public void aShortFileThatDeclaresItselfUtf8IsText() throws Exception {
        byte[] withMark = new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'h', 'i', '\n',
                                      'y', 'o', 'u', ' ', 't', 'h', 'e', 'r', 'e'};
        assertThat(TextFiles.isTextFile(file("short.conf", withMark))).isTrue();
    }

    /**
     * A read failure is reported, not answered.
     *
     * <p>Callers differ in what they owe the user for a file they could not open -- {@code grep}
     * counts it among the files it could not search, {@code index} among the files it could not
     * read -- and answering "binary" here turned both of those counts into silence.</p>
     */
    @Test
    public void aFileThatIsNotThereIsAFailureNotAVerdict() {
        Path missing = folder.getRoot().toPath().resolve("nowhere/missing.conf");
        assertThatThrownBy(() -> TextFiles.isTextFile(missing))
                .isInstanceOf(IOException.class);
    }

    @Test
    public void extensionsAreReadFromTheNameNotThePath() {
        assertThat(TextFiles.extensionOf("/home/someone/lib.d/native.SO")).isEqualTo("so");
        assertThat(TextFiles.extensionOf("Makefile")).isEmpty();
        assertThat(TextFiles.extensionOf("/home/some.dir/Makefile")).isEmpty();
        assertThat(TextFiles.extensionOf(null)).isEmpty();
    }

    /**
     * How big a file is is not a fact about its encoding.
     *
     * <p>The sniff reads a fixed sample -- one open and one read -- whatever the file's size, so a
     * ceiling above which a file was answered "not text" without being looked at saved nothing and
     * cost {@code grep} every large file whose name does not settle the question: a multi-megabyte
     * log with no extension was skipped without a word.</p>
     */
    @Test
    public void aFileTooBigToReadWholeIsStillJudgedByItsFirstBytes() throws Exception {
        Path big = folder.getRoot().toPath().resolve("huge.out");
        try (RandomAccessFile handle = new RandomAccessFile(big.toFile(), "rw")) {
            handle.write("# a log line that says nothing in particular\n".repeat(200)
                                 .getBytes(StandardCharsets.UTF_8));
            handle.setLength(11L * 1024L * 1024L);
        }

        assertThat(Files.size(big)).isGreaterThan(10L * 1024L * 1024L);
        assertThat(TextFiles.isTextFile(big))
                .as("the first bytes read as text; nothing else about the file was looked at")
                .isTrue();
    }
}
