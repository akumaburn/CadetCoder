package com.eonmux.cadetcoder.agents;

/**
 * One worker's assignment: the shared briefing every worker sees, plus the task only this one has.
 *
 * <p>Immutable, so the same instance can be read by the thread running the task, by the shell
 * rendering it, and by whatever inspects it afterwards, without any of them coordinating.</p>
 *
 * @param index    1-based position in the run, used to name the worker
 * @param task     what this worker alone is asked to do
 * @param briefing context shared verbatim by every worker in the run; may be empty
 */
public record WorkerTask(int index, String task, String briefing) {

    public WorkerTask {
        if (index < 1) {
            throw new IllegalArgumentException("worker index must be 1-based");
        }
        if (task == null || task.isBlank()) {
            throw new IllegalArgumentException("a worker must be given a task");
        }
        task     = task.strip();
        briefing = briefing == null ? "" : briefing.strip();
    }

    /** @return how this worker is named in the transcript, e.g. {@code Worker 2} */
    public String name() {
        return "Worker " + index;
    }

    /**
     * @return the task, whole, on one line; a heading fits it to the width it has
     */
    public String label() {
        return task.replaceAll("\\s+", " ");
    }

    /**
     * The full instruction handed to this worker.
     *
     * <p>Briefing first, task last. The shared half is identical across workers and therefore the
     * part a provider can serve from a cached prefix; putting the differing half at the end is what
     * makes that possible, and is the same append-only discipline the main loop's prompts follow.</p>
     *
     * @return the prompt text
     */
    public String prompt() {
        return briefing.isEmpty() ? task : briefing + "\n\nYour task:\n" + task;
    }
}
