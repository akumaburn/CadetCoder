package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ui.CommandOutputVisibility;

import java.util.Set;

/**
 * The one line that says what the shell is doing while it is busy.
 *
 * <h2>Why it is composed here</h2>
 *
 * <p>Three things compete for one row -- a mark, what is being done, and what the request has cost
 * so far -- and which of them survives a narrow terminal is a decision about columns, not about
 * running commands. Composed inside the shell it could only be checked by starting a terminal UI, so
 * the rule that a half-printed number is worse than an absent one was asserted by reading the code
 * rather than by running it.</p>
 *
 * <h2>Why the timing is dropped whole</h2>
 *
 * <p>The widget that draws this row clips what does not fit. A clipped {@code ~5,551 tokens} reads
 * as {@code ~5,55} -- a smaller number, stated with the same confidence -- where dropping the group
 * entirely reads as no number at all. So the group is measured against the room left for it and
 * dropped before it can be cut.</p>
 */
final class ShellActivityLine {

    /** Narrowest activity text worth showing; below this the timing group is dropped instead. */
    static final int MIN_ACTIVITY_COLUMNS = 16;

    private ShellActivityLine() {
    }

    /**
     * The status line, fitted to the row it has to occupy.
     *
     * @param mark     the leading mark: a spinner phase, or the question mark of a waiting prompt
     * @param activity what is being done, which may be empty
     * @param timing   how long the request has taken and what it has cost, which may be empty
     * @param width    the columns the row has
     * @param bullet   the separator between the activity and the timing
     * @param ellipsis the mark for activity text that had to be cut
     * @return the line
     */
    static String compose(String mark, String activity, String timing, int width, String bullet,
                          String ellipsis) {
        String lead  = ShellWidgets.nz(mark);
        String cost  = ShellWidgets.nz(timing);

        String separator = "  " + ShellWidgets.nz(bullet) + "  ";
        int    available = width - lead.length() - 1;
        if (!cost.isEmpty()
                && available - separator.length() - cost.length() < MIN_ACTIVITY_COLUMNS) {
            cost = "";
        }
        if (!cost.isEmpty()) {
            available -= separator.length() + cost.length();
        }

        StringBuilder out   = new StringBuilder(lead);
        String        shown = ShellWidgets.elide(ShellWidgets.nz(activity),
                                                 Math.max(0, available), ellipsis);
        if (!shown.isEmpty()) {
            out.append(' ').append(shown);
        }
        if (!cost.isEmpty()) {
            out.append(separator).append(cost);
        }
        return out.toString();
    }

    /**
     * What to show before the running command has said anything.
     *
     * <p>For a command the invocation IS the activity, and it is already short. For a message to the
     * model the answer is the same word the loop is about to print, so the line does not visibly
     * change class when the transcript takes over -- and above all it is not the user's own sentence,
     * which is already on screen twice, as the {@code >} echo and as the result separator's label.</p>
     *
     * @param line     what was typed
     * @param commands the command names this shell knows
     * @return the opening activity text
     */
    static String opening(String line, Set<String> commands) {
        InputRouter.Routed routed =
                InputRouter.route(line, commands, InputRouter.Mode.CONVERSATION);
        if (routed.isCommand()) {
            return CommandOutputVisibility.describe(routed.getName(),
                                                    String.join(" ", routed.getArgs()));
        }
        return "Thinking";
    }
}
