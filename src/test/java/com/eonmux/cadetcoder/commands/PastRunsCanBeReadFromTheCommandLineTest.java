package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.harness.cadet.RunRecord;
import com.eonmux.cadetcoder.harness.cadet.RunSummary;
import com.eonmux.cadetcoder.harness.ledger.Ledger;
import com.eonmux.cadetcoder.harness.ledger.Transition;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading back what the harness recorded, from the prompt.
 *
 * <h2>The defect</h2>
 *
 * <p>Every {@code agent} run wrote a ledger, a belief log, its models and its notes into
 * {@code .cadet/runs}, and the tool offered no way to open any of it. The evidence the harness
 * exists to produce could be reached only by knowing the layout and reaching for {@code cat}, which
 * means that in practice it was not read at all.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>That a project with no runs says so instead of printing an empty table; that each run is listed
 * with what it was asked to do and how it ended; that a run is opened by enough of its name to tell
 * it apart and that an ambiguous name is refused; that a record which cannot be read is listed with
 * what is wrong with it rather than hidden or allowed to hide the others; and that a ledger which
 * does not verify is reported as a failure rather than shown as an empty run.</p>
 */
public class PastRunsCanBeReadFromTheCommandLineTest {

    @Rule
    public TemporaryFolder project = new TemporaryFolder();

    private TestOutputCapture output;
    private RunsCommand       runs;

    @Before
    public void setUp() {
        output = new TestOutputCapture();
        runs   = new RunsIn(project.getRoot().toPath());
    }

    @After
    public void tearDown() {
        output.restore();
    }

    /** The command, pointed at the project a test made rather than at the working directory. */
    private static final class RunsIn extends RunsCommand {
        private final Path where;

        private RunsIn(Path where) {
            this.where = where;
        }

        @Override
        protected Path project() {
            return where;
        }
    }

    private RunRecord record(String name) throws IOException {
        return new RunRecord(Files.createDirectories(project.getRoot().toPath()
                                                            .resolve(RunRecord.RUNS)
                                                            .resolve(name)));
    }

    private RunRecord finished(String name, String task) throws IOException {
        RunRecord record = record(name);
        record.describe(new RunSummary(task, "bash true", "2026-01-01T10:00:00Z",
                                       "2026-01-01T10:04:00Z", "DONE", 7, 4, 9, 0,
                                       "DONE: the test passes"));
        return record;
    }

    private RunRecord unfinished(String name, String task) throws IOException {
        RunRecord record = record(name);
        record.describe(new RunSummary(task, null, "2026-01-01T09:00:00Z", null, null,
                                       0, 0, 0, 0, null));
        return record;
    }

    @Test
    public void aProjectThatHasNeverRunTheAgentSaysSoAndSaysHowToStartOne() {
        assertThat(runs.execute(new String[0])).isZero();

        assertThat(output.getAllOutput()).contains("No runs").contains("agent");
    }

    @Test
    public void everyRunIsListedWithWhatItWasAskedToDoAndHowItEnded() throws IOException {
        finished("20260101-100000-a", "make the parser reject an empty block");
        unfinished("20260101-090000-b", "index the repository");

        assertThat(runs.execute(new String[0])).isZero();

        String listed = output.getAllOutput();
        assertThat(listed).contains("20260101-100000-a")
                          .contains("make the parser reject an empty block")
                          .contains("DONE")
                          .contains("20260101-090000-b")
                          .contains("index the repository");
        assertThat(listed.indexOf("20260101-100000-a"))
                .as("the newest run is the one a person is looking for")
                .isLessThan(listed.indexOf("20260101-090000-b"));
    }

    @Test
    public void aRunThatNeverReachedAnEndingIsNotListedAsOneThatDid() throws IOException {
        unfinished("20260101-090000-b", "index the repository");

        runs.execute(new String[0]);

        assertThat(output.getAllOutput()).contains("unfinished");
    }

    @Test
    public void aRunIsOpenedByEnoughOfItsNameToTellItApart() throws IOException {
        finished("20260101-100000-a", "make the parser reject an empty block");

        assertThat(runs.execute(new String[] {"show", "20260101-1"})).isZero();

        assertThat(output.getAllOutput()).contains("make the parser reject an empty block")
                                         .contains("bash true")
                                         .contains("7 deliberations");
    }

    @Test
    public void showingARunNamesWhereItsEvidenceIs() throws IOException {
        RunRecord record = finished("20260101-100000-a", "make the parser reject an empty block");

        runs.execute(new String[] {"show", "20260101-100000-a"});

        assertThat(output.getAllOutput()).contains(record.directory().toString());
    }

    @Test
    public void theNewestRunIsWhatShowingNoParticularOneMeans() throws IOException {
        unfinished("20260101-090000-b", "index the repository");
        finished("20260101-100000-a", "make the parser reject an empty block");

        assertThat(runs.execute(new String[] {"show"})).isZero();

        assertThat(output.getAllOutput()).contains("make the parser reject an empty block");
    }

    @Test
    public void askingForARunThatIsNotThereIsAFailureRatherThanAnEmptyReport() throws IOException {
        finished("20260101-100000-a", "make the parser reject an empty block");

        assertThat(runs.execute(new String[] {"show", "19990101"})).isEqualTo(1);

        assertThat(output.getAllOutput()).contains("19990101");
    }

    @Test
    public void aNameThatFitsSeveralRunsIsRefusedAndTheCandidatesAreNamed() throws IOException {
        finished("20260101-100000-a", "one");
        finished("20260101-110000-b", "two");

        assertThat(runs.execute(new String[] {"show", "20260101"})).isEqualTo(1);

        assertThat(output.getAllOutput()).contains("20260101-100000-a")
                                         .contains("20260101-110000-b");
    }

    @Test
    public void aRecordThatCannotBeReadIsListedWithWhatIsWrongWithIt() throws IOException {
        finished("20260101-100000-a", "make the parser reject an empty block");
        RunRecord torn = record("20260101-110000-torn");
        Files.writeString(torn.summaryFile(), "not a summary");

        assertThat(runs.execute(new String[0])).isZero();

        assertThat(output.getAllOutput())
                .as("one damaged record must not hide the rest")
                .contains("20260101-110000-torn")
                .contains("20260101-100000-a")
                .contains("cannot be read");
    }

    @Test
    public void whatReallyHappenedIsPrintedFromTheLedger() throws IOException {
        RunRecord record = finished("20260101-100000-a", "make the parser reject an empty block");
        Ledger    ledger = new Ledger(record.ledger());
        ledger.append(Transition.proposal(0, Map.of("pos", 1), Map.of("run", "mvn test"),
                                          Map.of("pos", 2), Map.of(), Map.of()));

        assertThat(runs.execute(new String[] {"ledger", "20260101-100000-a"})).isZero();

        assertThat(output.getAllOutput()).contains("mvn test");
    }

    @Test
    public void aLedgerThatDoesNotVerifyIsReportedRatherThanShownAsAnEmptyRun() throws IOException {
        RunRecord record = finished("20260101-100000-a", "make the parser reject an empty block");
        Files.writeString(record.ledger(), "not a transition\n");

        assertThat(runs.execute(new String[] {"ledger", "20260101-100000-a"})).isEqualTo(1);

        assertThat(output.getAllOutput()).contains("ledger");
    }

    @Test
    public void aRunWithNoLedgerSaysSoRatherThanFailing() throws IOException {
        finished("20260101-100000-a", "make the parser reject an empty block");

        assertThat(runs.execute(new String[] {"ledger", "20260101-100000-a"})).isZero();

        assertThat(output.getAllOutput()).contains("nothing");
    }

    @Test
    public void whatTheAgentWroteDownForItselfIsShown() throws IOException {
        RunRecord record = finished("20260101-100000-a", "make the parser reject an empty block");
        Files.writeString(record.notes(), "the empty-block case is in ParserTest\n");

        runs.execute(new String[] {"show", "20260101-100000-a"});

        assertThat(output.getAllOutput()).contains("the empty-block case is in ParserTest");
    }

    @Test
    public void anUnknownSubcommandIsRefusedRatherThanTakenForARunName() {
        assertThat(runs.execute(new String[] {"delete", "everything"})).isEqualTo(1);

        assertThat(output.getAllOutput()).contains("runs");
    }
}
