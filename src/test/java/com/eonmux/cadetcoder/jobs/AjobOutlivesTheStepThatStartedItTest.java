package com.eonmux.cadetcoder.jobs;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.eonmux.cadetcoder.timers.TimerScope;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Starting work that the step which started it does not wait for.
 *
 * <h2>Why this exists</h2>
 *
 * <p>{@code bash} waits, so anything longer than its timeout is killed before it finishes and the
 * step that ran it is spent on the waiting. A build, a test suite or a server that has to stay up
 * could not be run at all. A job is the same command let go: the call returns at once, the output
 * accumulates, and the ending is announced into a later prompt.</p>
 *
 * <h2>Why the ending is taken rather than looked at</h2>
 *
 * <p>An ending announced twice would tell the model the build finished again several steps after it
 * did. An ending taken by a prompt that was never sent would never be announced at all. So it is
 * withdrawn and then either committed or put back, which is the arrangement timers already
 * use.</p>
 *
 * <h2>Why the ending is owed to whoever started it</h2>
 *
 * <p>A worker and the session take their announcements from the same registry. Kept as one list,
 * whichever asked first took every job's ending, including ones started elsewhere, and the scope
 * that was owed the notice never heard about its own build. What a worker has taken for a prompt is
 * therefore its own, and a worker that ends before its job does hands what it is owed to the
 * session, which is what outlives every run.</p>
 */
public class AjobOutlivesTheStepThatStartedItTest {

    private static final File HERE = new File(System.getProperty("user.dir"));

    @BeforeEach
    @AfterEach
    public void noJobsLeftOver() {
        JobRegistry.clear();
    }

    /** Starts a command and waits for it to finish, so what it printed is all in. */
    private BackgroundJob ran(String command) throws IOException, InterruptedException {
        BackgroundJob job = JobRegistry.start(command, null, HERE);
        assertThat(job.awaitEnd(Duration.ofSeconds(20)))
                .as("the command should have finished well inside the wait")
                .isTrue();
        // The reader is on its own thread and the pipe closes a moment after the process does.
        for (int i = 0; i < 100 && job.output().produced() == 0; i++) {
            Thread.sleep(20);
        }
        return job;
    }

    @Test
    void thecallReturnsWhileTheCommandIsStillRunning() throws Exception {
        long started = System.currentTimeMillis();

        BackgroundJob job = JobRegistry.start("sleep 5", "a slow one", HERE);
        long elapsed = System.currentTimeMillis() - started;

        assertThat(elapsed).as("starting must not wait for the command").isLessThan(3000L);
        assertThat(job.state()).isEqualTo(BackgroundJob.State.RUNNING);
        assertThat(job.isDone()).isFalse();
        assertThat(JobRegistry.running()).contains(job);
        assertThat(job.description()).isEqualTo("a slow one");
    }

    @Test
    void whatItPrintedIsKeptForWhoeverAsksLater() throws Exception {
        BackgroundJob job = ran("echo hello from a job");

        assertThat(job.state()).isEqualTo(BackgroundJob.State.EXITED);
        assertThat(job.exitCode()).contains(0);
        assertThat(job.succeeded()).isTrue();
        assertThat(job.readNew(0).lines()).containsExactly("hello from a job");
        // Read once: a second look asks what is NEW, and nothing is.
        assertThat(job.readNew(0).lines()).isEmpty();
    }

    @Test
    void afailureKeepsItsExitCodeAndItsOutput() throws Exception {
        BackgroundJob job = ran("echo going wrong; exit 3");

        assertThat(job.succeeded()).isFalse();
        assertThat(job.exitCode()).contains(3);
        assertThat(job.readNew(0).lines()).contains("going wrong");
    }

    @Test
    void stderrArrivesWithTheRestOfTheOutput() throws Exception {
        // The half that says why it failed is the half worth keeping.
        BackgroundJob job = ran("echo trouble >&2");

        assertThat(job.readNew(0).lines()).contains("trouble");
    }

    @Test
    void ajobCanBeStoppedAndSaysThatIsWhatHappened() throws Exception {
        BackgroundJob job = JobRegistry.start("sleep 30", null, HERE);

        assertThat(job.stop()).isTrue();
        assertThat(job.isDone()).isTrue();
        assertThat(job.state()).isEqualTo(BackgroundJob.State.STOPPED);
        assertThat(job.succeeded()).as("a job that was killed did not succeed").isFalse();
        assertThat(job.stop()).as("there is nothing left to stop").isFalse();
    }

    @Test
    void thereIsAceilingOnHowManyRunAtOnce() throws Exception {
        for (int i = 0; i < JobRegistry.MAX_RUNNING; i++) {
            JobRegistry.start("sleep 20", null, HERE);
        }

        assertThatThrownBy(() -> JobRegistry.start("sleep 20", null, HERE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stop one before starting another");
    }

    @Test
    void afinishedJobIsAnnouncedOnceAndOnlyOnce() throws Exception {
        BackgroundJob job = ran("echo done");

        List<BackgroundJob> first = JobRegistry.takeFinished();
        JobRegistry.delivered();

        assertThat(first).containsExactly(job);
        assertThat(JobRegistry.takeFinished())
                .as("announced twice, the model is told the build finished again")
                .isEmpty();
    }

    @Test
    void anAnnouncementThatNeverReachedThemodelIsOwedAgain() throws Exception {
        BackgroundJob job = ran("echo done");

        assertThat(JobRegistry.takeFinished()).containsExactly(job);
        assertThat(JobRegistry.returnUndelivered()).isEqualTo(1);
        assertThat(JobRegistry.takeFinished())
                .as("the prompt was never sent, so nobody has been told yet")
                .containsExactly(job);
    }


    @Test
    void ajobIsAnnouncedToTheScopeThatStartedItAndNotToAnother() {
        TimerScope.in("Worker 1", () -> {
            BackgroundJob job = ranQuietly("echo done");

            TimerScope.in("Worker 2", () -> assertThat(JobRegistry.takeFinished())
                    .as("Worker 2 did not start it and must not be told it has finished")
                    .isEmpty());

            assertThat(JobRegistry.takeFinished())
                    .as("the scope that started it is the scope that is owed the ending")
                    .containsExactly(job);
        });
    }

    @Test
    void ajobOutlivingItsWorkerIsAnnouncedToTheSession() throws Exception {
        BackgroundJob[] started = new BackgroundJob[1];
        TimerScope.in("Worker 1", () -> started[0] = ranQuietly("echo done"));

        // The worker is gone. Its name will be given to the next run's first worker, so a notice
        // still owed to it would be read by somebody who never started anything.
        assertThat(JobRegistry.takeFinished())
                .as("the session inherits what the worker never collected")
                .containsExactly(started[0]);
    }

    @Test
    void aworkerThatTookAnAnnouncementAndVanishedDoesNotTakeItAway() throws Exception {
        BackgroundJob[] started = new BackgroundJob[1];
        TimerScope.in("Worker 1", () -> {
            started[0] = ranQuietly("echo done");
            assertThat(JobRegistry.takeFinished()).containsExactly(started[0]);
            // No delivered() and no returnUndelivered(): the worker ends holding it.
        });

        assertThat(JobRegistry.takeFinished())
                .as("a prompt nobody sent leaves the ending still owed to somebody")
                .containsExactly(started[0]);
    }

    @Test
    void areaderTakingPartOfWhatIsWaitingIsToldHowMuchItLeft() throws Exception {
        BackgroundJob job = ran("printf 'one\\ntwo\\nthree\\nfour\\nfive\\n'");

        BackgroundJob.Slice slice = job.readNew(2);

        assertThat(slice.lines()).hasSize(2);
        assertThat(slice.skipped())
                .as("a reader given the tail of the output must know the rest was there")
                .isEqualTo(3);
        assertThat(job.readNew(0).lines())
                .as("the cursor moved past everything that was waiting, not past what was read")
                .isEmpty();
    }

    /** Starts a command, waits for it, and turns a failure to start into an unchecked one. */
    private BackgroundJob ranQuietly(String command) {
        try {
            return ran(command);
        } catch (IOException | InterruptedException notStarted) {
            throw new IllegalStateException(notStarted);
        }
    }

    @Test
    void ajobStillRunningIsNotAnnounced() throws Exception {
        JobRegistry.start("sleep 20", null, HERE);

        assertThat(JobRegistry.takeFinished()).isEmpty();
    }

    @Test
    void thenoticeSaysHowItEndedAndWhereTheRestIs() throws Exception {
        // The arithmetic is so that what it PRINTS ("42") is not also in what it RAN: the notice
        // quotes the command, and a phrase in both would not tell the two apart.
        BackgroundJob job = ran("echo $((21+21))");

        String notice = JobNotice.render(List.of(job));

        assertThat(notice).contains("[job]")
                          .contains(job.id())
                          .contains("finished successfully")
                          .contains("1 line")
                          .contains("job output " + job.id());
        assertThat(notice)
                .as("the output is offered, not pasted in; a build's transcript would fill the prompt")
                .doesNotContain("42");
    }

    @Test
    void afailedJobQuotesItsLastLinesWithoutBeingAsked() throws Exception {
        BackgroundJob job = ran("echo BUILD FAILURE; exit 1");

        String notice = JobNotice.render(List.of(job));

        assertThat(notice).contains("failed with exit code 1")
                          .as("a run told only that the build failed will act without reading why")
                          .contains("BUILD FAILURE");
    }

    @Test
    void thereIsNoNoticeWhenNothingHasEnded() {
        assertThat(JobNotice.render(List.of())).isEmpty();
        assertThat(JobNotice.render(null)).isEmpty();
        assertThat(JobNotice.dueNow()).isEmpty();
    }

    @Test
    void ajobIsFoundByItsIdWithOrWithoutTheLetter() throws Exception {
        BackgroundJob job = JobRegistry.start("sleep 20", null, HERE);

        assertThat(JobRegistry.find(job.id())).contains(job);
        assertThat(JobRegistry.find(job.id().substring(1))).contains(job);
        assertThat(JobRegistry.find("j99")).isEmpty();
        assertThat(JobRegistry.find(null)).isEmpty();
    }

    @Test
    void stoppingEverythingLeavesNothingRunning() throws Exception {
        JobRegistry.start("sleep 20", null, HERE);
        JobRegistry.start("sleep 20", null, HERE);

        assertThat(JobRegistry.stopAll()).hasSize(2);
        assertThat(JobRegistry.running()).isEmpty();
        assertThat(JobRegistry.all()).as("they are still on the list, they are just over").hasSize(2);
    }
}
