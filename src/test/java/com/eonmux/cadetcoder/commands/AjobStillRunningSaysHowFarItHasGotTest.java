package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.jobs.BackgroundJob;
import com.eonmux.cadetcoder.jobs.JobRegistry;
import com.eonmux.cadetcoder.test.TestOutputCapture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Looking at a job that has not finished says how long it has run and what it last printed.
 *
 * <h2>The defect</h2>
 *
 * <p>An agent running an hour-long fit waited on it ten minutes at a time. Every wait that ran out
 * said the same sentence -- "Waited 10m; still running." -- so the second one was, to the guard
 * that stops an agent repeating itself, the same command with the same result, and it was refused
 * as a loop. The agent could not wait on its own job. It tried {@code sleep 420} (killed at the
 * two-minute shell limit), then {@code ps}, then {@code jstack}, to learn what one line from the
 * wait could have told it: how long the job had been going and whether it was still printing.</p>
 *
 * <p>{@code job output} with nothing new had the same sentence problem. Both now say how long the
 * job has run, which differs every time it is asked, and a wait says how much the job has printed
 * and its latest line.</p>
 */
class AjobStillRunningSaysHowFarItHasGotTest {

    private static final File HERE = new File(System.getProperty("user.dir"));

    private final JobCommand        job = new JobCommand();
    private       TestOutputCapture output;

    @BeforeEach
    void startCapturing() {
        JobRegistry.clear();
        output = new TestOutputCapture();
        output.startCapture();
    }

    @AfterEach
    void stopCapturing() {
        output.stopCapture();
        JobRegistry.stopAll();
        JobRegistry.clear();
    }

    /** Starts a job that prints two lines and then keeps running. */
    private BackgroundJob printingThenQuiet() throws Exception {
        BackgroundJob started =
                JobRegistry.start("printf 'loading\\nfitting tau 0.7\\n'; sleep 30", null, HERE);
        for (int i = 0; i < 250 && started.output().produced() < 2; i++) {
            Thread.sleep(20);
        }
        assertThat(started.output().produced()).isEqualTo(2);
        return started;
    }

    private String said(String... args) {
        int before = output.getOutput().length();
        assertThat(job.execute(args)).isZero();
        return output.getOutput().substring(before);
    }

    @Test
    void awaitThatRunsOutSaysHowLongTheJobHasRunAndWhatItLastPrinted() throws Exception {
        BackgroundJob started = printingThenQuiet();

        String said = said("wait", "-t", "1", started.id());

        assertThat(said)
                .contains(started.id())
                .contains("still running")
                .contains("2 lines")
                .contains("fitting tau 0.7");
    }

    @Test
    void twoWaitsThatRunOutDoNotSayTheSameThing() throws Exception {
        BackgroundJob started = printingThenQuiet();

        String first  = said("wait", "-t", "1", started.id());
        String second = said("wait", "-t", "1", started.id());

        assertThat(second)
                .as("a wait on a running job is not a repeat of the one before it")
                .isNotEqualTo(first);
    }

    @Test
    void ajobThatHasPrintedNothingYetIsSaidToHavePrintedNothing() throws Exception {
        BackgroundJob started = JobRegistry.start("sleep 30", null, HERE);

        String said = said("wait", "-t", "0", started.id());

        assertThat(said).contains("still running").contains("printed nothing");
    }

    @Test
    void lookingAgainWithNothingNewSaysHowLongItHasRun() throws Exception {
        BackgroundJob started = printingThenQuiet();
        said("output", started.id());
        Thread.sleep(1100);

        String said = said("output", started.id());

        assertThat(said).contains("Nothing new").contains("still running").containsPattern("\\d+s");
    }
}
