package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedConstruction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

public class ShellCommandTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private ShellCommand      shellCommand;
    private TestOutputCapture outputCapture;

    /**
     * Test double that forces the interactivity verdict so the guard branch under
     * test is deterministic regardless of whether Surefire provides a real
     * {@link System#console()}.
     */
    private static final class FixedInteractivityShellCommand extends ShellCommand {
        private final boolean interactive;

        FixedInteractivityShellCommand(boolean interactive) {
            this.interactive = interactive;
        }

        @Override
        boolean isInteractive() {
            return interactive;
        }
    }

    @Before
    public void setUp() {
        // Default to the interactive branch so the InteractiveShell construction tests
        // exercise the post-guard path. Guard-specific tests use their own instances.
        shellCommand  = new FixedInteractivityShellCommand(true);
        outputCapture = new TestOutputCapture();
        outputCapture.startCapture();
    }

    @After
    public void tearDown() {
        outputCapture.stopCapture();
        System.clearProperty(ShellCommand.INTERACTIVE_PROPERTY);
    }

    @Test
    public void testShellCommand_InitializationSuccess() throws Exception {
        // Mock InteractiveShell creation and behavior
        try (MockedConstruction<InteractiveShell> mockShell = mockConstruction(InteractiveShell.class,
                (mock, context) -> {
                    when(mock.runShell()).thenReturn(0);
                })) {

            // Execute command
            int exitCode = shellCommand.execute(new String[] {});

            // Verify
            assertThat(exitCode).isEqualTo(0);
            assertThat(mockShell.constructed()).hasSize(1);
        }
    }

    @Test
    public void testShellCommand_InitializationError() throws Exception {
        // Mock InteractiveShell to throw exception during construction
        try (MockedConstruction<InteractiveShell> mockShell = mockConstruction(InteractiveShell.class,
                (mock, context) -> {
                    throw new Exception("Jexer initialization error");
                })) {

            // Execute command
            int exitCode = shellCommand.execute(new String[] {});

            // Verify
            assertThat(exitCode).isEqualTo(1);
            String output = outputCapture.getAllOutput();
            assertThat(output).contains("Error initializing interactive shell:");
        }
    }

    @Test
    public void testShellCommand_RunError() throws Exception {
        // Mock InteractiveShell with run error
        try (MockedConstruction<InteractiveShell> mockShell = mockConstruction(InteractiveShell.class,
                (mock, context) -> {
                    when(mock.runShell()).thenThrow(new RuntimeException("Runtime error during execution"));
                })) {

            // Execute command
            int exitCode = shellCommand.execute(new String[] {});

            // Verify
            assertThat(exitCode).isEqualTo(1);
            String output = outputCapture.getAllOutput();
            assertThat(output).contains("Error initializing interactive shell: Runtime error during execution");
        }
    }

    @Test
    public void testShellCommand_NonInteractiveContext_RefusedWithoutConstructingShell() throws Exception {
        // Guard (shell-iface-1): a non-interactive context must never attach the raw-mode TUI.
        ShellCommand nonInteractive = new FixedInteractivityShellCommand(false);

        try (MockedConstruction<InteractiveShell> mockShell = mockConstruction(InteractiveShell.class)) {
            int exitCode = nonInteractive.execute(new String[] {});

            assertThat(exitCode).isEqualTo(1);
            // The shell must not even be constructed in a non-interactive context.
            assertThat(mockShell.constructed()).isEmpty();
            String output = outputCapture.getAllOutput();
            assertThat(output).contains("requires a real terminal");
        }
    }

    @Test
    public void testShellCommand_InteractivePropertyFalse_OverridesAndRefuses() throws Exception {
        // An explicit -Dcadet.interactive=false must force-disable even where a console exists.
        System.setProperty(ShellCommand.INTERACTIVE_PROPERTY, "false");
        ShellCommand realProbe = new ShellCommand();

        try (MockedConstruction<InteractiveShell> mockShell = mockConstruction(InteractiveShell.class)) {
            int exitCode = realProbe.execute(new String[] {});

            assertThat(exitCode).isEqualTo(1);
            assertThat(mockShell.constructed()).isEmpty();
            assertThat(outputCapture.getAllOutput()).contains("requires a real terminal");
        }
    }

    @Test
    public void testShellCommand_InteractivePropertyTrue_BypassesConsoleProbe() throws Exception {
        // An explicit -Dcadet.interactive=true must let the shell run even without a real console.
        System.setProperty(ShellCommand.INTERACTIVE_PROPERTY, "true");
        ShellCommand realProbe = new ShellCommand();

        try (MockedConstruction<InteractiveShell> mockShell = mockConstruction(InteractiveShell.class,
                (mock, context) -> when(mock.runShell()).thenReturn(0))) {

            int exitCode = realProbe.execute(new String[] {});

            assertThat(exitCode).isEqualTo(0);
            assertThat(mockShell.constructed()).hasSize(1);
        }
    }

    @Test
    public void testGetDescription() {
        assertThat(shellCommand.getDescription())
                .isEqualTo("Open the interactive shell");
    }

    @Test
    public void testGetUsage() {
        assertThat(shellCommand.getUsage())
                .isEqualTo("shell");
    }

    @Test
    public void testShellCommand_ImplementsCommandInterface() {
        assertThat(shellCommand).isNotNull();
        assertThat(shellCommand).isInstanceOf(CommandRegistry.Command.class);
    }
}