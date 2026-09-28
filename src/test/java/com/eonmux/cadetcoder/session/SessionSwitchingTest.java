package com.eonmux.cadetcoder.session;

import com.eonmux.cadetcoder.commands.ModelDispatch;
import com.eonmux.cadetcoder.commands.SessionCommand;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
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
 * Ending one session and opening another.
 *
 * <p>Sessions were archived, capped, pruned and reopenable by identifier, and none of that was
 * reachable: nothing listed them, so {@code --resume &lt;id&gt;} wanted an identifier nobody could
 * learn, and there was no way to draw a line under a conversation that a resumed run then spends a
 * quarter of its context window replaying.</p>
 */
public class SessionSwitchingTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private MockedStatic<ConfigManager> configMock;
    private Path                        baseDir;

    @Before
    public void setUp() throws Exception {
        resetSessionManager();
        baseDir = tempFolder.getRoot().toPath();
        Files.createDirectories(baseDir.resolve("sessions"));

        ConfigManager mockConfigManager = mock(ConfigManager.class);
        Configuration mockConfig        = new Configuration();
        mockConfig.setBaseDir(baseDir.toString());
        when(mockConfigManager.getConfig()).thenReturn(mockConfig);

        configMock = mockStatic(ConfigManager.class);
        configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
    }

    @After
    public void tearDown() throws Exception {
        SessionManager.getInstance().setTranscriptSource(null);
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

    private static TranscriptEntry region(String title, String line) {
        return new TranscriptEntry("COMMAND", 0, title, "OK", List.of(line));
    }

    @Test
    public void aNewSessionStartsEmptyAndLeavesTheOldOneWhereItWas() {
        SessionManager sessions = SessionManager.getInstance();
        sessions.addUserRequest("the first conversation");
        String first = sessions.getCurrentSessionId();

        String second = sessions.startNewSession();

        assertThat(second).isNotEqualTo(first);
        assertThat(sessions.getConversationHistory()).isEmpty();
        assertThat(sessions.getTranscript()).isEmpty();
        assertThat(sessions.isResumed()).isFalse();

        // The point of a bound archive is that leaving a session does not lose it.
        assertThat(baseDir.resolve("sessions").resolve(first + ".json")).exists();
        assertThat(sessions.listRecentSessions(10))
                .extracting(SessionState::getSessionId)
                .contains(first, second);
    }

    @Test
    public void theSessionBeingLeftKeepsItsScrollback() {
        SessionManager sessions = SessionManager.getInstance();
        sessions.setTranscriptSource(() -> List.of(region("ls src", "Session.java")));
        sessions.addUserRequest("what is in src");
        String first = sessions.getCurrentSessionId();

        sessions.startNewSession();

        assertThat(sessions.loadSessionById(first)).isTrue();
        assertThat(sessions.getTranscript())
                .extracting(TranscriptEntry::getTitle)
                .containsExactly("ls src");
    }

    @Test
    public void reopeningASessionDoesNotWriteTheCurrentScreenIntoIt() {
        SessionManager sessions = SessionManager.getInstance();

        // A session with a scrollback of its own, then left.
        sessions.setTranscriptSource(() -> List.of(region("grep TODO", "found 3")));
        sessions.addUserRequest("find the todos");
        String earlier = sessions.getCurrentSessionId();
        sessions.startNewSession();

        // The shell is now showing something else entirely.
        sessions.setTranscriptSource(() -> List.of(region("git status", "clean")));
        sessions.addUserRequest("what changed");
        sessions.saveSession();

        assertThat(sessions.loadSessionById(earlier)).isTrue();

        // Reopening used to save on the way in, which pulled the shell's CURRENT scrollback and
        // wrote it over the one the reopened session was opened to show.
        assertThat(sessions.getTranscript())
                .extracting(TranscriptEntry::getTitle)
                .as("the reopened session shows its own screen, not the one being left")
                .containsExactly("grep TODO");
        assertThat(sessions.getConversationHistory()).containsExactly("User: find the todos");
        assertThat(sessions.isResumed()).isTrue();
    }

    @Test
    public void sessionsStartedInTheSameMillisecondAreStillDistinct() {
        // The identifier is the archive's file name, so two sessions sharing one means the second
        // writes over the first -- and the whole point of `session new` is that what you leave is
        // kept. A millisecond used to be enough by accident, because a session was minted about
        // once per install; it stopped being enough the moment a person could ask for one.
        SessionManager sessions = SessionManager.getInstance();
        List<String>   ids      = new java.util.ArrayList<>();
        ids.add(sessions.getCurrentSessionId());
        for (int i = 0; i < 25; i++) {
            ids.add(sessions.startNewSession());
        }

        assertThat(ids).doesNotHaveDuplicates();
        for (String id : ids) {
            assertThat(baseDir.resolve("sessions").resolve(id + ".json"))
                    .as("every session that was left is still on disk")
                    .exists();
        }
    }

    @Test
    public void theListingMarksTheSessionYouAreIn() {
        SessionManager sessions = SessionManager.getInstance();
        sessions.addUserRequest("older work");
        String older = sessions.getCurrentSessionId();
        String now   = sessions.startNewSession();

        List<SessionState> recent = sessions.listRecentSessions(10);

        assertThat(recent).extracting(SessionState::getSessionId).contains(older, now);
        assertThat(sessions.getCurrentSessionId()).isEqualTo(now);
    }

    @Test
    public void statusAndListAreReadOnly() {
        SessionManager sessions = SessionManager.getInstance();
        sessions.addUserRequest("keep me");
        String before = sessions.getCurrentSessionId();

        assertThat(new SessionCommand().execute(new String[0])).isZero();
        assertThat(new SessionCommand().execute(new String[]{"list"})).isZero();

        assertThat(sessions.getCurrentSessionId()).isEqualTo(before);
        assertThat(sessions.getConversationHistory()).containsExactly("User: keep me");
    }

    @Test
    public void aModelCannotEndTheUsersSession() {
        SessionManager sessions = SessionManager.getInstance();
        sessions.addUserRequest("the user's conversation");
        String before = sessions.getCurrentSessionId();

        // The same class of decision as `quit`: about the session rather than about the work, and
        // the model discarding it would discard its own context and the user's with it.
        int newCode    = ModelDispatch.run("session", () -> new SessionCommand().execute(new String[]{"new"}));
        int resumeCode = ModelDispatch.run("session",
                () -> new SessionCommand().execute(new String[]{"resume", before}));

        assertThat(newCode).isNotZero();
        assertThat(resumeCode).isNotZero();
        assertThat(sessions.getCurrentSessionId()).isEqualTo(before);
        assertThat(sessions.getConversationHistory()).containsExactly("User: the user's conversation");
    }

    @Test
    public void resumingWithoutAnIdentifierSaysSoRatherThanGuessing() {
        assertThat(new SessionCommand().execute(new String[]{"resume"})).isNotZero();
        assertThat(new SessionCommand().execute(new String[]{"resume", "  "})).isNotZero();
        assertThat(new SessionCommand().execute(new String[]{"resume", "no-such-session"}))
                .isNotZero();
    }

    @Test
    public void anUnknownActionIsRejected() {
        assertThat(new SessionCommand().execute(new String[]{"destroy"})).isNotZero();
        assertThat(new SessionCommand().execute(new String[]{"list", "not-a-number"})).isNotZero();
    }
}
