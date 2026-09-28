package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ai.AIManager;
import com.eonmux.cadetcoder.ai.CommandApproval;
import com.eonmux.cadetcoder.ai.PromptData;
import com.eonmux.cadetcoder.jobs.BackgroundJob;
import com.eonmux.cadetcoder.jobs.JobNotice;
import com.eonmux.cadetcoder.jobs.JobRegistry;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.File;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Several background jobs are started, tracked and waited on together.
 *
 * <h2>The defect</h2>
 *
 * <p>Up to {@link JobRegistry#MAX_RUNNING} jobs could run at once, but nothing the model was told
 * said so. A run with independent experiments to do started one, waited on it, read it, and only
 * then started the next: two fits that could have run side by side took the time of both. Each
 * start cost a step, and each step read a quarter of a million tokens; the model had to ask
 * {@code job list} to learn what was still running; and {@code job wait} could only wait for the
 * first of several jobs, so waiting for a set of them was one wait per job.</p>
 */
class SeveralJobsRunAtOnceTest {

    private static final File HERE = new File(System.getProperty("user.dir"));

    private TestOutputCapture       output;
    private MockedStatic<AIManager> managers;

    @BeforeEach
    void setUp() throws Exception {
        JobRegistry.clear();
        System.setProperty(CommandApproval.PROPERTY, "auto");
        AIManager model = mock(AIManager.class);
        when(model.complete(any(PromptData.class))).thenReturn("ALLOW: it only sleeps.");
        managers = mockStatic(AIManager.class);
        managers.when(AIManager::getInstance).thenReturn(model);
        output = new TestOutputCapture();
        output.startCapture();
    }

    @AfterEach
    void tearDown() {
        output.stopCapture();
        managers.close();
        System.clearProperty(CommandApproval.PROPERTY);
        JobRegistry.stopAll();
        JobRegistry.clear();
    }

    private static int run(String rawLine) {
        return new JobCommand().execute(JobCommand.argvFor(rawLine, null).toArray(new String[0]));
    }

    @Test
    void oneActionWithAjobOnEachLineStartsThemAll() {
        int exit = run("start\n-d \"cap 60\" sleep 30\n-d \"cap 120\" sleep 30\n");

        assertThat(exit).isZero();
        assertThat(JobRegistry.running()).extracting(BackgroundJob::description)
                                         .containsExactly("cap 60", "cap 120");
        assertThat(output.getAllOutput()).contains("j1").contains("j2");
    }

    @Test
    void everyCommandOfSuchAnActionIsScreened() {
        String[] argv = JobCommand.argvFor("start\n-d a sleep 1\nsleep 2", null).toArray(new String[0]);

        assertThat(JobCommand.startedCommands(argv)).containsExactly("sleep 1", "sleep 2");
    }

    @Test
    void acommandOnOneLineIsStillOneJob() {
        assertThat(run("start -d \"the suite\" sleep 30")).isZero();

        assertThat(JobRegistry.running()).hasSize(1);
    }

    @Test
    void jobsBeyondTheLimitAreRefusedAndTheRestStillStart() {
        StringBuilder lines = new StringBuilder("start\n");
        for (int i = 0; i <= JobRegistry.MAX_RUNNING; i++) {
            lines.append("sleep 30\n");
        }

        int exit = run(lines.toString());

        assertThat(exit).isNotZero();
        assertThat(JobRegistry.running()).hasSize(JobRegistry.MAX_RUNNING);
        assertThat(output.getAllOutput()).contains(JobRegistry.MAX_RUNNING + " background jobs");
    }

    @Test
    void waitingForAllReturnsOnlyWhenEveryOneHasEnded() throws Exception {
        BackgroundJob quick = JobRegistry.start("sleep 0.2", null, HERE);
        BackgroundJob slow  = JobRegistry.start("sleep 1.5", null, HERE);

        int exit = new JobCommand().execute(new String[] {"wait", "--all", quick.id(), slow.id()});

        assertThat(exit).isZero();
        assertThat(quick.isDone()).isTrue();
        assertThat(slow.isDone()).isTrue();
    }

    @Test
    void waitingForAnyNamesWhatEndedAndWhatIsStillRunning() throws Exception {
        BackgroundJob quick = JobRegistry.start("sleep 0.1", null, HERE);
        BackgroundJob slow  = JobRegistry.start("sleep 30", null, HERE);
        quick.awaitEnd(Duration.ofSeconds(10));

        new JobCommand().execute(new String[] {"wait", quick.id(), slow.id()});

        assertThat(output.getAllOutput())
                .contains(quick.id() + " has finished")
                .contains(slow.id() + " is still running");
    }

    @Test
    void everyPromptListsTheJobsStillRunning() throws Exception {
        JobRegistry.start("sleep 30", "cap 60", HERE);
        JobRegistry.start("sleep 30", "cap 120", HERE);

        String status = JobNotice.stillRunning();

        assertThat(status).contains("j1").contains("cap 60").contains("j2").contains("cap 120");
        assertThat(status).contains(String.valueOf(JobRegistry.MAX_RUNNING));
    }

    @Test
    void jobsAreListedInTheOrderTheyStarted() throws Exception {
        for (int i = 0; i < JobRegistry.MAX_RUNNING; i++) {
            JobRegistry.start("sleep 30", null, HERE);
        }

        assertThat(JobRegistry.all()).extracting(BackgroundJob::id)
                                     .containsExactly("j1", "j2", "j3", "j4",
                                                      "j5", "j6", "j7", "j8");
    }

    @Test
    void nothingIsListedWhenNothingIsRunning() {
        assertThat(JobNotice.stillRunning()).isEmpty();
    }

    @Test
    void theLatestLineOfAjobIsQuotedWhole() throws Exception {
        String        line = "x".repeat(500);
        BackgroundJob job  = JobRegistry.start("echo " + line + "; sleep 30", null, HERE);
        for (int i = 0; i < 250 && job.output().produced() < 1; i++) {
            Thread.sleep(20);
        }

        assertThat(JobCommand.progress(job)).endsWith(line).doesNotContain("...");
    }
}
