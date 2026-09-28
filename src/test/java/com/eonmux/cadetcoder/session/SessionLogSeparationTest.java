package com.eonmux.cadetcoder.session;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.logging.SessionLogger;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for keeping {@link SessionLogger} artefacts out of the session-state
 * directory that {@link SessionManager} scans.
 * <p>
 * Both components used to write plain {@code .json} files into {@code <baseDir>/sessions/}
 * with incompatible schemas, which is what broke {@code --continue} / {@code --resume}. The
 * logger now writes into {@code <baseDir>/sessions/logs/} and relocates the files earlier
 * versions left behind. These tests live in the session package because what they protect is
 * the session-restore contract.
 */
public class SessionLogSeparationTest {

    private static final String FOREIGN_SESSION_LOG_JSON =
            "{\n"
            + "  \"logType\" : \"session\",\n"
            + "  \"version\" : \"1.0\",\n"
            + "  \"sessionId\" : \"session-legacy\",\n"
            + "  \"startTime\" : 1750000000000,\n"
            + "  \"entries\" : [ ]\n"
            + "}\n";

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private final ObjectMapper mapper = new ObjectMapper();

    private MockedStatic<ConfigManager> configMock;
    private Configuration               config;
    private Path                        sessionsDir;
    private Path                        logsDir;

    @Before
    public void setUp() throws Exception {
        resetSingletons();

        sessionsDir = tempFolder.getRoot().toPath().resolve("sessions");
        logsDir     = sessionsDir.resolve("logs");
        Files.createDirectories(sessionsDir);

        config = new Configuration();
        config.setBaseDir(tempFolder.getRoot().getAbsolutePath());

        ConfigManager mockConfigManager = mock(ConfigManager.class);
        when(mockConfigManager.getConfig()).thenReturn(config);

        configMock = mockStatic(ConfigManager.class);
        configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
    }

    @After
    public void tearDown() throws Exception {
        try {
            SessionLogger logger = currentSessionLogger();
            if (logger != null) {
                logger.shutdown();
            }
        } finally {
            if (configMock != null) {
                configMock.close();
            }
            resetSingletons();
        }
    }

    private SessionLogger currentSessionLogger() throws Exception {
        java.lang.reflect.Field instance = SessionLogger.class.getDeclaredField("instance");
        instance.setAccessible(true);
        return (SessionLogger) instance.get(null);
    }

    private void resetSingletons() throws Exception {
        java.lang.reflect.Field loggerInstance = SessionLogger.class.getDeclaredField("instance");
        loggerInstance.setAccessible(true);
        loggerInstance.set(null, null);

        java.lang.reflect.Field managerInstance = SessionManager.class.getDeclaredField("instance");
        managerInstance.setAccessible(true);
        managerInstance.set(null, null);
    }

    private Path writeSessionArchive(String sessionId, long lastModified) throws Exception {
        SessionState state = new SessionState();
        state.setSessionId(sessionId);
        state.setCreatedAt(lastModified);
        state.setLastModified(lastModified);
        state.setConversationHistory(new ArrayList<>(List.of("marker:" + sessionId)));

        Path file = sessionsDir.resolve(sessionId + ".json");
        mapper.writeValue(file.toFile(), state);
        assertThat(file.toFile().setLastModified(lastModified)).isTrue();
        return file;
    }

    private List<String> listJson(Path dir) {
        String[] names = dir.toFile().list((d, name) -> name.endsWith(".json"));
        return names == null ? List.of() : List.of(names);
    }

    private List<String> listLogs(Path dir) {
        String[] names = dir.toFile().list((d, name) -> name.endsWith(".log"));
        return names == null ? List.of() : List.of(names);
    }

    /**
     * New session logs must land in the dedicated sub-directory, leaving the session-state
     * directory free of foreign JSON.
     */
    @Test
    public void testSessionLogsAreWrittenToDedicatedSubdirectory() throws Exception {
        SessionLogger logger = SessionLogger.getInstance();
        assertThat(logger.isSessionLoggingEnabled()).isTrue();

        assertThat(logger.getSessionLogFile()).isNotNull();
        assertThat(logger.getSessionLogFile().getParent()).isEqualTo(logsDir);
        assertThat(logger.getStructuredLogFile().getParent()).isEqualTo(logsDir);
        assertThat(Files.exists(logger.getStructuredLogFile())).isTrue();

        // Nothing that SessionManager scans was created in the sessions directory itself.
        assertThat(listJson(sessionsDir)).isEmpty();
        assertThat(listLogs(sessionsDir)).isEmpty();
    }

    /**
     * Logs left in the sessions directory by earlier versions are migrated into the log
     * sub-directory, while genuine session archives stay exactly where they are, so a
     * pre-existing session still resumes.
     */
    @Test
    public void testLegacyLogsAreMigratedAndSessionArchivesAreLeftInPlace() throws Exception {
        writeSessionArchive("session-real", 1_000_000L);

        Path legacyJson = sessionsDir.resolve("session-legacy_20250615_120000.json");
        Files.writeString(legacyJson, FOREIGN_SESSION_LOG_JSON);
        Path legacyLog = sessionsDir.resolve("session-legacy_20250615_120000.log");
        Files.writeString(legacyLog, "[2025-06-15 12:00:00.000] SESSION: Session started\n");

        SessionLogger.getInstance();

        // The foreign documents moved out ...
        assertThat(Files.exists(legacyJson)).isFalse();
        assertThat(Files.exists(legacyLog)).isFalse();
        assertThat(listJson(logsDir)).contains("session-legacy_20250615_120000.json");
        assertThat(listLogs(logsDir)).contains("session-legacy_20250615_120000.log");

        // ... and the real session archive stayed put and is still restorable.
        assertThat(Files.exists(sessionsDir.resolve("session-real.json"))).isTrue();
        SessionState recent = SessionManager.getInstance().getMostRecentSession();
        assertThat(recent).isNotNull();
        assertThat(recent.getSessionId()).isEqualTo("session-real");
        assertThat(recent.getConversationHistory()).containsExactly("marker:session-real");
    }

    /**
     * Session logs are bounded by {@code logging.maxSessionLogs}: the oldest log sets are
     * removed and the log of the session being written is never touched.
     */
    @Test
    public void testSessionLogRetentionCapIsEnforced() throws Exception {
        config.getLogging().setMaxSessionLogs(3);
        Files.createDirectories(logsDir);

        for (int i = 0; i < 10; i++) {
            String stem = String.format("session-old-%02d_20250615_1200%02d", i, i);
            Path   log  = logsDir.resolve(stem + ".log");
            Path   json = logsDir.resolve(stem + ".json");
            Files.writeString(log, "old log " + i + "\n");
            Files.writeString(json, FOREIGN_SESSION_LOG_JSON);
            long stamp = 1_000_000L + i * 1_000L;
            assertThat(log.toFile().setLastModified(stamp)).isTrue();
            assertThat(json.toFile().setLastModified(stamp)).isTrue();
        }

        SessionLogger logger = SessionLogger.getInstance();

        List<String> remainingLogs = listLogs(logsDir);
        assertThat(remainingLogs).hasSize(3);
        // The current session's log survives ...
        assertThat(remainingLogs).contains(logger.getSessionLogFile().getFileName().toString());
        // ... together with the two newest pre-existing ones, and the oldest are gone.
        assertThat(remainingLogs).contains("session-old-09_20250615_120009.log",
                "session-old-08_20250615_120008.log");
        assertThat(remainingLogs).doesNotContain("session-old-00_20250615_120000.log",
                "session-old-07_20250615_120007.log");
        // The structured sibling of a pruned log goes with it.
        assertThat(listJson(logsDir)).doesNotContain("session-old-00_20250615_120000.json");
        assertThat(listJson(logsDir)).contains("session-old-09_20250615_120009.json");
    }

    /**
     * A retention value of zero or less must fall back to the built-in default instead of
     * deleting every log.
     */
    @Test
    public void testNonPositiveRetentionFallsBackToDefault() throws Exception {
        config.getLogging().setMaxSessionLogs(0);
        Files.createDirectories(logsDir);

        Path log = logsDir.resolve("session-keep_20250615_120000.log");
        Files.writeString(log, "keep me\n");
        assertThat(log.toFile().setLastModified(1_000_000L)).isTrue();

        SessionLogger.getInstance();

        assertThat(Files.exists(log)).isTrue();
    }
}
