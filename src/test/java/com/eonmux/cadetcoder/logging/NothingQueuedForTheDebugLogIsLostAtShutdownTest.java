package com.eonmux.cadetcoder.logging;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * What was written to the debug log during a session is in the file once the session has ended.
 *
 * <h2>The defect</h2>
 *
 * <p>Entries are queued and written by a thread of its own, which spends nearly all its life
 * blocked waiting for the next one. {@code shutdown} cleared the thread's stop flag and, in the
 * same breath, interrupted it -- so the interrupt landed in that wait, the loop broke on the
 * {@link InterruptedException} rather than draining, and the file was closed with everything still
 * queued unwritten. The entries lost that way are the newest ones, and a debug log is read after a
 * failure, which is exactly when the newest entries are the only ones anybody wants. The same
 * ordering was already got right for the session log; the debug log had it the other way round.
 * </p>
 *
 * <p>The logger under test is pointed at a temporary directory and forgotten afterwards, because
 * shutting the shared one down would end debug logging for every test that runs after this
 * one.</p>
 */
public class NothingQueuedForTheDebugLogIsLostAtShutdownTest {

    /** Enough entries that the writer is still working through them when the end is asked for. */
    private static final int BURST = 2_000;

    /**
     * Made once and attached to every entry of the burst.
     *
     * <p>Queueing an entry costs almost nothing and writing one costs a little, so a burst of plain
     * lines is drained as fast as it is produced and the writer is never behind. Rendering a stack
     * trace and masking it is work the writer does and the producer does not, which is what puts a
     * backlog in the queue for the end of the session to arrive in the middle of. Built once so
     * that filling in the trace is not charged to the producer.</p>
     */
    private static final Throwable DEEP = new IllegalStateException("something went wrong");

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private MockedStatic<ConfigManager> configManager;

    @Before
    public void setUp() throws Exception {
        forgetLogger();

        Configuration config = new Configuration();
        config.setBaseDir(folder.getRoot().getAbsolutePath());
        config.getLogging().setDebugEnabled(true);

        ConfigManager configured = mock(ConfigManager.class);
        when(configured.getConfig()).thenReturn(config);

        configManager = mockStatic(ConfigManager.class);
        configManager.when(ConfigManager::getInstance).thenReturn(configured);
    }

    @After
    public void tearDown() throws Exception {
        try {
            if (configManager != null) {
                configManager.close();
            }
        } finally {
            forgetLogger();
        }
    }

    private static void forgetLogger() throws Exception {
        Field instance = DebugLogger.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    private static void shutDown(DebugLogger logger) throws Exception {
        Method shutdown = DebugLogger.class.getDeclaredMethod("shutdown");
        shutdown.setAccessible(true);
        shutdown.invoke(logger);
    }

    private static String written(DebugLogger logger) throws Exception {
        Field file = DebugLogger.class.getDeclaredField("debugLogFile");
        file.setAccessible(true);
        return Files.readString((Path) file.get(logger), StandardCharsets.UTF_8);
    }

    @Test
    public void thelastThingLoggedIsOnDiskWhenShutdownReturns() throws Exception {
        DebugLogger logger = DebugLogger.getInstance();

        logger.debug("a-test", "the-last-line-before-the-end");
        shutDown(logger);

        assertThat(written(logger)).contains("the-last-line-before-the-end");
    }

    @Test
    public void aburstStillBeingWrittenIsFinishedRatherThanDropped() throws Exception {
        DebugLogger logger = DebugLogger.getInstance();

        for (int entry = 0; entry < BURST; entry++) {
            logger.debug("a-test", "burst-entry-" + entry, DEEP);
        }
        shutDown(logger);

        String log = written(logger);
        for (int entry = 0; entry < BURST; entry++) {
            assertThat(log)
                    .as("entry %d of %d was queued before the end and must be in the file",
                        entry, BURST)
                    .contains("burst-entry-" + entry);
        }
    }
}
