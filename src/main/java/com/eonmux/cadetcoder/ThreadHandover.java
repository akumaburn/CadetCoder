package com.eonmux.cadetcoder;

import com.eonmux.cadetcoder.commands.ModelDispatch;
import com.eonmux.cadetcoder.timers.TimerScope;
import com.eonmux.cadetcoder.ui.InteractivePrompts;
import com.eonmux.cadetcoder.ui.OutputCapture;

/**
 * What goes with a piece of work when it is moved to a thread of its own.
 *
 * <h2>Why anything has to go with it</h2>
 *
 * <p>Four things about a running command are held per thread, because each of them is about one
 * line of work rather than about the program: which sink is collecting what it prints, whose timers
 * it is setting, whether a model asked for it, and whether it may stop and ask the user a question.
 * Every interruptible command is then dispatched on a freshly made thread, so that an interrupt has
 * a thread to land on -- and a fresh thread knows none of those four. Each of them failed
 * differently and silently: a captured run came back empty, a worker's reminder was filed under the
 * person at the terminal, a guard that asks whether the model is driving was answered "no" for a
 * command the model had just asked for, and {@code commit} put its confirmation to a user who was
 * never shown it -- which nobody could answer, so the model was told its commit was declined.</p>
 *
 * <h2>Why they are gathered here rather than fixed one at a time</h2>
 *
 * <p>They are one fact -- what was true where the work was asked for -- and the thing that loses
 * them is one line of code. Gathered, the next thing that becomes true of a thread is carried across
 * that line by adding it in one place, rather than by someone noticing months later that it was not.</p>
 */
public final class ThreadHandover {

    private ThreadHandover() {
    }

    /**
     * Wraps {@code body} so it runs as though it were still on the calling thread.
     *
     * <p>Read on the calling thread, at the moment this is called, which is what makes it the
     * caller's context and not whatever happens to be true when the new thread starts.</p>
     *
     * @param body the work about to be handed to another thread
     * @return the same work, wrapped; {@code null} for {@code null}
     */
    public static Runnable carrying(Runnable body) {
        if (body == null) {
            return null;
        }
        return TimerScope.carrying(ModelDispatch.carrying(
                InteractivePrompts.carrying(OutputCapture.carrying(body))));
    }
}
