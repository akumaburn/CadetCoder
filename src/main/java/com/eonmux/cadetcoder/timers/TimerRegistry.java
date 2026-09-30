package com.eonmux.cadetcoder.timers;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every standing timer, and the one place they are advanced.
 *
 * <h2>Why the state is process-global</h2>
 *
 * <p>A timer is set by a command -- the model's, run through the registry like any other -- and read
 * by whichever loop is about to build the next prompt. Those two are not connected by any object
 * either of them could be handed: commands are discovered reflectively and constructed with no
 * arguments, and the loop building the prompt is several frames away from the step that ran the
 * command. Passing a registry between them would mean threading it through every command in the
 * tool so that one of them could use it.</p>
 *
 * <p>The global is kept honest the way this project keeps the other one honest: every read and write
 * goes through one monitor, the timers themselves are immutable and are replaced rather than
 * changed, and the maps handed out are copies. {@link TimerScope} is what stops it being one shared
 * list -- concurrent agents each see their own.</p>
 *
 * <h2>Why firings are taken rather than looked at</h2>
 *
 * <p>{@link #takeDue} advances the timers it reports in the same locked step that reports them. A
 * reader that could ask what is due without consuming it would hand the same firing to two prompts
 * whenever anything read twice before the next tick, and the count the model was given would stop
 * meaning how many times the thing had actually happened.</p>
 */
public final class TimerRegistry {

    /**
     * How many timers one scope may hold at once.
     *
     * <p>Every live timer takes a share of every prompt it fires into. A handful is a check-in; a
     * dozen is a second conversation the model is holding with itself while trying to do the work.
     * </p>
     */
    public static final int MAX_PER_SCOPE = 8;

    private static final Object LOCK = new Object();

    /** Scope name to that scope's timers, replaced wholesale rather than edited. */
    private static Map<String, List<AgentTimer>> timers = Map.of();

    /**
     * What one scope's timers looked like before the firings now in flight were taken from them.
     *
     * <p>Held only between {@link #takeDue} and whichever of {@link #delivered} or
     * {@link #returnUndelivered} follows it. At most one entry per scope, because a scope builds one
     * prompt at a time.</p>
     */
    private static Map<String, Withdrawal> inFlight = Map.of();

    /**
     * Firings taken for a prompt that has not been sent yet.
     *
     * @param before  the scope's timers as they stood before they were advanced
     * @param firings how many firings were taken
     */
    private record Withdrawal(List<AgentTimer> before, int firings) {
    }

    /** Ids are unique across scopes, so one printed anywhere means one timer. */
    private static int nextId = 1;

    private TimerRegistry() {
    }

    /**
     * Sets a timer in the calling thread's scope.
     *
     * @param instruction what it reminds the model to do
     * @param interval    how long between firings
     * @param limit       how many firings it gets, or {@link AgentTimer#UNLIMITED}
     * @param now         the moment it is being set
     * @return the timer as set
     * @throws IllegalStateException when the scope already holds {@link #MAX_PER_SCOPE} of them
     */
    public static AgentTimer create(String instruction, Duration interval, int limit, Instant now) {
        synchronized (LOCK) {
            String           scope = TimerScope.current();
            List<AgentTimer> held  = timers.getOrDefault(scope, List.of());
            if (held.size() >= MAX_PER_SCOPE) {
                throw new IllegalStateException(
                        "there are already " + MAX_PER_SCOPE + " timers running, which is as many as"
                        + " one run may have; cancel one before setting another");
            }
            AgentTimer timer = AgentTimer.starting("t" + nextId++, instruction, interval, limit, now);
            replace(scope, append(held, timer));
            return timer;
        }
    }

    /** As {@link #create(String, Duration, int, Instant)}, set now. */
    public static AgentTimer create(String instruction, Duration interval, int limit) {
        return create(instruction, interval, limit, Instant.now());
    }

    /**
     * The calling scope's timers.
     *
     * @return them, soonest due first; empty when none are set
     */
    public static List<AgentTimer> active() {
        synchronized (LOCK) {
            List<AgentTimer> held   = timers.getOrDefault(TimerScope.current(), List.of());
            List<AgentTimer> sorted = new ArrayList<>(held);
            sorted.sort(Comparator.comparing(AgentTimer::dueAt));
            return List.copyOf(sorted);
        }
    }

    /**
     * The timers the person's session has set, whichever thread asks.
     *
     * <p>{@link #active()} answers for the calling thread's scope. The session is saved from
     * threads that belong to no scope of their own, such as the one that runs when the program
     * exits, and what it records are the timers of the conversation the session holds.</p>
     *
     * @return them, soonest due first; empty when none are set
     */
    public static List<AgentTimer> inSession() {
        synchronized (LOCK) {
            List<AgentTimer> sorted = new ArrayList<>(timers.getOrDefault(TimerScope.SESSION,
                                                                          List.of()));
            sorted.sort(Comparator.comparing(AgentTimer::dueAt));
            return List.copyOf(sorted);
        }
    }

    /**
     * Stops one timer.
     *
     * @param id the id it was given when it was set
     * @return whether there was such a timer in this scope to stop
     */
    public static boolean cancel(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        String wanted = id.trim();
        synchronized (LOCK) {
            String           scope = TimerScope.current();
            List<AgentTimer> held  = timers.getOrDefault(scope, List.of());
            List<AgentTimer> kept  = new ArrayList<>(held.size());
            for (AgentTimer timer : held) {
                if (!timer.id().equalsIgnoreCase(wanted)) {
                    kept.add(timer);
                }
            }
            if (kept.size() == held.size()) {
                return false;
            }
            replace(scope, List.copyOf(kept));
            return true;
        }
    }

    /**
     * Stops every timer in the calling scope.
     *
     * @return how many were stopped
     */
    public static int cancelAll() {
        synchronized (LOCK) {
            String scope   = TimerScope.current();
            int    stopped = timers.getOrDefault(scope, List.of()).size();
            replace(scope, List.of());
            return stopped;
        }
    }

    /**
     * Every firing the calling scope is owed, withdrawing them as it reports them.
     *
     * <p>Withdrawn, not yet spent: see {@link #delivered} and {@link #returnUndelivered}. A second
     * withdrawal before either of those abandons the first, because the prompt it was built for is
     * no longer being sent.</p>
     *
     * @param now the moment being asked about
     * @return the firings, oldest-due first; empty when nothing is owed
     */
    public static List<TimerFiring> takeDue(Instant now) {
        synchronized (LOCK) {
            String           scope = TimerScope.current();
            List<AgentTimer> held  = timers.getOrDefault(scope, List.of());
            if (held.isEmpty()) {
                return List.of();
            }

            List<TimerFiring> fired = new ArrayList<>();
            List<AgentTimer>  kept  = new ArrayList<>(held.size());
            for (AgentTimer timer : soonestFirst(held)) {
                if (!timer.isDue(now)) {
                    kept.add(timer);
                    continue;
                }
                AgentTimer advanced = timer.fired(now);
                fired.add(TimerFiring.of(advanced, now));
                // A spent timer is forgotten here rather than left to be skipped forever: its last
                // firing is still reported, and what it leaves behind is nothing.
                if (!advanced.isSpent()) {
                    kept.add(advanced);
                }
            }
            if (!fired.isEmpty()) {
                withdraw(scope, held, fired.size());
                replace(scope, List.copyOf(kept));
            }
            return List.copyOf(fired);
        }
    }

    /**
     * Records that the firings just taken are on their way to the model but have not arrived.
     *
     * @param scope   whose timers they came from
     * @param before  that scope's timers as they stood before the firings were taken
     * @param firings how many were taken
     */
    private static void withdraw(String scope, List<AgentTimer> before, int firings) {
        Map<String, Withdrawal> next = new LinkedHashMap<>(inFlight);
        next.put(scope, new Withdrawal(List.copyOf(before), firings));
        inFlight = Map.copyOf(next);
    }

    /**
     * The prompt carrying the withdrawn firings reached the model, so they are spent.
     *
     * <p>Idempotent, and harmless when nothing was withdrawn: a turn that had no firing to carry
     * still says it sent its prompt, and saying so must not be a special case at the call site.</p>
     */
    public static void delivered() {
        synchronized (LOCK) {
            drop(TimerScope.current());
        }
    }

    /**
     * The prompt carrying the withdrawn firings was never sent, so they are owed again.
     *
     * <h2>Why the timers go back rather than the firings</h2>
     *
     * <p>A firing is an event that was reported; what has to be undone is the advance that reporting
     * it caused. Putting the scope's timers back as they stood restores the count, the next due
     * time, and a limited timer that was forgotten because that firing was its last -- all of which
     * the firing itself no longer knows.</p>
     *
     * @return how many firings were put back; zero when none were in flight
     */
    public static int returnUndelivered() {
        synchronized (LOCK) {
            String     scope = TimerScope.current();
            Withdrawal held  = inFlight.get(scope);
            if (held == null) {
                return 0;
            }
            drop(scope);
            replace(scope, held.before());
            return held.firings();
        }
    }

    /** Forgets what one scope had in flight, whatever became of it. */
    private static void drop(String scope) {
        if (!inFlight.containsKey(scope)) {
            return;
        }
        Map<String, Withdrawal> next = new LinkedHashMap<>(inFlight);
        next.remove(scope);
        inFlight = Map.copyOf(next);
    }

    /**
     * When the calling scope's next firing is owed.
     *
     * @return the moment, or empty when no timer is set
     */
    public static Optional<Instant> nextDueAt() {
        return active().stream().map(AgentTimer::dueAt).min(Comparator.naturalOrder());
    }

    /**
     * Forgets one scope's timers, because the scope itself is over.
     *
     * <p>Not public, and not a cancel: cancelling is something a run asks for and is told about,
     * while this is the disposal of a run that has ended and can no longer be told anything.
     * {@link TimerScope} is the only thing that knows when that has happened, and is the only
     * caller -- so a scope is a name it has already resolved, never null.</p>
     *
     * @param scope the name the timers were set under
     */
    static void forget(String scope) {
        synchronized (LOCK) {
            drop(scope);
            replace(scope, List.of());
        }
    }

    /** Forgets every timer in every scope, in flight or standing. */
    public static void clearAll() {
        synchronized (LOCK) {
            timers   = Map.of();
            inFlight = Map.of();
        }
    }

    /** The timers of {@code held} in the order they should be considered, soonest due first. */
    private static List<AgentTimer> soonestFirst(List<AgentTimer> held) {
        List<AgentTimer> ordered = new ArrayList<>(held);
        ordered.sort(Comparator.comparing(AgentTimer::dueAt));
        return ordered;
    }

    /** @return {@code held} with {@code timer} on the end, as a new list */
    private static List<AgentTimer> append(List<AgentTimer> held, AgentTimer timer) {
        List<AgentTimer> grown = new ArrayList<>(held);
        grown.add(timer);
        return List.copyOf(grown);
    }

    /** Swaps one scope's timers for {@code replacement}, leaving every other scope alone. */
    private static void replace(String scope, List<AgentTimer> replacement) {
        Map<String, List<AgentTimer>> next = new LinkedHashMap<>(timers);
        if (replacement.isEmpty()) {
            next.remove(scope);
        } else {
            next.put(scope, replacement);
        }
        timers = Map.copyOf(next);
    }
}
