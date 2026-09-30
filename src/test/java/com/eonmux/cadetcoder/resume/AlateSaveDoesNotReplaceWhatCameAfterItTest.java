package com.eonmux.cadetcoder.resume;

import com.eonmux.cadetcoder.session.ResumePoint;
import com.eonmux.cadetcoder.session.SessionManager;
import com.eonmux.cadetcoder.test.TestOutputCapture;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A run that saves its resume point after the user moved on saves nothing.
 *
 * <p>An interrupted command gets a short time to end, and the shell then takes the next command
 * while the old one may still be on its way out. Its save came after the new command started, and
 * put a stale point over the new command's own, or into a session the user had switched to.</p>
 */
public class AlateSaveDoesNotReplaceWhatCameAfterItTest {

    private TestOutputCapture output;

    @Before
    public void setUp() {
        SessionManager.getInstance().clearResumePoint();
        output = new TestOutputCapture();
        output.startCapture();
    }

    @After
    public void tearDown() {
        output.stopCapture();
        SessionManager.getInstance().clearResumePoint();
    }

    private static ResumePoint aChat(String request) {
        return ResumePoint.chat(List.of(request), new ResumePoint.Chat(request, List.of(), 0));
    }

    @Test
    public void aRunThatSavesAfterANewerRunStartedSavesNothing() {
        ResumeScope older = ResumeScope.open();
        older.close();
        try (ResumeScope newer = ResumeScope.open()) {
            newer.stopped(aChat("the newer run"));
        }

        older.stopped(aChat("the older run"));

        assertThat(SessionManager.getInstance().getResumePoint())
                .map(point -> point.chat().request())
                .contains("the newer run");
    }

    @Test
    public void aRunThatSavesAfterTheSessionChangedSavesNothing() {
        try (ResumeScope scope = ResumeScope.open()) {
            SessionManager.getInstance().startNewSession();
            scope.stopped(aChat("a run of the earlier session"));
        }

        assertThat(SessionManager.getInstance().getResumePoint()).isEmpty();
    }

    @Test
    public void aRunThatSavesInTimeSavesItsPoint() {
        try (ResumeScope scope = ResumeScope.open()) {
            scope.stopped(aChat("the run"));
        }

        assertThat(SessionManager.getInstance().getResumePoint()).isPresent();
    }
}
