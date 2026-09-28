package com.eonmux.cadetcoder.ai.parsing;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Which actions need a file to work on, and whether one has been given.
 *
 * <h2>Why one class holds this</h2>
 *
 * <p>Four parsers each kept a private copy of this rule, and the four copies disagreed. The same
 * reply was therefore judged differently depending on which strategy happened to parse it:
 * {@code multiread} without a path was refused by the action-block parser and accepted by the XML
 * and JSON ones, {@code multiedit} without a path was refused by three and accepted by the semantic
 * parser, and an action whose path arrived under {@code filename} was reported by the XML parser as
 * needing inference while {@link ParsedAction#getFilePath()} -- the method the dispatcher calls
 * before running it -- read that path without difficulty. A verdict that depends on the notation the
 * model chose is not a verdict about the action.</p>
 *
 * <h2>Why the question is the dispatcher's question</h2>
 *
 * <p>Whether an action names a file is answered by asking what the dispatcher will read, rather than
 * by a list of spellings kept alongside it. The private copies each listed three or four keys, none
 * of them {@code filename}, {@code files} or {@code paths}, so an action the dispatcher would have
 * run with a perfectly good path was reported as having none.</p>
 */
public final class FilePathRule {

    /**
     * The commands that mean nothing without a file.
     *
     * <p>{@code multiread} and {@code multiedit} are here for the same reason as the rest: an
     * action of either kind with no path at all cannot be run. Both carry their paths under keys
     * the path lookup already knows, so nothing has to be said about their plural shape.</p>
     */
    private static final Set<String> NEEDS_A_PATH = new LinkedHashSet<>(Arrays.asList(
            "read", "write", "edit", "multiedit", "multiread", "analyze", "explain"));

    private FilePathRule() {
    }

    /**
     * Whether this command cannot be run without a file to run it on.
     *
     * @param command the command name, in any case
     * @return whether an action for it must name a file
     */
    public static boolean requiresPath(String command) {
        return command != null && NEEDS_A_PATH.contains(command.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Whether these parameters name a file the dispatcher would be able to use.
     *
     * @param parameters the action's parameters, whatever the model called them
     * @return whether a usable path is among them
     */
    public static boolean isSatisfiedBy(Map<String, Object> parameters) {
        return ParsedAction.filePathIn(parameters) != null;
    }
}
