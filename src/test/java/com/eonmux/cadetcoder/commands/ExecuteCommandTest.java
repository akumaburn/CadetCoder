package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

public class ExecuteCommandTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private ExecuteCommand    executeCommand;
    private TestOutputCapture outputCapture;

    @Before
    public void setUp() {
        executeCommand = new ExecuteCommand();
        outputCapture  = new TestOutputCapture();
        outputCapture.startCapture();
    }

    @After
    public void tearDown() {
        outputCapture.stopCapture();
    }

    @Test
    public void testExecuteScript_Success() throws Exception {
        // Create test script file
        Path scriptFile = tempFolder.newFile("test_script.txt").toPath();
        String script = "# Test script\n" +
                        "help\n" +
                        "# Another comment\n" +
                        "\n";  // Empty line
        Files.writeString(scriptFile, script);

        // Execute command
        int exitCode = executeCommand.execute(new String[] {scriptFile.toString()});

        // Verify
        assertThat(exitCode).isEqualTo(0);
        String output = outputCapture.getStdout();
        assertThat(output).contains("Script executed successfully");
        assertThat(output).contains("Commands"); // From help command
    }

    @Test
    public void testExecuteScript_FileNotFound() {
        // Execute command with non-existent file
        int exitCode = executeCommand.execute(new String[] {"/non/existent/script.txt"});

        // Verify
        assertThat(exitCode).isEqualTo(1);
        String output = outputCapture.getAllOutput();
        assertThat(output).contains("File not found: /non/existent/script.txt");
        assertThat(output).contains("No similar files found in the project directory.");
    }

    @Test
    public void testExecuteScript_UnknownCommand() throws Exception {
        // Create test script with unknown command
        Path   scriptFile = tempFolder.newFile("bad_script.txt").toPath();
        String script     = "unknowncommand arg1 arg2";
        Files.writeString(scriptFile, script);

        // Execute command
        int exitCode = executeCommand.execute(new String[] {scriptFile.toString()});

        // Verify: an unknown command now propagates a non-zero aggregate exit code (no false success).
        assertThat(exitCode).isEqualTo(1);
        String allOutput = outputCapture.getAllOutput();
        assertThat(allOutput).contains("Unknown command in script: unknowncommand");
        assertThat(allOutput).contains("Script completed with failures");
    }

    @Test
    public void testExecuteScript_FailedCommand() throws Exception {
        // Create test script with a command that will fail
        Path   scriptFile = tempFolder.newFile("failing_script.txt").toPath();
        String script     = "read /non/existent/file.txt";
        Files.writeString(scriptFile, script);

        // Execute command
        int exitCode = executeCommand.execute(new String[] {scriptFile.toString()});

        // Verify: a failing command now propagates a non-zero aggregate exit code (no false success).
        assertThat(exitCode).isNotEqualTo(0);
        String allOutput = outputCapture.getAllOutput();
        assertThat(allOutput).contains("Command 'read' failed with exit code");
        assertThat(allOutput).contains("Script completed with failures");
    }

    @Test
    public void testExecuteScript_EmptyFile() throws Exception {
        // Create empty script file
        Path scriptFile = tempFolder.newFile("empty_script.txt").toPath();
        Files.writeString(scriptFile, "");

        // Execute command
        int exitCode = executeCommand.execute(new String[] {scriptFile.toString()});

        // Verify
        assertThat(exitCode).isEqualTo(0);
        String output = outputCapture.getStdout();
        assertThat(output).contains("Script executed successfully");
    }

    @Test
    public void testExecuteScript_OnlyComments() throws Exception {
        // Create script with only comments and empty lines
        Path scriptFile = tempFolder.newFile("comments_script.txt").toPath();
        String script = "# This is a comment\n" +
                        "# Another comment\n" +
                        "\n" +
                        "   \n" +  // Empty line with spaces
                        "# Final comment";
        Files.writeString(scriptFile, script);

        // Execute command
        int exitCode = executeCommand.execute(new String[] {scriptFile.toString()});

        // Verify
        assertThat(exitCode).isEqualTo(0);
        String output = outputCapture.getStdout();
        assertThat(output).contains("Script executed successfully");
    }

    @Test
    public void testExecuteScript_NoFileArgument() {
        // Execute command without file argument
        int exitCode = executeCommand.execute(new String[] {});

        // Verify
        assertThat(exitCode).isEqualTo(1);
        String output = outputCapture.getAllOutput();
        assertThat(output).contains("Script file path is required"); // Error message from ExecuteCommand
    }

    @Test
    public void testGetDescription() {
        ExecuteCommand executeCommand = new ExecuteCommand();
        assertThat(executeCommand.getDescription())
                .isEqualTo("Run a file of commands, one per line");
    }

    @Test
    public void testGetUsage() {
        ExecuteCommand executeCommand = new ExecuteCommand();
        assertThat(executeCommand.getUsage())
                .isEqualTo("execute <script_file>");
    }
}