package com.eonmux.cadetcoder.harness.cadet;

import com.eonmux.cadetcoder.harness.ledger.Ledger;
import com.eonmux.cadetcoder.harness.ledger.LedgerException;
import com.eonmux.cadetcoder.harness.ledger.LedgerStats;
import com.eonmux.cadetcoder.harness.ledger.Transition;
import com.eonmux.cadetcoder.harness.model.ModelRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reading a project's runs back out of the records they left.
 *
 * <h2>The defect</h2>
 *
 * <p>Runs wrote their records and nothing ever read one. Everything a run established -- what really
 * happened, what it came to believe, what it wrote down for itself -- sat in a hidden directory that
 * the tool offered no way to open, so the evidence the harness exists to produce could only be
 * reached by knowing the layout and running {@code cat}.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>That the newest run is the first one listed; that a run is found by enough of its name to be
 * unambiguous and that an ambiguous name is refused rather than guessed at; that one damaged record
 * cannot take the listing down with it, and says what is wrong with it; that a ledger which does not
 * verify is reported rather than counted as an empty one; and that reading a record does not write
 * to it.</p>
 */
class WhatPastRunsLeftBehindCanBeReadBackTest {

    private static final String COUNTER = """
            fn parse(obs) { return {"pos": obs.pos}; }
            fn step(state, action) { return {"pos": state.pos + action.d}; }
            fn predict(state) { return {"pos": state.pos}; }
            fn is_goal(state) { return state.pos >= 3; }
            """;

    /** A record with the name it would have had if it had been made at that moment. */
    private static RunRecord named(Path project, String name) throws IOException {
        return new RunRecord(Files.createDirectories(project.resolve(RunRecord.RUNS)
                                                            .resolve(name)));
    }

    private static RunRecord described(Path project, String name, String task, String began)
            throws IOException {
        RunRecord record = named(project, name);
        record.describe(new RunSummary(task, null, began, null, null, 0, 0, 0, 0, null));
        return record;
    }

    @Test
    void theRunsOfAProjectAreListedNewestFirst(@TempDir Path project) throws IOException {
        described(project, "20260101-100000-a", "first", "2026-01-01T10:00:00Z");
        described(project, "20260101-110000-b", "second", "2026-01-01T11:00:00Z");
        described(project, "20260101-120000-c", "third", "2026-01-01T12:00:00Z");

        assertThat(RecordedRun.under(project)).extracting(RecordedRun::name)
                                              .containsExactly("20260101-120000-c",
                                                               "20260101-110000-b",
                                                               "20260101-100000-a");
    }

    @Test
    void aRunThatNeverSaidAnythingAboutItselfIsStillOrderedByWhenItBegan(@TempDir Path project)
            throws IOException {
        described(project, "20260101-100000-a", "first", "2026-01-01T10:00:00Z");
        named(project, "20260101-120000-silent");

        assertThat(RecordedRun.under(project)).extracting(RecordedRun::name)
                                              .containsExactly("20260101-120000-silent",
                                                               "20260101-100000-a");
    }

    @Test
    void aProjectThatHasNeverRunAnythingHasNoRecords(@TempDir Path project) {
        assertThat(RecordedRun.under(project)).isEmpty();
    }

    @Test
    void aRunIsFoundByItsWholeName(@TempDir Path project) throws IOException {
        described(project, "20260101-100000-a", "first", "2026-01-01T10:00:00Z");

        Optional<RecordedRun> found = RecordedRun.named(project, "20260101-100000-a");

        assertThat(found).isPresent();
        assertThat(found.get().summary().orElseThrow().task()).isEqualTo("first");
    }

    @Test
    void aRunIsFoundByEnoughOfItsNameToTellItApart(@TempDir Path project) throws IOException {
        described(project, "20260101-100000-a", "first", "2026-01-01T10:00:00Z");
        described(project, "20260102-100000-b", "second", "2026-01-02T10:00:00Z");

        assertThat(RecordedRun.named(project, "20260102")).isPresent();
    }

    @Test
    void aNameThatFitsSeveralRunsIsRefusedRatherThanGuessedAt(@TempDir Path project)
            throws IOException {
        described(project, "20260101-100000-a", "first", "2026-01-01T10:00:00Z");
        described(project, "20260101-110000-b", "second", "2026-01-01T11:00:00Z");

        assertThatThrownBy(() -> RecordedRun.named(project, "20260101"))
                .isInstanceOf(RunRecordException.class)
                .hasMessageContaining("20260101-100000-a")
                .hasMessageContaining("20260101-110000-b");
    }

    @Test
    void theMostRecentRunIsAskedForByName(@TempDir Path project) throws IOException {
        described(project, "20260101-100000-a", "first", "2026-01-01T10:00:00Z");
        described(project, "20260101-110000-b", "second", "2026-01-01T11:00:00Z");

        Optional<RecordedRun> found = RecordedRun.named(project, RecordedRun.LATEST);

        assertThat(found).isPresent();
        assertThat(found.get().summary().orElseThrow().task()).isEqualTo("second");
    }

    @Test
    void aNameThatFitsNoRunFindsNothing(@TempDir Path project) throws IOException {
        described(project, "20260101-100000-a", "first", "2026-01-01T10:00:00Z");

        assertThat(RecordedRun.named(project, "19990101")).isEmpty();
    }

    @Test
    void aRecordThatCannotBeReadIsStillListedAndSaysWhatIsWrongWithIt(@TempDir Path project)
            throws IOException {
        described(project, "20260101-100000-a", "first", "2026-01-01T10:00:00Z");
        RunRecord torn = named(project, "20260101-110000-torn");
        Files.writeString(torn.summaryFile(), "not a summary at all");

        List<RecordedRun> runs = RecordedRun.under(project);

        assertThat(runs).hasSize(2);
        RecordedRun damaged = runs.get(0);
        assertThat(damaged.name()).isEqualTo("20260101-110000-torn");
        assertThat(damaged.summary()).isEmpty();
        assertThat(damaged.problem()).isPresent();
        assertThat(damaged.problem().get()).contains(torn.summaryFile().toString());
    }

    @Test
    void whatTheLedgerAmountsToIsReadFromTheRecord(@TempDir Path project) throws IOException {
        RunRecord record = named(project, "20260101-100000-a");
        Ledger    ledger = new Ledger(record.ledger());
        ledger.append(Transition.proposal(0, Map.of("pos", 1), Map.of("d", 1), Map.of("pos", 2),
                                          Map.of(), Map.of()));

        Optional<LedgerStats> evidence = RecordedRun.at(record.directory()).evidence();

        assertThat(evidence).isPresent();
        assertThat(evidence.get().transitions()).isEqualTo(1);
    }

    @Test
    void aRunThatNeverRecordedATransitionHasNoLedgerToRead(@TempDir Path project)
            throws IOException {
        RunRecord record = named(project, "20260101-100000-a");

        assertThat(RecordedRun.at(record.directory()).evidence()).isEmpty();
    }

    @Test
    void aLedgerThatDoesNotVerifyIsReportedRatherThanCountedAsAnEmptyOne(@TempDir Path project)
            throws IOException {
        RunRecord record = named(project, "20260101-100000-a");
        Files.writeString(record.ledger(), "not a transition\n");

        assertThatThrownBy(() -> RecordedRun.at(record.directory()).evidence())
                .isInstanceOf(LedgerException.class);
    }

    @Test
    void theModelVersionsARunWroteAreReadBack(@TempDir Path project) throws IOException {
        RunRecord record = named(project, "20260101-100000-a");
        new ModelRegistry(record.directory()).save(COUNTER, 0, "first try");

        assertThat(RecordedRun.at(record.directory()).models())
                .singleElement()
                .extracting(model -> model.note())
                .isEqualTo("first try");
    }

    @Test
    void readingARecordThatWroteNoModelsDoesNotGiveItAModelStore(@TempDir Path project)
            throws IOException {
        RunRecord record = named(project, "20260101-100000-a");

        assertThat(RecordedRun.at(record.directory()).models()).isEmpty();
        assertThat(record.directory().resolve(ModelRegistry.DIRECTORY)).doesNotExist();
    }
}
