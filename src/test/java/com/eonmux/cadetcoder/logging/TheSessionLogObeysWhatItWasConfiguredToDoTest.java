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
 * The two session-log settings that existed and did nothing.
 *
 * <p><b>The defect</b>: {@code logging.structuredLogging} is documented as "Enable JSON structured
 * logging" and {@code logging.maxSessionLogSize} as "MB per session log". Both were settable, both
 * had defaults, and no code anywhere read either of them. The JSON log was written whether it was
 * wanted or not, and a session log grew for as long as the session lasted -- a run that printed a
 * great deal wrote a file with no upper bound at all, beside a setting that said it had one.</p>
 */
public class TheSessionLogObeysWhatItWasConfiguredToDoTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Configuration               config;
    private MockedStatic<ConfigManager> configManager;

    @Before
    public void setUp() throws Exception {
        forgetSingletons();

        config = new Configuration();
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

    @Test
    public void theJsonLogIsWrittenWhenStructuredLoggingIsOn() throws Exception {
        config.getLogging().setStructuredLogging(true);

        SessionLogger logger = SessionLogger.getInstance();
        logger.logCommandOutput("a-line");
        logger.shutdown();

        assertThat(logger.getStructuredLogFile()).isNotNull();
        assertThat(Files.readString(logger.getStructuredLogFile(), StandardCharsets.UTF_8))
                .contains("a-line");
    }

    @Test
    public void theJsonLogIsNotWrittenWhenStructuredLoggingIsOff() throws Exception {
        config.getLogging().setStructuredLogging(false);

        SessionLogger logger = SessionLogger.getInstance();
        logger.logCommandOutput("a-line");
        logger.shutdown();

        assertThat(logger.getStructuredLogFile())
                .as("the setting says not to write one, so there is not one")
                .isNull();
        assertThat(Files.readString(logger.getSessionLogFile(), StandardCharsets.UTF_8))
                .as("the readable log is unaffected")
                .contains("a-line");
    }

    @Test
    public void thesessionLogStopsAtTheConfiguredSizeAndSaysSo() throws Exception {
        config.getLogging().setStructuredLogging(false);
        config.getLogging().setMaxSessionLogSize(1);

        SessionLogger logger = SessionLogger.getInstance();
        // A long line is shortened before it is recorded, so the volume comes from the number of
        // entries rather than from the length of any one of them.
        String padding = "y".repeat(200);
        for (int line = 0; line < 12_000; line++) {
            logger.logCommandOutput("line-" + line + " " + padding);
        }
        logger.shutdown();

        long size = Files.size(logger.getSessionLogFile());
        assertThat(size)
                .as("logging.maxSessionLogSize says one megabyte, so that is the bound")
                .isLessThanOrEqualTo(2L * 1024 * 1024);
        assertThat(Files.readString(logger.getSessionLogFile(), StandardCharsets.UTF_8))
                .as("a log that stopped recording says that it did")
                .contains("maxSessionLogSize");
    }
}
