package com.eonmux.cadetcoder.shell;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedConstruction;

import java.io.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test class for ScriptExecutor
 */
public class ScriptExecutorTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private TestOutputCapture outputCapture;
    private File              scriptFile;

    @Before
    public void setUp() throws IOException {
        outputCapture = new TestOutputCapture();
        scriptFile    = tempFolder.newFile("test-script.cadet");
    }

    @After
    public void tearDown() {
        outputCapture.restore();
    }

    @Test
    public void testExecuteScript_EmptyFile() throws IOException {
        // Create empty script file
        try (FileWriter writer = new FileWriter(scriptFile)) {
            writer.write("");
        }

        ScriptExecutor executor = new ScriptExecutor(scriptFile.getPath());

        // Should execute without error
        assertThatCode(() -> executor.executeScript()).doesNotThrowAnyException();
    }

    @Test
    public void testExecuteScript_OnlyCommentsAndEmptyLines() throws IOException {
        try (FileWriter writer = new FileWriter(scriptFile)) {
            writer.write("# This is a comment\n");
            writer.write("\n");
            writer.write("  \n");
            writer.write("# Another comment\n");
            writer.write("\t\n");
        }

        ScriptExecutor executor = new ScriptExecutor(scriptFile.getPath());
        executor.executeScript();

        // Should not produce any output for comments and empty lines (except possibly logging)
        String output = outputCapture.getAllOutput();
        // Allow for reflections logging or other framework output
        if (!output.isEmpty()) {
            assertThat(output).doesNotContain("Command")
                              .doesNotContain("failed")
                              .doesNotContain("Unknown");
        }
    }

    @Test
    public void testExecuteScript_ValidCommands() throws IOException {
        try (FileWriter writer = new FileWriter(scriptFile)) {
            writer.write("help\n");
            writer.write("read file.txt\n");
            writer.write("# Comment in between\n");
            writer.write("context add Main.java\n");
        }

        try (MockedConstruction<CommandRegistry> mockRegistry = mockConstruction(CommandRegistry.class,
                (mock, context) -> {
                    CommandRegistry.Command helpCommand = mock(CommandRegistry.Command.class);
                    when(helpCommand.execute(new String[] {})).thenReturn(0);
                    when(mock.getCommand("help")).thenReturn(helpCommand);

                    CommandRegistry.Command readCommand = mock(CommandRegistry.Command.class);
                    when(readCommand.execute(new String[] {"file.txt"})).thenReturn(0);
                    when(mock.getCommand("read")).thenReturn(readCommand);

                    CommandRegistry.Command contextCommand = mock(CommandRegistry.Command.class);
                    when(contextCommand.execute(new String[] {"add", "Main.java"})).thenReturn(0);
                    when(mock.getCommand("context")).thenReturn(contextCommand);
                })) {

            ScriptExecutor executor = new ScriptExecutor(scriptFile.getPath());
            executor.executeScript();

            // Verify commands were called
            CommandRegistry registry = mockRegistry.constructed().get(0);
            verify(registry).getCommand("help");
            verify(registry).getCommand("read");
            verify(registry).getCommand("context");
        }
    }

    @Test
    public void testExecuteScript_CommandFailure() throws IOException {
        try (FileWriter writer = new FileWriter(scriptFile)) {
            writer.write("failing-command arg1 arg2\n");
        }

        try (MockedConstruction<CommandRegistry> mockRegistry = mockConstruction(CommandRegistry.class,
                (mock, context) -> {
                    CommandRegistry.Command failingCommand = mock(CommandRegistry.Command.class);
                    when(failingCommand.execute(new String[] {"arg1", "arg2"})).thenReturn(1);
                    when(mock.getCommand("failing-command")).thenReturn(failingCommand);
                })) {

            ScriptExecutor executor = new ScriptExecutor(scriptFile.getPath());
            executor.executeScript();

            String output = outputCapture.getAllOutput();
            assertThat(output).contains("Command 'failing-command' failed with exit code 1");
        }
    }

    @Test
    public void testExecuteScript_UnknownCommand() throws IOException {
        try (FileWriter writer = new FileWriter(scriptFile)) {
            writer.write("unknown-command arg1\n");
        }

        try (MockedConstruction<CommandRegistry> mockRegistry = mockConstruction(CommandRegistry.class,
                (mock, context) -> {
                    when(mock.getCommand("unknown-command")).thenReturn(null);
                })) {

            ScriptExecutor executor = new ScriptExecutor(scriptFile.getPath());
            executor.executeScript();

            String output = outputCapture.getAllOutput();
            assertThat(output).contains("Unknown command in script: unknown-command");
        }
    }

    @Test
    public void testExecuteScript_FileNotFound() {
        ScriptExecutor executor = new ScriptExecutor("/nonexistent/script.cadet");

        assertThatThrownBy(() -> executor.executeScript())
                .isInstanceOf(IOException.class);

        String output = outputCapture.getAllOutput();
        assertThat(output).contains("Failed to execute script");
    }

    @Test
    public void testExecuteScript_CommandThrowsException() throws IOException {
        try (FileWriter writer = new FileWriter(scriptFile)) {
            writer.write("error-command\n");
        }

        try (MockedConstruction<CommandRegistry> mockRegistry = mockConstruction(CommandRegistry.class,
                (mock, context) -> {
                    CommandRegistry.Command errorCommand = mock(CommandRegistry.Command.class);
                    when(errorCommand.execute(any())).thenThrow(new RuntimeException("Command error"));
                    when(mock.getCommand("error-command")).thenReturn(errorCommand);
                })) {

            ScriptExecutor executor = new ScriptExecutor(scriptFile.getPath());

            // A fatal runtime error is no longer swallowed: it surfaces as an IOException so the
            // caller maps it to a non-zero exit code rather than reporting a false success.
            assertThatThrownBy(() -> executor.executeScript())
                    .isInstanceOf(java.io.IOException.class)
                    .hasMessageContaining("Script execution aborted");

            String output = outputCapture.getAllOutput();
            assertThat(output).contains("Error during script execution: Command error");
        }
    }

    @Test
    public void testExecuteScript_ComplexScript() throws IOException {
        try (FileWriter writer = new FileWriter(scriptFile)) {
            writer.write("# CadetCoder Script Example\n");
            writer.write("# Initialize project context\n");
            writer.write("\n");
            writer.write("context add src/Main.java\n");
            writer.write("context add src/Utils.java\n");
            writer.write("\n");
            writer.write("# Read configuration\n");
            writer.write("read config.json\n");
            writer.write("\n");
            writer.write("# Run analysis\n");
            writer.write("analyze\n");
            writer.write("suggest improvements\n");
            writer.write("\n");
            writer.write("# This command will fail\n");
            writer.write("unknown-cmd\n");
            writer.write("\n");
            writer.write("# Continue with other commands\n");
            writer.write("help\n");
        }

        try (MockedConstruction<CommandRegistry> mockRegistry = mockConstruction(CommandRegistry.class,
                (mock, context) -> {
                    // Mock successful commands
                    CommandRegistry.Command contextCommand = mock(CommandRegistry.Command.class);
                    when(contextCommand.execute(any())).thenReturn(0);
                    when(mock.getCommand("context")).thenReturn(contextCommand);

                    CommandRegistry.Command readCommand = mock(CommandRegistry.Command.class);
                    when(readCommand.execute(any())).thenReturn(0);
                    when(mock.getCommand("read")).thenReturn(readCommand);

                    CommandRegistry.Command analyzeCommand = mock(CommandRegistry.Command.class);
                    when(analyzeCommand.execute(any())).thenReturn(0);
                    when(mock.getCommand("analyze")).thenReturn(analyzeCommand);

                    CommandRegistry.Command suggestCommand = mock(CommandRegistry.Command.class);
                    when(suggestCommand.execute(any())).thenReturn(0);
                    when(mock.getCommand("suggest")).thenReturn(suggestCommand);

                    CommandRegistry.Command helpCommand = mock(CommandRegistry.Command.class);
                    when(helpCommand.execute(any())).thenReturn(0);
                    when(mock.getCommand("help")).thenReturn(helpCommand);

                    // Unknown command returns null
                    when(mock.getCommand("unknown-cmd")).thenReturn(null);
                })) {

            ScriptExecutor executor = new ScriptExecutor(scriptFile.getPath());
            executor.executeScript();

            // Verify all commands were attempted
            CommandRegistry registry = mockRegistry.constructed().get(0);
            verify(registry, times(2)).getCommand("context");
            verify(registry).getCommand("read");
            verify(registry).getCommand("analyze");
            verify(registry).getCommand("suggest");
            verify(registry).getCommand("unknown-cmd");
            verify(registry).getCommand("help");

            // Check output contains error for unknown command
            String output = outputCapture.getAllOutput();
            assertThat(output).contains("Unknown command in script: unknown-cmd");
        }
    }

    @Test
    public void testExecuteScript_CommandWithMultipleSpaces() throws IOException {
        try (FileWriter writer = new FileWriter(scriptFile)) {
            writer.write("command   arg1    arg2     arg3\n");
        }

        try (MockedConstruction<CommandRegistry> mockRegistry = mockConstruction(CommandRegistry.class,
                (mock, context) -> {
                    CommandRegistry.Command command = mock(CommandRegistry.Command.class);
                    when(command.execute(argThat(args ->
                                    args.length == 5 && // Multiple spaces create empty strings in split
                                    args[0].equals("") &&
                                    args[1].equals("arg1") &&
                                    args[2].equals("") &&
                                    args[3].equals("arg2") &&
                                    args[4].equals("")
                                                ))).thenReturn(0);
                    when(mock.getCommand("command")).thenReturn(command);
                })) {

            ScriptExecutor executor = new ScriptExecutor(scriptFile.getPath());
            executor.executeScript();

            // Command should be found and executed
            CommandRegistry registry = mockRegistry.constructed().get(0);
            verify(registry).getCommand("command");
        }
    }
}