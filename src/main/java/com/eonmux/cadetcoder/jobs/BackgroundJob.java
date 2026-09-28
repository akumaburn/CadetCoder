package com.eonmux.cadetcoder.jobs;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * One shell command left running while the conversation goes on.
 *
 * <h2>What this is for</h2>
 *
 * <p>{@code bash} runs a command and waits for it, which is right for almost everything and wrong
 * for the things that take longer than a step: a full build, a test suite, a server that has to be
 * up while something else is tried against it. Waiting for those spends the turn, the timeout, and
 * eventually the whole run on a process nobody is reading. A job is the same command started and
 * then let go: the step ends immediately with an id, the output accumulates here, and whoever wants
 * it asks for it later.</p>
 *
 * <h2>Why the job is a live object rather than a record</h2>
 *
 * <p>A timer is a value and is replaced whenever it advances, which works because a timer is
 * nothing but the arithmetic of when it next fires. A job owns an operating-system process and a
 * thread reading its pipe, and neither of those can be copied: what this holds IS the job, so it is
 * one object whose state is read under its own monitor.</p>
 *
 * <h2>Why the reader thread is a daemon</h2>
 *
 * <p>It reads a pipe that a child the command left running can hold open for as long as it likes,
 * and a blocked read on such a pipe cannot be taken back -- killing the process does not end it and
 * interrupting the thread does not end it. A non-daemon thread there would keep the whole session
 * from exiting until something unrelated happened to stop. {@code BashCommand} learned the same
 * thing the same way.</p>
 */
public final class BackgroundJob {

    /** Where a job has got to. */
    public enum State {
        /** Still going. */
        RUNNING,
        /** Ended on its own. */
        EXITED,
        /** Ended because it was told to. */
        STOPPED
    }

    /** How long a stopped job is given to end politely before it is ended for it. */
    private static final long TERMINATE_GRACE_MILLIS = 2_000;

    private final String  id;
    private final String  command;
    private final String  description;
    private final String  owner;
    private final Process process;
    private final Instant startedAt;

    private final JobOutput output = new JobOutput();

    /** Where a reader of this job's output has got to; see {@link JobOutput}. */
    private long cursor;

    private volatile Instant endedAt;
    private volatile boolean stopRequested;

    /**
     * Starts a command.
     *
     * @param id          what this job is called, for the life of the session
     * @param command     the shell command line
     * @param description what it is for, or {@code null}
     * @param owner       whose work this is, so a notice reaches the agent that started it
     * @param directory   where to run it
     * @throws IOException when the process cannot be started
     */
    BackgroundJob(String id, String command, String description, String owner,
                  java.io.File directory) throws IOException {
        this.id          = id;
        this.command     = command;
        this.description = description;
        this.owner       = owner;

        ProcessBuilder builder = new ProcessBuilder("bash", "-c", command);
        // One stream, as bash does it: a job's stderr is the half that says why it failed, and two
        // streams read separately arrive interleaved in an order that is nobody's.
        builder.redirectErrorStream(true);
        builder.directory(directory);

        this.process   = builder.start();
        this.startedAt = Instant.now();
        // Nobody is watching this, so nobody can answer it. Left open, a command that reads
        // standard input -- `git push` asking for a password, `npm init` asking anything -- blocks
        // on a read that will never see end-of-file: it holds one of the few slots a session has
        // for as long as the session lasts, prints nothing, and cannot be asked what it wants.
        // Closed, it gets end-of-file at once and fails in the ordinary way, with a reason.
        try {
            process.getOutputStream().close();
        } catch (IOException alreadyGone) {
            // The process ended before its pipe could be closed, which needs no other answer: it
            // has no standard input left to block on.
        }

        Thread reader = new Thread(this::readUntilClosed, "cadet-job-" + id);
        reader.setDaemon(true);
        reader.start();
    }

    /** Copies the process's output into {@link #output} until the pipe reaches end-of-file. */
    private void readUntilClosed() {
        try (BufferedReader lines =
                     new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = lines.readLine()) != null) {
                output.append(line);
            }
        } catch (IOException closed) {
            // The pipe went away, which is what happens when the process is killed. Whatever was
            // read is what there is; the exit code is collected from the process, not from here.
            output.append("[output stopped: " + closed.getMessage() + "]");
        }
    }

    /** @return what this job is called */
    public String id() {
        return id;
    }

    /** @return the shell command line it runs */
    public String command() {
        return command;
    }

    /** @return what it was said to be for, or {@code null} */
    public String description() {
        return description;
    }

    /** @return whose work this is; see {@code TimerScope} for what a scope means */
    public String owner() {
        return owner;
    }

    /** @return the operating-system process id, when the platform reports one */
    public Optional<Long> pid() {
        try {
            return Optional.of(process.pid());
        } catch (UnsupportedOperationException notReported) {
            return Optional.empty();
        }
    }

    /** @return when it was started */
    public Instant startedAt() {
        return startedAt;
    }

    /** @return how long it has been going, or how long it took */
    public Duration runtime() {
        return Duration.between(startedAt, endedAt == null ? Instant.now() : endedAt);
    }

    /** @return whether it has finished, however it finished */
    public boolean isDone() {
        return !process.isAlive();
    }

    /** @return where it has got to */
    public State state() {
        if (process.isAlive()) {
            return State.RUNNING;
        }
        return stopRequested ? State.STOPPED : State.EXITED;
    }

    /**
     * @return the exit code, once it has one; empty while it is still running
     */
    public Optional<Integer> exitCode() {
        return process.isAlive() ? Optional.empty() : Optional.of(process.exitValue());
    }

    /** @return whether it ended on its own with an exit code of zero */
    public boolean succeeded() {
        return state() == State.EXITED && exitCode().orElse(1) == 0;
    }

    /**
     * Notes that the process has ended, so its runtime stops growing.
     *
     * <p>Called by the registry when it notices, rather than by a thread of this job's own: one
     * sweep over every job costs nothing and a watcher thread each does not.</p>
     */
    void noteEnded() {
        if (endedAt == null && !process.isAlive()) {
            endedAt = Instant.now();
        }
    }

    /** @return everything it has printed so far, and how much of it there is */
    public JobOutput output() {
        return output;
    }

    /**
     * What one read of a job's output came to.
     *
     * @param lines   the lines returned, oldest first
     * @param skipped how many lines the limit left out; they were printed, they were not dropped,
     *                and this read is the last chance anybody had to see them
     */
    public record Slice(List<String> lines, long skipped) {

        public Slice {
            lines = List.copyOf(lines);
        }
    }

    /**
     * The output this job has produced since it was last read here.
     *
     * <p>Incremental by default because that is the question asked repeatedly: an agent checking on
     * a build wants what is new, and handing it the whole transcript each time would spend the
     * context window on lines it has already read.</p>
     *
     * <h2>Why what the limit left out is counted</h2>
     *
     * <p>The limit keeps the newest lines, because the end of a build is the part anybody reads.
     * The cursor then moves to the end regardless, since the lines it passed over are older than
     * ones already returned and handing them back later would report a build's middle after its
     * end. That makes them unreachable, so a read that leaves any out has to say so -- a run told
     * "nothing new" about a build whose compile error scrolled past its own limit would conclude
     * the build printed no errors.</p>
     *
     * @param limit the most lines to return, newest kept; zero or less means all of them
     * @return what is new, and how much of it the limit left out
     */
    public synchronized Slice readNew(int limit) {
        long         waiting = output.pendingFrom(cursor);
        List<String> slice   = output.since(cursor, limit);
        cursor = output.produced();
        return new Slice(slice, Math.max(0, waiting - slice.size()));
    }

    /**
     * How many lines a reader here has missed because they were dropped.
     *
     * @return the number of lines gone from the front of the buffer since the last read
     */
    public synchronized long missed() {
        return output.missedBefore(cursor);
    }

    /** @return how many lines are waiting to be read here */
    public synchronized long pending() {
        return output.pendingFrom(cursor);
    }

    /** Puts the read cursor back to the start, so the next read returns everything still kept. */
    public synchronized void rewind() {
        cursor = 0;
    }

    /**
     * Ends the job.
     *
     * <p>Asked politely first and then insisted upon, because a process given no chance to shut
     * down leaves whatever it was writing half-written. The grace is short: this is called when
     * somebody has already decided the job should stop.</p>
     *
     * @return whether there was anything still running to stop
     */
    public boolean stop() {
        if (!process.isAlive()) {
            return false;
        }
        stopRequested = true;
        process.destroy();
        try {
            if (!process.waitFor(TERMINATE_GRACE_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        noteEnded();
        return true;
    }

    /**
     * Waits for this job to end.
     *
     * @param timeout how long to wait at most
     * @return whether it had ended by the time the wait was over
     * @throws InterruptedException when the waiting thread is interrupted
     */
    public boolean awaitEnd(Duration timeout) throws InterruptedException {
        boolean ended = process.waitFor(timeout.toMillis(),
                                        java.util.concurrent.TimeUnit.MILLISECONDS);
        if (ended) {
            noteEnded();
        }
        return ended;
    }

    @Override
    public String toString() {
        return id + " " + state() + " " + command;
    }
}
