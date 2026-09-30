package com.eonmux.cadetcoder.session;

import com.eonmux.cadetcoder.ai.PromptData;
import com.eonmux.cadetcoder.commands.ResumeCommand;
import com.eonmux.cadetcoder.test.StubbedProvider;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import com.eonmux.cadetcoder.timers.AgentTimer;
import com.eonmux.cadetcoder.timers.TimerRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The resume point is kept in the session, so it survives a restart, {@code --continue} and
 * {@code session resume}.
 *
 * <p>What a run left in the background does not survive a restart: CadetCoder stops every job
 * when it exits, and timers live only in memory. A run resumed in a new process is told its jobs
 * stopped, and its timers are set again.</p>
 */
public class AresumePointOutlivesTheProcessTest {

    private static final ResumePoint.Chat TIDYING =
            new ResumePoint.Chat("tidy the notes", List.of("System: read the notes"), 1);

    private TestOutputCapture output;

    @Before
    public void setUp() {
        SessionManager.getInstance().clearResumePoint();
        TimerRegistry.clearAll();
        output = new TestOutputCapture();
        output.startCapture();
    }

    @After
    public void tearDown() {
        output.stopCapture();
        TimerRegistry.clearAll();
        SessionManager.getInstance().clearResumePoint();
    }

    /** A point saved by a CadetCoder process that has since exited. */
    private static ResumePoint fromAnEarlierProcess() {
        return new ResumePoint(ResumePoint.CHAT, System.getProperty("user.dir"),
                               System.currentTimeMillis(), "a process that has exited",
                               List.of("tidy the notes"), TIDYING, null, null, List.of(),
                               List.of(new ResumePoint.Job("j4", "npm run dev", "the dev server")),
                               List.of(new ResumePoint.Timer("check the build", 300, 2)));
    }

    @Test
    public void theSessionFileKeepsTheResumePoint() throws Exception {
        ResumePoint  point = fromAnEarlierProcess();
        SessionState state = new SessionState();
        state.setResumePoint(point);
        ObjectMapper mapper = new ObjectMapper();

        SessionState read = mapper.readValue(mapper.writeValueAsString(state), SessionState.class);

        assertThat(read.getResumePoint()).isEqualTo(point);
    }

    @Test
    public void aSessionWrittenBeforeResumePointsExistedHasNone() throws Exception {
        SessionState read = new ObjectMapper().readValue("{\"sessionId\":\"x\"}", SessionState.class);

        assertThat(read.getResumePoint()).isNull();
    }

    @Test
    public void afterARestartTheTimersAreSetAgainAndTheJobsAreReportedStopped() {
        SessionManager.getInstance().setResumePoint(fromAnEarlierProcess());

        try (StubbedProvider resumed = StubbedProvider.answering("SUCCESS: tidy")) {
            new ResumeCommand().execute(new String[0]);

            String first = resumed.asked().stream().map(PromptData::getUserPrompt)
                                  .findFirst().orElseThrow();
            assertThat(first).contains("npm run dev")
                             .contains("stopped when CadetCoder exited")
                             .contains("check the build");
        }
        assertThat(TimerRegistry.active()).singleElement().satisfies(timer -> {
            assertThat(timer.instruction()).isEqualTo("check the build");
            assertThat(timer.interval()).isEqualTo(Duration.ofSeconds(300));
            assertThat(timer.limit()).isEqualTo(2);
        });
    }

    @Test
    public void inTheSameProcessTheTimersAreStillSetAndAreNotSetTwice() {
        TimerRegistry.create("check the build", Duration.ofMinutes(5), 2);
        SessionManager.getInstance().setResumePoint(
                ResumePoint.chat(List.of("tidy the notes"), TIDYING)
                           .withTimers(List.of(new ResumePoint.Timer("check the build", 300, 2))));

        try (StubbedProvider ignored = StubbedProvider.answering("SUCCESS: tidy")) {
            new ResumeCommand().execute(new String[0]);
        }

        assertThat(TimerRegistry.active()).hasSize(1);
    }

    @Test
    public void aSaveRecordsTheTimersAsTheyStandNow() {
        SessionManager.getInstance().setResumePoint(
                ResumePoint.chat(List.of("tidy the notes"), TIDYING));
        TimerRegistry.create("check the build", Duration.ofMinutes(5), AgentTimer.UNLIMITED);

        SessionManager.getInstance().saveSession();

        assertThat(SessionManager.getInstance().getResumePoint().orElseThrow().timers())
                .containsExactly(new ResumePoint.Timer("check the build", 300, 0));
    }

    @Test
    public void aSessionOpenedAgainHasItsTimersSetAgainOnResume() {
        SessionManager sessions = SessionManager.getInstance();
        sessions.setResumePoint(ResumePoint.chat(List.of("tidy the notes"), TIDYING));
        TimerRegistry.create("check the build", Duration.ofMinutes(5), AgentTimer.UNLIMITED);
        String left = sessions.getCurrentSessionId();

        try {
            sessions.startNewSession();
            assertThat(sessions.getResumePoint()).as("a new session has nothing to resume")
                                                 .isEmpty();
            assertThat(sessions.loadSessionById(left)).isTrue();

            assertThat(TimerRegistry.active()).as("leaving a session stops its timers").isEmpty();
            assertThat(sessions.getResumePoint().orElseThrow().timersAreSet())
                    .as("so its resume point has to set them again")
                    .isFalse();
        } finally {
            // A fresh session, so no later test runs inside the one this test opened again.
            sessions.startNewSession();
        }
    }
}
