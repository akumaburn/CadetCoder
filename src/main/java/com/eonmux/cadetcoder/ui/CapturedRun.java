package com.eonmux.cadetcoder.ui;

import com.eonmux.cadetcoder.security.SecretRedactor;

import java.util.function.IntSupplier;

/**
 * Runs a command and hands back both its exit code and everything it printed.
 *
 * <h2>Why not replace {@code System.out}</h2>
 *
 * <p>That is what this replaces, and it was a process-global mutation performed from threads that
 * run concurrently. {@link com.eonmux.cadetcoder.agents.WorkerPool} runs up to eight agent loops at
 * once and each one captured on every step by saving {@code System.out} into a local and restoring
 * it in a {@code finally}. Interleaved, the second worker to enter saved the first worker's
 * {@code ByteArrayOutputStream} as "the original" and restored that on the way out, so for the rest
 * of the process every write to {@code System.out} went into a buffer nobody reads -- the terminal
 * and the TUI simply went quiet, with no error. In the window before that, one worker's output was
 * collected into another's capture and fed to that worker's model as its own command output.</p>
 *
 * <p>{@link OutputCapture} is per thread and already the mechanism workers install, so capturing
 * through it is both correct under concurrency and the one place output is collected. It also
 * nests: an agent capturing one command inside a worker's own capture restores the worker's sink
 * afterwards rather than detaching it.</p>
 *
 * <h2>One stream, not two</h2>
 *
 * <p>Standard output and standard error come back as a single transcript, in the order they were
 * written. That is what the user sees in a terminal, what {@link OutputRouter} already does with
 * them ({@code appendError} delegates to {@code appendOutput}), and what a worker transcript
 * records. Splitting them here would put the two halves of an interleaved failure in the wrong
 * order relative to each other, which is worse than not labelling them.</p>
 *
 * <h2>Why credentials are removed here</h2>
 *
 * <p>Because this is where the text exists before anyone has it. What a capture returns goes four
 * places: into the prompt, onto the console, into the debug record, and into a worker's transcript.
 * Redacting at each of those is four rules that have to agree, and they did not -- the loggers
 * redacted and the two agent paths did not, so a command the security rules allow put a key
 * verbatim into the prompt and onto the screen. A {@code read} of a configuration file is enough;
 * so is a provider quoting the request's own Authorization header back in a 401 body.</p>
 *
 * <p>Applied before the output is measured, so what is counted, cut and stored is what will be
 * sent. {@link com.eonmux.cadetcoder.security.SecretRedactor} recognises credential shapes and is a
 * net rather than a guarantee; text that must never be recorded still should not be printed.</p>
 *
 * <h2>Why the collector is not a bare StringBuilder</h2>
 *
 * <p>{@link OutputCapture#carrying} hands this run's sink to the thread an interruptible command is
 * moved onto, and the two threads are not as neatly sequenced as that once assumed. When an
 * interrupt's grace period expires, {@code CommandRegistry} stops waiting and returns while the
 * command thread is still alive -- still running, still printing, still holding this very sink. So
 * two threads appended to one {@code StringBuilder} with nothing between them, which is not merely
 * untidy: an unsynchronized {@code StringBuilder} can lose writes, interleave halves of two lines,
 * or throw out of {@code append} when its array is resized underneath. The abandoned thread also
 * went on writing into a transcript whose owner had already read it and moved on.</p>
 *
 * <p>The collector below settles both. It is synchronized, so concurrent lines arrive whole and in
 * some order rather than corrupting each other; and it is closed when the run ends, so a thread
 * nobody is waiting for any more cannot add to a record that has already been handed over.</p>
 */
public final class CapturedRun {

    private CapturedRun() {
    }

    /**
     * What a captured command produced.
     *
     * @param exitCode the command's exit code
     * @param output   everything it printed, ANSI-stripped, credential-redacted, newline-terminated
     *                 per line
     */
    public record Result(int exitCode, String output) {
    }

    /**
     * Runs {@code body} with this thread's output collected instead of printed.
     *
     * @param body the command to run; its return value is the exit code
     * @return the exit code and the collected output
     */
    public static Result of(IntSupplier body) {
        // Bare System.out writes are rare but real, and a capture that silently loses them is worse
        // than one that never ran. Installed once, permanently, and thread-aware.
        OutputCapture.installStreamBridge();

        Transcript collected = new Transcript();
        int[] exitCode = {1};

        OutputCapture.collectInto(collected::add, () -> exitCode[0] = body.getAsInt());

        return new Result(exitCode[0],
                          SecretRedactor.redact(AnsiStripper.strip(collected.finish())));
    }

    /**
     * What a run has said so far, safe to write from more than one thread and closed when it ends.
     *
     * <p>See the class comment for the pair of threads this exists for. Lines are taken under the
     * monitor and refused once the run's output has been handed to its caller, because a thread
     * that outlived the command it belonged to has nowhere legitimate to put them: adding them
     * would put one command's output into a transcript that has already been read, attributed to a
     * step that had finished before they were written.</p>
     */
    private static final class Transcript {

        private final StringBuilder said = new StringBuilder();
        private boolean finished;

        /** @param line one line as it was printed, without its terminator */
        synchronized void add(String line) {
            if (finished) {
                return;
            }
            said.append(line).append(System.lineSeparator());
        }

        /** @return everything said, after which nothing further is taken */
        synchronized String finish() {
            finished = true;
            return said.toString();
        }
    }
}
