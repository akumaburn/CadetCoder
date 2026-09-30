package com.eonmux.cadetcoder.session;

import com.eonmux.cadetcoder.timers.AgentTimer;
import com.eonmux.cadetcoder.timers.TimerRegistry;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Where an interrupted run stopped, kept so that {@code resume} can carry it on.
 *
 * <h2>Why "continue" is not enough</h2>
 *
 * <p>A new request starts a new run, and a new run knows nothing of the one before it: not the
 * request, not the actions the run took, not which pass of a loop it was on, and not how many of
 * uber mode's closing questions its work had passed. This record holds those, per kind of run, and
 * is saved with the session, so it survives a restart as the conversation does.</p>
 *
 * <h2>What else it holds</h2>
 *
 * <p>The workers the run started, which the interrupt stops, with which of them finished. The jobs
 * that ran when it was interrupted, which an interrupt leaves alone but an exit stops. The session's
 * timers, which live only in memory, so a resume in a new process sets them again. {@link #process}
 * says which process the timers are set in: this one, when it matches {@link #THIS_PROCESS}.</p>
 *
 * @param kind          which command the run was: {@link #CHAT}, {@link #AGENT}, {@link #LOOP},
 *                      {@link #LOOPFRESH} or {@link #WORKERS}
 * @param project       the directory the run worked in
 * @param interruptedAt when it was interrupted, in milliseconds since the epoch
 * @param process       the process whose timers {@link #timers} describes; see {@link #timersAreSet}
 * @param arguments     the command's arguments as it was started with them
 * @param chat          a chat's progress, for {@link #CHAT}
 * @param agent         an agent's progress, for {@link #AGENT}
 * @param loop          a loop's progress, for {@link #LOOP} and {@link #LOOPFRESH}
 * @param workers       the workers the run started, and how far each got
 * @param jobs          the session's jobs that ran when the run was interrupted
 * @param timers        the session's timers
 */
@JsonIgnoreProperties (ignoreUnknown = true)
public record ResumePoint(String kind, String project, long interruptedAt, String process,
                          List<String> arguments, Chat chat, Agent agent, Loop loop,
                          List<Worker> workers, List<Job> jobs, List<Timer> timers) {

    public static final String CHAT      = "chat";
    public static final String AGENT     = "agent";
    public static final String LOOP      = "loop";
    public static final String LOOPFRESH = "loopfresh";
    public static final String WORKERS   = "workers";

    /** Names this process, so a point can tell whether its timers are still set. */
    public static final String THIS_PROCESS = UUID.randomUUID().toString();

    /** A worker whose task did not reach an ending: it was stopped, or it never started. */
    public static final String UNFINISHED = "UNFINISHED";

    public ResumePoint {
        arguments = arguments == null ? List.of() : List.copyOf(arguments);
        workers   = workers == null ? List.of() : List.copyOf(workers);
        jobs      = jobs == null ? List.of() : List.copyOf(jobs);
        timers    = timers == null ? List.of() : List.copyOf(timers);
    }

    /**
     * What a chat had done.
     *
     * @param request          what the user asked for
     * @param transcript       the run's conversation, as the model was shown it
     * @param uberChecksPassed how many of uber mode's closing questions the work had passed
     */
    @JsonIgnoreProperties (ignoreUnknown = true)
    public record Chat(String request, List<String> transcript, int uberChecksPassed) {
        public Chat {
            transcript = transcript == null ? List.of() : List.copyOf(transcript);
        }
    }

    /**
     * What an agent had done.
     *
     * @param record           the directory of the harness run's record, or {@code null} under the
     *                         classic loop, which keeps none
     * @param actions          the classic loop's actions and outcomes, oldest first
     * @param earlier          what the earlier attempts at the task did, when this run was itself
     *                         a resume, or {@code null}
     * @param uberChecksPassed how many of uber mode's closing questions the classic loop's work had
     *                         passed
     */
    @JsonIgnoreProperties (ignoreUnknown = true)
    public record Agent(String record, List<String> actions, String earlier, int uberChecksPassed) {
        public Agent {
            actions = actions == null ? List.of() : List.copyOf(actions);
        }

        /** @return whether there is any work to carry on */
        @JsonIgnore
        public boolean didSomething() {
            return record != null || !actions.isEmpty() || earlier != null && !earlier.isBlank();
        }
    }

    /**
     * How far a loop had got.
     *
     * @param pass            the pass to carry on from, counting from 1
     * @param times           how many passes the loop was asked for
     * @param iterations      how many iterations the loop had run, the part of {@code pass} that
     *                        ran included
     * @param lastSaid        what the last finished pass ended by saying, or {@code null}
     * @param interruptedPass what {@code pass} had done before the interrupt, or {@code null} when
     *                        it had not started
     */
    @JsonIgnoreProperties (ignoreUnknown = true)
    public record Loop(int pass, int times, int iterations, String lastSaid, Chat interruptedPass) {
    }

    /**
     * One worker the run started.
     *
     * @param index  its number in the run, counting from 1
     * @param task   its task
     * @param status {@code COMPLETED}, {@code FAILED}, {@code INTERRUPTED} or {@link #UNFINISHED}
     * @param output what it printed
     */
    @JsonIgnoreProperties (ignoreUnknown = true)
    public record Worker(int index, String task, String status, List<String> output) {
        public Worker {
            output = output == null ? List.of() : List.copyOf(output);
        }

        /** @return whether the task reached an ending of its own, and so is not run again */
        @JsonIgnore
        public boolean finished() {
            return "COMPLETED".equals(status) || "FAILED".equals(status);
        }
    }

    /**
     * A job that ran when the run was interrupted.
     *
     * @param id          the id it was started under
     * @param command     the command line it runs
     * @param description what it was started for
     */
    @JsonIgnoreProperties (ignoreUnknown = true)
    public record Job(String id, String command, String description) {
    }

    /**
     * A timer the session had set.
     *
     * @param instruction     what it reminds the model to do
     * @param intervalSeconds how long between firings
     * @param firingsLeft     how many firings it has left, or {@code 0} for no limit
     */
    @JsonIgnoreProperties (ignoreUnknown = true)
    public record Timer(String instruction, long intervalSeconds, int firingsLeft) {

        /** @return the timers the session has set now */
        public static List<Timer> inSession() {
            return TimerRegistry.inSession().stream().map(Timer::of).toList();
        }

        /**
         * @param timer a timer as the registry holds it
         * @return it, as it is kept: a timer with no firings left is not kept by the registry, so
         *         one that is has at least one
         */
        public static Timer of(AgentTimer timer) {
            int left = timer.limit() == AgentTimer.UNLIMITED
                       ? 0 : Math.max(1, timer.limit() - timer.fireCount());
            return new Timer(timer.instruction(), timer.interval().toSeconds(), left);
        }
    }

    /**
     * A chat interrupted in this project, now.
     *
     * @param arguments the chat's arguments
     * @param chat      what it had done
     * @return the point
     */
    public static ResumePoint chat(List<String> arguments, Chat chat) {
        return now(CHAT, arguments, chat, null, null, List.of());
    }

    /**
     * An agent interrupted in this project, now.
     *
     * @param arguments the agent's arguments
     * @param agent     what it had done
     * @return the point
     */
    public static ResumePoint agent(List<String> arguments, Agent agent) {
        return now(AGENT, arguments, null, agent, null, List.of());
    }

    /**
     * A loop interrupted in this project, now.
     *
     * @param kind      {@link #LOOP} or {@link #LOOPFRESH}
     * @param arguments the loop's arguments
     * @param loop      how far it had got
     * @return the point
     */
    public static ResumePoint loop(String kind, List<String> arguments, Loop loop) {
        return now(kind, arguments, null, null, loop, List.of());
    }

    /**
     * A workers run interrupted in this project, now.
     *
     * @param arguments the command's arguments
     * @param workers   how far each worker got
     * @return the point
     */
    public static ResumePoint workers(List<String> arguments, List<Worker> workers) {
        return now(WORKERS, arguments, null, null, null, workers);
    }

    private static ResumePoint now(String kind, List<String> arguments, Chat chat, Agent agent,
                                   Loop loop, List<Worker> workers) {
        return new ResumePoint(kind, projectHere(), System.currentTimeMillis(), THIS_PROCESS,
                               arguments, chat, agent, loop, workers, List.of(), List.of());
    }

    /** @return the directory this process works in */
    public static String projectHere() {
        return Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize().toString();
    }

    /** @return whether {@link #timers} are set in this process, so that a resume leaves them */
    @JsonIgnore
    public boolean timersAreSet() {
        return THIS_PROCESS.equals(process);
    }

    /** @return whether the run worked in the directory this process works in */
    @JsonIgnore
    public boolean isHere() {
        return projectHere().equals(project);
    }

    /**
     * @param set the session's timers as this process holds them
     * @return this point, with those timers, set in this process
     */
    public ResumePoint withTimers(List<Timer> set) {
        return new ResumePoint(kind, project, interruptedAt, THIS_PROCESS, arguments, chat, agent,
                               loop, workers, jobs, set);
    }

    /**
     * @return this point, with its timers marked as not set in this process
     */
    public ResumePoint withTimersNotSet() {
        return new ResumePoint(kind, project, interruptedAt, null, arguments, chat, agent, loop,
                               workers, jobs, timers);
    }

    /**
     * @param text        what makes one piece of text safe to keep
     * @param commandLine what makes one command line safe to keep
     * @return this point, with every piece of text it holds passed through those
     */
    public ResumePoint redacted(UnaryOperator<String> text, UnaryOperator<String> commandLine) {
        return new ResumePoint(kind, project, interruptedAt, process, each(arguments, text),
                               redacted(chat, text),
                               agent == null ? null : new Agent(
                                       agent.record(), each(agent.actions(), text),
                                       orNull(agent.earlier(), text), agent.uberChecksPassed()),
                               loop == null ? null : new Loop(
                                       loop.pass(), loop.times(), loop.iterations(),
                                       orNull(loop.lastSaid(), text),
                                       redacted(loop.interruptedPass(), text)),
                               workers.stream().map(worker -> new Worker(
                                       worker.index(), text.apply(worker.task()), worker.status(),
                                       each(worker.output(), text))).toList(),
                               jobs.stream().map(job -> new Job(
                                       job.id(), commandLine.apply(job.command()),
                                       orNull(job.description(), text))).toList(),
                               timers.stream().map(timer -> new Timer(
                                       text.apply(timer.instruction()), timer.intervalSeconds(),
                                       timer.firingsLeft())).toList());
    }

    private static Chat redacted(Chat chat, UnaryOperator<String> text) {
        return chat == null ? null : new Chat(orNull(chat.request(), text),
                                              each(chat.transcript(), text),
                                              chat.uberChecksPassed());
    }

    private static List<String> each(List<String> texts, UnaryOperator<String> text) {
        return texts.stream().map(one -> orNull(one, text)).toList();
    }

    private static String orNull(String one, UnaryOperator<String> text) {
        return one == null ? null : text.apply(one);
    }

    /**
     * @param started the workers the run started
     * @param running the jobs that were running
     * @param set     the session's timers
     * @return this point, with what the run left in the background
     */
    public ResumePoint withBackground(List<Worker> started, List<Job> running, List<Timer> set) {
        return new ResumePoint(kind, project, interruptedAt, THIS_PROCESS, arguments, chat, agent,
                               loop, started, running, set);
    }
}
