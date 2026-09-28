package com.eonmux.cadetcoder.commands;

import java.util.Objects;

/**
 * One thing the console can show instead of the transcript: a worker, a background job, or the
 * list of both.
 *
 * <h2>Why these are one type and not three fields</h2>
 *
 * <p>The console showed a worker by holding the worker's number, and "no worker" by holding a
 * sentinel. Adding jobs that way means a second field and a second sentinel, and adding the
 * overview means a third, with the rule that exactly one of them may be set written nowhere and
 * enforced by hand at every assignment. One field of one type cannot be two things at once, so the
 * rule needs no enforcing.</p>
 *
 * @param kind   what is being shown
 * @param worker the worker's number, for {@link Kind#WORKER}; zero otherwise
 * @param job    the job's id, for {@link Kind#JOB}; {@code null} otherwise
 */
record BackgroundPane(Kind kind, int worker, String job) {

    /** What a pane shows. */
    enum Kind {

        /** Every worker and every job at once, as a list. */
        OVERVIEW,

        /** One worker's output. */
        WORKER,

        /** One background job's output. */
        JOB
    }

    /** @return the pane that lists everything */
    static BackgroundPane overview() {
        return new BackgroundPane(Kind.OVERVIEW, 0, null);
    }

    /**
     * @param number the worker's number, as the run lists it
     * @return the pane showing that worker
     */
    static BackgroundPane worker(int number) {
        return new BackgroundPane(Kind.WORKER, number, null);
    }

    /**
     * @param id the job's id, such as {@code j1}
     * @return the pane showing that job
     */
    static BackgroundPane job(String id) {
        return new BackgroundPane(Kind.JOB, 0, Objects.requireNonNull(id, "job id"));
    }

    /** @return whether this pane lists everything */
    boolean isOverview() {
        return kind == Kind.OVERVIEW;
    }

    /** @return whether this pane shows one worker */
    boolean isWorker() {
        return kind == Kind.WORKER;
    }

    /** @return whether this pane shows one job */
    boolean isJob() {
        return kind == Kind.JOB;
    }
}
