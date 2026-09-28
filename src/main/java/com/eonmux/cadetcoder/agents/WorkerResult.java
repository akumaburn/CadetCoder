package com.eonmux.cadetcoder.agents;

import java.util.Collections;
import java.util.List;

/** What one worker produced, and how it ended. */
public final class WorkerResult {

    /** How a worker's run finished. */
    public enum Status {
        /** Ran to completion. */
        COMPLETED,
        /** Ran, but reported failure. */
        FAILED,
        /** Stopped before finishing, by the user or by a shutdown. */
        INTERRUPTED
    }

    private final WorkerTask   task;
    private final Status       status;
    private final List<String> output;
    private final long         durationMillis;
    private final String       failure;

    public WorkerResult(WorkerTask task, Status status, List<String> output,
                        long durationMillis, String failure) {
        this.task           = task;
        this.status         = status == null ? Status.FAILED : status;
        this.output         = output == null ? List.of() : List.copyOf(output);
        this.durationMillis = Math.max(0, durationMillis);
        this.failure        = failure;
    }

    public WorkerTask task() {
        return task;
    }

    public Status status() {
        return status;
    }

    /** @return every line the worker printed, in order; never null */
    public List<String> output() {
        return Collections.unmodifiableList(output);
    }

    public long durationMillis() {
        return durationMillis;
    }

    /** @return why the worker failed, or null when it did not */
    public String failure() {
        return failure;
    }

    /** @return whether this worker finished its task */
    public boolean succeeded() {
        return status == Status.COMPLETED;
    }
}
