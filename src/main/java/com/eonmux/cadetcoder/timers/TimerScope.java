package com.eonmux.cadetcoder.timers;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Whose timers the thread running right now is working with.
 *
 * <h2>Why timers are not simply one list</h2>
 *
 * <p>Several agents can be running at once. A worker run puts up to eight of them on their own
 * threads, each with its own task, its own transcript and nothing shared but the briefing -- so a
 * timer one of them sets is a note that one of them left itself. Delivered from a single list, that
 * note would arrive in whichever agent's prompt happened to be built next, telling an agent working
 * on something else to check on a job it has never heard of, while the agent that set it waited for
 * a reminder that had already been handed away.</p>
 *
 * <h2>Why the scope is the thread and not the run</h2>
 *
 * <p>The interactive shell runs every line the user types on a fresh thread, so a scope keyed by
 * thread identity would lose the timers between one message and the next -- which is the case the
 * feature exists for, since a job long enough to need checking on outlives the turn that started
 * it. The name is what is scoped instead: everything the person at the terminal drives shares
 * {@link #SESSION} however many threads it is spread over, and a worker is the only thing that
 * takes a name of its own, for exactly as long as it runs.</p>
 */
public final class TimerScope {

    /** Everything the person at the terminal is driving, however many threads it takes. */
    public static final String SESSION = "session";

    private static final ThreadLocal<String> CURRENT = ThreadLocal.withInitial(() -> SESSION);

    /** What has asked to be told when a named scope ends; see {@link #onScopeEnd(Consumer)}. */
    private static final List<Consumer<String>> ENDED = new CopyOnWriteArrayList<>();

    private TimerScope() {
    }

    /** @return the scope whose timers this thread reads and writes */
    public static String current() {
        return CURRENT.get();
    }

    /**
     * Runs {@code body} with its own timers, and gives the thread back as it was found.
     *
     * <p>Given the work rather than an enter/leave pair because the threads that need a scope of
     * their own come from a pool and are reused: a scope left set by one task would be inherited by
     * the next one to run on that thread, which is the leak this exists to prevent, arriving from
     * the mechanism meant to prevent it.</p>
     *
     * <h2>Why what was set inside is forgotten on the way out</h2>
     *
     * <p>A named scope is one run of one worker, and the names repeat: workers are numbered from
     * one in every run, so "Worker 1" is a different agent doing different work each time
     * {@code workers} is used. Timers left behind under that name would be handed to the next
     * worker to carry it -- told to check on a job it has never heard of, with part of its
     * allowance already spent -- which is the cross-talk this exists to prevent, arriving by way of
     * the mechanism meant to prevent it. The session is what everything the person at the terminal
     * drives shares and outlives any one run of anything, so it is never what is discarded.</p>
     *
     * @param owner what to call this scope; blank means {@link #SESSION}
     * @param body  the work to run inside it
     */
    public static void in(String owner, Runnable body) {
        String previous = CURRENT.get();
        String scope    = owner == null || owner.isBlank() ? SESSION : owner;
        CURRENT.set(scope);
        try {
            body.run();
        } finally {
            CURRENT.set(previous);
            if (!SESSION.equals(scope) && !scope.equals(previous)) {
                TimerRegistry.forget(scope);
                for (Consumer<String> listener : ENDED) {
                    listener.accept(scope);
                }
            }
        }
    }

    /**
     * Asks to be told when a named scope ends.
     *
     * <h2>Why anything else has to be told rather than asked</h2>
     *
     * <p>A scope is one run of one worker, and what the run leaves behind is not all the same kind
     * of thing. A timer is a note to itself and is discarded here. A background job is a process
     * that is still running, and what becomes of it is the job register's business, not this
     * one's. That register already depends on this class to know whose work a job is; this class
     * depending on it in return would be a cycle, and a scope would then mean whatever the last
     * package to be added decided it meant. So it listens.</p>
     *
     * <p>Listeners are run after the timers are forgotten and after the thread's scope is restored,
     * so one that reads {@link #current()} sees the scope it is being told about as already
     * over.</p>
     *
     * @param listener told the name of each scope as it ends; never {@link #SESSION}, which does
     *                 not end while the process lives
     */
    public static void onScopeEnd(Consumer<String> listener) {
        if (listener != null) {
            ENDED.add(listener);
        }
    }

    /**
     * Carries this thread's scope onto whichever thread ends up running {@code body}.
     *
     * <p>Work is handed to a thread of its own often enough that a scope which only ever meant "the
     * thread I am on" would be wrong most of the time it mattered: every interruptible command is
     * dispatched that way so an interrupt has something to interrupt, and {@code timer} is one of
     * them. A new thread starts at {@link #SESSION}, so a worker that set itself a reminder filed it
     * under the person at the terminal -- the worker was never reminded, and the user was told to
     * check on a job they had never started.</p>
     *
     * <p>This borrows the scope rather than owning it: what the work leaves behind belongs to
     * whoever the scope belongs to, so nothing is forgotten on the way out. {@link #in} is still
     * what opens a scope, and still what closes it.</p>
     *
     * @param body the work about to be moved to another thread
     * @return the same work, wrapped so it reads and writes this thread's scope
     */
    public static Runnable carrying(Runnable body) {
        if (body == null) {
            return null;
        }
        String scope = CURRENT.get();
        return () -> {
            String previous = CURRENT.get();
            CURRENT.set(scope);
            try {
                body.run();
            } finally {
                CURRENT.set(previous);
            }
        };
    }
}
