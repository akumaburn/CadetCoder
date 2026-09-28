package com.eonmux.cadetcoder.ui;

import com.eonmux.cadetcoder.ai.StandInAnswer;
import com.eonmux.cadetcoder.commands.InteractiveShell;
import com.eonmux.cadetcoder.logging.SessionLogger;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Output router that captures {@code System.out}/{@code System.err} and redirects the
 * captured text into the immediate-mode {@link InteractiveShell} output buffer.
 *
 * <p>The interactive shell renders with TamboUI (an immediate-mode TUI), so there is no
 * retained text widget to push into. Instead, captured output is forwarded to
 * {@link InteractiveShell#appendOutputDirect(String)}, which stores it in a thread-safe
 * line buffer and requests a redraw on the next render tick.</p>
 */
public class OutputRouter {
    private static volatile OutputRouter instance;

    // Written by the shell's own thread and read by every command thread that prints, so the
    // reader has to be guaranteed to see the writer's value rather than a cached one.
    private volatile InteractiveShell shell;
    private final    PrintStream      originalOut;
    private final    PrintStream      originalErr;
    private volatile PrintStream      capturingOut;
    private volatile PrintStream      capturingErr;
    private volatile boolean          isRouting = false;

    private OutputRouter() {
        this.originalOut = System.out;
        this.originalErr = System.err;

        // Add a shutdown hook to ensure console state is always restored
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (isRouting) {
                System.setOut(originalOut);
                System.setErr(originalErr);
            }
        }));
    }

    public static synchronized OutputRouter getInstance() {
        if (instance == null) {
            instance = new OutputRouter();
        }
        return instance;
    }

    /**
     * Register the interactive shell that captured output should be routed to.
     * Must be called before {@link #startRouting()}.
     */
    public void setShell(InteractiveShell shell) {
        this.shell = shell;
    }

    /**
     * Start routing output to the interactive shell.
     */
    public void startRouting() {
        if (isRouting || shell == null) {
            return;
        }

        capturingOut = capturingStream(this::appendOutput);
        capturingErr = capturingStream(this::appendError);

        System.setOut(capturingOut);
        System.setErr(capturingErr);
        isRouting = true;
    }

    /**
     * Append text to the shell's output buffer.
     */
    private void appendOutput(String text) {
        // Log output to session logger
        SessionLogger.getInstance().logCommandOutput(text);

        // A worker collects its own output so concurrent runs stay attributable. The capture is
        // per thread and this replacement of System.out is process-wide, so this is the point at
        // which the two meet: without it a worker's line took whichever route it was printed by --
        // UnifiedOutput.println into the worker's transcript, an error or a bare System.out.print
        // into the shell's, where it appeared with nothing saying which worker had said it.
        if (OutputCapture.offerChunk(text)) {
            return;
        }

        InteractiveShell target = shell;
        if (target != null) {
            target.appendOutputDirect(text);
        } else {
            // Defensive fallback: if there is no shell, write straight to the real stream
            originalOut.print(text);
        }
    }

    /**
     * Append error text (routed identically to standard output).
     */
    private void appendError(String text) {
        appendOutput(text);
    }

    /**
     * Stop routing output and restore original streams.
     */
    public void stopRouting() {
        if (!isRouting) {
            return;
        }

        try {
            // Ensure all pending output is flushed before restoring streams. Nothing is queued
            // behind this: the handler runs on the thread that printed, so once flush returns
            // every byte has already been delivered. (A sleep here used to stand in for an
            // asynchronous hand-off that does not exist.)
            if (capturingOut != null) {
                capturingOut.flush();
            }
            if (capturingErr != null) {
                capturingErr.flush();
            }

            // Restore original streams
            System.setOut(originalOut);
            System.setErr(originalErr);

            // Flush the original streams to ensure any captured content is displayed
            originalOut.flush();
            originalErr.flush();

            // Clear references to prevent memory leaks
            capturingOut = null;
            capturingErr = null;

        } catch (Exception e) {
            // Emergency fallback - force restore original streams
            System.setOut(originalOut);
            System.setErr(originalErr);
            try {
                originalOut.flush();
                originalErr.flush();
            } catch (Exception ignored) {
                // Best effort cleanup
            }
        } finally {
            isRouting = false;
        }
    }

    /**
     * Get a line of user input through the interactive shell's prompt line.
     * Blocks the calling (command) thread until the user submits a response.
     */
    public String getUserInput(String prompt) {
        if (!canReachAPerson("input")) {
            return "";
        }
        if (shell == null || !isRouting) {
            // Fallback to console input if the TUI is not active
            System.out.print(prompt);
            return System.console() != null ? System.console().readLine() : "";
        }
        return shell.getUserInput(prompt);
    }

    /**
     * Whether a question asked now would actually reach someone who can answer it.
     *
     * <h2>Why the check lives here</h2>
     *
     * <p>This is the one point every prompt passes through. {@code IterativeExecutor} has its own,
     * narrower version for the questions a STEP produces, and that left every other asker
     * uncovered: {@code bash} confirming a command, {@code write} confirming an overwrite,
     * {@code undo} confirming a reset, plan mode, a notebook edit, a manual retry. Run any of those
     * inside a worker and it took the shell's input line for a question the user never saw, because
     * a worker's output is collected into its own transcript while the bare prompt went to the
     * terminal.</p>
     *
     * <p>The refusal is printed rather than silent, so it lands in that worker's transcript and
     * {@code workers show &lt;n&gt;} says what was asked and what was assumed.</p>
     *
     * @param what the kind of answer being sought, for the note
     * @return {@code false} when the caller's output is being collected, so nobody would see it
     */
    private static boolean canReachAPerson(String what) {
        if (!OutputCapture.isCapturing()) {
            return true;
        }
        // Collected output belongs to a worker or to a model's step; neither reaches the screen.
        UnifiedOutput.println("(no one can be asked for " + what
                              + " here, because this output is collected rather than shown;"
                              + " continuing without it)");
        return false;
    }

    /**
     * Show a confirmation prompt. Delegates to the interactive shell when active.
     *
     * @param message the question, naming what it is about: in auto mode it is all the model
     *                answering it is shown
     * @return whether the user agreed; {@code false} when no one could be asked
     */
    public boolean getConfirmation(String message) {
        if (OutputCapture.isCapturing() && StandInAnswer.answersNow()) {
            // security.commandApproval is auto and a model asked for this work: the model answers,
            // in a request of its own, as it does for a step's question and a shell command.
            return StandInAnswer.confirms(message);
        }
        if (!canReachAPerson("confirmation")) {
            // Denial, not approval. A confirmation nobody can answer must not be self-granted: these
            // gate destructive and privileged actions, and the whole point of the gate is that
            // something other than the caller decides.
            return false;
        }
        if (shell == null || !isRouting) {
            // Fallback to console input
            System.out.print(message + " (y/n): ");
            String input = System.console() != null ? System.console().readLine() : "";
            return input != null && input.toLowerCase().startsWith("y");
        }
        return shell.getConfirmation(message);
    }


    /**
     * Check if output is currently being routed.
     */
    public boolean isRouting() {
        return isRouting;
    }

    /**
     * How a command is invoked on the surface the user is currently looking at.
     *
     * <p>At the interactive prompt a command is written with a leading slash, because a line
     * without one is a message for the model; on the command line it is written after the program
     * name. Any message that tells the user to run something has to pick the right one, and the
     * only thing that knows which surface is live is this router.</p>
     *
     * @return {@code "/"} while the shell is running, otherwise {@code "cadet "}
     */
    public String commandPrefix() {
        return isRouting ? "/" : "cadet ";
    }

    /**
     * Whether an interactive prompt can currently obtain input. Returns {@code true} when the
     * TUI is routing (the shell can prompt on its input line) or a real console is attached.
     * When {@code false} (e.g. piped/redirected stdin, or a non-interactive test harness),
     * callers should avoid blocking or looping on user input and fall back to a default.
     */
    public boolean canPrompt() {
        return isRouting || System.console() != null;
    }

    /**
     * Public method to append output directly (for UnifiedOutput).
     */
    public void appendOutputPublic(String text) {
        appendOutput(text);
    }

    /**
     * Public method to append error directly (for UnifiedOutput).
     */
    public void appendErrorPublic(String text) {
        appendError(text);
    }

    /**
     * Asks the interactive shell to rebuild its console for a session that has just been switched.
     *
     * @return whether a running shell took the request
     */
    public boolean requestSessionReload() {
        InteractiveShell target = shell;
        if (target == null || !isRouting) {
            return false;
        }
        target.reloadSession();
        return true;
    }

    /**
     * Asks the interactive shell to clear its console.
     *
     * @return whether a running shell took the request
     */
    public boolean requestConsoleClear() {
        InteractiveShell target = shell;
        if (target == null || !isRouting) {
            return false;
        }
        target.clearConsole();
        return true;
    }

    /**
     * Request that the interactive shell exit gracefully (for the quit command).
     * Returns {@code true} if the request was delivered to a running shell.
     */
    public boolean requestExit() {
        if (shell != null) {
            shell.requestQuit();
            return true;
        }
        return false;
    }

    /**
     * A {@code System.out} replacement that hands everything written to it to {@code handler}.
     *
     * <h2>Why the stream is wrapped rather than the methods overridden</h2>
     *
     * <p>This used to be a {@link PrintStream} subclass overriding the handful of methods the code
     * base happened to call. Every other one — {@code print(Object)}, {@code print(char[])},
     * {@code printStackTrace}, anything writing through a {@link java.io.PrintWriter} — went the
     * long way round, through {@code PrintStream}'s own encoder, and arrived at
     * {@code write(byte[], int, int)}, which decoded each chunk on its own with
     * {@code new String(bytes)}. A character whose bytes straddled two of those chunks became two
     * replacement characters, which is how a {@code ✓} turns into {@code ??}; and
     * {@code write(int)} cast a single BYTE to a {@code char}, so every byte above 0x7F was
     * mojibake by construction.</p>
     *
     * <p>Wrapping the underlying stream instead means there is one path, not seven: the print
     * stream encodes as UTF-8, this decodes UTF-8, and a sequence split across two writes is held
     * until the rest of it arrives. Every {@code PrintStream} method works, including the ones
     * nothing calls today.</p>
     *
     * <p>Package-private so the stream can be driven exactly as {@link #startRouting()} drives it,
     * without a test having to replace the process's own {@code System.out} to reach it.</p>
     *
     * @param handler receives the text, in the chunks it was written in
     * @return the stream to install
     */
    static PrintStream capturingStream(Consumer<String> handler) {
        return new PrintStream(new DecodingOutputStream(handler), true, StandardCharsets.UTF_8);
    }

    /** Turns the bytes a {@link PrintStream} writes back into text, without splitting characters. */
    private static final class DecodingOutputStream extends OutputStream {

        private final Consumer<String> handler;
        private final CharsetDecoder   decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);

        /** Bytes of a character whose remainder has not been written yet. */
        private byte[] carry = new byte[0];

        DecodingOutputStream(Consumer<String> handler) {
            this.handler = handler;
        }

        @Override
        public synchronized void write(int b) {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int length) {
            if (length <= 0) {
                return;
            }
            ByteBuffer in = ByteBuffer.allocate(carry.length + length);
            in.put(carry).put(bytes, offset, length).flip();

            // One char per byte is the most UTF-8 can decode to, so this can never overflow.
            CharBuffer out = CharBuffer.allocate(in.remaining());
            decoder.decode(in, out, false);

            carry = new byte[in.remaining()];
            in.get(carry);

            out.flip();
            if (out.hasRemaining()) {
                handler.accept(out.toString());
            }
        }
    }
}
