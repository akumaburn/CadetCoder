package com.eonmux.cadetcoder.logging;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.session.SessionManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * What was logged during a session is in the file once the session has ended.
 *
 * <h2>The defect</h2>
 *
 * <p>Entries are queued and written by a thread of its own. {@code shutdown} queued the
 * "Session ended" record and then, in the same breath, cleared the thread's stop flag and
 * interrupted it. The thread spends nearly all its life waiting for the next entry, so the
 * interrupt almost always landed in that wait, ended the loop through its
 * {@link InterruptedException} and closed the files with everything still queued unwritten --
 * beginning with the record that had just been queued. Every session log therefore stopped
 * mid-sentence, and the one entry that says how long the session ran and how much it recorded was
 * the one entry that was never written.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>When {@code shutdown} returns, what was queued is on disk: the last thing a command printed,
 * the whole of a burst that was still being written, and the record that says the session
 * ended.</p>
 *
 * <p>The logger under test is its own, pointed at a temporary directory, because shutting the
 * shared one down would end logging for every test that runs after this one.</p>
 */
public class NothingQueuedIsLostWhenTheSessionEndsTest {

    /** Enough entries that the writer is still working through them when the end is asked for. */
    private static final int BURST = 300;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private MockedStatic<ConfigManager> configManager;

    @Before
    public void setUp() throws Exception {
        forgetSingletons();

        Configuration config = new Configuration();
        config.setBaseDir(folder.getRoot().getAbsolutePath());

        ConfigManager configured = mock(ConfigManager.class);
        when(configured.getConfig()).thenReturn(config);

        configManager = mockStatic(ConfigManager.class);
        configManager.when(ConfigManager::getInstance).thenReturn(configured);
    }

    @After
    public void tearDown() throws Exception {
        try {
            SessionLogger mine = currentLogger();
            if (mine != null) {
                mine.shutdown();
            }
        } finally {
            if (configManager != null) {
                configManager.close();
            }
            forgetSingletons();
        }
    }

    private static SessionLogger currentLogger() throws Exception {
        Field instance = SessionLogger.class.getDeclaredField("instance");
        instance.setAccessible(true);
        return (SessionLogger) instance.get(null);
    }

    private static void forgetSingletons() throws Exception {
        Field logger = SessionLogger.class.getDeclaredField("instance");
        logger.setAccessible(true);
        logger.set(null, null);

        Field manager = SessionManager.class.getDeclaredField("instance");
        manager.setAccessible(true);
        manager.set(null, null);
    }

    private static String written(SessionLogger logger) throws Exception {
        return Files.readString(logger.getSessionLogFile(), StandardCharsets.UTF_8);
    }

    private static String structured(SessionLogger logger) throws Exception {
        return Files.readString(logger.getStructuredLogFile(), StandardCharsets.UTF_8);
    }

    @Test
    public void theRecordThatSaysTheSessionEndedIsOnDiskWhenShutdownReturns() throws Exception {
        SessionLogger logger = SessionLogger.getInstance();

        logger.shutdown();

        assertThat(written(logger))
                .as("the last record a session writes is the one that says it ended")
                .contains("Session ended");
    }

    @Test
    public void theLastThingACommandPrintedIsOnDiskWhenShutdownReturns() throws Exception {
        SessionLogger logger = SessionLogger.getInstance();

        logger.logCommandOutput("the-last-line-before-the-end");
        logger.shutdown();

        assertThat(written(logger)).contains("the-last-line-before-the-end");
    }

    @Test
    public void aBurstStillBeingWrittenIsFinishedRatherThanDropped() throws Exception {
        SessionLogger logger = SessionLogger.getInstance();

        for (int entry = 0; entry < BURST; entry++) {
            logger.logCommandOutput("burst-entry-" + entry);
        }
        logger.shutdown();

        String log = written(logger);
        for (int entry = 0; entry < BURST; entry++) {
            assertThat(log)
                    .as("entry %d of %d was queued before the end and must be in the file",
                        entry, BURST)
                    .contains("burst-entry-" + entry);
        }
    }

    @Test
    public void theStructuredLogEndsWhereTheReadableOneDoes() throws Exception {
        SessionLogger logger = SessionLogger.getInstance();

        logger.logCommandOutput("a-line-in-both-files");
        logger.shutdown();

        assertThat(structured(logger))
                .as("the two files are written from the same queue and must not disagree")
                .contains("a-line-in-both-files")
                .contains("Session ended");
    }
}
