package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ui.OutputRouter;

/**
 * Spells a command's usage the way the person reading it would have to type it.
 *
 * <h2>Why this exists</h2>
 *
 * <p>There is one usage string per command and two places it is read, and they do not agree on how
 * a command is invoked. On the command line it is {@code cadet grep ...}; at the interactive shell's
 * prompt it is {@code /grep ...}, because a line without the slash is a message for the model. The
 * strings were written for whichever surface their author had in mind: most said {@code cadet ...},
 * a handful said the bare name, and all of them were shown unchanged in both places. So the shell
 * answered a missing argument with {@code Usage: cadet grep <pattern>} -- a line that does nothing
 * if typed where it was just printed -- and {@code cadet help session} answered with
 * {@code Usage: session [status | list ...]}, which needs the program name it had just omitted.</p>
 *
 * <p>The usage strings now carry the bare form ({@code grep <pattern>}) and this puts the prefix on,
 * chosen from where the text is about to appear. A line that does not begin with the command's own
 * name is left alone, so option lists and notes under a usage summary keep their shape.</p>
 */
public final class CommandUsage {

    private CommandUsage() {
    }

    /**
     * How a command is invoked where the user is reading.
     *
     * @return {@code "/"} while the interactive shell is running, otherwise {@code "cadet "}
     */
    public static String prefix() {
        return OutputRouter.getInstance().commandPrefix();
    }

    /**
     * Renders a bare usage string for the surface the user is on.
     *
     * <p>The command's name is taken from the first token of the first non-blank line, so a usage
     * that lists sub-commands ({@code session list}, {@code session new}) has every one of them
     * prefixed, while an indented option line ({@code -p, --path <dir>}) is left as it is.</p>
     *
     * @param usage the usage text, with no program name or slash on it
     * @return the same text with each invocation line prefixed; {@code null} in yields {@code ""}
     */
    public static String render(String usage) {
        if (usage == null || usage.isEmpty()) {
            return "";
        }
        String name = firstToken(usage);
        if (name.isEmpty()) {
            return usage;
        }
        String        prefix = prefix();
        String[]      lines  = usage.split("\n", -1);
        StringBuilder out    = new StringBuilder(usage.length() + lines.length * prefix.length());
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append('\n');
            }
            out.append(prefixIfInvocation(lines[i], name, prefix));
        }
        return out.toString();
    }

    /** The command name a usage string is about: the first token of its first non-blank line. */
    private static String firstToken(String usage) {
        for (String line : usage.split("\n", -1)) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                int end = trimmed.indexOf(' ');
                return end < 0 ? trimmed : trimmed.substring(0, end);
            }
        }
        return "";
    }

    /** Prefixes {@code line} when it begins with {@code name} as a whole word. */
    private static String prefixIfInvocation(String line, String name, String prefix) {
        int indent = 0;
        while (indent < line.length() && line.charAt(indent) == ' ') {
            indent++;
        }
        if (!line.startsWith(name, indent)) {
            return line;
        }
        int after = indent + name.length();
        if (after < line.length() && line.charAt(after) != ' ') {
            // "grepping" is not "grep"; only a whole word is the command's name.
            return line;
        }
        return line.substring(0, indent) + prefix + line.substring(indent);
    }
}
