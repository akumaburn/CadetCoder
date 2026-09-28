package com.eonmux.cadetcoder.commands;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Predictive completion of a command name as it is typed.
 *
 * <p>Answers one question: given what has been typed so far, what would the shell add if the user
 * accepted the suggestion? Pure and terminal-free, so the rule can be tested without driving a
 * terminal.</p>
 *
 * <h2>What is completed, and what is not</h2>
 *
 * <p>Only a command name, and only after the leading {@code /} that marks the line as a command.
 * A line without it is a message for the AI ({@link InputRouter}), and predicting a command name
 * into prose would suggest the shell was about to run something it is not. Once a space has been
 * typed the name is settled and what follows is arguments, which this does not attempt.</p>
 *
 * <h2>Why the longest common prefix</h2>
 *
 * <p>With several candidates, completing to the first alphabetically would be a guess that is
 * usually wrong. Completing to the longest prefix they all share is never wrong: it adds exactly the
 * characters the user would have had to type anyway, and stops where a real choice begins.</p>
 */
public final class CommandCompletion {

    /** Nothing to suggest. */
    public static final CommandCompletion NONE = new CommandCompletion("", 0);

    private final String suffix;
    private final int    matches;

    private CommandCompletion(String suffix, int matches) {
        this.suffix  = suffix;
        this.matches = matches;
    }

    /**
     * @return the characters that would be appended on acceptance; empty when there is nothing to add
     */
    public String getSuffix() {
        return suffix;
    }

    /** @return how many command names the typed prefix matches */
    public int getMatches() {
        return matches;
    }

    /** @return whether accepting the suggestion would change the input */
    public boolean hasSuffix() {
        return !suffix.isEmpty();
    }

    /** @return whether there is anything worth showing the user */
    public boolean isEmpty() {
        return suffix.isEmpty() && matches <= 1;
    }

    /**
     * The hint rendered after the cursor: the completion itself, and -- when the prefix is still
     * ambiguous -- how many commands remain.
     *
     * @return the text to draw, or an empty string when there is nothing to show
     */
    public String ghostText() {
        if (matches <= 1) {
            return suffix;
        }
        return suffix + "  (" + matches + " commands)";
    }

    /**
     * Computes the completion for a partially typed line.
     *
     * @param line          the whole input line as typed
     * @param knownCommands the registered command names
     * @return the completion, never {@code null}
     */
    public static CommandCompletion of(String line, Set<String> knownCommands) {
        if (line == null || knownCommands == null || knownCommands.isEmpty()) {
            return NONE;
        }
        // No leading slash: the line is a message for the AI, not a command.
        if (line.isEmpty() || line.charAt(0) != InputRouter.COMMAND_PREFIX) {
            return NONE;
        }
        // "//" escapes a message that begins with a slash.
        if (line.startsWith("//")) {
            return NONE;
        }
        String typed = line.substring(1);
        // A space means the name is settled; anything after it is arguments.
        if (typed.isEmpty() || typed.indexOf(' ') >= 0) {
            return NONE;
        }

        String       prefix     = typed.toLowerCase(Locale.ROOT);
        List<String> candidates = new ArrayList<>();
        for (String command : knownCommands) {
            if (command != null && command.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                candidates.add(command);
            }
        }
        if (candidates.isEmpty()) {
            return NONE;
        }
        Collections.sort(candidates);

        String common = longestCommonPrefix(candidates);
        String suffix = common.length() > typed.length() ? common.substring(typed.length()) : "";
        return new CommandCompletion(suffix, candidates.size());
    }

    private static String longestCommonPrefix(List<String> values) {
        String common = values.get(0);
        for (int i = 1; i < values.size() && !common.isEmpty(); i++) {
            String other = values.get(i);
            int    limit = Math.min(common.length(), other.length());
            int    j     = 0;
            while (j < limit && Character.toLowerCase(common.charAt(j)) == Character.toLowerCase(other.charAt(j))) {
                j++;
            }
            common = common.substring(0, j);
        }
        return common;
    }
}
