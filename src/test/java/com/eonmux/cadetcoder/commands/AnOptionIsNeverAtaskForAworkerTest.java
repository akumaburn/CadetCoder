package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An argument written as an option is answered as one, not run as a worker's task.
 *
 * <p><b>The defect</b>: anything the parser did not recognise became a task, options included.
 * {@code workers --help} -- the first thing anyone types at an unfamiliar command -- started an
 * agent whose task was the word {@code --help}, spent a model call on it and reported it as a
 * failed worker. A mistyped {@code --brief} started two agents: one on the typo and one on the
 * briefing text that followed it. {@code -b} written last, with nothing after it, became a task
 * called {@code -b}.</p>
 */
public class AnOptionIsNeverAtaskForAworkerTest {

    private TestOutputCapture outputCapture;

    @Before
    public void setUp() {
        outputCapture = new TestOutputCapture();
        outputCapture.startCapture();
    }

    @After
    public void tearDown() {
        outputCapture.stopCapture();
    }

    @Test
    public void askingForHelpIsAnsweredWithTheUsageRatherThanAnAgent() {
        int exitCode = new WorkersCommand().execute(new String[] {"--help"});

        assertThat(exitCode).isEqualTo(0);
        String output = outputCapture.getAllOutput();
        assertThat(output).contains("workers status");
        assertThat(output).doesNotContain("Each worker reports below");
    }

    @Test
    public void shortHelpIsAnsweredTheSameWay() {
        assertThat(new WorkersCommand().execute(new String[] {"-h"})).isEqualTo(0);
        assertThat(outputCapture.getAllOutput()).doesNotContain("Each worker reports below");
    }

    @Test
    public void anUnknownOptionIsReportedAndNothingIsStarted() {
        int exitCode = new WorkersCommand().execute(new String[] {"--brief", "the net package", "review retries"});

        assertThat(exitCode).isEqualTo(1);
        String output = outputCapture.getAllOutput();
        assertThat(output).contains("--brief");
        assertThat(output)
                .as("neither the typo nor the briefing text may become a worker's task")
                .doesNotContain("Each worker reports below");
    }

    @Test
    public void anOptionLeftWithoutItsValueIsReportedRatherThanRunAsAtask() {
        int exitCode = new WorkersCommand().execute(new String[] {"review retries", "-b"});

        assertThat(exitCode).isEqualTo(1);
        assertThat(outputCapture.getAllOutput()).contains("-b");
        assertThat(outputCapture.getAllOutput()).doesNotContain("Each worker reports below");
    }
}
