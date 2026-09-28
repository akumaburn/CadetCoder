package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * A shell command is over when its process exits, not when its output pipe closes.
 *
 * <p>The two are not the same. A child that the command leaves running in the background inherits
 * the command's stdout, and holds the write end of that pipe open for as long as it lives -- so the
 * pipe reaches end-of-file long after the command itself is gone. Waiting on the pipe therefore
 * waits on the background child: {@code /bash ./start-dev}, where the script starts a server and
 * returns, was reported as "Command timed out after 120000ms" with exit code 1 two minutes after it
 * had in fact succeeded.</p>
 *
 * <p>Nothing can undo that wait once it has begun. Killing the process does not end it, closing the
 * stream does not end it, and interrupting the thread does not end it -- a blocked {@code read} on a
 * pipe another process still holds is not interruptible. So the reader thread has to be one the
 * program is allowed to walk away from, and it was not: {@code Executors.newSingleThreadExecutor()}
 * makes non-daemon threads, and {@code shutdown()} does not reclaim one that will never return. A
 * session that started a background server could not exit until that server did.</p>
 */
public class BashOutlivesItsBackgroundChildrenTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private BashCommand                bashCommand;
    private TestOutputCapture          outputCapture;
    private MockedStatic<ConfigManager> configMock;

    @Before
    public void setUp() {
        bashCommand   = new BashCommand();
        outputCapture = new TestOutputCapture();
        outputCapture.startCapture();

        Configuration.SecurityConfig security = mock(Configuration.SecurityConfig.class);
        when(security.isReadOnlyMode()).thenReturn(false);
        when(security.isSandboxMode()).thenReturn(false);
        when(security.isRequireConfirmation()).thenReturn(false);
        when(security.isAllowRemoteExecution()).thenReturn(true);
        when(security.isAllowOutsideProject()).thenReturn(true);
        when(security.getAllowedCommands()).thenReturn(new String[0]);

        Configuration config = mock(Configuration.class);
        when(config.getSecurity()).thenReturn(security);

        ConfigManager manager = mock(ConfigManager.class);
        when(manager.getConfig()).thenReturn(config);

        configMock = mockStatic(ConfigManager.class);
        configMock.when(ConfigManager::getInstance).thenReturn(manager);
    }

    @After
    public void tearDown() {
        configMock.close();
        outputCapture.stopCapture();
    }

    /**
     * Writes a script that prints a line, leaves a child running, and returns.
     *
     * <p>The trailing pause is what makes the case reproducible rather than a coin toss: it holds
     * the script alive until the reader is parked inside {@code read}, which is the position from
     * which the pipe can no longer be abandoned.</p>
     *
     * @return the script's absolute path, named without an extension so the shell screen -- which
     *         refuses {@code .sh} arguments outright -- lets it through
     */
    private String scriptLeavingAChildBehind() throws IOException {
        File script = tempFolder.newFile("start-dev");
        Files.writeString(script.toPath(),
                "#!/bin/bash\n"
                + "echo started\n"
                + "sleep 8 &\n"
                + "sleep 0.3\n"
                + "exit 0\n");
        return script.getAbsolutePath();
    }

    @Test
    public void aCommandWhoseBackgroundChildHoldsTheOutputPipeStillFinishes() throws Exception {
        long started  = System.currentTimeMillis();
        int  exitCode = bashCommand.execute(new String[] {"-t", "4000", "bash", scriptLeavingAChildBehind()});
        long elapsed  = System.currentTimeMillis() - started;

        assertThat(exitCode)
                .as("the script exited 0; only its background child was still running")
                .isEqualTo(0);
        assertThat(elapsed)
                .as("waited for the background child instead of for the command")
                .isLessThan(4000L);
    }

    @Test
    public void whatTheCommandItselfPrintedIsStillReported() throws Exception {
        bashCommand.execute(new String[] {"-t", "4000", "bash", scriptLeavingAChildBehind()});

        assertThat(outputCapture.getAllOutput())
                .as("output written before the command returned must survive the shorter wait")
                .contains("started");
    }

    /**
     * The reader that cannot be stopped must at least be one the program can leave behind.
     */
    @Test
    public void noThreadLeftReadingThatPipeCanHoldTheProgramOpen() throws Exception {
        bashCommand.execute(new String[] {"-t", "4000", "bash", scriptLeavingAChildBehind()});

        for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
            Thread thread = entry.getKey();
            if (thread == Thread.currentThread() || thread.isDaemon() || !thread.isAlive()) {
                continue;
            }
            for (StackTraceElement frame : entry.getValue()) {
                assertThat(frame.getClassName())
                        .as("non-daemon thread %s is stuck in BashCommand and will outlive the session",
                                thread.getName())
                        .isNotEqualTo(BashCommand.class.getName());
            }
        }
    }

    /** A command that really does run too long is still a timeout. */
    @Test
    public void aCommandThatOutlivesItsTimeoutIsStillReportedAsOne() {
        int exitCode = bashCommand.execute(new String[] {"-t", "1000", "sleep", "8"});

        assertThat(exitCode).isEqualTo(1);
        assertThat(outputCapture.getAllOutput()).contains("timed out");
    }
}
