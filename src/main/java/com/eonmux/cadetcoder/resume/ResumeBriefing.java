package com.eonmux.cadetcoder.resume;

import com.eonmux.cadetcoder.jobs.BackgroundJob;
import com.eonmux.cadetcoder.jobs.JobRegistry;
import com.eonmux.cadetcoder.security.SecretRedactor;
import com.eonmux.cadetcoder.session.ResumePoint;
import com.eonmux.cadetcoder.timers.AgentTimer;
import com.eonmux.cadetcoder.timers.TimerInterval;
import com.eonmux.cadetcoder.timers.TimerRegistry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What a resumed run is told about what its interrupted run left in the background, and the
 * timers that have to be set again.
 *
 * <h2>Jobs</h2>
 *
 * <p>An interrupt leaves jobs running, and an exit stops them. A job the point names is still
 * running when this process started it and it has not ended. Otherwise it is not running now, and
 * the run is told so, so it does not wait for output from a server that is gone.</p>
 *
 * <h2>Timers</h2>
 *
 * <p>Timers live only in memory. When the point's timers are not set in this process, because
 * CadetCoder restarted or the session was opened again, they are set again here, and the run is
 * told their new ids.</p>
 *
 * <h2>Workers</h2>
 *
 * <p>The interrupt stopped the workers the run started. A resumed workers run runs the unfinished
 * tasks again by itself. Any other run is told which workers finished and which were stopped, so
 * it can start the stopped ones again if the work still needs them.</p>
 */
public final class ResumeBriefing {

    private ResumeBriefing() {
    }

    /**
     * Sets the point's timers again where this process does not hold them, and describes what
     * the interrupted run left in the background.
     *
     * @param point where the run stopped
     * @return the lines to tell the resumed run, or an empty string when there is nothing to tell
     */
    public static String restore(ResumePoint point) {
        List<String> lines = new ArrayList<>();
        if (!ResumePoint.WORKERS.equals(point.kind())) {
            lines.addAll(workers(point.workers()));
        }
        lines.addAll(jobs(point.jobs()));
        if (!point.timersAreSet()) {
            lines.addAll(timersSetAgain(point.timers()));
        }
        return String.join("\n", lines);
    }

    private static List<String> workers(List<ResumePoint.Worker> workers) {
        List<String> lines = new ArrayList<>();
        if (workers.isEmpty()) {
            return lines;
        }
        lines.add("The interrupt stopped the workers the run started:");
        for (ResumePoint.Worker worker : workers) {
            lines.add("- Worker " + worker.index() + " (" + worker.task() + ") "
                      + (worker.finished() ? "finished: " + worker.status().toLowerCase()
                                           : "was stopped before it finished") + ".");
        }
        lines.add("Start the stopped ones again with workers if the work still needs them.");
        return lines;
    }

    private static List<String> jobs(List<ResumePoint.Job> jobs) {
        List<String> lines = new ArrayList<>();
        for (ResumePoint.Job job : jobs) {
            // The point keeps the command redacted, so the running job's command is compared the same.
            Optional<BackgroundJob> here = JobRegistry.find(job.id())
                    .filter(found -> SecretRedactor.redactCommandLine(found.command())
                                                   .equals(job.command()));
            String state;
            if (here.isEmpty()) {
                state = "stopped when CadetCoder exited, so it does not run now. Start it again if "
                        + "the work needs it.";
            } else if (here.get().isDone()) {
                state = "has ended since the interrupt.";
            } else {
                state = "is still running.";
            }
            lines.add("Job " + job.id() + " (" + job.command()
                      + (job.description() == null || job.description().isBlank()
                         ? "" : ", " + job.description())
                      + ") " + state);
        }
        return lines;
    }

    private static List<String> timersSetAgain(List<ResumePoint.Timer> timers) {
        List<String> lines = new ArrayList<>();
        for (ResumePoint.Timer timer : timers) {
            Duration every = Duration.ofSeconds(timer.intervalSeconds());
            String   about = "\"" + timer.instruction() + "\", every " + TimerInterval.render(every);
            try {
                AgentTimer set = TimerRegistry.create(timer.instruction(), every,
                                                      timer.firingsLeft());
                lines.add("Timer " + about + " was set again as " + set.id()
                          + ", because this CadetCoder process did not hold it.");
            } catch (RuntimeException refused) {
                lines.add("Timer " + about + " could not be set again: " + refused.getMessage());
            }
        }
        return lines;
    }
}
