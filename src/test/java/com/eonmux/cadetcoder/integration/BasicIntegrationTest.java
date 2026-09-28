package com.eonmux.cadetcoder.integration;

import com.eonmux.cadetcoder.Main;
import com.eonmux.cadetcoder.test.AiTestSupport;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Basic integration tests for CadetCoder
 */
public class BasicIntegrationTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private TestOutputCapture outputCapture;
    private String            originalDir;

    @Before
    public void setUp() {
        // Keep the chat fallback (testInvalidCommand routes an unknown command to ChatCommand) offline,
        // so the test cannot block on a live AI call when a real model server is reachable locally.
        AiTestSupport.installOfflineStub();
        outputCapture = new TestOutputCapture();
        originalDir   = System.getProperty("user.dir");
        // Change to temp directory for tests
        System.setProperty("user.dir", tempFolder.getRoot().getAbsolutePath());
    }

    @After
    public void tearDown() {
        outputCapture.restore();
        System.setProperty("user.dir", originalDir);
        AiTestSupport.reset();
    }

    @Test
    public void testMainHelp() {
        int exitCode = Main.execute(new String[] {"--help"});

        assertThat(exitCode).isEqualTo(0);
        String output = outputCapture.getOutput();
        // The program is named for the word the user types, not for the artifact: --help used to
        // open "Usage: CadetCoder" and send the reader to "CadetCoder help", neither of which is a
        // command that exists.
        assertThat(output).contains("Usage: cadet");
        assertThat(output).contains("AI-assisted coding tool");
        // "Commands:" was picocli's own subcommand section, and it listed 22 of 42. Help now points
        // at the one list that is complete rather than printing a partial one.
        assertThat(output).contains("cadet help");
        assertThat(output).doesNotContain("CadetCoder help");
        assertThat(output).contains("sent to the AI");
    }

    @Test
    public void testMainVersion() {
        int exitCode = Main.execute(new String[] {"--version"});

        assertThat(exitCode).isEqualTo(0);
        String output = outputCapture.getOutput();
        assertThat(output).contains("CadetCoder 1.0");
    }

    @Test
    public void testReadCommand() throws Exception {
        // Create test file
        File testFile = tempFolder.newFile("test.txt");
        Files.write(testFile.toPath(), "Hello, World!".getBytes());

        int exitCode = Main.execute(new String[] {"read", testFile.getAbsolutePath()});

        assertThat(exitCode).isEqualTo(0);
        String output = outputCapture.getOutput();
        assertThat(output).contains("Hello, World!");
    }

    @Test
    public void testWriteCommand() throws Exception {
        Path testFile = tempFolder.getRoot().toPath().resolve("output.txt");

        int exitCode = Main.execute(new String[] {"write", testFile.toString(), "Test content"});

        assertThat(exitCode).isEqualTo(0);
        // Check file was created
        assertThat(Files.exists(testFile)).isTrue();
        assertThat(Files.readString(testFile)).isEqualTo("Test content");
    }

    @Test
    public void testHelpCommand() {
        int exitCode = Main.execute(new String[] {"help"});

        assertThat(exitCode).isEqualTo(0);
        String output = outputCapture.getOutput();
        // This assertion used to be `contains("Commands:")`, which only held because of a bug:
        // Main single-type-imported picocli.CommandLine.HelpCommand, and that import outranked the
        // com.eonmux.cadetcoder.commands.* wildcard, so `help` ran picocli's built-in help and
        // reprinted Main's own usage block. `help` now runs this project's registry-backed
        // HelpCommand, which lists every registered command -- including ones that were never
        // picocli subcommands at all.
        assertThat(output).contains("Commands");
        assertThat(output).contains("List the commands, or show how one is used");
        assertThat(output).contains("login");
    }

    @Test
    public void testInvalidCommand() {
        int exitCode = Main.execute(new String[] {"nonexistentcommand"});

        // A bare first word that is not a registered command is a natural-language request and must
        // still route to chat (which may then fail for lack of an AI provider, so the exit code is
        // not asserted). This previously asserted the "Executing quick command mode with provided
        // arguments." banner, which was printed unconditionally before every quick command and has
        // been removed as noise; the assertion now states the actual contract instead.
        String output = outputCapture.getOutput() + outputCapture.getStderr();
        assertThat(output)
                .as("bare input must fall back to chat, not be rejected as an unknown command")
                .doesNotContain("Unknown command");
    }

    @Test
    public void testExplicitSlashCommandIsNotForwardedToChat() {
        int exitCode = Main.execute(new String[] {"/nonexistentcommand"});

        // The mirror image: an explicit "/name" is a command invocation. Forwarding a mistyped one to
        // the model costs a request and never tells the user the name was wrong.
        String output = outputCapture.getOutput() + outputCapture.getStderr();
        assertThat(exitCode).isEqualTo(1);
        assertThat(output).contains("Unknown command");
    }

    @Test
    public void testSlashPrefixedCommandRuns() {
        int exitCode = Main.execute(new String[] {"/help"});

        assertThat(exitCode).isEqualTo(0);
        assertThat(outputCapture.getOutput()).contains("Commands");
    }

    @Test
    public void testNoArguments() {
        int exitCode = Main.execute(new String[0]);

        assertThat(exitCode).isEqualTo(0);
        String output = outputCapture.getOutput();
        // Should show help when no arguments
        assertThat(output).contains("Usage:");
    }
}