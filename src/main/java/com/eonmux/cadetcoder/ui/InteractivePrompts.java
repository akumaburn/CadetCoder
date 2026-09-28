package com.eonmux.cadetcoder.ui;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;

/**
 * Whether this run may stop and ask the person at the terminal a question.
 *
 * <h2>Why one place decides</h2>
 *
 * <p>Twenty-two commands each asked the same question by reading the same system property with the
 * same default written out by hand. {@code ui.interactivePrompts} -- the setting {@code /config}
 * offers, lists and saves for exactly this -- was read by none of them, so turning it off reported
 * success and changed nothing, and a command added later had one more place to forget.</p>
 *
 * <h2>Which answer wins</h2>
 *
 * <p>The system property when it is set, then the configuration, then {@code true}. That is the
 * order {@link CommandOutputVisibility} already uses: a single run says what it needs on the command
 * line, and what is saved is what every other run gets.</p>
 */
public final class InteractivePrompts {

    /** System property that overrides the configured preference for one run. */
    public static final String PROPERTY = "cadet.interactive";

    /**
     * Whether the work on this thread was asked for by a model rather than typed by a person.
     *
     * <p>Thread-scoped, so a worker running its own agent does not silence the shell's thread.</p>
     */
    private static final ThreadLocal<Boolean> MODEL_DRIVEN = ThreadLocal.withInitial(() -> false);

    private InteractivePrompts() {
    }

    /**
     * Runs {@code body} as work a model asked for, during which no command may stop and ask.
     *
     * <h2>Why a nested command must not ask</h2>
     *
     * <p>A command a model dispatched runs with its console output collected, so anything it prints
     * reaches the transcript rather than the screen. Its question therefore does not, and the user
     * is left looking at a bare input prompt for a question they were never shown, with the run
     * blocked until they guess. {@code edit}, {@code multiedit} and {@code refactor} each ask the
     * user to confirm the changes they generated, and each one blocked an agent run that way.</p>
     *
     * <p>Every one of those commands already has the right behaviour for a run with nobody to ask.
     * This makes them take it. When {@code security.commandApproval} is {@code auto}, a question a
     * step addresses to the user is answered by the model in a request of its own, as a shell
     * command is judged; see {@code StandInAnswer}. In manual mode nobody answers it and the
     * command takes its default. The action-level screening in {@code ActionRun} and the security
     * gates the command itself consults apply in both modes.</p>
     *
     * @param body what to run
     * @param <T>  what it returns
     * @return whatever {@code body} returned
     */
    public static <T> T asModelDrivenWork(java.util.function.Supplier<T> body) {
        boolean previous = MODEL_DRIVEN.get();
        MODEL_DRIVEN.set(true);
        try {
            return body.get();
        } finally {
            // Restored rather than cleared, so a nested dispatch cannot release its caller's scope.
            MODEL_DRIVEN.set(previous);
        }
    }

    /**
     * @return {@code true} when a model asked for the work running on this thread
     */
    public static boolean isModelDrivenWork() {
        return MODEL_DRIVEN.get();
    }

    /**
     * Carries "a model asked for this" onto whichever thread ends up running {@code body}.
     *
     * <h2>Why it has to be carried at all</h2>
     *
     * <p>Every interruptible command is dispatched on a thread of its own, so that an interrupt has
     * a thread to land on. The scope {@link #asModelDrivenWork} opened is held per thread, so the
     * new one did not have it: {@link #isOn} there fell back to the system property, which reads
     * {@code true} inside the interactive shell -- and the interactive shell is exactly where an
     * agent loop runs. So {@code commit}, {@code edit} and {@code refactor}, all of them
     * interruptible, put their confirmation question to a user who was never shown it. Nobody could
     * answer, the safe default is no, and the model was told its commit had been declined.</p>
     *
     * <p>Read on the calling thread, at the moment this is called, which is what makes it the
     * caller's scope rather than whatever is true when the new thread starts.</p>
     *
     * @param body the work about to be handed to another thread
     * @return the same work, wrapped; {@code null} for {@code null}
     */
    public static Runnable carrying(Runnable body) {
        if (body == null) {
            return null;
        }
        boolean modelDriven = MODEL_DRIVEN.get();
        return () -> {
            boolean previous = MODEL_DRIVEN.get();
            MODEL_DRIVEN.set(modelDriven);
            try {
                body.run();
            } finally {
                // Restored rather than cleared, for the same reason asModelDrivenWork restores.
                MODEL_DRIVEN.set(previous);
            }
        };
    }

    /**
     * Whether there is a session with a person driving it at all.
     *
     * <h2>Why this is not the same question as {@link #isOn()}</h2>
     *
     * <p>"Nobody is there" and "do not ask me things" look alike and are not. A run piped through a
     * script, or started by a test, has no terminal to converse with; a user who turned prompts off
     * is very much present and still expects a conversation to happen, just without being asked to
     * confirm each step. Answered from the system property alone, because that is what a caller sets
     * to say there is no terminal -- reading the preference here would make
     * {@code ui.interactivePrompts false} quietly turn the chat loop into a command runner.</p>
     *
     * @return {@code true} when something is driving this run interactively
     */
    public static boolean someoneIsThere() {
        return Boolean.parseBoolean(System.getProperty(PROPERTY, "true"));
    }

    /**
     * Whether a command may stop and put a question to a person.
     *
     * <h2>Why a collected run counts as nobody to ask</h2>
     *
     * <p>A question is only a question if somebody sees it. On a thread whose output is being
     * collected -- a worker, a nested dispatch, a pass of {@code loop} -- the text of the question
     * goes into the transcript, so the one thing that reaches the screen is the bare input prompt.
     * Nobody can answer a question they were not shown, and an unanswered confirmation is denied,
     * so the whole exchange came down to cancelling the step. {@code multiedit} showed its preview,
     * asked, answered itself "no" and reported "Edits cancelled by user" -- to a reader who had
     * never been asked anything.</p>
     *
     * <p>The commands that ask already have the right behaviour for a run with nobody to ask, and
     * {@code SkippedConsent} puts the skip on the record. This makes them take that path instead of
     * the one that ends in a denial nobody chose.</p>
     *
     * @return {@code true} when a command may prompt for confirmation or input
     */
    public static boolean isOn() {
        if (MODEL_DRIVEN.get()) {
            // See asModelDrivenWork: this command's output is collected, so its question would
            // never reach the screen and the bare prompt would block the run.
            return false;
        }
        if (OutputCapture.isCapturing()) {
            return false;
        }
        String override = System.getProperty(PROPERTY);
        if (override != null && !override.isBlank()) {
            return Boolean.parseBoolean(override.trim());
        }
        try {
            Configuration.UiConfig ui = ConfigManager.getInstance().getConfig().getUi();
            return ui == null || ui.isInteractivePrompts();
        } catch (RuntimeException unreadable) {
            // A missing or half-initialised configuration must not decide policy by crashing, and
            // the safe answer is to keep asking rather than to act unattended.
            return true;
        }
    }
}
