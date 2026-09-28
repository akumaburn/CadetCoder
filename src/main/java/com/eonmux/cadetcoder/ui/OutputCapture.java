package com.eonmux.cadetcoder.ui;

import com.eonmux.cadetcoder.logging.SessionLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Redirects one thread's console output to a collector instead of the shared console.
 *
 * <h2>Why this exists</h2>
 *
 * <p>{@link OutputRouter} captures output by replacing {@code System.out} for the whole process.
 * That is right for a single run, and useless the moment two things run at once: several workers all
 * writing to one stream produce a transcript whose lines cannot be attributed to whoever wrote them,
 * so the interleaving is not merely ugly but unrecoverable.</p>
 *
 * <p>A thread-local sink is the smallest thing that fixes it. A worker installs one for the duration
 * of its task; everything that thread prints is collected under that worker, and every other thread —
 * including the shell's own — is untouched.</p>
 *
 * <h2>What this is not</h2>
 *
 * <p>It is not a general output-suppression mechanism. Code that wants to hide output should not
 * print it. This exists so concurrent work can be told apart, and it deliberately keeps every line
 * rather than dropping any, because a worker's output is the record of what it did.</p>
 */
public final class OutputCapture {

    private static final ThreadLocal<Consumer<String>> SINK = new ThreadLocal<>();

    /**
     * The part of a line that has arrived without its newline yet.
     *
     * <p>A sink is given whole lines, because that is what a worker's transcript is made of. Output
     * does not arrive that way: a {@code PrintStream} hands over whatever was written, which may be
     * half a line, several lines, or a line and the start of the next. Held per thread alongside the
     * sink, so the two are installed and released together.</p>
     */
    private static final ThreadLocal<StringBuilder> PARTIAL = new ThreadLocal<>();

    /**
     * Whether the calling thread's output reaches the console as well as its sink.
     *
     * <h2>Why a capture would otherwise hide a run that is meant to be watched</h2>
     *
     * <p>A capture exists so concurrent work can be told apart, and it takes output INSTEAD of
     * printing it -- which is right for a worker nobody is watching and wrong for a run that is the
     * only thing happening. {@code loop} runs a hundred passes and keeps the tail of each one to
     * tell the next; collected the ordinary way, the user would watch a blank screen for an hour
     * and be handed a summary at the end, with no way to steer or stop it.</p>
     */
    private static final ThreadLocal<Boolean> ECHOING = ThreadLocal.withInitial(() -> false);

    /** The streams this class installed, so an install that is still in place is not repeated. */
    private static java.io.PrintStream bridgedOut;
    private static java.io.PrintStream bridgedErr;

    private OutputCapture() {
    }

    /**
     * Makes a bare {@code System.out.print} reach the calling thread's sink.
     *
     * <p>Almost everything printed here goes through {@link UnifiedOutput}, which consults this
     * class directly. A few places write to {@code System.out} itself, and under the interactive
     * shell {@link OutputRouter} catches those. On the plain command line nothing did, so a command
     * that printed that way was invisible to a capture -- and the caller could not tell the
     * difference between "the command printed nothing" and "the command's output was dropped".</p>
     *
     * <p>The replacement is installed <b>once</b> and never swapped back. That matters: the reason
     * the previous arrangement was unsafe is that it saved and restored a process-global on threads
     * that run concurrently, so two of them interleaving left the stream pointing at a dead buffer.
     * A permanent stream that decides per thread has nothing to restore and therefore no window to
     * get wrong.</p>
     */
    public static synchronized void installStreamBridge() {
        // Keyed on "is our stream still the installed one" rather than on a once-only flag: the
        // shell's router and the test harness both call System.setOut, and a flag would record the
        // bridge as present long after something else had replaced it -- at which point raw writes
        // are silently uncaptured again, which is the failure this method exists to prevent.
        if (System.out != bridgedOut) {
            bridgedOut = bridge(System.out);
            System.setOut(bridgedOut);
        }
        if (System.err != bridgedErr) {
            bridgedErr = bridge(System.err);
            System.setErr(bridgedErr);
        }
    }

    /**
     * A stream that offers text to the calling thread's sink, or to {@code delegate} if there is none.
     *
     * <h2>Why the session log is written here</h2>
     *
     * <p>Under the interactive shell the delegate is the router's stream, and the router is what
     * writes the session log. This bridge sits in front of it and is installed permanently, so from
     * the first captured run onwards a capturing thread's output stopped at the sink and never
     * reached the router -- a worker's entire transcript was missing from the session log, silently,
     * while the same lines printed by a non-capturing thread were still recorded.</p>
     *
     * <p>So what the sink takes is logged here and what it does not is logged by whoever the
     * delegate is: every line is recorded exactly once, whichever route it took.</p>
     */
    private static java.io.PrintStream bridge(java.io.PrintStream delegate) {
        return OutputRouter.capturingStream(text -> {
            if (offerChunk(text)) {
                SessionLogger.getInstance().logCommandOutput(text);
                return;
            }
            delegate.print(text);
        });
    }

    /**
     * Whether the calling thread's output is being collected.
     *
     * @return {@code true} when a sink is installed for this thread
     */
    public static boolean isCapturing() {
        return SINK.get() != null;
    }

    /**
     * Runs something with this thread's capture set aside, so it reaches the screen.
     *
     * <p>For a question, and only for a question. An agent step runs with its output collected so
     * the model can read what the command said, and a question asked inside that window was written
     * into the buffer instead of onto the screen: the user saw nothing, answered nothing, and the
     * step was refused on their behalf. The question and the answer belong to the person at the
     * terminal rather than to the step's transcript.</p>
     *
     * <p>Whatever was captured before is flushed first and restored afterwards, so the transcript
     * keeps everything the step actually said, in order.</p>
     *
     * @param body what to run outside the capture
     * @param <T>  what it answers
     * @return what {@code body} returned
     */
    public static <T> T outsideCapture(java.util.function.Supplier<T> body) {
        Consumer<String> sink = SINK.get();
        if (sink == null) {
            return body.get();
        }
        StringBuilder partial = PARTIAL.get();
        flushPartial(sink);
        SINK.remove();
        PARTIAL.remove();
        try {
            return body.get();
        } finally {
            SINK.set(sink);
            PARTIAL.set(partial == null ? new StringBuilder() : partial);
        }
    }

    /**
     * Offers a line to the calling thread's sink.
     *
     * @param text the line, as it would have been printed
     * @return {@code true} when the sink took it and it must not also reach the console
     */
    public static boolean offer(String text) {
        Consumer<String> sink = SINK.get();
        if (sink == null) {
            return false;
        }
        flushPartial(sink);
        sink.accept(text == null ? "" : text);
        return !ECHOING.get();
    }

    /**
     * Offers arbitrary output to the calling thread's sink, splitting it into lines.
     *
     * <p>The counterpart to {@link #offer(String)} for callers that do not have a line to give:
     * everything the process prints reaches the capture through a {@code PrintStream}, in whatever
     * chunks the caller happened to write. A trailing fragment is held until the rest of its line
     * arrives, or until the capture ends.</p>
     *
     * @param text the output, as it would have been written; may span or split lines
     * @return {@code true} when the sink took it and it must not also reach the console
     */
    public static boolean offerChunk(String text) {
        Consumer<String> sink = SINK.get();
        if (sink == null) {
            return false;
        }
        if (text == null || text.isEmpty()) {
            return !ECHOING.get();
        }
        StringBuilder partial = PARTIAL.get();
        if (partial == null) {
            // A sink installed by something other than collectInto; nothing to assemble lines in.
            sink.accept(text);
            return !ECHOING.get();
        }
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                partial.append(text, start, i);
                sink.accept(partial.toString());
                partial.setLength(0);
                start = i + 1;
            }
        }
        partial.append(text, start, text.length());
        return !ECHOING.get();
    }

    /** Commits a held fragment as a line of its own, so nothing printed is silently dropped. */
    private static void flushPartial(Consumer<String> sink) {
        StringBuilder partial = PARTIAL.get();
        if (partial != null && partial.length() > 0) {
            sink.accept(partial.toString());
            partial.setLength(0);
        }
    }

    /**
     * Carries this thread's capture onto whichever thread ends up running {@code body}.
     *
     * <p>A capture belongs to a thread, and some work is handed to a thread of its own after the
     * capture is in place -- every interruptible command is, so that an interrupt has something to
     * interrupt. The new thread starts with no sink, so everything the work printed went to the
     * console and the caller was handed an empty transcript, unable to tell that from a command
     * that printed nothing.</p>
     *
     * <p>The two threads share the sink rather than each getting a copy, because the work is one
     * command and its output is one transcript. They are not reliably sequenced, though, and the
     * comment here used to say they were: when an interrupt's grace period expires,
     * {@code CommandRegistry} stops waiting and reports while the command thread is still alive and
     * still printing through this same sink. A sink handed on by this method is therefore written
     * by two threads at once and has to be safe to be -- see {@code CapturedRun} for the collector
     * that is, and for what an unsynchronized one did.</p>
     *
     * @param body the work about to be moved to another thread
     * @return the same work, wrapped so it reports to this thread's sink; unchanged when there is
     *         no capture to carry
     */
    public static Runnable carrying(Runnable body) {
        Consumer<String> sink = SINK.get();
        if (sink == null || body == null) {
            return body;
        }
        return () -> collectInto(sink, body);
    }

    /**
     * Runs {@code body} with this thread's output collected.
     *
     * <p>The list handed back is a copy taken once the body has finished, and the one it was
     * collected in is safe to append to from more than one thread: {@link #carrying} can put a
     * second thread on the same sink, and an {@link java.util.ArrayList} written by two threads
     * loses lines and throws out of {@code add}. The copy also means a thread that outlived the
     * work cannot add to what the caller is already reading.</p>
     *
     * @param body what to run
     * @return every line the thread printed, in order
     */
    public static List<String> collect(Runnable body) {
        List<String> lines = java.util.Collections.synchronizedList(new ArrayList<>());
        collectInto(lines::add, body);
        synchronized (lines) {
            return new ArrayList<>(lines);
        }
    }

    /**
     * Runs {@code body} with this thread's output handed to {@code sink} as it is produced.
     *
     * <p>The difference from {@link #collect(Runnable)} is only WHEN the caller can see the lines.
     * {@code collect} hands back a list once the body has finished, which is all a caller needs when
     * it is going to wait anyway — and useless for showing a long-running worker's progress, because
     * nothing outside holds the list until the run is over. A worker's transcript is the one thing
     * worth watching while it works; passing the sink in is what makes that possible.</p>
     *
     * @param sink receives every line, on the calling thread, in order
     * @param body what to run
     */
    public static void collectInto(Consumer<String> sink, Runnable body) {
        collect(sink, body, false);
    }

    /**
     * Runs {@code body} with this thread's output handed to {@code sink} AND printed as usual.
     *
     * <p>The difference from {@link #collectInto} is who else sees it. A collected run is one
     * nobody is watching, so its output belongs to its transcript; a run that is the only thing
     * happening is watched, and taking its output away would leave the screen blank for as long as
     * it lasts. Both are wanted at once by anything that keeps a record of a run the user is
     * sitting in front of.</p>
     *
     * @param sink receives every line, on the calling thread, in order
     * @param body what to run
     */
    public static void collectAlongside(Consumer<String> sink, Runnable body) {
        collect(sink, body, true);
    }

    /**
     * @param sink      receives every line
     * @param body      what to run
     * @param alsoPrint whether the console gets the output too
     */
    private static void collect(Consumer<String> sink, Runnable body, boolean alsoPrint) {
        Consumer<String> previousSink    = SINK.get();
        StringBuilder    previousPartial = PARTIAL.get();
        boolean          previousEcho    = ECHOING.get();
        SINK.set(sink);
        PARTIAL.set(new StringBuilder());
        ECHOING.set(alsoPrint);
        try {
            body.run();
        } finally {
            ECHOING.set(previousEcho);
            // A last line with no newline is still something the worker said.
            flushPartial(sink);
            // Restored rather than cleared, so a nested capture cannot silently detach the outer
            // one and send the rest of its owner's output to the shared console.
            if (previousSink == null) {
                SINK.remove();
                PARTIAL.remove();
            } else {
                SINK.set(previousSink);
                PARTIAL.set(previousPartial);
            }
        }
    }
}
