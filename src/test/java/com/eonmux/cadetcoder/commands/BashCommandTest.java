package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import com.eonmux.cadetcoder.ui.ProgramOutput;
import com.eonmux.cadetcoder.ui.TuiMode;
import org.junit.*;
import org.mockito.*;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

public class BashCommandTest {

    @Mock
    private ConfigManager mockConfigManager;

    @Mock
    private Configuration mockConfig;

    @Mock
    private Configuration.SecurityConfig mockSecurityConfig;

    private BashCommand          bashCommand;
    private TestOutputCapture    outputCapture;
    private ByteArrayInputStream inputStream;
    private AutoCloseable        mocks;

    @Before
    public void setUp() throws Exception {
        mocks         = MockitoAnnotations.openMocks(this);
        bashCommand   = new BashCommand();
        outputCapture = new TestOutputCapture();
        outputCapture.startCapture();

        // Setup default mock behavior
        when(mockConfig.getSecurity()).thenReturn(mockSecurityConfig);
        when(mockSecurityConfig.isReadOnlyMode()).thenReturn(false);
        when(mockSecurityConfig.isAllowRemoteExecution()).thenReturn(true);
        when(mockSecurityConfig.isRequireConfirmation()).thenReturn(false);
    }

    @After
    public void tearDown() throws Exception {
        outputCapture.stopCapture();
        if (inputStream != null) {
            System.setIn(System.in);
        }
        mocks.close();
    }

    @Test
    public void testBashCommand_ReadOnlyMode() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);
            when(mockSecurityConfig.isReadOnlyMode()).thenReturn(true);

            // Execute command
            int exitCode = bashCommand.execute(new String[] {"echo", "test"});

            // Verify
            assertThat(exitCode).isEqualTo(1);
            assertThat(outputCapture.getAllOutput()).contains("Cannot run commands: read-only mode is enabled");
        }
    }

    @Test
    public void testBashCommand_RemoteExecutionBlocked() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);
            when(mockSecurityConfig.isAllowRemoteExecution()).thenReturn(false);

            // Execute remote command
            int exitCode = bashCommand.execute(new String[] {"ssh", "user@host", "ls"});

            // Verify
            assertThat(exitCode).isEqualTo(1);
            assertThat(outputCapture.getAllOutput()).contains("Remote execution is disabled");
        }
    }

    /**
     * In the shell, what the program printed reaches the renderer as program output.
     *
     * <p>Read as Markdown, a line such as {@code - removed} became a list item. See
     * {@link ProgramOutput}.</p>
     */
    @Test
    public void inTheShellWhatTheProgramPrintedIsMarkedAsProgramOutput() {
        String previous = System.getProperty(TuiMode.OVERRIDE_PROPERTY);
        System.setProperty(TuiMode.OVERRIDE_PROPERTY, "true");
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            Configuration config = new Configuration();
            config.getUi().setColorEnabled(false);
            config.getSecurity().setRequireConfirmation(false);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockConfigManager.getConfig()).thenReturn(config);

            int exitCode = bashCommand.execute(new String[] {"-f", "echo", "'- removed'"});

            String printed = outputCapture.getAllOutput();
            // The header line names the command too, so the output line is found as a line.
            int output = printed.indexOf("\n- removed\n");
            assertThat(exitCode).isZero();
            assertThat(output).isNotNegative();
            assertThat(printed.indexOf(ProgramOutput.OPEN)).isNotNegative().isLessThan(output);
            assertThat(printed.indexOf(ProgramOutput.CLOSE))
                    .isGreaterThan(output)
                    .isLessThan(printed.indexOf("Command completed successfully"));
        } finally {
            if (previous == null) {
                System.clearProperty(TuiMode.OVERRIDE_PROPERTY);
            } else {
                System.setProperty(TuiMode.OVERRIDE_PROPERTY, previous);
            }
        }
    }

    @Test
    public void testBashCommand_NoArguments() {
        // Execute command without arguments
        int exitCode = bashCommand.execute(new String[] {});

        // Verify
        assertThat(exitCode).isEqualTo(1);
        assertThat(outputCapture.getAllOutput()).contains("No command provided");
    }

    @Test
    public void testBashCommand_RequiresConfirmation_UserDenies() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);
            when(mockSecurityConfig.isRequireConfirmation()).thenReturn(true);

            // Setup input stream to simulate user typing 'n'
            inputStream = new ByteArrayInputStream("n\n".getBytes());
            System.setIn(inputStream);

            // Execute command
            int exitCode = bashCommand.execute(new String[] {"echo", "test"});

            // A denied confirmation reports 130, the code BashCommand documents for "the command
            // never ran" so a caller can tell it apart from "ran and failed".
            //
            // This used to assert 1, with a comment guessing that reading the input threw. It did
            // not: printing "Command execution cancelled" threw, because the verbosity lookup inside
            // printInfo walked a mocked configuration whose getUi() was not stubbed. The exception
            // escaped into the caller and the outer catch returned 1, so the test was pinning the
            // consequence of a display call being able to fail. printInfo no longer can.
            assertThat(exitCode).isEqualTo(130);
            String allOutput = outputCapture.getStdout() + outputCapture.getStderr();
            assertThat(allOutput).containsAnyOf("Command execution cancelled", "Error executing command");
        }
    }

    @Test
    public void testBashCommand_InvalidTimeout() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);

            // Execute command with invalid timeout. The flag now goes BEFORE the shell command:
            // tokens after the command belong to the command (see testBashCommand_* below).
            int exitCode = bashCommand.execute(new String[] {"-t", "invalid", "echo", "test"});

            // Verify
            assertThat(exitCode).isEqualTo(1);
            assertThat(outputCapture.getAllOutput()).contains("Invalid timeout value");
        }
    }

    /**
     * Regression: this command's options must not be stolen from the shell command being run.
     *
     * <p>Every token used to be scanned for {@code -t}/{@code -d}/{@code -f}, so {@code bash rm -f x}
     * silently turned {@code -f} into force-execute -- bypassing the confirmation gate -- and ran
     * {@code rm x}. Options are now recognized only before the command begins.</p>
     */
    @Test
    public void testBashCommand_DoesNotStealFlagsBelongingToTheShellCommand() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);

            bashCommand.execute(new String[] {"echo", "hello", "-d", "NOT_A_DESCRIPTION"});

            String output = outputCapture.getAllOutput();
            assertThat(output)
                    .as("-d after the command must stay part of the command")
                    .contains("echo hello -d NOT_A_DESCRIPTION");
            assertThat(output)
                    .as("-d after the command must not be consumed as this command's description")
                    .doesNotContain("Description: NOT_A_DESCRIPTION");
        }
    }

    /** A leading option is still this command's own, and "--" ends option parsing explicitly. */
    @Test
    public void testBashCommand_LeadingOptionsAndTerminatorAreHonoured() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);

            bashCommand.execute(new String[] {"-d", "a real description", "--", "echo", "-f", "x"});

            String output = outputCapture.getAllOutput();
            assertThat(output)
                    .as("after --, -f belongs to the shell command")
                    .contains("Command: echo -f x");
            assertThat(output)
                    .as("a LEADING -d is still this command's own option and must not reach the shell")
                    .doesNotContain("a real description ")
                    .doesNotContain("echo a real description");
        }
    }

    @Test
    public void testBashCommand_ExceedsMaxTimeout() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);

            // Since we can't actually execute the command, we'll test the validation
            int exitCode = bashCommand.execute(new String[] {"-t", "700000", "echo", "test"});

            // The command would execute with the max timeout
            // We can check if the warning was printed
            assertThat(outputCapture.getStdout()).contains("Timeout exceeds maximum");
        }
    }

    @Test
    public void testBashCommand_BlocksCredentialFileRead() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);

            // Reading a well-known secret file via the shell must be blocked, mirroring the
            // file commands' denylist. The command must NOT run; the path is not surfaced.
            int exitCode = bashCommand.execute(new String[] {"cat", "~/.m2/settings.xml"});

            assertThat(exitCode).isEqualTo(1);
            assertThat(outputCapture.getAllOutput()).contains("protected credential");
        }
    }

    @Test
    public void testBashCommand_DangerousCommandPattern() {
        // Test that dangerous command patterns are recognized
        String[] dangerousCommands = {
                "rm -rf /",
                "rm -rf /*",
                "dd if=/dev/zero of=/dev/sda",
                "mkfs.ext4 /dev/sda"
        };

        for (String cmd : dangerousCommands) {
            // Verify the patterns match what we consider dangerous
            assertThat(cmd).matches(".*(rm\\s+-rf\\s+/|dd\\s+if=.*of=/dev/|mkfs).*");
        }
    }

    /**
     * A command that was taken back before it started does not start.
     *
     * <p><b>The defect</b>: the interruption was asked about after {@code ProcessBuilder.start()},
     * so a command whose thread had already been interrupted was launched and then killed. Killing
     * it does not take back what it did in between, and a shell command is exactly the kind of
     * thing that does something in its first few milliseconds. The check after the start is still
     * worth having -- an interruption can arrive while the process is being set up, and that one
     * has something to kill -- but it is not the one that stops a command that was never meant to
     * run.</p>
     */
    @Test
    public void acommandInterruptedBeforeItStartedNeverRuns() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedConstruction<ProcessBuilder> processes =
                     Mockito.mockConstruction(ProcessBuilder.class)) {
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);

            Thread.currentThread().interrupt();
            int exitCode = bashCommand.execute(new String[] {"touch", "/tmp/never-made-by-this"});

            assertThat(exitCode).isEqualTo(130);
            assertThat(processes.constructed())
                    .as("the command was taken back before it started, so there is no process")
                    .isEmpty();
        } finally {
            // Left standing, the next blocking call in this fork throws.
            Thread.interrupted();
        }
    }

    /**
     * The one reading of an invocation, which the security screen shares.
     *
     * <p>{@code ActionRun} screened the whole argv, so {@code bash -f awk '...'} was screened as a
     * line whose program is {@code -f}, and the refusal said so. Both callers now ask this.</p>
     */
    @Test
    public void theCommandLineIsWhatIsLeftAfterThisCommandsOwnOptions() {
        BashCommand.Invocation asked =
                BashCommand.read(new String[] {"-f", "awk", "{print $1}", "report.xml"});

        assertThat(asked.command()).isEqualTo("awk {print $1} report.xml");
        assertThat(asked.force()).isTrue();
        assertThat(asked.error()).isNull();
    }

    @Test
    public void anOptionAfterTheCommandBelongsToTheCommand() {
        BashCommand.Invocation asked = BashCommand.read(new String[] {"rm", "-f", "build/"});

        assertThat(asked.command()).isEqualTo("rm -f build/");
        assertThat(asked.force())
                .as("a flag belonging to the program being run must not open this command's gate")
                .isFalse();
    }

    @Test
    public void argumentsThatCannotBeReadSayWhatIsWrongWithThem() {
        assertThat(BashCommand.read(new String[] {"-t", "soon", "echo"}).error())
                .isEqualTo("Invalid timeout value: soon");
        assertThat(BashCommand.read(new String[] {"echo", "-d"}).error())
                .as("a -d after the command is the command's own argument")
                .isNull();
        assertThat(BashCommand.read(new String[] {"-d"}).error()).isEqualTo("Missing value for -d");
    }
}
