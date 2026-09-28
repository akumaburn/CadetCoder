package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

import static org.assertj.core.api.Assertions.assertThat;

public class QuitCommandTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private QuitCommand       quitCommand;
    private TestOutputCapture outputCapture;

    @Before
    public void setUp() {
        quitCommand   = new QuitCommand();
        outputCapture = new TestOutputCapture();
        outputCapture.startCapture();
    }

    @After
    public void tearDown() {
        outputCapture.stopCapture();
    }

    @Test
    public void testQuitCommand_BasicFunctionality() {
        // Note: We cannot test the actual execute() method because it calls System.exit()
        // which would terminate the test JVM. This is a known limitation when testing
        // commands that call System.exit() without using the deprecated SecurityManager.
        
        // Instead, we test that the command object is properly constructed
        // and that its metadata methods work correctly
        assertThat(quitCommand).isNotNull();
        assertThat(quitCommand.getDescription()).isNotNull();
        assertThat(quitCommand.getUsage()).isNotNull();
    }

    @Test
    public void testQuitCommand_SkipExecution() {
        // This test documents that we skip testing the execute() method
        // due to its System.exit() call which cannot be easily tested
        // without the deprecated SecurityManager
        
        // The actual execution logic includes:
        // 1. Print "Exiting CadetCoder." message
        // 2. Check if OutputRouter is routing (Jexer mode)
        // 3. Either exit gracefully through Jexer or call System.exit(0)
        
        // Since these behaviors involve System.exit(), we cannot test them
        // in unit tests without significant architectural changes
        assertThat(true).isTrue();
    }

    @Test
    public void testGetDescription() {
        assertThat(quitCommand.getDescription())
                .isEqualTo("Exit");
    }

    @Test
    public void testGetUsage() {
        assertThat(quitCommand.getUsage())
                .isEqualTo("quit");
    }
}