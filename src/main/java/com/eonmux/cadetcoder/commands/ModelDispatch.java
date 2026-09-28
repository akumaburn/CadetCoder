package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.OutputFormatter;

import java.util.Locale;
import java.util.Set;
import java.util.function.IntSupplier;

/**
 * The one way a command is run because a model asked for it rather than because a person typed it.
 *
 * <h2>Why a command needs to know who asked</h2>
 *
 * <p>Almost none do: reading a file, running a search, editing a buffer mean the same thing whoever
 * asked. The exceptions are the commands that act on the SESSION rather than on the work, and for
 * those the difference is the whole point. {@code quit} is the clear one. It ends the process, which
 * is exactly right when a person types it and never right when a model emits it: the model is one
 * participant in a session, and a participant deciding to end everyone's session is not a decision
 * it has been given.</p>
 *
 * <p>{@code QuitCommand} already had a contract for this and keyed it on the process-wide
 * {@code cadet.interactive} property. That reads {@code true} inside the interactive shell -- which
 * is precisely where an agent loop runs -- so a model emitting {@code COMMAND: quit} during a chat
 * run closed the user's shell. The property answers "is anyone at a terminal", and the question here
 * is "did they ask for this".</p>
 *
 * <h2>Why the loop refusal lives here too</h2>
 *
 * <p>Being marked and being kept out of {@link #STARTS_A_LOOP} are the same rule seen twice: a model
 * running a command is a participant, and a participant may neither end the session nor start a
 * second loop inside the one it is in. Keeping them in two places is what let a third dispatch site
 * be written with one and not the other -- which is exactly what happened. {@link #run} is the only
 * way to mark a dispatch, so there is no way to mark one and forget to refuse the loop.</p>
 *
 * <p>Thread-scoped, so the mark follows the dispatch rather than the process: a worker runs its
 * agent on its own thread, and the shell's own thread is unaffected while it does.</p>
 */
public final class ModelDispatch {

    /**
     * The commands that drive a model loop of their own.
     *
     * <p>A model already inside one may not start another. Left to itself, a model that emits
     * {@code agent do the whole thing} nests loops until the stack, the clock or the token budget
     * runs out, and every one of those endings is worse than being told no.</p>
     */
    private static final Set<String> STARTS_A_LOOP =
            Set.of("agent", "chat", "loop", "loopfresh");

    /** What a shell reads as a command that refused to run. */
    public static final int REFUSED = 1;

    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> false);

    private ModelDispatch() {
    }

    /**
     * @return {@code true} when the command on this thread came from a model's action block
     */
    public static boolean isModelDriven() {
        return ACTIVE.get();
    }

    /**
     * Runs one command on the model's behalf.
     *
     * <p>A refusal is printed rather than returned, so it reaches whatever is collecting this
     * thread's output -- the transcript the model reads next, and the terminal when command output
     * is being shown. A model that is told nothing repeats the action it was refused.</p>
     *
     * @param command  the command name the model asked for
     * @param dispatch what running it does
     * @return whatever the dispatch returned, or {@link #REFUSED} if the model was not allowed to
     *         ask for this at all
     */
    public static int run(String command, IntSupplier dispatch) {
        if (startsALoop(command)) {
            OutputFormatter.printWarning(refusalFor(command));
            return REFUSED;
        }
        boolean previous = ACTIVE.get();
        ACTIVE.set(true);
        try {
            // A command a model asked for may not stop and ask the user: its console output is
            // collected, so the question never reaches the screen while the bare input prompt does.
            // See InteractivePrompts.asModelDrivenWork.
            return com.eonmux.cadetcoder.ui.InteractivePrompts.asModelDrivenWork(
                    dispatch::getAsInt);
        } finally {
            if (previous) {
                ACTIVE.set(true);
            } else {
                ACTIVE.remove();
            }
        }
    }

    /**
     * @param command a command name, in any case and with any surrounding space
     * @return whether running it would put a model inside a second loop
     */
    public static boolean startsALoop(String command) {
        return command != null && STARTS_A_LOOP.contains(command.trim().toLowerCase(Locale.ROOT));
    }

    /** What the model is told when it asks for a loop inside the loop it is already in. */
    public static String refusalFor(String command) {
        return "Refusing to launch a nested '" + command + "' from within a model run.";
    }

    /**
     * Whether {@code --force} came from the person rather than from a model.
     *
     * <p>The flag means "the user has already said yes", so it skips a confirmation. On the agentic
     * path the argument list is written by the model, and a model's {@code --force} would answer
     * that question on the user's behalf without the user being asked. The flag therefore counts
     * only when the command did not come from a model.</p>
     *
     * @param asked whether the argument list carried the flag
     * @return whether to honour it
     */
    public static boolean personsForce(boolean asked) {
        return asked && !isModelDriven();
    }

    /**
     * Refuses a change to the user's setup when a model asked for it.
     *
     * <p>The settings, the credentials, the prompt templates and the choice of model decide what
     * every other check allows and what every later run is told, so a model that could change them
     * could allow itself anything. The commands that hold them ({@code config}, {@code models},
     * {@code login}, {@code copilot}, {@code prompt}, {@code ubermode}, {@code compact} and
     * {@code theme}) call this before each subcommand that changes something, and still answer a
     * model that only reads.</p>
     *
     * @param what the change, as the user would name it, e.g. {@code "config security.sandboxMode"}
     * @return {@link #REFUSED} when a model asked for the change, after saying so; otherwise
     *         {@code null}, and the caller goes ahead
     */
    public static Integer refuseSetupChange(String what) {
        if (!isModelDriven()) {
            return null;
        }
        OutputFormatter.printWarning("Refusing '" + what + "' from within a model run: only the "
                + "user can change settings, credentials, prompt templates or the active model.");
        return REFUSED;
    }

    /**
     * Carries "this came from a model" onto whichever thread ends up running {@code body}.
     *
     * <p>Whose doing a command is decides what some of them are allowed to do -- {@code quit} ends
     * the run rather than the session, {@code session} refuses to switch under a model -- and it is
     * recorded per thread. An interruptible command is dispatched on a thread of its own, which
     * starts out as nobody's doing, so a guard that reads it there is reading about a request the
     * user never made.</p>
     *
     * @param body the work about to be moved to another thread
     * @return the same work, wrapped so it answers {@link #isModelDriven()} as this thread does
     */
    public static Runnable carrying(Runnable body) {
        if (body == null) {
            return null;
        }
        boolean modelDriven = ACTIVE.get();
        return () -> {
            boolean previous = ACTIVE.get();
            ACTIVE.set(modelDriven);
            try {
                body.run();
            } finally {
                if (previous) {
                    ACTIVE.set(true);
                } else {
                    ACTIVE.remove();
                }
            }
        };
    }
}
