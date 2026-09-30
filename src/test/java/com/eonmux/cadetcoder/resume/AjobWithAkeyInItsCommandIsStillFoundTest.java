package com.eonmux.cadetcoder.resume;

import com.eonmux.cadetcoder.jobs.JobRegistry;
import com.eonmux.cadetcoder.session.ResumePoint;
import com.eonmux.cadetcoder.session.SessionManager;
import com.eonmux.cadetcoder.test.TestOutputCapture;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A job whose command holds a key is saved with the key redacted, and a resume still finds it.
 *
 * <p>The point keeps the redacted command, and the running job keeps the real one. Compared as
 * they are, the two never match, and the resumed run is told that a job still running stopped, so
 * it may start a second copy of it.</p>
 */
public class AjobWithAkeyInItsCommandIsStillFoundTest {

    private static final String KEY = "sk-live0123456789abcdef";

    private TestOutputCapture output;

    @Before
    public void setUp() {
        JobRegistry.clear();
        SessionManager.getInstance().clearResumePoint();
        output = new TestOutputCapture();
        output.startCapture();
    }

    @After
    public void tearDown() {
        output.stopCapture();
        JobRegistry.stopAll();
        JobRegistry.clear();
        SessionManager.getInstance().clearResumePoint();
    }

    @Test
    public void theSavedJobHasNoKeyAndIsStillReportedAsRunning() throws Exception {
        JobRegistry.start("API_KEY=" + KEY + " sleep 30", "the dev server",
                          new File(System.getProperty("user.dir")));
        try (ResumeScope scope = ResumeScope.open()) {
            scope.stopped(ResumePoint.chat(List.of("serve it"),
                                           new ResumePoint.Chat("serve it", List.of(), 0)));
        }

        ResumePoint point = SessionManager.getInstance().getResumePoint().orElseThrow();
        assertThat(point.jobs()).hasSize(1);
        assertThat(point.jobs().get(0).command()).doesNotContain(KEY);

        String briefing = ResumeBriefing.restore(point);
        assertThat(briefing).contains("is still running.").doesNotContain(KEY);
    }
}
