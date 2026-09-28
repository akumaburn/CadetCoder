package com.eonmux.cadetcoder;

import com.eonmux.cadetcoder.commands.IterativeCommand;
import com.eonmux.cadetcoder.commands.IterativeExecutor;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One event, one answer: a run the user took back reports the same code whichever loop noticed.
 *
 * <h2>The defect</h2>
 *
 * <p>The same key press was reported three different ways depending on which routine saw it first.
 * {@code bash} and {@code workers} answered 130. A harness run somebody called off answered 1, as
 * though the task had been found impossible. {@code agent}, {@code chat} and {@code edit} answered
 * 0 -- so a script that stopped an agent part-way was told the agent had finished the job, and
 * would go on to build, test or deploy on top of work that was never done.</p>
 *
 * <p>There was no reading of the exit code that recovered the truth, because the three answers do
 * not merely differ in value: two of them are the codes for the other two outcomes.</p>
 */
public class OneInterruptionIsOneExitCodeTest {

    /** A command whose single step reports the ending it was built with. */
    private static final class OneStep implements IterativeCommand {

        private final StepResult ending;

        private OneStep(StepResult ending) {
            this.ending = ending;
        }

        @Override
        public StepResult executeStep(String[] args, Map<String, Object> context, String llmResponse) {
            return ending;
        }

        @Override
        public String getInitialPrompt(String[] args) {
            return null; // nothing to open a conversation with: the executor steps it directly
        }

        @Override
        public int execute(String[] args) {
            return new IterativeExecutor().execute(this, args);
        }

        @Override
        public String getUsage() {
            return "one-step";
        }
    }

    private TestOutputCapture output;

    @Before
    public void setUp() {
        output = new TestOutputCapture();
        output.startCapture();
    }

    @After
    public void tearDown() {
        output.stopCapture();
        InterruptSignal.clear();
    }

    private static int run(IterativeCommand.StepResult ending) {
        return new IterativeExecutor().execute(new OneStep(ending), new String[0]);
    }

    private static Map<String, Object> context() {
        return new HashMap<>();
    }

    @Test
    public void astepThatWasTakenBackReportsTheInterruptionCode() {
        assertThat(run(IterativeCommand.StepResult.interrupted("stopped", context())))
                .isEqualTo(ExitCode.INTERRUPTED);
    }

    /**
     * The value matters, not just its distinctness: 130 is what a shell reports for SIGINT, so
     * everything that already reads exit codes knows it without being told.
     */
    @Test
    public void theInterruptionCodeIsTheOneAshellAlreadyKnows() {
        assertThat(ExitCode.INTERRUPTED).isEqualTo(128 + 2);
    }

    @Test
    public void aninterruptionIsNeitherOfTheOtherTwoEndings() {
        int stopped = run(IterativeCommand.StepResult.interrupted("stopped", context()));

        assertThat(stopped).isNotEqualTo(run(IterativeCommand.StepResult.success("done", context())));
        assertThat(stopped).isNotEqualTo(run(IterativeCommand.StepResult.failure("broke", context())));
    }

    @Test
    public void whatWasSaidAboutAstoppedRunIsStillShown() {
        run(IterativeCommand.StepResult.interrupted("Agent interrupted by user", context()));

        assertThat(output.getAllOutput()).contains("Agent interrupted by user");
    }

    /**
     * A stopped run is not announced as a completed one.
     *
     * <p>{@code finish()} tested only for an error before reporting completion, so an interruption
     * -- which is explicitly NOT an error -- went down the success path and was printed as the
     * run's answer.
     */
    @Test
    public void astoppedRunIsNotReportedAsAnAnswer() {
        run(IterativeCommand.StepResult.interrupted("Agent interrupted by user", context()));

        assertThat(output.getAllOutput())
                .as("nothing here finished; it was stopped")
                .doesNotContain("completed successfully");
    }

    /** Carrying the ending through a copy must not lose what kind of ending it was. */
    @Test
    public void sayingWhoAstepIsAddressedToDoesNotTurnItBackIntoAsuccess() {
        assertThat(IterativeCommand.StepResult.interrupted("stopped", context())
                                              .addressedToUser()
                                              .isInterrupted()).isTrue();
        assertThat(IterativeCommand.StepResult.interrupted("stopped", context())
                                              .addressedToModel()
                                              .isInterrupted()).isTrue();
    }

    @Test
    public void anendingNobodyStoppedIsNotMarkedAsStopped() {
        assertThat(IterativeCommand.StepResult.success("done", context()).isInterrupted()).isFalse();
        assertThat(IterativeCommand.StepResult.failure("broke", context()).isInterrupted()).isFalse();
    }

    /**
     * The codes a script is told to branch on are the codes the tool actually reports.
     *
     * <p>An exit code is only useful to whoever reads it, and the only place they can learn what it
     * means is the README. A table that drifts from the constants is worse than no table: it is a
     * documented contract that silently stopped being true.</p>
     */
    @Test
    public void thecodesTheReadmeDocumentsAreTheCodesThisToolReports() throws java.io.IOException {
        String readme = java.nio.file.Files.readString(java.nio.file.Paths.get("README.md"));

        assertThat(readme).as("the exit codes have to be written down somewhere")
                          .contains("### Exit codes");

        String table = readme.substring(readme.indexOf("### Exit codes"));
        table = table.substring(0, table.indexOf("## Command reference"));

        assertThat(table).contains("| `" + ExitCode.OK + "` |")
                         .contains("| `" + ExitCode.FAILED + "` |")
                         .contains("| `" + ExitCode.UNREACHABLE + "` |")
                         .contains("| `" + ExitCode.INTERRUPTED + "` |");
    }
}
