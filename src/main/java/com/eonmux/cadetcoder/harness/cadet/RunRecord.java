package com.eonmux.cadetcoder.harness.cadet;

import com.eonmux.cadetcoder.harness.Json;
import com.eonmux.cadetcoder.harness.tools.ToolSession;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;

/**
 * Where one run keeps everything it established, for as long as the project lasts.
 *
 * <h2>Why a run gets its own directory rather than a shared one</h2>
 *
 * <p>The ledger is a hash chain, and a chain is only evidence while it has one writer. Two runs
 * appending to the same file interleave into a chain neither of them can certify, and a second
 * attempt at a task that reused the first attempt's directory would destroy the only record of what
 * went wrong the first time -- which is the record somebody comparing the two attempts needs.</p>
 *
 * <h2>Why the name is a timestamp and the uniqueness is not</h2>
 *
 * <p>The stamp is there so a person scanning the directory can find the run they remember. It cannot
 * also be what keeps runs apart: two runs started inside the same second, which is what a worker pool
 * does routinely, would be handed the same name. The directory is therefore created rather than
 * named -- the filesystem settles the collision in one atomic operation, and no two runs can be told
 * they own the same record however close together they began.</p>
 *
 * <h2>Why the record lives inside the project</h2>
 *
 * <p>It is about this project and it should travel and be thrown away with it. {@link #RUNS} sits
 * under the hidden directory this tool already keeps its index and its models in, which is also what
 * keeps it out of {@link WorkspaceTree}: a record inside the observed workspace would make the run's
 * own writing look like the world moving underneath it, and contradict every prediction the agent
 * made about its own actions.</p>
 *
 * @param directory where this run's ledger, beliefs, models and notes are
 */
public record RunRecord(Path directory) {

    /** Where a project keeps what its runs established, relative to the project root. */
    public static final String RUNS = ".cadet/runs";

    /** Where a record says what its run was and how it ended. */
    public static final String SUMMARY_NAME = "summary.json";

    /** The moment a run began, in a form that sorts and that a filesystem will hold. */
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** Where a summary is built before it replaces the one the record already has. */
    private static final String WHILE_WRITING = SUMMARY_NAME + ".writing";

    public RunRecord {
        if (directory == null) {
            throw new IllegalArgumentException("a record has to be somewhere");
        }
    }

    /**
     * Makes a record for a run about to start against a project.
     *
     * @param project the project the run will act on; must already exist
     * @return the record, its directory created and empty
     */
    public static RunRecord under(Path project) {
        if (project == null || !Files.isDirectory(project)) {
            throw new IllegalArgumentException("there is no project at " + project);
        }
        Path runs = project.resolve(RUNS);
        try {
            Files.createDirectories(runs);
            return new RunRecord(Files.createTempDirectory(runs, stamp() + "-"));
        } catch (IOException failure) {
            throw new UncheckedIOException("this run has nowhere to keep its record under " + runs,
                                           failure);
        }
    }

    /** What this run is called, which is what somebody looking for it later reads. */
    public String name() {
        Path name = directory.getFileName();
        return name == null ? "" : name.toString();
    }

    /** What really happened, in the order it happened. */
    public Path ledger() {
        return directory.resolve(ToolSession.LEDGER_NAME);
    }

    /** What the agent holds and what it is still asking. */
    public Path beliefs() {
        return directory.resolve(ToolSession.BELIEFS_NAME);
    }

    /** What the agent wrote down for itself. */
    public Path notes() {
        return directory.resolve(ToolSession.NOTES_NAME);
    }

    /** What the run was and how it ended. */
    public Path summaryFile() {
        return directory.resolve(SUMMARY_NAME);
    }

    /**
     * Writes down what is known about the run so far.
     *
     * <p>Written beside the file and then renamed onto it, because this is called twice -- once
     * before the run and once after -- and the second write is the one a killed process interrupts.
     * A half-written second write that had truncated the first would take the beginning down with
     * the ending, leaving the anonymous directory this file exists to prevent.</p>
     *
     * @param summary what to say about the run
     * @throws RunRecordException if the record will not take it
     */
    public void describe(RunSummary summary) {
        if (summary == null) {
            throw new IllegalArgumentException("a record cannot be described by nothing");
        }
        Path writing = directory.resolve(WHILE_WRITING);
        try {
            Files.createDirectories(directory);
            Files.writeString(writing, Json.canonical(summary.toValue()));
            Files.move(writing, summaryFile(), StandardCopyOption.REPLACE_EXISTING,
                       StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException failure) {
            throw new RunRecordException("this run cannot describe itself in " + summaryFile(),
                                         failure);
        }
    }

    /**
     * What the run said about itself, if it said anything.
     *
     * @return the summary, or empty if the record has none
     * @throws RunRecordException if it has one that cannot be read, which is not the same thing
     */
    public Optional<RunSummary> summary() {
        Path file = summaryFile();
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(RunSummary.fromValue(asRecorded(Json.parse(Files.readString(file)))));
        } catch (IOException | IllegalArgumentException | RunRecordException failure) {
            throw new RunRecordException("the run summary in " + file + " cannot be read: "
                                         + failure.getMessage(), failure);
        }
    }

    @SuppressWarnings ("unchecked")
    private static Map<String, Object> asRecorded(Object written) {
        if (!(written instanceof Map)) {
            throw new RunRecordException("a run summary is an object, not " + Json.canonical(written));
        }
        return (Map<String, Object>) written;
    }

    private static String stamp() {
        return LocalDateTime.now().format(STAMP);
    }
}
