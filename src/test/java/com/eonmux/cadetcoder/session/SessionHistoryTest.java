package com.eonmux.cadetcoder.session;

import com.eonmux.cadetcoder.commands.TodoReadCommand;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for the session history directory.
 * <p>
 * The history directory used to be shared with {@link com.eonmux.cadetcoder.logging.SessionLogger},
 * whose structured logs use an incompatible schema. A single such file made
 * {@link SessionManager#getMostRecentSession()} abort, which broke {@code --continue} and
 * {@code --resume} outright. The directory also grew without any retention.
 */
public class SessionHistoryTest {

    /** A structured session log exactly as SessionLogger used to write it into sessions/. */
    private static final String FOREIGN_SESSION_LOG_JSON =
            "{\n"
            + "  \"logType\" : \"session\",\n"
            + "  \"version\" : \"1.0\",\n"
            + "  \"sessionId\" : \"session-foreign\",\n"
            + "  \"startTime\" : 1750000000000,\n"
            + "  \"startTimeFormatted\" : \"2025-06-15 12:00:00.000\",\n"
            + "  \"entries\" : [ ]\n"
            + "}\n";

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private final ObjectMapper mapper = new ObjectMapper();

    private MockedStatic<ConfigManager> configMock;
    private Path                        historyDir;

    @Before
    public void setUp() throws Exception {
        resetSessionManager();

        historyDir = tempFolder.getRoot().toPath().resolve("sessions");
        Files.createDirectories(historyDir);

        ConfigManager mockConfigManager = mock(ConfigManager.class);
        Configuration mockConfig        = new Configuration();
        mockConfig.setBaseDir(tempFolder.getRoot().getAbsolutePath());
        when(mockConfigManager.getConfig()).thenReturn(mockConfig);

        configMock = mockStatic(ConfigManager.class);
        configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
    }

    @After
    public void tearDown() throws Exception {
        if (configMock != null) {
            configMock.close();
        }
        resetSessionManager();
    }

    private void resetSessionManager() throws Exception {
        java.lang.reflect.Field instance = SessionManager.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    /**
     * Writes a genuine session archive with a controlled modification time.
     */
    private Path writeSessionArchive(String sessionId, long lastModified) throws Exception {
        SessionState state = new SessionState();
        state.setSessionId(sessionId);
        state.setCreatedAt(lastModified);
        state.setLastModified(lastModified);
        state.setConversationHistory(new ArrayList<>(List.of("marker:" + sessionId)));

        Path file = historyDir.resolve(sessionId + ".json");
        mapper.writeValue(file.toFile(), state);
        assertThat(file.toFile().setLastModified(lastModified)).isTrue();
        return file;
    }

    private Path writeForeignSessionLog(String fileName, long lastModified) throws Exception {
        Path file = historyDir.resolve(fileName);
        Files.writeString(file, FOREIGN_SESSION_LOG_JSON);
        assertThat(file.toFile().setLastModified(lastModified)).isTrue();
        return file;
    }

    // ---------------------------------------------------------------- defect 1

    /**
     * The reported failure: a SessionLogger JSON that is newer than every real session used
     * to be selected by {@code getMostRecentSession()}, throw on the unknown "logType"
     * property, and degrade to "No recent session found". It must now be skipped.
     */
    @Test
    public void testGetMostRecentSession_SkipsForeignLogAndReturnsRealSession() throws Exception {
        writeSessionArchive("session-1000", 1_000_000L);
        // Foreign file is the NEWEST entry in the directory - the old code picked it first.
        writeForeignSessionLog("session-1000_20250615_120000.json", 9_000_000L);

        SessionState recent = SessionManager.getInstance().getMostRecentSession();

        assertThat(recent).isNotNull();
        assertThat(recent.getSessionId()).isEqualTo("session-1000");
        assertThat(recent.getConversationHistory()).containsExactly("marker:session-1000");
    }

    /**
     * A truncated / corrupted archive must not stop the scan either.
     */
    @Test
    public void testGetMostRecentSession_SkipsCorruptAndEmptyFiles() throws Exception {
        writeSessionArchive("session-1000", 1_000_000L);

        Path corrupt = historyDir.resolve("session-corrupt.json");
        Files.writeString(corrupt, "{ this is not json");
        assertThat(corrupt.toFile().setLastModified(8_000_000L)).isTrue();

        Path empty = historyDir.resolve("session-empty.json");
        Files.writeString(empty, "");
        assertThat(empty.toFile().setLastModified(9_000_000L)).isTrue();

        SessionState recent = SessionManager.getInstance().getMostRecentSession();

        assertThat(recent).isNotNull();
        assertThat(recent.getSessionId()).isEqualTo("session-1000");
    }

    /**
     * Among several genuine sessions the most recently modified one wins, deterministically.
     */
    @Test
    public void testGetMostRecentSession_MostRecentWins() throws Exception {
        writeSessionArchive("session-oldest", 1_000_000L);
        writeSessionArchive("session-newest", 3_000_000L);
        writeSessionArchive("session-middle", 2_000_000L);
        writeForeignSessionLog("session-newest_20250615_120000.json", 4_000_000L);

        SessionState recent = SessionManager.getInstance().getMostRecentSession();

        assertThat(recent).isNotNull();
        assertThat(recent.getSessionId()).isEqualTo("session-newest");
    }

    /**
     * Listing must return real sessions newest-first and keep filling up to the requested
     * limit across foreign entries instead of losing a slot to each of them.
     */
    @Test
    public void testListRecentSessions_SkipsForeignEntriesAndKeepsOrder() throws Exception {
        writeSessionArchive("session-a", 1_000_000L);
        writeSessionArchive("session-b", 2_000_000L);
        writeSessionArchive("session-c", 3_000_000L);
        writeForeignSessionLog("session-c_20250615_120000.json", 3_500_000L);
        writeForeignSessionLog("session-b_20250615_110000.json", 2_500_000L);

        List<SessionState> sessions = SessionManager.getInstance().listRecentSessions(3);

        assertThat(sessions).extracting(SessionState::getSessionId)
                            .containsExactly("session-c", "session-b", "session-a");
    }

    @Test
    public void testListRecentSessions_RejectsNonPositiveLimit() throws Exception {
        writeSessionArchive("session-a", 1_000_000L);

        assertThat(SessionManager.getInstance().listRecentSessions(0)).isEmpty();
        assertThat(SessionManager.getInstance().listRecentSessions(-5)).isEmpty();
    }

    /**
     * End-to-end restore path used by {@code cadet -c}: the current session is archived on
     * save, and a fresh manager can find it and load it back with its contents intact.
     */
    @Test
    public void testSaveSession_ArchivesSessionSoContinueCanRestoreIt() throws Exception {
        SessionManager manager = SessionManager.getInstance();
        manager.addToConversationHistory("User: remember this");
        manager.setTodoList(List.of(new TodoReadCommand.TodoItem("t1", "REMEMBER-THIS-TASK",
                TodoReadCommand.TodoItem.Status.PENDING,
                TodoReadCommand.TodoItem.Priority.HIGH)));
        manager.saveSession();

        String archivedId = manager.getCurrentSessionId();
        assertThat(historyDir.resolve(archivedId + ".json")).exists();

        // A foreign log alongside it must not disturb the restore.
        writeForeignSessionLog(archivedId + "_20250615_120000.json", System.currentTimeMillis() + 60_000L);

        // Simulate a brand new process whose current session.json is gone.
        Files.deleteIfExists(tempFolder.getRoot().toPath().resolve("session.json"));
        resetSessionManager();

        SessionManager restarted = SessionManager.getInstance();
        SessionState   recent    = restarted.getMostRecentSession();
        assertThat(recent).isNotNull();
        assertThat(recent.getSessionId()).isEqualTo(archivedId);

        assertThat(restarted.loadSessionById(recent.getSessionId())).isTrue();
        assertThat(restarted.getConversationHistory()).contains("User: remember this");
        assertThat(restarted.getTodoList()).extracting(TodoReadCommand.TodoItem::getContent)
                                           .containsExactly("REMEMBER-THIS-TASK");
    }

    @Test
    public void testLoadSessionById_RejectsBlankIdentifier() {
        SessionManager manager = SessionManager.getInstance();
        assertThat(manager.loadSessionById(null)).isFalse();
        assertThat(manager.loadSessionById("   ")).isFalse();
    }

    // ---------------------------------------------------------------- defect 2

    /**
     * The history directory is capped: saving prunes oldest-first down to
     * {@link SessionManager#MAX_SESSION_HISTORY_FILES}.
     */
    @Test
    public void testSaveSession_EnforcesRetentionCapOldestFirst() throws Exception {
        int overflow = 12;
        for (int i = 0; i < SessionManager.MAX_SESSION_HISTORY_FILES + overflow; i++) {
            // Older index -> older modification time.
            writeSessionArchive(String.format("session-%03d", i), 1_000_000L + i * 1_000L);
        }

        SessionManager manager = SessionManager.getInstance();
        manager.saveSessionToHistory();

        List<String> remaining = listHistoryJsonNames();
        assertThat(remaining).hasSize(SessionManager.MAX_SESSION_HISTORY_FILES);

        // 62 archives + the freshly written active one = 63 candidates; the 13 oldest go.
        assertThat(remaining).doesNotContain("session-000.json", "session-006.json", "session-012.json");
        assertThat(remaining).contains("session-013.json", "session-061.json");
        assertThat(remaining).contains(manager.getCurrentSessionId() + ".json");
    }

    /**
     * The active session must survive pruning even when it is the oldest file in the
     * directory.
     */
    @Test
    public void testPrune_NeverDeletesActiveSession() throws Exception {
        long future = System.currentTimeMillis() + 3_600_000L;
        for (int i = 0; i < SessionManager.MAX_SESSION_HISTORY_FILES + 5; i++) {
            writeSessionArchive(String.format("session-%03d", i), future + i * 1_000L);
        }

        SessionManager manager   = SessionManager.getInstance();
        String         activeId  = manager.getCurrentSessionId();
        manager.saveSessionToHistory(); // active archive gets "now" -> the OLDEST file

        // Pruning removed the surplus but deliberately spared the active session, so exactly
        // one file beyond the cap remains rather than the active session being destroyed.
        List<String> remaining = listHistoryJsonNames();
        assertThat(remaining).contains(activeId + ".json");
        assertThat(remaining).hasSize(SessionManager.MAX_SESSION_HISTORY_FILES + 1);
        assertThat(remaining).doesNotContain("session-000.json", "session-004.json");
        assertThat(remaining).contains("session-005.json", "session-054.json");
    }

    /**
     * Pruning must only remove documents this component owns. Files it cannot read back as a
     * session belong to someone else and are left in place rather than destroyed.
     */
    @Test
    public void testPrune_LeavesForeignFilesAlone() throws Exception {
        long base = 1_000_000L;
        for (int i = 0; i < SessionManager.MAX_SESSION_HISTORY_FILES + 5; i++) {
            writeSessionArchive(String.format("session-%03d", i), base + i * 1_000L);
        }
        // Oldest entries in the directory, i.e. squarely inside the prune window.
        writeForeignSessionLog("legacy-log-1.json", 1L);
        writeForeignSessionLog("legacy-log-2.json", 2L);

        SessionManager.getInstance().saveSessionToHistory();

        List<String> remaining = listHistoryJsonNames();
        assertThat(remaining).contains("legacy-log-1.json", "legacy-log-2.json");
    }

    private List<String> listHistoryJsonNames() {
        String[] names = historyDir.toFile().list((dir, name) -> name.endsWith(".json")
                                                                && new File(dir, name).isFile());
        assertThat(names).isNotNull();
        return List.of(names);
    }
}
