package com.eonmux.cadetcoder.logging;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.MockitoJUnitRunner;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * {@code logging.level} decides what reaches the log file.
 *
 * <p><b>The defect</b>: the setting existed, was documented as "DEBUG, INFO, WARN, ERROR",
 * defaulted to INFO, was settable from {@code /config} -- and nothing anywhere read it. Every line
 * every logger produced went to the file whatever it said, so a user who set
 * {@code logging.level ERROR} to quieten a noisy log was told the setting was saved and got exactly
 * the same log as before.</p>
 */
@RunWith (MockitoJUnitRunner.Silent.class)
public class TheConfiguredLevelDecidesWhatIsRecordedTest {

    @Mock private ConfigManager               mockConfigManager;
    @Mock private Configuration               mockConfiguration;
    @Mock private Configuration.LoggingConfig mockLoggingConfig;

    private Path                        logDir;
    private MockedStatic<ConfigManager> configManagerMock;

    @Before
    public void setUp() throws Exception {
        logDir = Paths.get(System.getProperty("java.io.tmpdir"), "cadet-log-level-test");
        deleteTree(logDir);
        Files.createDirectories(logDir);

        configManagerMock = mockStatic(ConfigManager.class);
        configManagerMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
        when(mockConfigManager.getConfig()).thenReturn(mockConfiguration);
        when(mockConfiguration.getLogging()).thenReturn(mockLoggingConfig);
        when(mockConfiguration.getBaseDir()).thenReturn(logDir.toString());
        when(mockLoggingConfig.getLogFile()).thenReturn("cadet.log");
        when(mockLoggingConfig.getMaxLogSize()).thenReturn(10);
        when(mockLoggingConfig.getMaxLogFiles()).thenReturn(5);
    }

    @After
    public void tearDown() throws Exception {
        if (configManagerMock != null) {
            configManagerMock.close();
        }
        CadetLogger.reopenLogFile();

        java.lang.reflect.Field cache = CadetLogger.class.getDeclaredField("loggerCache");
        cache.setAccessible(true);
        ((java.util.Map<?, ?>) cache.get(null)).clear();

        deleteTree(logDir);
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        Files.walk(root).sorted((a, b) -> -a.compareTo(b)).map(Path::toFile).forEach(File::delete);
    }

    /** Logs one line at each level and returns what the file says once the queue has settled. */
    private String logAtEveryLevelWith(String configuredLevel) throws Exception {
        when(mockLoggingConfig.getLevel()).thenReturn(configuredLevel);
        CadetLogger.reopenLogFile();

        CadetLogger logger = CadetLogger.getLogger("LevelTest");
        logger.trace("a-trace-line");
        logger.debug("a-debug-line");
        logger.infoToFile("an-info-line");
        logger.warnToFile("a-warn-line");
        logger.errorToFile("an-error-line");

        Path   file     = logDir.resolve("cadet.log");
        long   deadline = System.currentTimeMillis() + 10_000;
        String written  = "";
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
            written = Files.exists(file) ? Files.readString(file) : "";
            if (written.contains("an-error-line")) {
                return written;
            }
        }
        return written;
    }

    @Test
    public void atWarnOnlyWarningsAndErrorsAreWritten() throws Exception {
        String written = logAtEveryLevelWith("WARN");

        assertThat(written).contains("a-warn-line").contains("an-error-line");
        assertThat(written)
                .as("everything below the configured level is not recorded")
                .doesNotContain("a-trace-line")
                .doesNotContain("a-debug-line")
                .doesNotContain("an-info-line");
    }

    @Test
    public void atDebugEverythingFromDebugUpIsWritten() throws Exception {
        String written = logAtEveryLevelWith("DEBUG");

        assertThat(written)
                .contains("a-debug-line")
                .contains("an-info-line")
                .contains("a-warn-line")
                .contains("an-error-line");
        assertThat(written)
                .as("TRACE is below DEBUG")
                .doesNotContain("a-trace-line");
    }

    /** An unreadable setting leaves logging as it was rather than silencing it. */
    @Test
    public void anUnrecognisedLevelRecordsEverythingFromTheDefaultUp() throws Exception {
        String written = logAtEveryLevelWith("not-a-level");

        assertThat(written).contains("an-info-line").contains("an-error-line");
    }
}
