package com.eonmux.cadetcoder;

import com.eonmux.cadetcoder.commands.ChatCommand;
import com.eonmux.cadetcoder.commands.HelpCommand;
import com.eonmux.cadetcoder.error.ErrorHandler;
import com.eonmux.cadetcoder.logging.DebugLogger;
import com.eonmux.cadetcoder.test.AiTestSupport;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.*;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

public class CommandRegistryTest {

    private CommandRegistry   registry;
    private TestOutputCapture outputCapture;

    @Before
    public void setUp() {
        // testExecuteUnknownCommand routes an unknown command to the real ChatCommand fallback; keep
        // that path offline so it returns instantly instead of blocking on a live model when one is up.
        AiTestSupport.installOfflineStub();
        outputCapture = new TestOutputCapture();
        registry      = new CommandRegistry();
    }

    @After
    public void tearDown() {
        outputCapture.restore();
        AiTestSupport.reset();
    }

    @Test
    public void testCommandRegistration() {
        // Test that commands are registered
        Map<String, CommandRegistry.Command> commands = registry.getCommands();

        assertThat(commands).isNotEmpty();
        assertThat(commands).containsKeys("chat", "help", "read", "write", "edit");
    }

    @Test
    public void testGetCommand() {
        CommandRegistry.Command chatCommand = registry.getCommand("chat");
        assertThat(chatCommand).isNotNull();
        assertThat(chatCommand).isInstanceOf(ChatCommand.class);

        CommandRegistry.Command helpCommand = registry.getCommand("help");
        assertThat(helpCommand).isNotNull();
        assertThat(helpCommand).isInstanceOf(HelpCommand.class);
    }

    @Test
    public void testGetCommandCaseInsensitive() {
        CommandRegistry.Command command1 = registry.getCommand("chat");
        CommandRegistry.Command command2 = registry.getCommand("CHAT");

        // Registry uses lowercase keys
        assertThat(command1).isNotNull();
        assertThat(command2).isNull();
    }

    @Test
    public void testExecuteCommand() {
        try (MockedStatic<DebugLogger> debugMock = mockStatic(DebugLogger.class)) {
            DebugLogger mockDebugLogger = mock(DebugLogger.class);
            debugMock.when(DebugLogger::getInstance).thenReturn(mockDebugLogger);

            int exitCode = registry.executeCommand("help", new String[0]);

            assertThat(exitCode).isEqualTo(0);
            assertThat(outputCapture.getOutput()).contains("Commands");

            // Verify logging
            verify(mockDebugLogger).logCommand("help", new String[0]);
            verify(mockDebugLogger).logResponse(eq("help"), eq(0), anyLong());
        }
    }

    @Test
    public void testExecuteCommandWithArguments() {
        // Create a test file
        String testFile = "/tmp/test.txt";

        try (MockedStatic<DebugLogger> debugMock = mockStatic(DebugLogger.class)) {
            DebugLogger mockDebugLogger = mock(DebugLogger.class);
            debugMock.when(DebugLogger::getInstance).thenReturn(mockDebugLogger);

            // This will fail because the file doesn't exist, but that's ok for this test
            int exitCode = registry.executeCommand("read", new String[] {testFile});

            // Should fail with non-zero exit code
            assertThat(exitCode).isNotEqualTo(0);

            // Verify logging with arguments
            verify(mockDebugLogger).logCommand("read", new String[] {testFile});
            verify(mockDebugLogger).logResponse(eq("read"), eq(1), anyLong());
        }
    }

    @Test
    public void testExecuteUnknownCommand() {
        try (MockedStatic<DebugLogger> debugMock = mockStatic(DebugLogger.class)) {
            DebugLogger mockDebugLogger = mock(DebugLogger.class);
            debugMock.when(DebugLogger::getInstance).thenReturn(mockDebugLogger);

            // Unknown commands should fall back to chat
            int exitCode = registry.executeCommand("unknown", new String[] {"arg1", "arg2"});

            // Chat command might fail without AI setup, but it should be called. The iteration
            // marker is what says the chat loop opened; the request is not echoed back, because by
            // this point it is already on screen.
            assertThat(outputCapture.getOutput()).contains("Iteration 1");

            // When falling back to chat, args are modified to include the original command
            verify(mockDebugLogger).logCommand("unknown", new String[] {"unknown", "arg1", "arg2"});
        }
    }

    @Test
    public void testExecuteCommandException() {
        // Mock a command that throws an exception
        CommandRegistry.Command badCommand = new CommandRegistry.Command() {
            @Override
            public int execute(String[] args) {
                throw new RuntimeException("Test exception");
            }

            @Override
            public String getDescription() {
                return "Bad command";
            }

            @Override
            public String getUsage() {
                return "bad";
            }
        };

        // Inject the bad command
        registry.register("bad", badCommand);

        try (MockedStatic<ErrorHandler> errorMock = mockStatic(ErrorHandler.class);
             MockedStatic<DebugLogger> debugMock = mockStatic(DebugLogger.class)) {

            ErrorHandler mockErrorHandler = mock(ErrorHandler.class);
            errorMock.when(ErrorHandler::getInstance).thenReturn(mockErrorHandler);

            DebugLogger mockDebugLogger = mock(DebugLogger.class);
            debugMock.when(DebugLogger::getInstance).thenReturn(mockDebugLogger);

            int exitCode = registry.executeCommand("bad", new String[0]);

            assertThat(exitCode).isEqualTo(1);

            // Verify error handling
            verify(mockErrorHandler).handleException(any(RuntimeException.class));
            verify(mockDebugLogger).error(eq("CommandRegistry"),
                    contains("Command execution failed"),
                    any(Exception.class));
        }
    }

    @Test
    public void testCommandNaming() throws Exception {
        // Test the determineCommandName method using reflection
        Field determineCommandNameMethod = CommandRegistry.class.getDeclaredField("commands");
        determineCommandNameMethod.setAccessible(true);
        Map<String, CommandRegistry.Command> commands =
                (Map<String, CommandRegistry.Command>) determineCommandNameMethod.get(registry);

        // Check that command names are properly extracted from class names
        for (Map.Entry<String, CommandRegistry.Command> entry : commands.entrySet()) {
            String                  commandName = entry.getKey();
            CommandRegistry.Command command     = entry.getValue();
            String                  className   = command.getClass().getSimpleName();

            // The registry key honors an explicit picocli @Command(name=...) when present
            // (e.g. PlanModeCommand -> "plan", UberModeCommand -> "ubermode"); otherwise it is
            // the class name without the "Command" suffix, in lowercase.
            picocli.CommandLine.Command annotation =
                    command.getClass().getAnnotation(picocli.CommandLine.Command.class);
            if (annotation != null && !annotation.name().isEmpty()
                    && !"<main class>".equals(annotation.name())) {
                assertThat(commandName).isEqualTo(annotation.name().toLowerCase());
            } else if (className.endsWith("Command")) {
                String expectedName = className.substring(0, className.length() - 7).toLowerCase();
                assertThat(commandName).isEqualTo(expectedName);
            }
        }
    }

    @Test
    public void testRedactSensitiveArgs_MasksLoginKeyButNotOtherCommands() throws Exception {
        java.lang.reflect.Method m = CommandRegistry.class
                .getDeclaredMethod("redactSensitiveArgs", String.class, String[].class);
        m.setAccessible(true);

        // A misused `login <provider> <apikey>` must not log the key.
        String[] login = (String[]) m.invoke(null, "login", new String[] {"anthropic", "sk-secret-value"});
        assertThat(login[0]).isEqualTo("anthropic");
        assertThat(login[1]).isEqualTo("***redacted***");
        assertThat(java.util.Arrays.asList(login)).doesNotContain("sk-secret-value");

        // Other commands are logged unchanged.
        String[] read = (String[]) m.invoke(null, "read", new String[] {"a", "b"});
        assertThat(read).containsExactly("a", "b");
    }

    @Test
    public void testChatCommandFallback() {
        try (MockedStatic<DebugLogger> debugMock = mockStatic(DebugLogger.class)) {
            DebugLogger mockDebugLogger = mock(DebugLogger.class);
            debugMock.when(DebugLogger::getInstance).thenReturn(mockDebugLogger);

            // Remove chat command temporarily to test the fallback behavior
            CommandRegistry.Command chatCommand = registry.unregister("chat");

            int exitCode = registry.executeCommand("nonexistent", new String[] {"test"});

            assertThat(exitCode).isEqualTo(1);
            // The message used to be "Command not found: x" followed by an unsorted dump of every
            // registered name. It now names the closest matches and points at /help, because an
            // unknown command is almost always a typo and 40 names on one line is not an answer.
            assertThat(outputCapture.getStderr()).contains("Unknown command: nonexistent");
            assertThat(outputCapture.getOutput()).contains("cadet help");

            // Restore chat command
            registry.register("chat", chatCommand);
        }
    }

    @Test
    public void testSetCommandRegistry() {
        // Test that commands with setCommandRegistry method get the registry injected
        CommandRegistry.Command chatCommand = registry.getCommand("chat");
        assertThat(chatCommand).isNotNull();

        // ChatCommand should have the registry set
        if (chatCommand instanceof ChatCommand) {
            // The setCommandRegistry method should have been called during registration
            // We can't easily verify this without modifying the ChatCommand class
            // but we can verify the command exists and works
            assertThat(chatCommand.getDescription()).isNotNull();
            assertThat(chatCommand.getUsage()).isNotNull();
        }
    }

    /**
     * The command table is process-wide state, so handing it out is handing out the ability to
     * add or drop a command for everyone. Registration has its own methods for that.
     */
    @Test
    public void theCommandMapCannotBeChangedThroughTheAccessor() {
        CommandRegistry.Command anyCommand = registry.getCommand("read");

        assertThatThrownBy(() -> registry.getCommands().put("injected", anyCommand))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> registry.getCommands().remove("read"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(registry.getCommand("read")).as("and nothing was actually removed").isNotNull();
    }

    @Test
    public void registeringAndUnregisteringAreRealOperations() {
        CommandRegistry.Command chat = registry.unregister("chat");
        assertThat(registry.getCommand("chat")).isNull();

        registry.register("chat", chat);
        assertThat(registry.getCommand("chat")).isSameAs(chat);
    }
}