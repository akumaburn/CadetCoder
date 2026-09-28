package com.eonmux.cadetcoder.logging;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.session.SessionManager;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * The files that hold the prompts are protected as carefully as the file that holds the API keys.
 *
 * <p><b>The defect</b>: {@code ConfigManager} creates {@code config.json} owner-only and
 * {@code SessionManager} does the same for {@code session.json}, both because of what is in them.
 * The logs were opened through a bare {@code FileWriter}, so they were created at whatever the
 * umask allowed -- world-readable on a great many machines -- and they hold the same material and
 * more: every line a command prints, the whole of every prompt, the whole of every request payload
 * and the contents of every file read along the way. The most revealing of the three files was the
 * only one any local account could read.</p>
 *
 * <p>Permissions are asked for at the moment of creation rather than narrowed afterwards, because a
 * umask can only take permissions away: there is then no window in which the file exists, holds a
 * prompt, and is readable by everyone.</p>
 */
public class AlogOfPromptsIsNoMoreReadableThanTheConfigIsTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Configuration               config;
    private MockedStatic<ConfigManager> configManager;

    @Before
    public void setUp() throws Exception {
        Assume.assumeTrue("POSIX permissions are what this locks",
                          Files.getFileStore(folder.getRoot().toPath())
                               .supportsFileAttributeView(PosixFileAttributeView.class));
        forgetSingletons();

        config = new Configuration();
        config.setBaseDir(folder.getRoot().getAbsolutePath());
        config.getLogging().setStructuredLogging(true);

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
            // The rotating writer and the logger cache are process-wide; put them back on the real
            // configured destination now that the mocked configuration has gone.
            CadetLogger.reopenLogFile();
            Field cache = CadetLogger.class.getDeclaredField("loggerCache");
            cache.setAccessible(true);
            ((java.util.Map<?, ?>) cache.get(null)).clear();
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

    private static void assertOwnerOnly(Path file) throws Exception {
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file);
        assertThat(permissions)
                .as("%s holds prompts, file contents and command output", file.getFileName())
                .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ,
                                           PosixFilePermission.OWNER_WRITE);
    }

    @Test
    public void bothSessionLogFilesAreReadableByTheirOwnerAlone() throws Exception {
        SessionLogger logger = SessionLogger.getInstance();
        logger.logAIRequest("Test", "a-model", "a prompt with a project's source in it");
        logger.shutdown();

        assertOwnerOnly(logger.getSessionLogFile());
        assertOwnerOnly(logger.getStructuredLogFile());
    }

    /** The rotating log the rest of the tool writes to carries the same material. */
    @Test
    public void theRotatingLogIsReadableByItsOwnerAlone() throws Exception {
        config.getLogging().setLogFile("cadet.log");
        config.getLogging().setLevel("INFO");

        CadetLogger.reopenLogFile();
        CadetLogger.getLogger("PermissionsCase").info("a line naming a file the run read");

        Path slot = folder.getRoot().toPath().resolve("cadet.log");
        for (int wait = 0; wait < 250 && !Files.exists(slot); wait++) {
            Thread.sleep(20);
        }

        assertThat(Files.exists(slot)).as("the log was written somewhere to check").isTrue();
        assertOwnerOnly(slot);
    }
}
