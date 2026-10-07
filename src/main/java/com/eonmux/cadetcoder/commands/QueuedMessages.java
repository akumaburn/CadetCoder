package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.InterruptSignal;
import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.session.SessionManager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * Lines the person sent from the shell while a command was still running.
 *
 * <h2>Where a line goes</h2>
 *
 * <p>A message for the model joins the conversation that is running, as soon as the step in
 * progress finishes: the chat loop, the agent's classic loop and the agent's harness loop each take
 * what is waiting before their next request to the model. Everything else waits for the command to
 * end and then runs as if it had been typed then: a {@code /command}, a line that carries a
 * picture, and any message that no conversation of the command took.</p>
 *
 * <h2>Why a conversation opens a handle to take from the queue</h2>
 *
 * <p>The conversation the shell started is the one the person is talking to. A command that the
 * model runs inside it, such as {@code explain}, has a loop of its own on the same thread, and a
 * message it took would end with that inner run, unseen by the conversation it was meant for. So
 * only the first conversation opened on the shell's command thread gets a handle that takes
 * anything; one opened inside it, on any other thread, or outside the shell gets
 * {@link Conversation#NONE}. The command thread is the one the shell started, or the thread the
 * registry hands its command to, through {@link #carrying}.</p>
 *
 * <h2>Why lines are taken from the front only</h2>
 *
 * <p>The lines run in the order they were sent. A conversation takes messages from the front of
 * the queue up to the first line that has to wait for the command to end, so a message sent after
 * a {@code /command} is never answered before that command runs.</p>
 */
public final class QueuedMessages {

    /**
     * One line as it was sent.
     *
     * @param typed    the line as typed, paste markers and all
     * @param shown    the line as typed with secrets masked, for the screen
     * @param expanded the line with each paste marker put back, which is what runs
     * @param message  what a running conversation takes from the line, or {@code null} when the
     *                 line has to wait for the command to end
     */
    public record Line(String typed, String shown, String expanded, String message) {

        /** @return whether a running conversation may take the line */
        public boolean forTheConversation() {
            return message != null;
        }
    }

    /**
     * The context key for the conversation's handle, for a step that runs a loop of its own.
     *
     * <p>The agent runs its whole classic loop, or its whole harness run, inside one step of the
     * conversation. Its loop takes the messages through the conversation's handle, which reaches
     * it in the step's context.</p>
     */
    static final String CONTEXT_KEY = "queuedMessages";

    /** Guards every field below. */
    private static final Object LOCK = new Object();

    private static final Deque<Line> LINES = new ArrayDeque<>();

    /** The thread running the shell's current command, or {@code null} when none is running. */
    private static Thread owner;

    /** The conversation that takes messages, or {@code null} when none is open. */
    private static Conversation open;

    private QueuedMessages() {
    }

    /**
     * Queues a line.
     *
     * @param line what was sent
     * @return how many lines are waiting now
     */
    static int add(Line line) {
        synchronized (LOCK) {
            LINES.addLast(line);
            return LINES.size();
        }
    }

    /** @return the oldest waiting line, removed from the queue, or {@code null} when none waits */
    static Line next() {
        synchronized (LOCK) {
            return LINES.pollFirst();
        }
    }

    /** @return every waiting line in the order sent, all removed from the queue */
    static List<Line> takeAll() {
        synchronized (LOCK) {
            List<Line> all = new ArrayList<>(LINES);
            LINES.clear();
            return all;
        }
    }

    /** @return how many lines are waiting */
    public static int size() {
        synchronized (LOCK) {
            return LINES.size();
        }
    }

    /**
     * Names the thread that runs the shell's command, the only one whose conversation may take.
     *
     * @param thread the command thread, or {@code null} when the command has ended
     */
    static void ownedBy(Thread thread) {
        synchronized (LOCK) {
            owner = thread;
            open  = null;
        }
    }

    /**
     * Wraps work handed to another thread, so the thread takes over as the owner while it runs.
     *
     * <p>The registry runs every command that can be interrupted on a thread of its own, chat and
     * agent among them, so the shell's command thread only waits. Read on the calling thread, at
     * the moment this is called: work handed over by any thread but the owner stays without one.
     * The owner goes back to the calling thread when the work ends, unless the command ended
     * first.</p>
     *
     * @param body the work about to be handed to another thread
     * @return the same work, wrapped; {@code null} for {@code null}
     */
    public static Runnable carrying(Runnable body) {
        if (body == null) {
            return null;
        }
        Thread caller;
        synchronized (LOCK) {
            caller = owner;
        }
        if (caller != Thread.currentThread()) {
            return body;
        }
        return () -> {
            Thread self = Thread.currentThread();
            synchronized (LOCK) {
                if (owner == caller) {
                    owner = self;
                }
            }
            try {
                body.run();
            } finally {
                synchronized (LOCK) {
                    if (owner == self) {
                        owner = caller;
                    }
                }
            }
        };
    }

    /**
     * Opens the handle a conversation takes messages through.
     *
     * @return a handle that takes, for the first conversation on the shell's command thread;
     *         otherwise {@link Conversation#NONE}
     */
    public static Conversation open() {
        synchronized (LOCK) {
            if (owner != Thread.currentThread() || open != null) {
                return Conversation.NONE;
            }
            open = new Conversation();
            return open;
        }
    }

    /**
     * The handle a step's context carries.
     *
     * @param context the step's context
     * @return the conversation's handle, or {@link Conversation#NONE} when the context has none
     */
    static Conversation in(Map<String, Object> context) {
        return context != null && context.get(CONTEXT_KEY) instanceof Conversation handle
               ? handle : Conversation.NONE;
    }

    /** Forgets every line and every owner. Intended for tests. */
    static void clear() {
        synchronized (LOCK) {
            LINES.clear();
            owner = null;
            open  = null;
        }
    }

    /**
     * The handle one conversation takes the messages sent to it through.
     *
     * <h2>Why a message is not done with once it is taken</h2>
     *
     * <p>A conversation takes messages before a request to the model, and the request can still
     * fail, or the run can end on its budget or on a stop before the model reads them. So the
     * conversation calls {@link #delivered()} once the model answers, and closing the handle names
     * each message taken since. Such a message stays part of the run it joined: the run's
     * transcript, its resume point and the session hold it, so it is not run again on its own,
     * which would put it in front of the model twice.</p>
     */
    public static final class Conversation implements AutoCloseable {

        /** A handle that never takes anything. */
        public static final Conversation NONE = new Conversation();

        /** The lines taken since the model last answered, oldest first. Guarded by LOCK. */
        private final List<Line> unconfirmed = new ArrayList<>();

        private Conversation() {
        }

        /**
         * Takes the messages waiting at the front of the queue.
         *
         * <p>Each one is said on screen, so the person sees where it joined the conversation, and
         * recorded in the session as the person's words, so a resumed session has it. Nothing is
         * taken once a stop is asked: the person wants the run to end, and the shell drops the
         * lines.</p>
         *
         * @return the messages, in the order sent; empty when none waits or this handle cannot take
         */
        public List<String> take() {
            List<Line> taken = new ArrayList<>();
            synchronized (LOCK) {
                if (this == NONE || this != open || InterruptSignal.isRequested()) {
                    return List.of();
                }
                while (!LINES.isEmpty() && LINES.peekFirst().forTheConversation()) {
                    taken.add(LINES.pollFirst());
                }
                unconfirmed.addAll(taken);
            }
            List<String> texts = new ArrayList<>(taken.size());
            for (Line line : taken) {
                OutputFormatter.printInfo("Added to the conversation: " + line.shown());
                SessionManager.getInstance().addUserRequest(line.message());
                texts.add(line.message());
            }
            return texts;
        }

        /** Says the model answered a request that carried every message taken so far. */
        public void delivered() {
            synchronized (LOCK) {
                unconfirmed.clear();
            }
        }

        @Override
        public void close() {
            List<Line> unread;
            synchronized (LOCK) {
                if (open == this) {
                    open = null;
                }
                unread = new ArrayList<>(unconfirmed);
                unconfirmed.clear();
            }
            for (Line line : unread) {
                OutputFormatter.printWarning("The run ended before the model read: " + line.shown()
                                             + ". Press Up to send it again.");
            }
        }
    }
}
