package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test security features of various commands
 */
public class SecurityTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private TestOutputCapture outputCapture;

    @Before
    public void setUp() {
        outputCapture = new TestOutputCapture();
    }

    /**
     * TestOutputCapture redirects System.out and System.err in its CONSTRUCTOR, so a class that
     * builds one and never stops it leaves the process streams pointing at its own buffers for
     * every test class that runs afterwards. Its siblings restore; this one had no @After at all.
     */
    @After
    public void tearDown() {
        outputCapture.stopCapture();
    }

    @Test
    public void testWriteCommand_PathTraversal() {
        WriteCommand writeCommand = new WriteCommand();

        // Test path traversal attempt
        int result = writeCommand.execute(new String[] {"../../../etc/passwd", "malicious content"});
        assertThat(result).isEqualTo(1);
        assertThat(outputCapture.getStderr()).contains("Access denied");
    }

    @Test
    public void testBashCommand_DangerousCharacters() {
        BashCommand bashCommand = new BashCommand();

        // A second command hidden behind a separator is refused for what it runs, and the refusal
        // names it: the line is taken apart, and `rm` is on the list whichever command it follows.
        int result = bashCommand.execute(new String[] {"-f", "echo hello; rm -rf /tmp"});
        assertThat(result).isEqualTo(1);
        assertThat(outputCapture.getStderr()).contains("'rm' is on the list");
    }

    @Test
    public void testBashCommand_RemoteExecution() {
        BashCommand bashCommand = new BashCommand();

        // Test remote command detection
        int result = bashCommand.execute(new String[] {"ssh user@malicious.com"});
        assertThat(result).isEqualTo(1);
        assertThat(outputCapture.getStderr()).contains("Remote execution is disabled");
    }

    @Test
    public void testReadCommand_SensitiveFiles() {
        ReadCommand readCommand = new ReadCommand();

        // Test reading sensitive files
        int result = readCommand.execute(new String[] {"/etc/passwd"});
        assertThat(result).isEqualTo(1);
        assertThat(outputCapture.getStderr()).contains("sensitive system files");
    }

    @Test
    public void testWriteCommand_SystemDirectories() {
        WriteCommand writeCommand = new WriteCommand();

        // Test writing to system directories
        int result = writeCommand.execute(new String[] {"/etc/test.conf", "content"});
        assertThat(result).isEqualTo(1);
        // System dirs are now blocked by the shared SecurityValidator policy (DANGEROUS_PATHS),
        // not a separate substring pre-check, so the message is the generic security-policy denial.
        assertThat(outputCapture.getAllOutput()).contains("blocked by security policy");
    }
}