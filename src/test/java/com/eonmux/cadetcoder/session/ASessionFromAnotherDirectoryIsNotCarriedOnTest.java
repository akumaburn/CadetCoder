package com.eonmux.cadetcoder.session;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * A run continues the session in {@code session.json} only in the directory the session was
 * started in.
 *
 * <p>{@code session.json} is one file shared by every directory the tool runs in, and the load used
 * to continue it from any of them: a first command in a second project inherited the first
 * project's conversation and its resume point. A run that was asked to carry a session on says so
 * with {@code --continue} or {@code --resume}, and that path is not tested here — it goes through
 * {@link SessionManager#loadSessionById(String)}, which asks nothing about the directory. What is
 * under test is the implicit load, which now starts a new session when the one on disk was started
 * somewhere else, and leaves the old one in the archive where an explicit resume finds it.</p>
 */
public class ASessionFromAnotherDirectoryIsNotCarriedOnTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private final ObjectMapper mapper = new ObjectMapper();

    private MockedStatic<ConfigManager> configMock;
    private TestOutputCapture           output;
    private Path                        baseDir;

    @Before
    public void setUp() throws Exception {
        resetSessionManager();

        baseDir = tempFolder.getRoot().toPath();
        Files.createDirectories(baseDir);

        ConfigManager mockConfigManager = mock(ConfigManager.class);
        Configuration mockConfig        = new Configuration();
        mockConfig.setBaseDir(tempFolder.getRoot().getAbsolutePath());
        when(mockConfigManager.getConfig()).thenReturn(mockConfig);

        configMock = mockStatic(ConfigManager.class);
        configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

        output = new TestOutputCapture();
    }

    @After
    public void tearDown() {
        if (output != null) {
            output.restore();
        }
        if (configMock != null) {
            configMock.close();
        }
        resetSessionManager();
    }

    @Test
    public void aSessionStartedSomewhereElseIsLeftInTheArchiveAndANewOneStartsHere() throws Exception {
        String leftId = writeSessionJson("session-left-behind",
                List.of("User: work from the other directory"), "/somewhere/else");

        SessionManager sessions = SessionManager.getInstance();

        assertThat(sessions.getCurrentSessionId()).isNotEqualTo(leftId);
        assertThat(sessions.getConversationHistory()).isEmpty();
        assertThat(sessions.getSessionState().getProject()).isEqualTo(ResumePoint.projectHere());

        Path archived = baseDir.resolve("sessions").resolve(leftId + ".json");
        assertThat(archived).exists();
        SessionState kept = mapper.readValue(archived.toFile(), SessionState.class);
        assertThat(kept.getConversationHistory())
                .containsExactly("User: work from the other directory");

        assertThat(output.getAllOutput()).contains("/somewhere/else");
    }

    @Test
    public void aSessionStartedHereIsContinued() throws Exception {
        String keptId = writeSessionJson("session-started-here",
                List.of("User: work from this directory"), ResumePoint.projectHere());

        SessionManager sessions = SessionManager.getInstance();

        assertThat(sessions.getCurrentSessionId()).isEqualTo(keptId);
        assertThat(sessions.getConversationHistory())
                .containsExactly("User: work from this directory");
        assertThat(sessions.getSessionState().getProject()).isEqualTo(ResumePoint.projectHere());
    }

    @Test
    public void aSessionThatRecordsNoDirectoryIsNotContinued() throws Exception {
        String unnamedId = writeSessionJson("session-without-a-directory",
                List.of("User: work from an older build"), null);

        SessionManager sessions = SessionManager.getInstance();

        assertThat(sessions.getCurrentSessionId()).isNotEqualTo(unnamedId);
        assertThat(baseDir.resolve("sessions").resolve(unnamedId + ".json")).exists();
    }

    @Test
    public void aSessionKnowsWhetherItWasStartedHere() {
        SessionState here = new SessionState();
        here.setProject(ResumePoint.projectHere());
        SessionState elsewhere = new SessionState();
        elsewhere.setProject("/somewhere/else");
        SessionState unnamed = new SessionState();

        assertThat(here.isHere()).isTrue();
        assertThat(elsewhere.isHere()).isFalse();
        assertThat(unnamed.isHere()).isFalse();
    }

    /** Writes {@code session.json} with a conversation and the directory it was started in. */
    private String writeSessionJson(String sessionId, List<String> history,
                                    String project) throws Exception {
        SessionState state = new SessionState();
        state.setSessionId(sessionId);
        state.setCreatedAt(System.currentTimeMillis());
        state.setLastModified(System.currentTimeMillis());
        state.setConversationHistory(new java.util.ArrayList<>(history));
        state.setProject(project);

        Path file = baseDir.resolve("session.json");
        mapper.writeValue(file.toFile(), state);
        return sessionId;
    }

    private void resetSessionManager() {
        try {
            java.lang.reflect.Field instance = SessionManager.class.getDeclaredField("instance");
            instance.setAccessible(true);
            instance.set(null, null);
        } catch (ReflectiveOperationException couldNotReset) {
            throw new IllegalStateException(couldNotReset);
        }
    }
}
