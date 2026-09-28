package com.eonmux.cadetcoder.harness.cadet;

import com.eonmux.cadetcoder.ExitCode;
import com.eonmux.cadetcoder.harness.loop.RunResult;
import com.eonmux.cadetcoder.harness.loop.RunStatus;

/**
 * What a run came to, and where to find what it established.
 *
 * <h2>Why only two endings are success</h2>
 *
 * <p>An exit code is read by shells and scripts that cannot read anything else about the run, so the
 * question it answers has to be the one they are asking: may the next command run on top of this
 * work. Only {@link RunStatus#GOAL} -- the world confirming it -- and {@link RunStatus#DONE} -- the
 * agent declaring it -- are grounds for yes. An allowance that ran out, an agent that gave up, an
 * episode the world ended and a run somebody took back all leave the task unfinished, whatever else
 * they achieved, and the run before this one answered success to three of the four.</p>
 *
 * <h2>Why the record is part of the outcome</h2>
 *
 * <p>The interesting half of a run is in the ledger, the models and the notes, and none of it is in
 * the result. An outcome that did not carry the directory would leave whoever asked for the run
 * hunting through a hidden directory for the newest of many, which is the point at which people stop
 * reading the evidence at all.</p>
 *
 * @param result what the run came to
 * @param record where what it established was kept
 */
public record RunOutcome(RunResult result, RunRecord record) {

    /** What a shell reads as work that finished. */
    public static final int FINISHED = ExitCode.OK;

    /** What a shell reads as work that did not. */
    public static final int UNFINISHED = ExitCode.FAILED;

    /** What a shell reads as work somebody stopped. */
    public static final int CALLED_OFF = ExitCode.INTERRUPTED;

    public RunOutcome {
        if (result == null || record == null) {
            throw new IllegalArgumentException("an outcome is a result and the record behind it");
        }
    }

    /** Whether the task was finished, as far as anything can tell. */
    public boolean finished() {
        return result.status() == RunStatus.GOAL || result.status() == RunStatus.DONE;
    }

    /**
     * What the shell that started the run is told.
     *
     * <p>A run somebody took back is answered for separately, because it is the one ending that is
     * neither an achievement nor a failure: nothing went wrong with it, and the task is still not
     * done. Folded into {@link #UNFINISHED} it was indistinguishable from an agent that gave up or a
     * world that ended the episode, and a script could not tell "you stopped this" from "this could
     * not be done".</p>
     */
    public int exitCode() {
        if (result.status() == RunStatus.STOPPED) {
            return CALLED_OFF;
        }
        return finished() ? FINISHED : UNFINISHED;
    }

    /** How it ended, what was said as it did, and where the evidence is. */
    public String render() {
        return result.render() + System.lineSeparator() + "record: " + record.directory();
    }
}
