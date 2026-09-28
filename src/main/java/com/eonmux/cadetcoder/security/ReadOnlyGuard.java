package com.eonmux.cadetcoder.security;

import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.config.ConfigManager;

/**
 * The single expression of what {@code --read-only} forbids.
 *
 * <h2>Why this is not a check each command writes for itself</h2>
 *
 * <p>{@code --read-only} is one promise -- "nothing in this run changes the project" -- and a
 * promise kept by five commands out of a dozen is not kept at all. When each mutating command
 * carried its own copy of the check, the copies drifted in wording and, more importantly, the
 * commands written later simply never got one: a hard reset, a commit, a push and a refactor all
 * proceeded normally in a run the user had asked to be read-only.</p>
 *
 * <p>Every operation that changes a file in the project or the state of its repository asks here
 * first, so the set of things the flag covers is visible in one place and a new command that
 * forgets to ask is a visible omission rather than an invisible one.</p>
 *
 * <h2>What it does not cover</h2>
 *
 * <p>CadetCoder's own state under the base directory -- the session transcript, the to-do list,
 * the search index -- is not the user's project, and a run that could not record its own progress
 * would not be read-only so much as broken. Those writes proceed.</p>
 */
public final class ReadOnlyGuard {

    private ReadOnlyGuard() {
    }

    /**
     * Whether the current run is read-only.
     *
     * <p>Reports nothing, for callers that need to phrase their own message or take a different
     * path rather than refuse outright.</p>
     *
     * @return {@code true} when {@code security.readOnlyMode} is set
     */
    public static boolean isEnabled() {
        return ConfigManager.getInstance().getConfig().getSecurity().isReadOnlyMode();
    }

    /**
     * Refuses an operation that would change the project, and says so.
     *
     * <p>Deliberately not exception-based: the caller returns its own failure code, and a refusal
     * is an expected answer to a legitimate request, not an error condition.</p>
     *
     * @param action what the caller was about to do, as a verb phrase that completes
     *               "Cannot ..." -- for example {@code "write file"} or {@code "push"}
     * @return {@code true} if the caller must stop, having already reported why
     */
    public static boolean blocks(String action) {
        if (!isEnabled()) {
            return false;
        }
        OutputFormatter.printError("Cannot " + action + ": read-only mode is enabled");
        return true;
    }
}
