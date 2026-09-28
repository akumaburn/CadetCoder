package com.eonmux.cadetcoder.ui;

import com.eonmux.cadetcoder.logging.SessionLogger;

import java.io.PrintWriter;
import java.io.Writer;
import java.util.function.Consumer;

/**
 * The one way the application writes to the console.
 *
 * <p>Everything ends up on {@code System.out}/{@code System.err}, which the {@link OutputRouter}
 * replaces while the interactive shell is running. Going through here rather than printing directly
 * is what lets a worker's output be told apart from everyone else's.</p>
 *
 * <h2>Where a worker's output is caught</h2>
 *
 * <p>{@link OutputCapture} is per thread and the router's stream replacement is per process, so a
 * line can be caught in either place — and it must be caught in exactly one, or it is either
 * duplicated or missing from the session log. While the shell is routing, the router does it, once,
 * for everything the process prints however it was printed. Outside the shell nothing intercepts
 * {@code System.out} at all, so here is the only chance, and these methods take it.</p>
 *
 * <p>Every method that emits text goes through {@link #captured(String)}, because the previous
 * arrangement covered only {@code println} — so a worker's errors, its partial lines and its
 * {@code printf} output escaped into the shell's own transcript, where they appeared with nothing
 * saying which worker had said them.</p>
 */
public final class UnifiedOutput {

    private UnifiedOutput() {
    }

    /**
     * Offers text to the calling thread's collector, when this is the layer that should offer it.
     *
     * <h2>Why the session log is written from here</h2>
     *
     * <p>The invariant above says a line is caught in exactly one place and recorded there. It held
     * for the router and it did not hold here: text taken by the thread's collector was answered
     * with {@code true} and went no further, so on the plain command line -- where nothing
     * intercepts {@code System.out} and this is the only interception there is -- a captured
     * command's output reached the model and the caller and never reached
     * {@link SessionLogger#logCommandOutput}. The session log recorded the command starting and the
     * command ending with nothing between them, for every command a non-TUI run captured, which is
     * all of them.</p>
     *
     * <p>{@code OutputCapture}'s stream bridge already does exactly this for a raw
     * {@code System.out} write that a collector takes, and for the same reason. Recorded here and
     * not there means each line is still recorded once: what this method takes never reaches the
     * stream below it.</p>
     *
     * @param text the text exactly as it would have been printed, newline included
     * @return {@code true} when it was taken and must not also be printed
     */
    private static boolean captured(String text) {
        if (OutputRouter.getInstance().isRouting() || !OutputCapture.offerChunk(text)) {
            return false;
        }
        SessionLogger.getInstance().logCommandOutput(text);
        return true;
    }

    /** Print a line of text. */
    public static void println(String text) {
        if (captured(text + "\n")) {
            return;
        }
        System.out.println(text);
    }

    /** Print an empty line. */
    public static void println() {
        if (captured("\n")) {
            return;
        }
        System.out.println();
    }

    /** Print text without a trailing newline. */
    public static void print(String text) {
        if (captured(text)) {
            return;
        }
        System.out.print(text);
    }

    /** Print formatted text (printf style). */
    public static void printf(String format, Object... args) {
        String formatted = String.format(format, args);
        if (captured(formatted)) {
            return;
        }
        System.out.print(formatted);
    }

    /** Print an error line. */
    public static void printlnErr(String text) {
        if (captured(text + "\n")) {
            return;
        }
        System.err.println(text);
    }

    /**
     * A writer for libraries that render into one — picocli's usage text, for instance.
     *
     * <p>Routed through {@link #print(String)} rather than wrapping {@code System.out} directly, so
     * a usage message printed inside a worker is collected with the rest of that worker's output
     * instead of appearing, unattributed, in the shell's.</p>
     *
     * @return a writer that forwards to standard output
     */
    public static PrintWriter getPrintWriter() {
        return new PrintWriter(new ForwardingWriter(UnifiedOutput::print), true);
    }

    /** As {@link #getPrintWriter()}, for error output. */
    public static PrintWriter getErrorWriter() {
        return new PrintWriter(new ForwardingWriter(UnifiedOutput::printErr), true);
    }

    /** Print error text without a trailing newline. */
    private static void printErr(String text) {
        if (captured(text)) {
            return;
        }
        System.err.print(text);
    }

    /**
     * Flush the console streams.
     *
     * <p>Nothing is queued behind this. Output is handed to its destination on the thread that
     * printed it, so by the time a print call returns the text has already arrived; the sleeps that
     * used to follow this stood in for an asynchronous hand-off that does not exist, and cost every
     * iteration of an agent loop the wait.</p>
     */
    public static void flush() {
        System.out.flush();
        System.err.flush();
    }

    /** Hands whatever is written to it to a printer, a chunk at a time. */
    private static final class ForwardingWriter extends Writer {

        private final Consumer<String> printer;

        ForwardingWriter(Consumer<String> printer) {
            this.printer = printer;
        }

        @Override
        public void write(char[] buffer, int offset, int length) {
            if (length > 0) {
                printer.accept(new String(buffer, offset, length));
            }
        }

        @Override
        public void flush() {
            // Written straight through; there is nothing held back to push.
        }

        @Override
        public void close() {
            // The console outlives this writer.
        }
    }
}
