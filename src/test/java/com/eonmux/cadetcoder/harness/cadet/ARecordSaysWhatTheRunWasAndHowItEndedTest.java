package com.eonmux.cadetcoder.harness.cadet;

import com.eonmux.cadetcoder.harness.budget.BudgetLimits;
import com.eonmux.cadetcoder.harness.loop.RunResult;
import com.eonmux.cadetcoder.harness.loop.RunStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a run's record says about itself.
 *
 * <h2>The defect</h2>
 *
 * <p>A run left behind a directory named after the second it started in, holding a ledger, some
 * models, a belief log and some notes -- and nothing at all saying what the run had been asked to do
 * or how it ended. Somebody looking for the record of "fix the failing build" among a dozen of them
 * had to open ledgers and read observations until they recognised one, and a run killed at the
 * terminal left a directory nobody could ever identify.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>That the task is written down before the run starts, so an interrupted run is still
 * identifiable; that a record which never reached an ending says so rather than implying one; that
 * an ending is added without losing what the beginning recorded; and that a summary which cannot be
 * read is reported rather than passed off as a run that said nothing.</p>
 */
class ARecordSaysWhatTheRunWasAndHowItEndedTest {

    private static RunRequest request(Path project) {
        return new RunRequest("make the parser reject an empty block", null, project,
                              BudgetLimits.unlimited());
    }

    private static RunResult finishedRun() {
        return new RunResult(RunStatus.DONE, 7, 4, 9, 1, "DONE: the test passes");
    }

    @Test
    void theSummarySaysWhatTheRunWasAskedToDo(@TempDir Path project) {
        RunSummary summary = RunSummary.beginning(request(project));

        assertThat(summary.task()).isEqualTo("make the parser reject an empty block");
    }

    @Test
    void aRunWithSomethingToCheckItselfAgainstSaysWhatThatIs(@TempDir Path project) {
        RunSummary summary = RunSummary.beginning(request(project).checkedBy("bash mvn -o -q test"));

        assertThat(summary.goalCheck()).isEqualTo("bash mvn -o -q test");
    }

    @Test
    void aRunWithNothingToCheckItselfAgainstSaysSoRatherThanLeavingItBlank(@TempDir Path project) {
        RunSummary summary = RunSummary.beginning(request(project));

        assertThat(summary.goalCheck()).isNull();
        assertThat(summary.render()).contains("no check");
    }

    @Test
    void whenTheRunBeganIsRecordedFromTheStart(@TempDir Path project) {
        RunSummary summary = RunSummary.beginning(request(project));

        assertThat(summary.began()).isNotBlank();
        assertThat(summary.ended()).isNull();
    }

    @Test
    void aRunThatNeverReachedAnEndingDoesNotClaimOne(@TempDir Path project) {
        RunSummary summary = RunSummary.beginning(request(project));

        assertThat(summary.over()).isFalse();
        assertThat(summary.status()).isNull();
        assertThat(summary.render()).contains("did not finish");
    }

    @Test
    void howTheRunEndedIsAddedWithoutLosingWhatTheBeginningRecorded(@TempDir Path project) {
        RunSummary began = RunSummary.beginning(request(project).checkedBy("bash mvn -o -q test"));

        RunSummary ended = began.completed(finishedRun());

        assertThat(ended.task()).isEqualTo(began.task());
        assertThat(ended.goalCheck()).isEqualTo(began.goalCheck());
        assertThat(ended.began()).isEqualTo(began.began());
        assertThat(ended.status()).isEqualTo(RunStatus.DONE.name());
        assertThat(ended.deliberations()).isEqualTo(7);
        assertThat(ended.actions()).isEqualTo(4);
        assertThat(ended.transitions()).isEqualTo(9);
        assertThat(ended.escalations()).isEqualTo(1);
        assertThat(ended.said()).isEqualTo("DONE: the test passes");
        assertThat(ended.ended()).isNotBlank();
        assertThat(ended.over()).isTrue();
    }

    @Test
    void completingASummaryLeavesTheOneItWasMadeFromAsItWas(@TempDir Path project) {
        RunSummary began = RunSummary.beginning(request(project));

        began.completed(finishedRun());

        assertThat(began.over()).isFalse();
    }

    @Test
    void whatIsWrittenReadsBackTheSame(@TempDir Path project) {
        RunRecord  record = RunRecord.under(project);
        RunSummary ended  = RunSummary.beginning(request(project).checkedBy("bash true"))
                                      .completed(finishedRun());

        record.describe(ended);

        assertThat(record.summary()).contains(ended);
    }

    @Test
    void aRecordWrittenBeforeTheRunStartsIsStillIdentifiableIfTheRunNeverEnds(
            @TempDir Path project) {
        RunRecord record = RunRecord.under(project);

        record.describe(RunSummary.beginning(request(project)));

        Optional<RunSummary> read = record.summary();
        assertThat(read).isPresent();
        assertThat(read.get().task()).isEqualTo("make the parser reject an empty block");
        assertThat(read.get().over()).isFalse();
    }

    @Test
    void aRecordWithNothingWrittenAboutItHasNoSummary(@TempDir Path project) {
        assertThat(RunRecord.under(project).summary()).isEmpty();
    }

    @Test
    void aSummaryThatIsNotARecordIsReportedRatherThanReadAsARunThatSaidNothing(
            @TempDir Path project) throws IOException {
        RunRecord record = RunRecord.under(project);
        Files.writeString(record.summaryFile(), "not json at all");

        assertThatThrownBy(record::summary)
                .isInstanceOf(RunRecordException.class)
                .hasMessageContaining(record.summaryFile().toString());
    }

    @Test
    void theSummaryOfAFinishedRunSaysTheTaskAndHowItEnded(@TempDir Path project) {
        String rendered = RunSummary.beginning(request(project)).completed(finishedRun()).render();

        assertThat(rendered).contains("make the parser reject an empty block")
                            .contains("DONE")
                            .contains("7 deliberations")
                            .contains("4 actions");
    }
}
