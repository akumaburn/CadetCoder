package com.eonmux.cadetcoder.harness.cadet;

import com.eonmux.cadetcoder.harness.env.Reversibility;
import com.eonmux.cadetcoder.security.DangerousCommands;

import java.util.Locale;
import java.util.Set;

/**
 * How much of the world each CadetCoder command spends.
 *
 * <h2>Why the answer comes from the name</h2>
 *
 * <p>The commit gate asks this before an action runs, because asking afterwards is asking after the
 * damage. The only thing known before a command runs is what it is called and what it was handed, so
 * that is what the answer is made of.</p>
 *
 * <h2>Why only proven readers are free</h2>
 *
 * <p>Two ways of being wrong, and they are not symmetric. Calling something free when it is not lets
 * an agent push a branch on a theory nothing has certified. Calling everything expensive makes the
 * gate demand a certificate before the agent may read a file -- and an agent that cannot look at
 * anything until it has a certified theory never acquires one. So the free list holds the commands
 * that read and do nothing else, it is short enough to check by eye, and everything not on it falls
 * to the middle.</p>
 *
 * <h2>Why a shell is judged by its line</h2>
 *
 * <p>{@code bash} is not one command, it is whichever one the agent wrote. Its cost is the cost of
 * the line, so the line is what is read -- by the same rule that guards the interactive shell, not
 * a second list that could disagree with it.</p>
 */
public final class CommandEffects {

    /**
     * Commands that read the workspace and change nothing.
     *
     * <p>Deliberately excludes several that look like readers. {@code context} can rebuild or clear
     * the project notes, {@code models} configures the active model, {@code index} writes a search
     * index and {@code session} loads one over the current run. Each of those has a mode that
     * writes, and a classification is about the command rather than about the arguments it happened
     * to be given this time.</p>
     *
     * <p>{@code patch} is deliberately absent. It writes the files a diff names, so it costs what
     * {@code write} costs, and the default for anything unlisted is already that.</p>
     */
    private static final Set<String> READS_ONLY =
            Set.of("read", "multiread", "ls", "stat", "diff", "grep", "glob", "search",
                   "todoread", "help", "notebookread");

    /**
     * Commands nothing can undo.
     *
     * <p>{@code commit}, {@code push} and {@code login} leave the workspace -- a published branch
     * and an authenticated session are facts about the world outside it. {@code undo} discards
     * uncommitted work, which is the one edit git cannot get back. {@code quit} ends the run.
     * {@code execute} runs a file of commands whose contents are not known here, so it is whatever
     * the worst line in that file is.</p>
     */
    private static final Set<String> CANNOT_BE_UNDONE =
            Set.of("commit", "push", "login", "undo", "quit", "execute");

    /** The command whose cost is the cost of the shell line it was given. */
    private static final String SHELL = "bash";

    private CommandEffects() {
    }

    /**
     * What running this would spend.
     *
     * @param command the command name, in any case and with any surrounding space
     * @param args    what it was handed; read only for {@link #SHELL}
     * @return its reversibility; the safe middle for anything not classified, including an action
     *         that names no command at all -- what cannot be read cannot be called free
     */
    public static Reversibility of(String command, String[] args) {
        String name = command == null ? "" : command.trim().toLowerCase(Locale.ROOT);
        if (name.isEmpty()) {
            return Reversibility.COSTLY;
        }
        if (CANNOT_BE_UNDONE.contains(name)) {
            return Reversibility.IRREVERSIBLE;
        }
        if (SHELL.equals(name)) {
            return DangerousCommands.isReferencedBy(line(args)) ? Reversibility.IRREVERSIBLE
                                                                : Reversibility.COSTLY;
        }
        return READS_ONLY.contains(name) ? Reversibility.REVERSIBLE : Reversibility.COSTLY;
    }

    private static String line(String[] args) {
        return args == null ? "" : String.join(" ", args);
    }
}
