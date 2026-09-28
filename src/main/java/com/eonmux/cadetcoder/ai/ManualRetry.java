package com.eonmux.cadetcoder.ai;

import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.net.LLMException;
import com.eonmux.cadetcoder.ui.OutputCapture;
import com.eonmux.cadetcoder.ui.OutputRouter;

/**
 * Offers the person at the terminal one more attempt after the automatic retries are spent.
 *
 * <p>The backend retries what is worth retrying — 429, 5xx and transport — and gives up after
 * {@code cadet.llm.maxAttempts}. Ending the run there throws away everything the run had built, for
 * a provider that is very often available again moments later. Pressing Enter costs one keystroke;
 * retyping the request and re-running the whole task costs considerably more.</p>
 *
 * <h2>When it does not ask</h2>
 *
 * <p>Only when there is somebody to ask, and only when the answer could differ. In a script, a pipe
 * or CI there is no console, so the failure propagates exactly as before and the exit code is
 * unchanged; an end-of-input answer counts as "no" for the same reason. A failure whose
 * {@link LLMException.Kind} is not retryable -- a revoked key, a malformed request, a payload with
 * no completion in it -- is not offered at all: the same call would fail the same way, and
 * {@code AIManager.complete} would ask again for as long as the answer was yes.</p>
 *
 * <p>Nor inside a pass of a loop, which is started so that it runs without anyone: there
 * {@code AIManager} waits out an outage by itself ({@link OutageWait}) and this is not called.</p>
 *
 * <h2>Why "is there a console?" is the wrong question</h2>
 *
 * <p>{@code OutputRouter.canPrompt()} is true for every thread while the shell is running, workers
 * included, and a worker is precisely where nobody is watching: its output is collected into its own
 * transcript, so {@code getUserInput} declines to take the shell's input line and hands back an
 * empty string at once. An empty string is what Enter sends, Enter means "try again", and the
 * request that had just failed with a persistent 429 failed again immediately -- for as long as the
 * process lived, with nothing on screen but the same message. {@link CommandApproval#aPersonCanBeAsked()}
 * is the question that distinguishes the two, and it is the one every other asker in this tool
 * already puts.</p>
 *
 * <h2>Why the question is asked outside the capture</h2>
 *
 * <p>An agent step in the shell also runs with its output collected, so the model can read what a
 * command said -- but the user IS watching that one. Asked inside the capture, the question is
 * filed into the step's transcript and the person sees nothing to answer. {@link OutputCapture#outsideCapture}
 * sets the collection aside for the duration of the question, which is what {@code CommandApproval}
 * does for the same reason.</p>
 */
public final class ManualRetry {

    /** System property to suppress the offer even on a terminal. */
    public static final String PROPERTY = "cadet.manualRetry";

    /**
     * The question, put on the input line rather than printed above it.
     *
     * <p>A prompt whose text reaches the screen by a separate route is a bare "&gt;&gt;&gt;" to
     * anyone whose console output is being collected, or who has scrolled since it was printed.</p>
     */
    private static final String QUESTION = "Enter to retry, or 'stop' to end the run >>> ";

    private ManualRetry() {
    }

    /**
     * Asks whether to try the failed request again.
     *
     * @param failure what went wrong, already reported to the user by the caller
     * @return {@code true} to attempt the request once more
     */
    public static boolean offer(LLMException failure) {
        if (!isEnabled()) {
            return false;
        }
        if (stopWasAsked()) {
            // The question would be put to somebody who has just said they want this to end, and
            // the act of asking to stop cancels any prompt that is waiting -- which this used to
            // read back as an empty line, which it read as "Enter", which it read as "try again".
            // The interrupt flag is still set, so the retry failed instantly and asked again, and
            // the run spun there printing the same failure until the process was killed.
            return false;
        }
        if (failure == null || !failure.getKind().isRetryable()) {
            // Whether trying again can change the outcome is already recorded, once, on
            // LLMException.Kind -- AUTH is annotated "a retry can never help" -- and the backend's
            // automatic policy reads it there. Asking a second question here meant a revoked key
            // was answered with "Press Enter to try again", and AIManager.complete loops for as
            // long as this says yes: 401, Enter, 401, Enter, with no way out but Ctrl+C.
            return false;
        }
        if (!CommandApproval.aPersonCanBeAsked()) {
            return false;
        }

        String answer = OutputCapture.outsideCapture(() -> {
            OutputFormatter.printError("AI request failed: " + failure.getMessage());
            try {
                return OutputRouter.getInstance().getUserInput(QUESTION);
            } catch (RuntimeException unusable) {
                // This runs while an LLM failure is already being handled; a second exception here
                // would replace the real cause with a terminal-plumbing one.
                return null;
            }
        });
        if (answer == null) {
            // No input source: treat as "stop" rather than looping on an EOF that will never change.
            return false;
        }
        String reply = answer.trim();
        if (reply.isEmpty()) {
            return anEmptyLineWasAkeystroke();
        }
        if (reply.equalsIgnoreCase("retry") || reply.equalsIgnoreCase("y")) {
            OutputFormatter.printInfo("Retrying the request.");
            return true;
        }
        return false;
    }

    /**
     * Whether an empty answer is the Enter this offer exists for, rather than a prompt that ended
     * without being answered.
     *
     * <h2>Why the blank line is interrogated at all</h2>
     *
     * <p>The shell delivers an empty string for three different events: the user pressed Enter, the
     * prompt was cancelled, and the shell stopped while the question was up. Only the first is
     * consent, and reading the other two as consent retries a failure nobody agreed to retry -- in a
     * loop, because whatever ended the prompt is still true on the next pass.</p>
     *
     * <p>Both of the other two are detectable after the fact. A cancelled prompt is cancelled by an
     * interrupt, which leaves its flag set; a shell that stopped can no longer be asked anything. So
     * the same two questions that decided whether to ask are put again once the answer is in.</p>
     *
     * @return whether somebody was still there to have pressed the key
     */
    private static boolean anEmptyLineWasAkeystroke() {
        if (stopWasAsked() || !CommandApproval.aPersonCanBeAsked()) {
            return false;
        }
        OutputFormatter.printInfo("Retrying the request.");
        return true;
    }

    /**
     * Whether the user has asked the work in flight to stop.
     *
     * <p>Both routes are read. The shared signal is set the instant the key is pressed and covers
     * every thread the run has fanned out to. The thread's own flag is the only one a request
     * interrupted outside the registry's monitor ever gets.</p>
     *
     * @return whether a stop has been asked for
     */
    private static boolean stopWasAsked() {
        return com.eonmux.cadetcoder.InterruptSignal.isRequested()
               || Thread.currentThread().isInterrupted();
    }

    private static boolean isEnabled() {
        String override = System.getProperty(PROPERTY);
        if (override != null && !override.isBlank()) {
            return Boolean.parseBoolean(override.trim());
        }
        return true;
    }
}
