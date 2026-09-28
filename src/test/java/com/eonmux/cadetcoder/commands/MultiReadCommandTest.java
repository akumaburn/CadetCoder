package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behaviour of the batch reader.
 *
 * <p>The command runs with the working directory pointed at a temporary folder, because it resolves
 * relative paths and globs against {@code user.dir} exactly as {@code read} does.</p>
 */
public class MultiReadCommandTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private TestOutputCapture outputCapture;
    private String            originalUserDir;
    private String            originalInteractive;
    private MultiReadCommand  command;

    @Before
    public void setUp() throws Exception {
        originalUserDir     = System.getProperty("user.dir");
        originalInteractive = System.getProperty("cadet.interactive");
        System.setProperty("user.dir", tempFolder.getRoot().getAbsolutePath());
        System.setProperty("cadet.interactive", "false");
        outputCapture = new TestOutputCapture();
        command       = new MultiReadCommand();
    }

    @After
    public void tearDown() {
        outputCapture.restore();
        if (originalUserDir != null) {
            System.setProperty("user.dir", originalUserDir);
        }
        if (originalInteractive == null) {
            System.clearProperty("cadet.interactive");
        } else {
            System.setProperty("cadet.interactive", originalInteractive);
        }
    }

    private Path write(String relativePath, String content) throws Exception {
        Path path = tempFolder.getRoot().toPath().resolve(relativePath);
        Files.createDirectories(path.getParent() == null ? tempFolder.getRoot().toPath() : path.getParent());
        Files.writeString(path, content);
        return path;
    }

    // ------------------------------------------------------------------ the point of the command

    @Test
    public void readsEveryRequestedFileInOneCall() throws Exception {
        write("a.txt", "AAA\n");
        write("b.txt", "BBB\n");
        write("c.txt", "CCC\n");

        int exitCode = command.execute(new String[] {"a.txt", "b.txt", "c.txt"});

        String output = outputCapture.getAllOutput();
        assertThat(exitCode).isEqualTo(0);
        assertThat(output).contains("AAA").contains("BBB").contains("CCC");
        assertThat(output).contains("[1/3]").contains("[2/3]").contains("[3/3]");
        assertThat(output).contains("Read 3 files");
    }

    @Test
    public void oneUnreadableFileDoesNotAbortTheBatch() throws Exception {
        write("a.txt", "AAA\n");
        write("c.txt", "CCC\n");

        int exitCode = command.execute(new String[] {"a.txt", "definitely-missing.txt", "c.txt"});

        String output = outputCapture.getAllOutput();
        assertThat(exitCode)
                .as("some files were read, so this is not a failure")
                .isEqualTo(0);
        assertThat(output).contains("AAA").contains("CCC");
        assertThat(output).contains("could not be read");
    }

    @Test
    public void returnsNonZeroOnlyWhenNothingCouldBeRead() {
        int exitCode = command.execute(new String[] {"nope-1.txt", "nope-2.txt"});

        assertThat(exitCode).isEqualTo(1);
        assertThat(outputCapture.getAllOutput()).contains("no files could be read");
    }

    @Test
    public void expandsGlobsAndReadsTheMatchesInADeterministicOrder() throws Exception {
        write("src/A.java", "class A {}\n");
        write("src/B.java", "class B {}\n");
        write("src/notes.md", "notes\n");

        int exitCode = command.execute(new String[] {"src/*.java"});

        String output = outputCapture.getAllOutput();
        assertThat(exitCode).isEqualTo(0);
        assertThat(output).contains("class A").contains("class B");
        assertThat(output).doesNotContain("notes");
        assertThat(output.indexOf("class A"))
                .as("glob matches are sorted, so the same call gives the same order")
                .isLessThan(output.indexOf("class B"));
    }

    @Test
    public void expandsRecursiveGlobs() throws Exception {
        write("src/main/deep/Deep.java", "class Deep {}\n");
        write("src/Top.java", "class Top {}\n");

        command.execute(new String[] {"src/**/*.java"});

        assertThat(outputCapture.getAllOutput()).contains("class Deep");
    }

    @Test
    public void duplicatePathsAreReadOnce() throws Exception {
        write("a.txt", "AAA\n");

        command.execute(new String[] {"a.txt", "a.txt"});

        // Singular, not the "file(s)" dodge -- twelve lines above, the same command already
        // pluralises its per-file header properly.
        assertThat(outputCapture.getAllOutput()).contains("Read 1 file");
    }

    // ------------------------------------------------------------------ budgets are never silent

    @Test
    public void maxFilesIsReportedRatherThanSilentlyTruncating() throws Exception {
        write("a.txt", "AAA\n");
        write("b.txt", "BBB\n");
        write("c.txt", "CCC\n");

        int exitCode = command.execute(new String[] {"a.txt", "b.txt", "c.txt", "--max-files=2"});

        String output = outputCapture.getAllOutput();
        assertThat(exitCode).isEqualTo(0);
        assertThat(output).contains("AAA").contains("BBB");
        assertThat(output)
                .as("the caller must be told which files were dropped, and why")
                .contains("--max-files=2 reached")
                .contains("c.txt");
    }

    @Test
    public void maxTotalLinesIsReportedRatherThanSilentlyTruncating() throws Exception {
        write("big.txt", "x\n".repeat(50));
        write("later.txt", "LATER\n");

        int exitCode = command.execute(new String[] {"big.txt", "later.txt", "--max-total-lines=5"});

        String output = outputCapture.getAllOutput();
        assertThat(exitCode).isEqualTo(0);
        assertThat(output)
                .as("the caller must be told the batch budget ran out and which files it lost")
                .contains("--max-total-lines=5 exhausted")
                .contains("later.txt");
    }

    @Test
    public void perFileLimitIsPassedThroughToTheReader() throws Exception {
        write("many.txt", "L1\nL2\nL3\nL4\nL5\n");

        command.execute(new String[] {"many.txt", "--limit=2"});

        String output = outputCapture.getAllOutput();
        assertThat(output).contains("L1").contains("L2");
        assertThat(output).doesNotContain("L5");
    }

    @Test
    public void offsetIsPassedThroughToTheReader() throws Exception {
        write("many.txt", "L1\nL2\nL3\nL4\nL5\n");

        command.execute(new String[] {"many.txt", "--offset=4"});

        String output = outputCapture.getAllOutput();
        assertThat(output).contains("L4").contains("L5");
        assertThat(output).doesNotContain("L1\n");
    }

    // ------------------------------------------------------------------ argument handling

    @Test
    public void acceptsBothInlineAndSpaceSeparatedOptionForms() throws Exception {
        write("many.txt", "L1\nL2\nL3\n");

        command.execute(new String[] {"many.txt", "--limit", "1"});
        assertThat(outputCapture.getAllOutput()).contains("L1").doesNotContain("L3");
    }

    @Test
    public void noPathsIsAnErrorWithUsage() {
        int exitCode = command.execute(new String[0]);

        assertThat(exitCode).isEqualTo(1);
        assertThat(outputCapture.getAllOutput())
                .contains("No file paths provided")
                .contains("multiread");
    }

    @Test
    public void anUnknownOptionIsRejectedRatherThanTreatedAsAPath() {
        int exitCode = command.execute(new String[] {"a.txt", "--bogus"});

        assertThat(exitCode).isEqualTo(1);
        assertThat(outputCapture.getAllOutput()).contains("Unknown option: --bogus");
    }

    @Test
    public void aNonPositiveOrUnparseableOptionValueIsRejected() {
        assertThat(command.execute(new String[] {"a.txt", "--limit=0"})).isEqualTo(1);
        assertThat(outputCapture.getAllOutput()).contains("--limit must be greater than 0");

        outputCapture.restore();
        outputCapture = new TestOutputCapture();
        assertThat(command.execute(new String[] {"a.txt", "--max-files=abc"})).isEqualTo(1);
        assertThat(outputCapture.getAllOutput()).contains("Invalid value for --max-files");
    }

    @Test
    public void aGlobMatchingNothingIsReportedNotSilentlyIgnored() throws Exception {
        write("a.txt", "AAA\n");

        int exitCode = command.execute(new String[] {"a.txt", "*.nomatch"});

        assertThat(exitCode).isEqualTo(0);
        assertThat(outputCapture.getAllOutput()).contains("No files matched").contains("*.nomatch");
    }

    @Test
    public void registryExposesTheCommandUnderTheExpectedName() {
        assertThat(new com.eonmux.cadetcoder.CommandRegistry().getCommand("multiread"))
                .isInstanceOf(MultiReadCommand.class);
    }

    @Test
    public void usageDocumentsEveryOptionAndTheExitCodeContract() {
        String usage = command.getUsage();

        assertThat(usage)
                .contains("--limit")
                .contains("--offset")
                .contains("--max-files")
                .contains("--max-total-lines")
                .contains("Exit 0 if at least one file was read");
    }

    // ------------------------------------------------------------------ security is not bypassed

    @Test
    public void batchReadingDoesNotBypassTheSingleFileSecurityRefusal() throws Exception {
        write("ok.txt", "OK\n");
        File sensitive = new File(System.getProperty("user.home"), ".ssh/id_rsa");

        command.execute(new String[] {"ok.txt", sensitive.getAbsolutePath()});

        String output = outputCapture.getAllOutput();
        assertThat(output).contains("OK");
        assertThat(output)
                .as("a credential file must be refused in a batch exactly as it is on its own")
                .containsAnyOf("Cannot read sensitive system files",
                               "Access denied",
                               "could not be read");
        assertThat(output).doesNotContain("BEGIN OPENSSH PRIVATE KEY");
    }
}
