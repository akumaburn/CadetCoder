package com.eonmux.cadetcoder.harness.cadet;

import com.eonmux.cadetcoder.harness.ledger.Ledger;
import com.eonmux.cadetcoder.harness.ledger.LedgerStats;
import com.eonmux.cadetcoder.harness.ledger.Transition;
import com.eonmux.cadetcoder.harness.model.ModelRecord;
import com.eonmux.cadetcoder.harness.model.ModelRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * One run's record, opened to be read rather than written to.
 *
 * <h2>Why reading is a different thing from running</h2>
 *
 * <p>{@link RunRecord} is what a run writes into; this is what somebody opens afterwards. Keeping
 * them apart is what makes it safe to point this at a directory that a run may still be writing, at
 * one left by a run that was killed, and at one that is damaged -- none of which may be repaired,
 * completed or created by the act of looking at them. Nothing here creates a file, which is why the
 * ledger and the model store are opened only once their own files are known to be there: both of
 * those constructors make the directories they expect, and a listing that quietly gave every old
 * record an empty model store would be changing the evidence in order to display it.</p>
 *
 * <h2>Why a damaged record is described rather than thrown</h2>
 *
 * <p>A project accumulates records, and one of them being unreadable is the ordinary case rather
 * than the exceptional one -- a run killed mid-write, a directory half-copied, a file edited by
 * hand. Listing them by throwing on the first bad one would hide every other run behind it, so what
 * is wrong with a record is carried by the record itself and every other one still lists. Reading
 * a run's evidence, on the other hand, does throw: somebody who asked for the ledger of one named
 * run is asking a question that has no shorter answer than "this ledger does not verify".</p>
 */
public final class RecordedRun {

    /** What to ask for to get the most recent run. */
    public static final String LATEST = "latest";

    /** How much of a record's name is the moment it began: {@code yyyyMMdd-HHmmss}. */
    private static final int STAMP_LENGTH = 15;

    private final RunRecord  record;
    private final RunSummary summary;
    private final String     problem;

    private RecordedRun(RunRecord record, RunSummary summary, String problem) {
        this.record  = record;
        this.summary = summary;
        this.problem = problem;
    }

    /**
     * Opens one record.
     *
     * @param directory where the run kept what it established
     * @return the record, whether or not it can say anything about itself
     */
    public static RecordedRun at(Path directory) {
        RunRecord record = new RunRecord(directory);
        try {
            return new RecordedRun(record, record.summary().orElse(null), null);
        } catch (RunRecordException failure) {
            return new RecordedRun(record, null, failure.getMessage());
        }
    }

    /**
     * Every run a project has a record of, newest first.
     *
     * @param project the project to look under
     * @return the records; empty if nothing has ever run here
     * @throws RunRecordException if the records are there but cannot be listed
     */
    public static List<RecordedRun> under(Path project) {
        Path runs = project.resolve(RunRecord.RUNS);
        if (!Files.isDirectory(runs)) {
            return List.of();
        }
        List<RecordedRun> found = new ArrayList<>();
        try (Stream<Path> entries = Files.list(runs)) {
            entries.filter(Files::isDirectory).forEach(directory -> found.add(at(directory)));
        } catch (IOException failure) {
            throw new RunRecordException("the runs of this project cannot be listed in " + runs,
                                         failure);
        }
        found.sort(Comparator.comparing(RecordedRun::stamp)
                             .thenComparing(RecordedRun::began)
                             .reversed()
                             .thenComparing(RecordedRun::name));
        return List.copyOf(found);
    }

    /**
     * The one run a name means.
     *
     * @param project   the project to look under
     * @param reference a whole name, enough of the beginning of one to tell it apart, or
     *                  {@link #LATEST}; nothing means the latest
     * @return the run, or empty if the name fits none
     * @throws RunRecordException if the name fits more than one
     */
    public static Optional<RecordedRun> named(Path project, String reference) {
        List<RecordedRun> runs   = under(project);
        String            wanted = reference == null ? "" : reference.strip();
        if (wanted.isEmpty() || LATEST.equals(wanted)) {
            return runs.isEmpty() ? Optional.empty() : Optional.of(runs.get(0));
        }
        for (RecordedRun run : runs) {
            if (run.name().equals(wanted)) {
                return Optional.of(run);
            }
        }
        List<RecordedRun> beginning = runs.stream()
                                          .filter(run -> run.name().startsWith(wanted))
                                          .toList();
        if (beginning.size() > 1) {
            throw new RunRecordException(wanted + " names more than one run: " + names(beginning));
        }
        return beginning.stream().findFirst();
    }

    /** Where this run kept what it established. */
    public RunRecord record() {
        return record;
    }

    /** What this run is called. */
    public String name() {
        return record.name();
    }

    /** What the run was and how it ended, if the record says. */
    public Optional<RunSummary> summary() {
        return Optional.ofNullable(summary);
    }

    /** Why the record could not say, when it could not. */
    public Optional<String> problem() {
        return Optional.ofNullable(problem);
    }

    /**
     * What really happened, in the few numbers it comes to.
     *
     * @return the totals, or empty if the run never recorded a transition
     * @throws com.eonmux.cadetcoder.harness.ledger.LedgerException if the ledger does not verify
     */
    public Optional<LedgerStats> evidence() {
        return ledger().map(Ledger::stats);
    }

    /**
     * The last few things that really happened.
     *
     * @param count how many to read back
     * @return them, oldest first; empty if the run never recorded a transition
     * @throws com.eonmux.cadetcoder.harness.ledger.LedgerException if the ledger does not verify
     */
    public List<Transition> lastTransitions(int count) {
        return ledger().map(ledger -> ledger.tail(count)).orElseGet(List::of);
    }

    /**
     * Every version of the world model the run wrote, oldest first.
     *
     * @return the versions; empty if it never wrote one
     * @throws com.eonmux.cadetcoder.harness.model.ModelStoreException if the store does not read back
     */
    public List<ModelRecord> models() {
        if (!Files.isDirectory(record.directory().resolve(ModelRegistry.DIRECTORY))) {
            return List.of();
        }
        ModelRegistry     store    = new ModelRegistry(record.directory());
        List<ModelRecord> versions = new ArrayList<>();
        for (String digest : store.digests()) {
            versions.add(store.record(digest));
        }
        return List.copyOf(versions);
    }

    /**
     * What the agent wrote down for itself.
     *
     * @return the notes, or empty if it wrote none
     * @throws RunRecordException if it wrote some that cannot be read
     */
    public Optional<String> notes() {
        if (!Files.isRegularFile(record.notes())) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(record.notes()));
        } catch (IOException failure) {
            throw new RunRecordException("the notes in " + record.notes() + " cannot be read",
                                         failure);
        }
    }

    private Optional<Ledger> ledger() {
        return Files.isRegularFile(record.ledger()) ? Optional.of(new Ledger(record.ledger()))
                                                    : Optional.empty();
    }

    /**
     * The second this run began, as its directory name records it.
     *
     * <p>The name is what orders a listing, because it is the one thing every record has, whether or
     * not it ever managed to describe itself.</p>
     */
    private String stamp() {
        String name = name();
        return name.length() >= STAMP_LENGTH ? name.substring(0, STAMP_LENGTH) : name;
    }

    /**
     * When this run began, to the instant, for the two records whose names agree to the second.
     *
     * <p>Not the primary key: what a summary records is UTC and what a name records is local time,
     * and ordering by one against the other would shuffle the listing by the machine's offset.</p>
     */
    private Instant began() {
        if (summary == null) {
            return Instant.EPOCH;
        }
        try {
            return Instant.parse(summary.began());
        } catch (DateTimeParseException notAnInstant) {
            return Instant.EPOCH;
        }
    }

    private static String names(List<RecordedRun> runs) {
        return String.join(", ", runs.stream().map(RecordedRun::name).toList());
    }
}
