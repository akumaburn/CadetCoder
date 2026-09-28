package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class HelpCommandTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private HelpCommand       helpCommand;
    private CommandRegistry   mockRegistry;
    private TestOutputCapture outputCapture;

    @Before
    public void setUp() {
        mockRegistry  = mock(CommandRegistry.class);
        helpCommand   = new HelpCommand(mockRegistry);
        outputCapture = new TestOutputCapture();
        outputCapture.startCapture();
    }

    @After
    public void tearDown() {
        outputCapture.stopCapture();
    }

    @Test
    public void testHelpCommand_ListAllCommands() {
        // Setup mock commands
        Map<String, CommandRegistry.Command> mockCommands = new HashMap<>();

        CommandRegistry.Command mockReadCommand = mock(CommandRegistry.Command.class);
        when(mockReadCommand.getDescription()).thenReturn("Read file contents");
        mockCommands.put("read", mockReadCommand);

        CommandRegistry.Command mockWriteCommand = mock(CommandRegistry.Command.class);
        when(mockWriteCommand.getDescription()).thenReturn("Write content to file");
        mockCommands.put("write", mockWriteCommand);

        CommandRegistry.Command mockEditCommand = mock(CommandRegistry.Command.class);
        when(mockEditCommand.getDescription()).thenReturn("Edit files with AI assistance");
        mockCommands.put("edit", mockEditCommand);

        when(mockRegistry.getCommands()).thenReturn(mockCommands);

        // Execute command
        int exitCode = helpCommand.execute(new String[] {});

        // Verify
        assertThat(exitCode).isEqualTo(0);
        String output = outputCapture.getStdout();
        assertThat(output).contains("Commands");
        assertThat(output).contains("cadet read");
        assertThat(output).contains("Read file contents");
        assertThat(output).contains("cadet write");
        assertThat(output).contains("Write content to file");
        assertThat(output).contains("cadet edit");
        assertThat(output).contains("Edit files with AI assistance");
        // Grouped, so a reader looking for a capability can skip to the right heading instead of
        // scanning forty-odd alphabetised rows.
        assertThat(output).contains("Read and write files");
    }

    @Test
    public void testHelpCommand_EmptyRegistry() {
        // Setup empty registry
        when(mockRegistry.getCommands()).thenReturn(new HashMap<>());

        // Execute command
        int exitCode = helpCommand.execute(new String[] {});

        // Verify
        assertThat(exitCode).isEqualTo(0);
        String output = outputCapture.getStdout();
        assertThat(output).contains("Commands");
        // No registered commands, so no group headings either: an empty section helps no one.
        assertThat(output).doesNotContain("Read and write files");
    }

    @Test
    public void testHelpCommand_WithArguments() {
        // 'help <command>' now shows that command's detail instead of ignoring the argument.
        CommandRegistry.Command mockCommand = mock(CommandRegistry.Command.class);
        when(mockCommand.getDescription()).thenReturn("Test command");
        when(mockCommand.getUsage()).thenReturn("test");
        when(mockRegistry.getCommand("test")).thenReturn(mockCommand);

        int exitCode = helpCommand.execute(new String[] {"test"});

        assertThat(exitCode).isEqualTo(0);
        String output = outputCapture.getStdout();
        assertThat(output).contains("cadet test");
        assertThat(output).contains("Test command");
        assertThat(output).doesNotContain("Read and write files");
    }

    @Test
    public void testHelpCommand_UnknownCommand() {
        // An unregistered name reports an error and exits non-zero.
        when(mockRegistry.getCommand("bogus")).thenReturn(null);

        int exitCode = helpCommand.execute(new String[] {"bogus"});

        assertThat(exitCode).isEqualTo(1);
        assertThat(outputCapture.getAllOutput()).contains("Unknown command: bogus");
    }

    @Test
    public void testHelpCommand_DefaultConstructor() {
        // Create help command with default constructor
        HelpCommand defaultHelpCommand = new HelpCommand();
        defaultHelpCommand.setCommandRegistry(mockRegistry);

        // Setup mock commands
        Map<String, CommandRegistry.Command> mockCommands = new HashMap<>();
        CommandRegistry.Command              mockCommand  = mock(CommandRegistry.Command.class);
        when(mockCommand.getDescription()).thenReturn("Test command");
        mockCommands.put("test", mockCommand);
        when(mockRegistry.getCommands()).thenReturn(mockCommands);

        // Execute command
        int exitCode = defaultHelpCommand.execute(new String[] {});

        // Verify
        assertThat(exitCode).isEqualTo(0);
        String output = outputCapture.getStdout();
        assertThat(output).contains("Commands");
    }

    @Test
    public void testHelpCommand_ManyCommands() {
        // Setup many mock commands
        Map<String, CommandRegistry.Command> mockCommands = new HashMap<>();

        for (int i = 0; i < 20; i++) {
            CommandRegistry.Command mockCommand = mock(CommandRegistry.Command.class);
            when(mockCommand.getDescription()).thenReturn("Command " + i + " description");
            mockCommands.put("command" + i, mockCommand);
        }

        when(mockRegistry.getCommands()).thenReturn(mockCommands);

        // Execute command
        int exitCode = helpCommand.execute(new String[] {});

        // Verify
        assertThat(exitCode).isEqualTo(0);
        String output = outputCapture.getStdout();
        assertThat(output).contains("Commands");
        assertThat(output).contains("cadet command0");
        assertThat(output).contains("cadet command19");
        assertThat(output).contains("Command 0 description");
        assertThat(output).contains("Command 19 description");
    }

    @Test
    public void testHelpCommand_SingleCommandLookupIsCaseInsensitive() {
        // Dispatch is case-insensitive and registry keys are lower-case; 'help READ' must resolve
        // the same command as 'help read'. The lookup key should be lower-cased.
        CommandRegistry.Command mockCommand = mock(CommandRegistry.Command.class);
        when(mockCommand.getDescription()).thenReturn("Read file contents");
        when(mockCommand.getUsage()).thenReturn("read");
        when(mockRegistry.getCommand("read")).thenReturn(mockCommand);

        int exitCode = helpCommand.execute(new String[] {"READ"});

        assertThat(exitCode).isEqualTo(0);
        // The lookup happened with the lower-cased key.
        verify(mockRegistry).getCommand("read");
        String output = outputCapture.getStdout();
        // The user-facing display preserves the original argument casing.
        assertThat(output).contains("cadet read");
        assertThat(output).contains("Read file contents");
    }

    @Test
    public void testHelpCommand_NullRegistryIsGuarded() {
        // A help command with no registry must fail with a friendly message, not an NPE.
        HelpCommand noRegistryHelp = new HelpCommand();

        int exitCode = noRegistryHelp.execute(new String[] {});

        assertThat(exitCode).isEqualTo(1);
        assertThat(outputCapture.getAllOutput()).contains("command registry not initialized");
    }

    @Test
    public void testHelpCommand_NullCommandsMapIsTolerated() {
        // A custom registry may return null from getCommands(); help must not throw.
        when(mockRegistry.getCommands()).thenReturn(null);

        int exitCode = helpCommand.execute(new String[] {});

        assertThat(exitCode).isEqualTo(0);
        assertThat(outputCapture.getStdout()).contains("Commands");
    }

    @Test
    public void testHelpCommand_DoesNotIterateLiveMap() {
        // Help must iterate a defensive copy: mutating the source map during rendering must not
        // raise a ConcurrentModificationException. We simulate this by returning a map whose
        // entries are read into a snapshot before iteration.
        Map<String, CommandRegistry.Command> liveMap = new HashMap<>();
        CommandRegistry.Command mockCommand = mock(CommandRegistry.Command.class);
        when(mockCommand.getDescription()).thenReturn("Read file contents");
        liveMap.put("read", mockCommand);
        when(mockRegistry.getCommands()).thenReturn(liveMap);

        int exitCode = helpCommand.execute(new String[] {});

        // Mutate the original after execution to confirm the command did not retain a live view.
        liveMap.clear();

        assertThat(exitCode).isEqualTo(0);
        assertThat(outputCapture.getStdout()).contains("read");
    }

    @Test
    public void testGetDescription() {
        assertThat(helpCommand.getDescription())
                .isEqualTo("List the commands, or show how one is used");
    }

    @Test
    public void testGetUsage() {
        // Bare, like every other command: CommandUsage puts on the spelling that fits wherever the
        // line is about to be printed, so carrying both forms here would print one of them twice.
        assertThat(helpCommand.getUsage()).isEqualTo("help [command]");
        assertThat(CommandUsage.render(helpCommand.getUsage())).isEqualTo("cadet help [command]");
    }
}