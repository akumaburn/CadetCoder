package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A word that reads as a mistyped {@code workers} verb is reported, not run as a task.
 *
 * <p><b>The defect</b>: {@code workers} treats every unrecognised first argument as a task, so
 * {@code workers staus} did not say that {@code status} was misspelled -- it started an agent whose
 * task was the word "staus", against the user's provider and at the user's expense, and answered a
 * question about what the workers were doing by doing something else. The command registry already
 * refuses {@code /raed pom.xml} for this exact reason; the same mistake one level down was
 * unguarded.</p>
 */
public class AmistypedVerbIsNotAtaskTest {

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
    public void aNearMissOfAverbIsReadAsThatVerbMisspelled() {
        assertThat(WorkersCommand.verbsResembling("staus")).contains("status");
        assertThat(WorkersCommand.verbsResembling("statsu")).contains("status");
        assertThat(WorkersCommand.verbsResembling("lst")).contains("list");
        assertThat(WorkersCommand.verbsResembling("sto")).isNotEmpty();
    }

    @Test
    public void averbSpeltCorrectlyIsNotAtypoOfItself() {
        for (String verb : new String[] {"list", "show", "status", "wait", "stop", "start"}) {
            assertThat(WorkersCommand.verbsResembling(verb))
                    .as("%s is the verb itself, handled before this test is reached", verb)
                    .isEmpty();
        }
    }

    @Test
    public void asentenceIsAtaskHoweverItBegins() {
        assertThat(WorkersCommand.verbsResembling("stop the memory leak in the indexer")).isEmpty();
        assertThat(WorkersCommand.verbsResembling("list every caller of ConfigManager")).isEmpty();
        assertThat(WorkersCommand.verbsResembling("review the retry logic")).isEmpty();
    }

    @Test
    public void awordThatResemblesNoVerbIsLeftAlone() {
        assertThat(WorkersCommand.verbsResembling("refactor")).isEmpty();
        assertThat(WorkersCommand.verbsResembling("benchmarks")).isEmpty();
        assertThat(WorkersCommand.verbsResembling(null)).isEmpty();
        assertThat(WorkersCommand.verbsResembling("   ")).isEmpty();
    }

    /** The refusal happens before anything is started, and says how to insist. */
    @Test
    public void nothingIsStartedAndTheWayRoundIsGiven() {
        int exitCode = new WorkersCommand().execute(new String[] {"staus"});

        assertThat(exitCode).isEqualTo(1);
        String output = outputCapture.getAllOutput();
        assertThat(output).contains("workers staus");
        assertThat(output).contains("status");
        assertThat(output).contains("workers start \"staus\"");
        assertThat(output)
                .as("the banner a started run prints; no provider may be called for a typo")
                .doesNotContain("Each worker reports below");
    }
}
