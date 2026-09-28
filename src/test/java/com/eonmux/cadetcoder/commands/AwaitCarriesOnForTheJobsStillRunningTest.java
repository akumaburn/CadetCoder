package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.jobs.BackgroundJob;
import com.eonmux.cadetcoder.jobs.JobNotice;
import com.eonmux.cadetcoder.jobs.JobRegistry;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import com.eonmux.cadetcoder.timers.TimerScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A run waits for the jobs whose results it needs next, and can keep waiting for the rest.
 *
 * <h2>The defect</h2>
 *
 * <p>A run does not always need every job before it can go on: one result may be enough to start
 * the next piece of work while the others keep running. After the first ending, the natural next
 * step is to wait again on the same jobs. A wait that named a job whose ending had already been
 * reported returned at once, saying that job had finished, so waiting for the rest was impossible
 * without first working out which ids to leave out. Nothing said how to keep waiting, and a wait
 * with no ids also waited on jobs other agents had started.</p>
 */
class AwaitCarriesOnForTheJobsStillRunningTest {

    private static final File HERE = new File(System.getProperty("user.dir"));

    private final JobCommand        job = new JobCommand();
    private       TestOutputCapture output;

    @BeforeEach
    void setUp() {
        JobRegistry.clear();
        output = new TestOutputCapture();
        output.startCapture();
    }

    @AfterEach
    void tearDown() {
        output.stopCapture();
        JobRegistry.stopAll();
        JobRegistry.clear();
    }

    /** Starts a job that ends at once, and reports its ending the way a prompt does. */
    private static BackgroundJob endedAndReported() throws Exception {
        BackgroundJob ended = JobRegistry.start("true", null, HERE);
        assertThat(ended.awaitEnd(Duration.ofSeconds(10))).isTrue();
        assertThat(JobNotice.dueNow()).contains(ended.id());
        JobNotice.delivered();
        return ended;
    }

    private String said(String... args) {
        int before = output.getOutput().length();
        assertThat(job.execute(args)).isZero();
        return output.getOutput().substring(before);
    }

    @Test
    void ajobWhoseEndingWasReportedDoesNotEndTheWait() throws Exception {
        BackgroundJob ended = endedAndReported();
        BackgroundJob slow  = JobRegistry.start("sleep 30", null, HERE);

        long   started = System.nanoTime();
        String said    = said("wait", "-t", "1", ended.id(), slow.id());

        assertThat(Duration.ofNanos(System.nanoTime() - started))
                .isGreaterThan(Duration.ofMillis(900));
        assertThat(said).contains(slow.id() + " is still running");
    }

    @Test
    void ajobThatEndedButWasNotYetReportedEndsTheWaitAtOnce() throws Exception {
        BackgroundJob ended = JobRegistry.start("true", null, HERE);
        assertThat(ended.awaitEnd(Duration.ofSeconds(10))).isTrue();
        BackgroundJob slow = JobRegistry.start("sleep 30", null, HERE);

        String said = said("wait", "-t", "5", ended.id(), slow.id());

        assertThat(said).contains(ended.id() + " has finished");
    }

    @Test
    void awaitOnJobsWhoseEndingsWereAllReportedSaysSoAtOnce() throws Exception {
        BackgroundJob ended = endedAndReported();

        long   started = System.nanoTime();
        String said    = said("wait", "-t", "5", ended.id());

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        assertThat(said).contains(ended.id()).contains("already");
    }

    @Test
    void theAnswerSaysHowToKeepWaitingForTheRest() throws Exception {
        BackgroundJob quick = JobRegistry.start("sleep 0.2", null, HERE);
        BackgroundJob slow  = JobRegistry.start("sleep 30", null, HERE);

        String said = said("wait", quick.id(), slow.id());

        assertThat(said).contains(quick.id() + " has finished")
                        .contains("`job wait " + slow.id() + "`");
    }

    @Test
    void awaitThatNamesNoJobIsForTheCallersOwnJobs() throws Exception {
        JobRegistry.start("sleep 30", "the session's build", HERE);

        StringBuilder said = new StringBuilder();
        TimerScope.in("Worker 1", () -> said.append(said("wait", "-t", "0")));

        assertThat(said.toString()).contains("nothing to wait for").doesNotContain("j1");
    }
}
