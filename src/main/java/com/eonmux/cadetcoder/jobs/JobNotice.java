package com.eonmux.cadetcoder.jobs;

import com.eonmux.cadetcoder.timers.TimerInterval;

import java.util.List;

/**
 * What a finished background job looks like when the model reads it.
 *
 * <h2>Why a job announces itself at all</h2>
 *
 * <p>An agent that starts a job and is never told it ended has to poll, and polling costs a model
 * call each time to be told "still running". Worse is the agent that forgets: a build started six
 * steps ago, never asked about again, and a run that finishes reporting success while the thing it
 * was meant to verify never even got looked at. The ending is the one moment worth interrupting
 * for, so it is delivered into the next prompt the way a timer firing is.</p>
 *
 * <h2>Why the output is offered rather than included</h2>
 *
 * <p>A build's output is thousands of lines and the useful part is usually the end of it. Pasted
 * into the prompt in full it would push out the conversation it arrived in; summarised here it
 * would be this class deciding what mattered. So the notice carries the outcome, which is short and
 * always relevant, and says how to read the rest.</p>
 */
public final class JobNotice {

    /** Marks the notice as the tool speaking, not the person. */
    private static final String LABEL = "[job]";

    /** How many of a failed job's last lines are worth quoting without being asked. */
    static final int FAILURE_TAIL_LINES = 20;

    private JobNotice() {
    }

    /**
     * Every job the calling scope is owed news of right now, rendered.
     *
     * <p>Withdraws the announcements, so the same job is never reported twice; see
     * {@link JobRegistry#takeFinished}. Withdrawing is not spending: whoever builds a prompt with
     * this in it must say afterwards whether the prompt was sent, with {@link #delivered()} or
     * {@link #undelivered()}.</p>
     *
     * @return the notice to append to the next prompt, or an empty string when nothing has finished
     */
    public static String dueNow() {
        return render(JobRegistry.takeFinished());
    }

    /**
     * The calling scope's jobs that are still going, rendered.
     *
     * <h2>Why every prompt says this</h2>
     *
     * <p>Only endings were announced. Between a start and its ending, a job appeared in no prompt,
     * so a run that wanted to know what it had going asked {@code job list}, and a run that forgot
     * started the same work again. This is read, not taken: it describes the present, and says the
     * same thing in every prompt until it changes.</p>
     *
     * @return the list, or an empty string when none of this scope's jobs is running
     */
    public static String stillRunning() {
        List<BackgroundJob> here = JobRegistry.runningHere();
        if (here.isEmpty()) {
            return "";
        }
        int           inUse = JobRegistry.running().size();
        StringBuilder text  = new StringBuilder();
        text.append(System.lineSeparator()).append(System.lineSeparator())
            .append(LABEL).append(" Your jobs still running (")
            .append(inUse).append(" of the ").append(JobRegistry.MAX_RUNNING)
            .append(" that may run at once are in use):");
        for (BackgroundJob job : here) {
            text.append(System.lineSeparator()).append("  ").append(running(job));
        }
        text.append(System.lineSeparator())
            .append("You are told when each one ends. Do not start the same work again. Go on"
                    + " with work that does not need them, and start other independent work as jobs"
                    + " beside them. When you need a result, wait only for the jobs that give it:"
                    + " `job wait <id>...` returns when the first of them ends, `job wait --all"
                    + " <id>...` when every one has.");
        return text.toString();
    }

    /**
     * One running job on one line: its id, how long it has run, what it is for, what it runs, and
     * how much of its output is unread.
     *
     * @param job a job that has not finished
     * @return the line, without a line break
     */
    public static String running(BackgroundJob job) {
        StringBuilder line = new StringBuilder();
        line.append(job.id()).append(", ").append(TimerInterval.render(job.runtime()))
            .append(" so far: ");
        if (job.description() != null && !job.description().isBlank()) {
            line.append(job.description().strip()).append(". It runs: ");
        }
        line.append(job.command());
        long pending = job.pending();
        if (pending > 0) {
            line.append(" (").append(pending).append(pending == 1 ? " line" : " lines")
                .append(" not read)");
        }
        return line.toString();
    }

    /** The prompt built with the last {@link #dueNow()} reached the model. */
    public static void delivered() {
        JobRegistry.delivered();
    }

    /**
     * The prompt built with the last {@link #dueNow()} was never sent.
     *
     * @return how many announcements were put back
     */
    public static int undelivered() {
        return JobRegistry.returnUndelivered();
    }

    /**
     * @param finished the jobs that have ended, in the order they were started
     * @return the notice, or an empty string when there were none
     */
    public static String render(List<BackgroundJob> finished) {
        if (finished == null || finished.isEmpty()) {
            return "";
        }
        StringBuilder notice = new StringBuilder();
        for (BackgroundJob job : finished) {
            notice.append(System.lineSeparator()).append(System.lineSeparator())
                  .append(one(job));
        }
        return notice.toString();
    }

    /** One job: what it was, how it ended, how long it took, and where its output is. */
    private static String one(BackgroundJob job) {
        StringBuilder text = new StringBuilder();
        text.append(LABEL).append(' ').append(job.id()).append(' ').append(outcome(job))
            .append(" after ").append(TimerInterval.render(job.runtime())).append('.')
            .append(System.lineSeparator())
            .append("It ran: ").append(job.command());
        if (job.description() != null && !job.description().isBlank()) {
            text.append(System.lineSeparator()).append("You started it to: ").append(job.description());
        }

        long pending = job.pending();
        if (pending == 0) {
            text.append(System.lineSeparator()).append("It printed nothing you have not read.");
        } else {
            text.append(System.lineSeparator())
                .append("It printed ").append(pending).append(pending == 1 ? " line" : " lines")
                .append(" you have not read; read them with `job output ").append(job.id())
                .append("`.");
        }

        // A failure's last lines go in unasked, whether or not anything is still unread. The whole
        // point of the notice is that nobody is watching, and a run told only that the build failed
        // will either ask for the output as its next step -- which is the step this notice was
        // meant to save -- or, worse, act on the failure without having read why. Reading a job's
        // output while it was still running leaves nothing pending, and that is exactly the run
        // that most needs to be shown how it ended.
        if (!job.succeeded()) {
            List<String> tail = job.output().since(
                    Math.max(0, job.output().produced() - FAILURE_TAIL_LINES), FAILURE_TAIL_LINES);
            if (!tail.isEmpty()) {
                text.append(System.lineSeparator()).append("Its last lines were:")
                    .append(System.lineSeparator());
                for (String line : tail) {
                    text.append(line).append(System.lineSeparator());
                }
            }
        }
        return text.toString();
    }

    /** How a job ended, said the way somebody would say it. */
    private static String outcome(BackgroundJob job) {
        if (job.state() == BackgroundJob.State.STOPPED) {
            return "was stopped";
        }
        int code = job.exitCode().orElse(-1);
        return code == 0 ? "finished successfully" : "failed with exit code " + code;
    }
}
