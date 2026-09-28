package com.eonmux.cadetcoder.jobs;

import com.eonmux.cadetcoder.timers.TimerScope;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Every background job, and the one place they are started, found and stopped.
 *
 * <h2>Why the state is process-global</h2>
 *
 * <p>A job is started by a command -- the model's, run through the registry like any other -- and
 * read by whichever loop is about to build the next prompt, by a later step asking what happened,
 * and by the status bar the person at the terminal is looking at. Those are not connected by any
 * object one of them could be handed: commands are discovered reflectively and constructed with no
 * arguments. {@code TimerRegistry} is global for exactly this reason and is kept honest the same
 * way -- every read and write goes through one monitor, and the lists handed out are copies.</p>
 *
 * <h2>Why every job is visible but a notice is not</h2>
 *
 * <p>Jobs are processes on one machine and the person at the terminal is answerable for all of
 * them, so {@link #all()} hides nothing: a build a worker started is still a build running on their
 * computer. What a job FINISHING is announced into is a different question. Several agents run at
 * once, and "the build finished" delivered to whichever prompt happened to be built next would tell
 * an agent working on something else to look at a job it has never heard of. So a job records whose
 * work it is, and {@link #takeFinished()} reports only that scope's.</p>
 *
 * <h2>Why finished jobs are taken rather than looked at</h2>
 *
 * <p>The same rule {@code TimerRegistry} follows. A reader that could ask what has finished without
 * consuming it would announce the same job into two prompts whenever anything read twice, and the
 * announcement would stop meaning "this has just happened". Taking is not spending: whoever builds a
 * prompt says afterwards whether it was sent, with {@link #delivered()} or
 * {@link #returnUndelivered()}.</p>
 */
public final class JobRegistry {

    /**
     * How many jobs may be running at once.
     *
     * <p>Every running job takes a line of every prompt that reports on it and a share of the
     * machine. A handful is work left to get on with; a dozen is a queue nobody is managing.</p>
     */
    public static final int MAX_RUNNING = 8;

    private static final Object LOCK = new Object();

    /**
     * Job id to job, oldest first. Replaced wholesale rather than edited.
     *
     * <p>An unmodifiable view of a {@link LinkedHashMap} and not {@link Map#copyOf}, which keeps no
     * order: with it, {@code job list}, the prompt's list of running jobs and the endings that
     * arrive together came out in an order that changed from one run of the program to the
     * next.</p>
     */
    private static Map<String, BackgroundJob> jobs = Map.of();

    /** Jobs whose ending has been announced already, so it is announced once. */
    private static Set<String> announced = Set.of();

    /**
     * One scope's jobs, taken for a prompt that has not been sent yet.
     *
     * <p>Keyed by scope rather than held in one slot, for the reason {@code TimerRegistry} keys its
     * own withdrawals: several agents build prompts at once. A single slot is overwritten by
     * whichever scope took second, so the first scope's endings are marked announced with nothing
     * left to put them back -- and a failure in the second scope then un-announces the first
     * scope's jobs, which are genuinely on their way. Both halves lose an ending, in opposite
     * directions.</p>
     */
    private static Map<String, List<BackgroundJob>> inFlight = Map.of();

    /**
     * Who is owed each job's ending, by job id.
     *
     * <p>Kept here rather than on the job because it changes and the job does not: a worker that
     * ends while its build runs is no longer anybody, so what it started is handed to the session.
     * {@link BackgroundJob#owner()} stays what it always was -- who started it.</p>
     */
    private static Map<String, String> owed = Map.of();

    /** How many jobs are between the capacity check and being recorded. */
    private static int starting;

    private static int nextId = 1;

    /** Whether the hook that stops surviving jobs at exit has been installed. */
    private static boolean hookInstalled;

    /** Whether this is listening for scopes ending; see {@link #watchForScopesEnding()}. */
    private static boolean watchingScopes;

    private JobRegistry() {
    }

    /**
     * Starts a command in the background.
     *
     * @param command     the shell command line, already screened by whoever is starting it
     * @param description what it is for, or {@code null}
     * @param directory   where to run it
     * @return the job, already running
     * @throws IllegalStateException when {@link #MAX_RUNNING} are already going
     * @throws IOException           when the process cannot be started
     */
    public static BackgroundJob start(String command, String description, File directory)
            throws IOException {
        String owner = TimerScope.current();
        String id;
        synchronized (LOCK) {
            sweep();
            if (running().size() + starting >= MAX_RUNNING) {
                throw new IllegalStateException(
                        "there are already " + MAX_RUNNING + " background jobs running, which is as"
                        + " many as one session may have; stop one before starting another");
            }
            installExitHook();
            watchForScopesEnding();
            id = "j" + nextId++;
            starting++;
        }
        // Started outside the monitor: a fork and an exec are slow enough to be felt, and every
        // other reader waits on this lock -- the status bar redraws through running(), and `job
        // list` and `job wait` tick through it. The slot was claimed above, so the ceiling still
        // holds while this runs.
        BackgroundJob job;
        try {
            job = new BackgroundJob(id, command, description, owner, directory);
        } catch (IOException | RuntimeException notStarted) {
            synchronized (LOCK) {
                starting--;
            }
            throw notStarted;
        }
        synchronized (LOCK) {
            starting--;
            Map<String, BackgroundJob> updated = new LinkedHashMap<>(jobs);
            updated.put(id, job);
            jobs = Collections.unmodifiableMap(updated);
            Map<String, String> owners = new LinkedHashMap<>(owed);
            owners.put(id, owner);
            owed = Map.copyOf(owners);
        }
        return job;
    }

    /** @return every job this session has started, oldest first */
    public static List<BackgroundJob> all() {
        synchronized (LOCK) {
            sweep();
            return List.copyOf(jobs.values());
        }
    }

    /** @return the jobs still going, oldest first */
    public static List<BackgroundJob> running() {
        synchronized (LOCK) {
            sweep();
            List<BackgroundJob> live = new ArrayList<>();
            for (BackgroundJob job : jobs.values()) {
                if (!job.isDone()) {
                    live.add(job);
                }
            }
            return List.copyOf(live);
        }
    }

    /**
     * The calling scope's jobs that are still going.
     *
     * <p>The same jobs whose endings {@link #takeFinished()} would tell this scope about, so what a
     * prompt lists as running is what the same scope is later told has ended.</p>
     *
     * @return the jobs still going that this scope is owed news of, oldest first
     */
    public static List<BackgroundJob> runningHere() {
        synchronized (LOCK) {
            sweep();
            String              scope = TimerScope.current();
            List<BackgroundJob> live  = new ArrayList<>();
            for (BackgroundJob job : jobs.values()) {
                if (!job.isDone() && scope.equals(owed.getOrDefault(job.id(), job.owner()))) {
                    live.add(job);
                }
            }
            return List.copyOf(live);
        }
    }

    /**
     * Whether a job's ending has been announced into a prompt already.
     *
     * @param job a job
     * @return whether it has ended and its ending was taken for a prompt
     */
    public static boolean wasAnnounced(BackgroundJob job) {
        synchronized (LOCK) {
            return job != null && announced.contains(job.id());
        }
    }

    /**
     * Finds one job.
     *
     * @param id what it was called when it started; the leading {@code j} may be left off
     * @return the job, when there is one by that name
     */
    public static Optional<BackgroundJob> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        String wanted = id.trim().toLowerCase(java.util.Locale.ROOT);
        String padded = wanted.startsWith("j") ? wanted : "j" + wanted;
        synchronized (LOCK) {
            BackgroundJob job = jobs.get(padded);
            return Optional.ofNullable(job);
        }
    }

    /**
     * The jobs belonging to the calling scope that have finished since anybody last looked.
     *
     * <p>Marks them as announced in the same locked step that reports them, and keeps them in hand
     * until the caller says whether the prompt was sent.</p>
     *
     * @return the newly finished jobs, in the order they were started
     */
    public static List<BackgroundJob> takeFinished() {
        synchronized (LOCK) {
            sweep();
            String              scope = TimerScope.current();
            List<BackgroundJob> ended = new ArrayList<>();
            for (BackgroundJob job : jobs.values()) {
                if (job.isDone() && !announced.contains(job.id())
                    && scope.equals(owed.getOrDefault(job.id(), job.owner()))) {
                    ended.add(job);
                }
            }
            if (ended.isEmpty()) {
                return List.of();
            }
            Set<String> marked = new LinkedHashSet<>(announced);
            for (BackgroundJob job : ended) {
                marked.add(job.id());
            }
            announced = Set.copyOf(marked);
            withdraw(scope, ended);
            return List.copyOf(ended);
        }
    }

    /** The prompt built with the calling scope's last {@link #takeFinished()} reached the model. */
    public static void delivered() {
        synchronized (LOCK) {
            dropWithdrawal(TimerScope.current());
        }
    }

    /**
     * The prompt built with the calling scope's last {@link #takeFinished()} was never sent.
     *
     * @return how many announcements were put back
     */
    public static int returnUndelivered() {
        synchronized (LOCK) {
            String              scope = TimerScope.current();
            List<BackgroundJob> held  = inFlight.get(scope);
            if (held == null || held.isEmpty()) {
                return 0;
            }
            Set<String> marked = new LinkedHashSet<>(announced);
            for (BackgroundJob job : held) {
                marked.remove(job.id());
            }
            announced = Set.copyOf(marked);
            dropWithdrawal(scope);
            return held.size();
        }
    }

    /** Records what one scope has taken but not yet sent. Called with {@link #LOCK} held. */
    private static void withdraw(String scope, List<BackgroundJob> taken) {
        Map<String, List<BackgroundJob>> next = new LinkedHashMap<>(inFlight);
        next.put(scope, List.copyOf(taken));
        inFlight = Map.copyOf(next);
    }

    /** Forgets what one scope had in flight, whatever became of it. Called with {@link #LOCK} held. */
    private static void dropWithdrawal(String scope) {
        if (!inFlight.containsKey(scope)) {
            return;
        }
        Map<String, List<BackgroundJob>> next = new LinkedHashMap<>(inFlight);
        next.remove(scope);
        inFlight = Map.copyOf(next);
    }

    /**
     * Hands a scope's jobs to the session, because the scope itself is over.
     *
     * <h2>Why a job is re-homed rather than forgotten</h2>
     *
     * <p>A timer belongs to the run that set it and is discarded with it. A job is a process that
     * is still running, and its ending is still a fact somebody has to hear: a worker that starts a
     * build on its last step and exits leaves a build going, and a notice owed to a name no thread
     * will answer to again is a notice nobody ever reads.</p>
     *
     * <h2>Why it must not be left under the worker's name</h2>
     *
     * <p>Worker names repeat -- they are numbered from one in every run -- so the next run's
     * "Worker 2" would be told that a build it has never heard of has failed, with twenty lines of
     * somebody else's output under it. The session is what outlives every run, so it is what
     * inherits them.</p>
     *
     * @param scope the scope that has ended
     */
    static void scopeEnded(String scope) {
        if (scope == null || TimerScope.SESSION.equals(scope)) {
            return;
        }
        synchronized (LOCK) {
            // What it had taken for a prompt it never sent goes back first: the scope is gone, so
            // nothing will ever call delivered() for it, and an ending left marked announced would
            // be owed to the session and never told to it.
            List<BackgroundJob> held = inFlight.get(scope);
            if (held != null && !held.isEmpty()) {
                Set<String> marked = new LinkedHashSet<>(announced);
                for (BackgroundJob job : held) {
                    marked.remove(job.id());
                }
                announced = Set.copyOf(marked);
            }
            dropWithdrawal(scope);
            Map<String, String> owners = new LinkedHashMap<>(owed);
            owners.replaceAll((id, owner) -> scope.equals(owner) ? TimerScope.SESSION : owner);
            owed = Map.copyOf(owners);
        }
    }

    /**
     * Stops one job.
     *
     * @param id the id it was given when it started
     * @return whether there was such a job still running to stop
     */
    public static boolean stop(String id) {
        Optional<BackgroundJob> job = find(id);
        return job.isPresent() && job.get().stop();
    }

    /**
     * Stops everything still running.
     *
     * @return the jobs that were stopped, in the order they were started
     */
    public static List<BackgroundJob> stopAll() {
        List<BackgroundJob> stopped = new ArrayList<>();
        for (BackgroundJob job : running()) {
            if (job.stop()) {
                stopped.add(job);
            }
        }
        return List.copyOf(stopped);
    }

    /** Notes the ending of anything that has ended, so runtimes stop growing. */
    private static void sweep() {
        for (BackgroundJob job : jobs.values()) {
            job.noteEnded();
        }
    }

    /**
     * Arranges for surviving jobs to be stopped when the session ends.
     *
     * <p>A job is a process, not a thread, so leaving the JVM does not end it: without this, a
     * session that started a watch build and exited would leave it running with nothing left that
     * knows about it, and the next session would start one more. Installed on the first start
     * rather than at class load, so a session that never starts a job never installs a hook.</p>
     */
    private static void installExitHook() {
        if (hookInstalled) {
            return;
        }
        hookInstalled = true;
        Runtime.getRuntime().addShutdownHook(new Thread(JobRegistry::stopAll, "cadet-job-cleanup"));
    }

    /**
     * Asks to be told when a scope ends, so {@link #scopeEnded(String)} can re-home what it left.
     *
     * <p>Registered on the first start, beside the exit hook and for the same reason: a session
     * that never starts a job has nothing to be told about. The listener is what keeps the
     * dependency pointing one way -- jobs know about scopes, scopes know nothing about jobs.</p>
     */
    private static void watchForScopesEnding() {
        if (watchingScopes) {
            return;
        }
        watchingScopes = true;
        TimerScope.onScopeEnd(JobRegistry::scopeEnded);
    }

    /** Forgets everything, stopping anything still running. Used by tests. */
    public static void clear() {
        stopAll();
        synchronized (LOCK) {
            jobs      = Map.of();
            announced = Set.of();
            inFlight  = Map.of();
            owed      = Map.of();
            starting  = 0;
            nextId    = 1;
        }
    }
}
