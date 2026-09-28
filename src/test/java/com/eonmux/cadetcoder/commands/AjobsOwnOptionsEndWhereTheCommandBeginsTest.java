package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.jobs.BackgroundJob;
import com.eonmux.cadetcoder.jobs.JobRegistry;
import com.eonmux.cadetcoder.test.TestOutputCapture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where this command's own options stop and the command it is to run begins.
 *
 * <h2>Why the line is not rewritten on the way in</h2>
 *
 * <p>A job is given a command line to run, and that line has flags of its own.
 * {@code job start curl -d='{"a":1}' https://x} put through this command's inline-option expansion
 * came out as {@code -d} followed by {@code {"a":1}}, so the payload was read as the job's
 * description and the request that ran was a different request. The options are therefore read only
 * before the command begins, the inline form is understood where it is read, and {@code --} ends
 * them early.</p>
 *
 * <h2>Why a flag may come before the id</h2>
 *
 * <p>{@code job output -n 20 j1} is how anybody writes it. Read by position, the first token was
 * taken for the id, so the command answered that there is no job called {@code -n}. The id is the
 * first token that is not a flag or a flag's value, wherever it is written.</p>
 */
public class AjobsOwnOptionsEndWhereTheCommandBeginsTest {

    private static final File HERE = new File(System.getProperty("user.dir"));

    private final JobCommand  job     = new JobCommand();
    private       TestOutputCapture output;

    @BeforeEach
    public void startCapturing() {
        JobRegistry.clear();
        output = new TestOutputCapture();
        output.startCapture();
    }

    @AfterEach
    public void stopCapturing() {
        output.stopCapture();
        JobRegistry.clear();
    }

    /** Starts a command that prints three lines and waits for it to finish. */
    private BackgroundJob threeLines() throws Exception {
        BackgroundJob started =
                JobRegistry.start("printf 'one\\ntwo\\nthree\\n'", null, HERE);
        assertThat(started.awaitEnd(Duration.ofSeconds(20))).isTrue();
        for (int i = 0; i < 100 && started.output().produced() < 3; i++) {
            Thread.sleep(20);
        }
        return started;
    }

    @Test
    void thedescriptionIsReadInBothItsSpellings() {
        assertThat(JobCommand.read(new String[] {"-d", "the full suite", "mvn", "-o", "test"}))
                .isEqualTo(new JobCommand.Started("mvn -o test", "the full suite"));
        assertThat(JobCommand.read(new String[] {"-d=the full suite", "mvn", "-o", "test"}))
                .isEqualTo(new JobCommand.Started("mvn -o test", "the full suite"));
    }

    @Test
    void aflagBelongingToTheCommandIsLeftForTheCommand() {
        JobCommand.Started asked =
                JobCommand.read(new String[] {"curl", "-d={\"a\":1}", "https://example.test"});

        assertThat(asked.command()).isEqualTo("curl -d={\"a\":1} https://example.test");
        assertThat(asked.description())
                .as("the payload belongs to curl, not to the job")
                .isNull();
    }

    @Test
    void adashDashEndsTheOptionsEarly() {
        JobCommand.Started asked = JobCommand.read(new String[] {"--", "-d", "value"});

        assertThat(asked.command()).isEqualTo("-d value");
        assertThat(asked.description()).isNull();
    }

    @Test
    void aflagBeforeTheIdIsStillAflag() throws Exception {
        BackgroundJob started = threeLines();

        assertThat(job.execute(new String[] {"output", "-n", "2", started.id()})).isZero();
        assertThat(output.getAllOutput())
                .as("the id is the first token that is not a flag")
                .doesNotContain("No job called");
    }

    @Test
    void afigureThatIsMissingIsSaidToBeMissing() throws Exception {
        BackgroundJob started = threeLines();

        assertThat(job.execute(new String[] {"output", started.id(), "-n"})).isEqualTo(1);
        assertThat(output.getAllOutput()).contains("Missing value for -n");
    }

    @Test
    void askingForEverythingShowsWhatWasAlreadyRead() throws Exception {
        BackgroundJob started = threeLines();

        job.execute(new String[] {"output", started.id()});
        output.stopCapture();
        output = new TestOutputCapture();
        output.startCapture();

        assertThat(job.execute(new String[] {"output", started.id(), "--all"})).isZero();
        assertThat(output.getAllOutput())
                .as("--all means all of it, including what an earlier read has passed")
                .contains("one")
                .contains("three");
    }
}
