package com.eonmux.cadetcoder.resume;

import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.agents.WorkerPool;
import com.eonmux.cadetcoder.agents.WorkerResult;
import com.eonmux.cadetcoder.agents.WorkerRun;
import com.eonmux.cadetcoder.agents.WorkerTask;
import com.eonmux.cadetcoder.commands.CommandUsage;
import com.eonmux.cadetcoder.jobs.BackgroundJob;
import com.eonmux.cadetcoder.jobs.JobRegistry;
import com.eonmux.cadetcoder.logging.DebugLogger;
import com.eonmux.cadetcoder.security.SecretRedactor;
import com.eonmux.cadetcoder.session.ResumePoint;
import com.eonmux.cadetcoder.session.SessionManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One run that can be resumed, and the runs inside it.
 *
 * <h2>Why only the outermost run saves a resume point</h2>
 *
 * <p>Runs nest. Each pass of a loop is a chat, and a chat's model can start workers, each of which
 * is an agent. An interrupt stops all of them, and each one reports it. What the user can carry on
 * is the run they started, so only the outermost run saves the session's resume point. A run inside
 * it hands its own point to the run around it, which keeps it as part of its own: a loop keeps the
 * point of the pass it was on.</p>
 *
 * <h2>Why the workers are stopped here</h2>
 *
 * <p>A worker is an agent on a thread of its own, and nothing about the interrupt reaches it. Left
 * running, it spends model requests for a run the user stopped, and nothing reports what it did.
 * Every workers run started inside a run is noted on the outermost one, and stopped when that run
 * is interrupted. Which of the workers finished, and what each printed, goes into the
 * point, so a resume runs only the tasks that did not finish.</p>
 *
 * <p>Jobs are left alone. A job is often a server or a watch build that the work needs, and the
 * interrupt was about the run. The point notes which jobs were running, because an exit stops them
 * and a resume in a new process has to be told.</p>
 *
 * <h2>Why a late save is dropped</h2>
 *
 * <p>An interrupted command gets a short time to end, and the shell then takes the next command
 * while the old one may still be on its way out. A save that comes after another outermost run
 * started, or after the user switched sessions, would put a stale point over the newer run's own
 * or into the wrong session. Such a save is dropped. The workers are still stopped.</p>
 *
 * <h2>Why the point is redacted</h2>
 *
 * <p>The point is written to the session file, and it holds what the run read and printed. A key
 * the run came across there is replaced by {@link SecretRedactor} before the point is saved.</p>
 */
public final class ResumeScope implements AutoCloseable {

    /** How many of a worker's last lines are kept for the resume. */
    static final int WORKER_LINES_KEPT = 60;

    private static final ThreadLocal<ResumeScope> CURRENT = new ThreadLocal<>();

    /** How many outermost runs were opened, in this process. */
    private static final AtomicLong OPENED = new AtomicLong();

    private final ResumeScope     parent;
    private final long            order;
    private final String          session;
    private final List<WorkerRun> startedWorkers = new CopyOnWriteArrayList<>();
    private volatile ResumePoint  inner;

    /**
     * @param parent the run this one is inside, or {@code null}
     */
    private ResumeScope(ResumeScope parent) {
        this.parent  = parent;
        this.order   = parent != null ? parent.order : opened();
        this.session = parent == null ? SessionManager.getInstance().getCurrentSessionId()
                                      : parent.session;
    }

    /**
     * Counts a run opened with no run around it, unless a worker opened it.
     *
     * <p>A worker's thread comes from a pool, so its agent opens a scope with no run around it
     * there. It is still part of the workers run, and counted as a run of its own it would make
     * that run's save look late.</p>
     *
     * @return the new run's place in the count
     */
    private static long opened() {
        return WorkerPool.insideWorker() ? OPENED.get() : OPENED.incrementAndGet();
    }

    /**
     * Opens a run on the calling thread, inside whatever run is already open there.
     *
     * @return the run's scope, which the caller closes when the run ends
     */
    public static ResumeScope open() {
        ResumeScope scope = new ResumeScope(CURRENT.get());
        CURRENT.set(scope);
        return scope;
    }

    /** @return the run open on the calling thread, if any */
    public static Optional<ResumeScope> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /**
     * Wraps work handed to another thread so it runs inside the run open on this one.
     *
     * @param body the work
     * @return the same work, wrapped; {@code null} for {@code null}
     */
    public static Runnable carrying(Runnable body) {
        if (body == null) {
            return null;
        }
        ResumeScope scope = CURRENT.get();
        return () -> {
            ResumeScope previous = CURRENT.get();
            CURRENT.set(scope);
            try {
                body.run();
            } finally {
                CURRENT.set(previous);
            }
        };
    }

    /**
     * Notes a workers run as started by the run open on this thread, so an interrupt stops it.
     *
     * @param run the workers run
     */
    public static void workersStarted(WorkerRun run) {
        current().ifPresent(scope -> scope.outermost().startedWorkers.add(run));
    }

    /**
     * Reports that the run stopped part-way, and where: the user interrupted it, or the provider
     * stopped answering after the run had done some work.
     *
     * <p>A run inside another hands the point to the run around it. The outermost run stops the
     * workers it started, adds them, the running jobs and the session's timers to the point, and
     * saves it as the session's resume point. A worker is part of a run that saves its own point,
     * so it saves none.</p>
     *
     * @param point where the run stopped, holding the workers it already knows of
     */
    public void stopped(ResumePoint point) {
        if (parent != null) {
            parent.inner = point;
            return;
        }
        if (WorkerPool.insideWorker()) {
            return;
        }
        List<ResumePoint.Worker> workers = new ArrayList<>(point.workers());
        for (WorkerRun run : startedWorkers) {
            if (!run.isDone()) {
                run.cancel();
            }
            workers.addAll(stateOf(run));
        }
        if (!stillCurrent()) {
            DebugLogger.getInstance().info("ResumeScope", "A " + point.kind() + " run stopped after"
                                           + " the user moved on; its resume point is not saved.");
            return;
        }
        SessionManager.getInstance().setResumePoint(
                point.withBackground(workers, runningJobs(), ResumePoint.Timer.inSession())
                     .redacted(SecretRedactor::redact, SecretRedactor::redactCommandLine));
        OutputFormatter.printInfo("Saved where it stopped. " + CommandUsage.render("resume")
                                  + " carries it on.");
    }

    /** @return whether no other outermost run started since this one, in the same session */
    private boolean stillCurrent() {
        return OPENED.get() == order
               && Objects.equals(session, SessionManager.getInstance().getCurrentSessionId());
    }

    /** @return the point a run inside this one reported, if one did */
    public Optional<ResumePoint> inner() {
        return Optional.ofNullable(inner);
    }

    /** Ends the run on this thread. */
    @Override
    public void close() {
        if (parent == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(parent);
        }
    }

    private ResumeScope outermost() {
        ResumeScope scope = this;
        while (scope.parent != null) {
            scope = scope.parent;
        }
        return scope;
    }

    /**
     * How far each worker of a run got.
     *
     * @param run a workers run, stopped or finished
     * @return each of its workers, in order
     */
    static List<ResumePoint.Worker> stateOf(WorkerRun run) {
        List<ResumePoint.Worker> workers = new ArrayList<>();
        for (WorkerTask task : run.tasks()) {
            Optional<WorkerResult> result = run.resultFor(task.index());
            String status = result.map(finished -> finished.status().name())
                                  .orElse(ResumePoint.UNFINISHED);
            List<String> output = result.map(WorkerResult::output)
                                        .orElseGet(() -> run.outputSoFar(task.index()));
            workers.add(new ResumePoint.Worker(task.index(), task.task(), status,
                                               lastLines(output)));
        }
        return workers;
    }

    private static List<String> lastLines(List<String> lines) {
        return List.copyOf(lines.subList(Math.max(0, lines.size() - WORKER_LINES_KEPT),
                                         lines.size()));
    }

    private static List<ResumePoint.Job> runningJobs() {
        List<ResumePoint.Job> jobs = new ArrayList<>();
        for (BackgroundJob job : JobRegistry.running()) {
            jobs.add(new ResumePoint.Job(job.id(), job.command(), job.description()));
        }
        return jobs;
    }
}
