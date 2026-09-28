package com.eonmux.cadetcoder.session;

import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What happens when the session named on the command line will not open.
 *
 * <p>{@code --resume} stopped the run. {@code --continue} did not: it threw away what loading
 * returned and printed "Continuing session: &lt;id&gt;" underneath the error saying the session
 * could not be read, then did the work in whatever session was already current -- which is the one
 * outcome the flag exists to prevent. These tests hold both flags to the same answer.</p>
 */
public class ASessionAskedForByNameIsOpenedOrTheRunStopsTest {

    /** The id these tests name; which id it is has never been what is under test. */
    private static final String SESSION_ID = "session-20250101-120000";

    private SessionManager     sessions;
    private TestOutputCapture  output;

    @Before
    public void setUp() {
        sessions = mock(SessionManager.class);
        output   = new TestOutputCapture();
    }

    @After
    public void tearDown() {
        output.restore();
    }

    @Test
    public void aRunToldToContinueASessionThatWillNotOpenStopsInsteadOfUsingAnotherOne() {
        when(sessions.getMostRecentSession()).thenReturn(sessionNamed(SESSION_ID));
        when(sessions.loadSessionById(SESSION_ID)).thenReturn(false);

        int decision = SessionResumption.open(true, null, sessions);

        assertThat(decision).isEqualTo(SessionResumption.UNAVAILABLE);
    }

    @Test
    public void aRunToldToResumeASessionThatWillNotOpenStopsTheSameWay() {
        when(sessions.loadSessionById(SESSION_ID)).thenReturn(false);

        int decision = SessionResumption.open(false, SESSION_ID, sessions);

        assertThat(decision).isEqualTo(SessionResumption.UNAVAILABLE);
    }

    @Test
    public void aSessionThatOpensLetsTheRunCarryOn() {
        when(sessions.getMostRecentSession()).thenReturn(sessionNamed(SESSION_ID));
        when(sessions.loadSessionById(SESSION_ID)).thenReturn(true);

        assertThat(SessionResumption.open(true, null, sessions)).isEqualTo(SessionResumption.CARRY_ON);
    }

    @Test
    public void aFirstRunWithNoSessionToContinueIsToldSoAndCarriesOn() {
        when(sessions.getMostRecentSession()).thenReturn(null);

        int decision = SessionResumption.open(true, null, sessions);

        assertThat(decision).isEqualTo(SessionResumption.CARRY_ON);
        assertThat(output.getOutput()).contains("No recent session found");
        verify(sessions, never()).loadSessionById(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    public void aRunThatAskedForNoSessionOpensNone() {
        assertThat(SessionResumption.open(false, null, sessions)).isEqualTo(SessionResumption.CARRY_ON);

        verify(sessions, never()).getMostRecentSession();
        verify(sessions, never()).loadSessionById(org.mockito.ArgumentMatchers.anyString());
    }

    /** A session that exists as far as these tests are concerned. */
    private static SessionState sessionNamed(String id) {
        SessionState state = new SessionState();
        state.setSessionId(id);
        return state;
    }
}
