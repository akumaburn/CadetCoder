package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.session.SessionManager;
import com.eonmux.cadetcoder.session.SessionState;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * {@code session} is how someone finds their way back to work they left. These tests cover the
 * four actions it offers and the two it refuses to take on a model's say-so.
 */
class SessionCommandTest {

    private SessionCommand              command;
    private TestOutputCapture           output;
    private MockedStatic<SessionManager> sessions;
    private SessionManager              manager;

    private static SessionState session(String id, String... history) {
        SessionState state = new SessionState();
        state.setSessionId(id);
        state.setCreatedAt(1_700_000_000_000L);
        state.setLastModified(1_700_000_600_000L);
        state.setConversationHistory(new java.util.ArrayList<>(List.of(history)));
        return state;
    }

    @BeforeEach
    void setUp() {
        command  = new SessionCommand();
        output   = new TestOutputCapture();
        manager  = mock(SessionManager.class);
        sessions = mockStatic(SessionManager.class);
        sessions.when(SessionManager::getInstance).thenReturn(manager);

        when(manager.getSessionState()).thenReturn(session("session-1"));
        when(manager.getConversationHistory()).thenReturn(new java.util.ArrayList<>());
        when(manager.getTranscript()).thenReturn(new java.util.ArrayList<>());
        when(manager.getTodoList()).thenReturn(new java.util.ArrayList<>());
        when(manager.getCurrentSessionId()).thenReturn("session-1");
    }

    @AfterEach
    void tearDown() {
        sessions.close();
        output.restore();
    }

    @Test
    void noArgumentMeansStatus() {
        assertThat(command.execute(new String[0])).isZero();
        assertThat(output.getAllOutput()).contains("Current Session").contains("session-1");
    }

    @Test
    void statusNamesTheSessionAndHowToLeaveIt() {
        assertThat(command.execute(new String[] {"status"})).isZero();

        String shown = output.getAllOutput();
        assertThat(shown).contains("session list").contains("session new");
    }

    @Test
    void anEmptyListSaysSoRatherThanShowingAnEmptyTable() {
        when(manager.listRecentSessions(anyInt())).thenReturn(List.of());

        assertThat(command.execute(new String[] {"list"})).isZero();
        assertThat(output.getAllOutput()).contains("No sessions have been archived yet");
    }

    @Test
    void aListingMarksTheCurrentSessionAndPreviewsTheOpeningLine() {
        when(manager.listRecentSessions(anyInt())).thenReturn(List.of(
                session("session-1", "User: fix the parser"),
                session("session-0", "User: write the tests")));

        assertThat(command.execute(new String[] {"list"})).isZero();

        String shown = output.getAllOutput();
        assertThat(shown).contains("fix the parser").contains("write the tests");
        assertThat(shown).contains("* session-1");
    }

    @Test
    void aSessionThatSaidNothingStillListsCleanly() {
        when(manager.listRecentSessions(anyInt())).thenReturn(List.of(session("session-9")));

        assertThat(command.execute(new String[] {"list"})).isZero();
        assertThat(output.getAllOutput()).contains("(nothing said)");
    }

    @Test
    void aCountThatIsNotANumberIsRefusedRatherThanIgnored() {
        assertThat(command.execute(new String[] {"list", "lots"})).isEqualTo(1);
        assertThat(output.getAllOutput()).contains("Not a number: lots");
        verify(manager, never()).listRecentSessions(anyInt());
    }

    @Test
    void aCountIsPassedThrough() {
        when(manager.listRecentSessions(3)).thenReturn(List.of());

        assertThat(command.execute(new String[] {"list", "3"})).isZero();
        verify(manager).listRecentSessions(3);
    }

    @Test
    void startingANewSessionKeepsTheOldOneAndSaysHowToReopenIt() {
        when(manager.getCurrentSessionId()).thenReturn("session-1");
        when(manager.startNewSession()).thenReturn("session-2");

        assertThat(command.execute(new String[] {"new"})).isZero();

        String shown = output.getAllOutput();
        assertThat(shown).contains("Started session session-2");
        assertThat(shown).contains("session resume session-1");
    }

    @Test
    void resumingWithoutAnIdAsksForOne() {
        assertThat(command.execute(new String[] {"resume"})).isEqualTo(1);
        assertThat(output.getAllOutput()).contains("Which session?");
        verify(manager, never()).loadSessionById(anyString());
    }

    @Test
    void resumingAnUnknownSessionFails() {
        when(manager.loadSessionById("session-7")).thenReturn(false);

        assertThat(command.execute(new String[] {"resume", "session-7"})).isEqualTo(1);
    }

    @Test
    void resumingAKnownSessionSucceeds() {
        when(manager.loadSessionById("session-7")).thenReturn(true);

        assertThat(command.execute(new String[] {"resume", "session-7"})).isZero();
    }

    @Test
    void anUnknownActionShowsTheUsage() {
        assertThat(command.execute(new String[] {"rewind"})).isEqualTo(1);

        String shown = output.getAllOutput();
        assertThat(shown).contains("Unknown action: rewind");
        assertThat(shown).contains(CommandUsage.render(command.getUsage()));
    }

    @Test
    void theModelMayNotStartOrSwitchSessionsOnTheUsersBehalf() {
        // Both discard the context the person is working in, so both are the person's call.
        int started = ModelDispatch.run("session", () -> command.execute(new String[] {"new"}));
        int resumed = ModelDispatch.run("session", () -> command.execute(new String[] {"resume", "session-7"}));

        assertThat(started).isEqualTo(1);
        assertThat(resumed).isEqualTo(1);
        assertThat(output.getAllOutput()).contains("the user's call, not the model's");
        verify(manager, never()).startNewSession();
        verify(manager, never()).loadSessionById(anyString());
    }
}
