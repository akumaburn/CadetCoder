package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.jobs.BackgroundJob;
import com.eonmux.cadetcoder.jobs.JobNotice;
import com.eonmux.cadetcoder.jobs.JobRegistry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The question put to a run that says it is done while jobs it started are still running.
 *
 * <h2>Why the claim is not simply accepted</h2>
 *
 * <p>A run starts its jobs, does the work that does not depend on them, and declares the task
 * finished. The run ends, the jobs go on, and nothing will resume the run when they end: their
 * results never reach the answer they were started for. The model is the one that knows what each
 * job was for, so it is asked. It can wait for the jobs the answer needs, stop the ones it no longer
 * needs, or say it is done again and leave them running.</p>
 *
 * <h2>Why each set of jobs is asked about once</h2>
 *
 * <p>Leaving a job running is a legitimate choice: a server the person asked to have left up, or a
 * long run whose result the person will read. A run that has been asked and says it is done again
 * has made that choice. A job started after the question is a new one, and it is asked about.</p>
 */
final class JobsLeftRunning {

    /** Where a run records the jobs it has already been asked about, between steps. */
    static final String ASKED_KEY = "jobsAskedAbout";

    private JobsLeftRunning() {
    }

    /**
     * The calling scope's running jobs, when a claim of completion should be asked about them.
     *
     * @param askedAbout the ids of the jobs this run has already been asked about
     * @return every running job of this scope when any of them has not been asked about yet;
     *         empty otherwise
     */
    static List<BackgroundJob> toAskAbout(Collection<String> askedAbout) {
        List<BackgroundJob> running = JobRegistry.runningHere();
        for (BackgroundJob job : running) {
            if (askedAbout == null || !askedAbout.contains(job.id())) {
                return running;
            }
        }
        return List.of();
    }

    /**
     * What a run has been asked about, once it has been asked about these jobs too.
     *
     * @param askedAbout what it had been asked about before
     * @param running    the jobs it is being asked about now
     * @return the ids of both, in order
     */
    static List<String> nowAskedAbout(Collection<String> askedAbout, List<BackgroundJob> running) {
        Set<String> ids = new LinkedHashSet<>();
        if (askedAbout != null) {
            ids.addAll(askedAbout);
        }
        for (BackgroundJob job : running) {
            ids.add(job.id());
        }
        return List.copyOf(new ArrayList<>(ids));
    }

    /**
     * The question.
     *
     * @param running     the jobs still running
     * @param howToFinish how the loop asking it reads "more work" and "finished"
     * @return the prompt the run's next step is sent with
     */
    static String question(List<BackgroundJob> running, String howToFinish) {
        StringBuilder text = new StringBuilder();
        text.append("You said the work is finished, but jobs you started are still running:\n");
        for (BackgroundJob job : running) {
            text.append("  ").append(JobNotice.running(job)).append('\n');
        }
        String first = running.get(0).id();
        text.append("Decide what each one is for before the run ends:\n")
            .append("- If the answer needs a job's result, wait for it and read what it printed:"
                    + " `job wait ").append(first).append("` returns when that job ends, and"
                    + " `job wait --all <id>...` when every job named has ended.\n")
            .append("- If you no longer need a job, stop it with `job stop <id>`.\n")
            .append("- If the jobs should go on after the run ends, say you are finished again."
                    + " This run is not told when they end.\n\n")
            .append(howToFinish);
        return text.toString();
    }
}
