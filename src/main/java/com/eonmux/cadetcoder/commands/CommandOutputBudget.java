package com.eonmux.cadetcoder.commands;

/**
 * How much of a command's output is fed back to the model.
 *
 * <p>The counterpart of {@link com.eonmux.cadetcoder.ui.CommandOutputVisibility}, which decides how
 * much of it reaches the <em>console</em>. The two are independent on purpose: the console elides
 * output because a person does not want to read a thousand lines to follow a run, while the model
 * needs the content in order to reason about it. Hiding output from the console must never shorten
 * what is sent.</p>
 *
 * <h2>Why there is a limit at all</h2>
 *
 * <p>Commands bound their own output -- {@code read} takes a line limit, {@code multiread} a total,
 * {@code bash} caps what it collects -- and those are the limits that should normally apply, because
 * they are expressed in terms the command understands. This is a last-resort backstop against output
 * that is pathological rather than merely large, so it is set far above anything a working command
 * produces: a full-tree file listing runs to about 16,000 characters and a default {@code read} to
 * roughly 200,000.</p>
 *
 * <p>When the backstop does fire it says how much was dropped and what to do about it. A bare
 * "(output truncated)" tells a model that something is missing but not how to get it, and the
 * observed result is a wasted turn re-running a broader command in the hope of seeing more.</p>
 */
public final class CommandOutputBudget {

    /**
     * System property overriding the limit for one run; {@code 0} removes it entirely.
     */
    public static final String PROPERTY = "cadet.maxCommandOutputChars";

    /**
     * Characters of command output passed to the model by default.
     *
     * <p>Roughly 25,000 tokens. Chosen to sit above what a working command produces rather than at a
     * round number: the previous limits of 5,000 (chat) and 2,000 (agent) characters cut an ordinary
     * listing of this repository's Java files down to under a third, and the model -- correctly --
     * spent its next turn trying to recover the rest.</p>
     */
    public static final int DEFAULT_LIMIT = 100_000;

    private CommandOutputBudget() {
    }

    /**
     * @return the character limit in force, or {@link Integer#MAX_VALUE} when it has been removed
     */
    public static int limit() {
        String override = System.getProperty(PROPERTY);
        if (override != null && !override.isBlank()) {
            try {
                int configured = Integer.parseInt(override.trim());
                if (configured == 0) {
                    return Integer.MAX_VALUE;
                }
                if (configured > 0) {
                    return configured;
                }
            } catch (NumberFormatException e) {
                // An unparseable override falls back to the default rather than failing the run.
            }
        }
        return DEFAULT_LIMIT;
    }

    /**
     * Bounds captured output for inclusion in a prompt.
     *
     * @param output the captured output, possibly {@code null}
     * @return the output unchanged when within the limit, otherwise its leading portion followed by
     *         a note saying what was dropped and how to retrieve it
     */
    public static String forPrompt(String output) {
        if (output == null) {
            return "";
        }
        int limit = limit();
        if (output.length() <= limit) {
            return output;
        }
        int omitted = output.length() - limit;
        return output.substring(0, limit)
               + "\n... [" + omitted + " of " + output.length() + " characters were not included."
               + " Re-running the same command will not return more: narrow it instead -- a more"
               + " specific path or pattern, or 'read' with an offset and limit.]";
    }
}
