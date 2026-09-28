package com.eonmux.cadetcoder.logging;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.session.SessionManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * The structured session log exists to be read by something, and has to be bounded like the other.
 *
 * <p><b>Two defects</b>. The first: {@code logging.maxSessionLogSize} was checked before writing
 * the readable log and not before writing the JSON copy -- and the JSON copy is the larger of the
 * two, because it carries the whole prompt and the whole request payload where the readable log
 * carries a few hundred characters of each. The setting that says how big a session's logs may get
 * therefore bounded the small half and let the half that actually grows grow without limit.</p>
 *
 * <p>The second: the file was a pretty-printed header object followed by pretty-printed entry
 * objects, one after another with nothing joining them. That is not a JSON document, because there
 * is more than one value in it, and it is not JSON Lines, because each value spans many lines -- so
 * nothing could parse the file whose entire purpose is being parsed programmatically. It is written
 * one complete document per line, which also means a log that stops at its bound stops on a line
 * boundary and is readable to its last complete entry.</p>
 */
public class TheJsonSessionLogIsSomethingThatCanBeParsedTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Configuration               config;
    private MockedStatic<ConfigManager> configManager;

    @Before
    public void setUp() throws Exception {
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
    public void everyLineOfTheJsonLogIsAJsonDocument() throws Exception {
        SessionLogger logger = SessionLogger.getInstance();
        logger.logCommandOutput("a line of output");
        logger.logAIRequest("Test", "a-model", "a prompt");
        logger.shutdown();

        ObjectMapper mapper = new ObjectMapper();
        List<String> lines  = Files.readAllLines(logger.getStructuredLogFile(),
                                                 StandardCharsets.UTF_8)
                                   .stream().filter(line -> !line.isBlank()).toList();

        assertThat(lines).as("a header and at least the entries written above").hasSizeGreaterThan(2);
        for (String line : lines) {
            JsonNode parsed = mapper.readTree(line);
            assertThat(parsed.isObject())
                    .as("every line has to be a whole document: %s", line)
                    .isTrue();
        }
        assertThat(mapper.readTree(lines.get(0)).path("logType").asText())
                .as("the first line still identifies the file, which is how the migrator "
                    + "recognises a session log among the session archives")
                .isEqualTo("session");
    }

    @Test
    public void theJsonLogStopsAtTheConfiguredSizeAndSaysSo() throws Exception {
        config.getLogging().setMaxSessionLogSize(1);

        SessionLogger logger = SessionLogger.getInstance();
        // The JSON copy carries the whole prompt, so this is the file that actually grows.
        String prompt = "p".repeat(20_000);
        for (int request = 0; request < 200; request++) {
            logger.logAIRequest("Test", "a-model", request + " " + prompt);
        }
        logger.shutdown();

        assertThat(Files.size(logger.getStructuredLogFile()))
                .as("logging.maxSessionLogSize says one megabyte, and it is the JSON copy that "
                    + "carries the whole prompt")
                .isLessThanOrEqualTo(2L * 1024 * 1024);
        assertThat(Files.readString(logger.getStructuredLogFile(), StandardCharsets.UTF_8))
                .as("a log that stopped recording says that it did")
                .contains("maxSessionLogSize");
    }

    @Test
    public void ajsonLogThatStoppedAtItsBoundIsStillReadableToItsLastEntry() throws Exception {
        config.getLogging().setMaxSessionLogSize(1);

        SessionLogger logger = SessionLogger.getInstance();
        String prompt = "p".repeat(20_000);
        for (int request = 0; request < 200; request++) {
            logger.logAIRequest("Test", "a-model", request + " " + prompt);
        }
        logger.shutdown();

        ObjectMapper mapper = new ObjectMapper();
        for (String line : Files.readAllLines(logger.getStructuredLogFile(), StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                assertThat(mapper.readTree(line).isObject())
                        .as("stopping must not leave a half-written structure behind")
                        .isTrue();
            }
        }
    }
}
