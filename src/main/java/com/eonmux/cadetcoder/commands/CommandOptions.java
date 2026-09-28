package com.eonmux.cadetcoder.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Normalizes {@code --flag=value} into the {@code --flag value} pair that this project's hand-rolled
 * argument loops expect.
 *
 * <h2>Why</h2>
 *
 * <p>{@link CommandCatalog} tells the model, for every command: <em>"Options may be written as
 * --flag=value or --flag value"</em>. That was only true for the three commands that hand their argv
 * to picocli ({@code grep}, {@code glob}, {@code ls}); the rest parse with {@code args[i].equals(
 * "--flag")} loops and never split on {@code =}. So a model that followed the documented syntax --
 * {@code read Foo.java --limit=50} -- had the option silently ignored, got 2000 lines instead of 50,
 * and typically retried the same call. Making the documented form actually work removes a whole class
 * of failed tool calls.</p>
 *
 * <h2>Why it is opt-in per command rather than applied globally</h2>
 *
 * <p>Splitting on {@code =} is only safe for tokens that are genuinely this command's options. A
 * blanket rewrite in the dispatcher would corrupt real arguments: {@code bash} runs shell commands
 * containing {@code =} ({@code FOO=bar make}), {@code write} takes file content verbatim, and
 * {@code grep} patterns routinely contain {@code =}. Each command therefore passes the exact set of
 * flags that take a value, and nothing else is touched.</p>
 */
public final class CommandOptions {

    private CommandOptions() {
    }

    /**
     * Rewrites {@code --flag=value} (and {@code -f=value}) tokens into two tokens, for the given
     * value-taking flags only.
     *
     * <p>Everything else is passed through byte-for-byte, including a token whose flag name is not in
     * {@code valueTakingFlags}, a bare {@code --}, and any argument that merely contains {@code =}.
     * Splitting stops at a {@code --} terminator so trailing operands are never rewritten.</p>
     *
     * @param args             the raw argument vector (may be {@code null})
     * @param valueTakingFlags the exact flag spellings that take a value, e.g.
     *                         {@code Set.of("--limit", "-l")}
     * @return a new array with the recognized inline forms expanded; never {@code null}
     */
    public static String[] expandInlineValues(String[] args, Set<String> valueTakingFlags) {
        if (args == null || args.length == 0 || valueTakingFlags == null || valueTakingFlags.isEmpty()) {
            return args == null ? new String[0] : args.clone();
        }

        List<String> expanded  = new ArrayList<>(args.length + 2);
        boolean      afterTerm = false;

        for (String arg : args) {
            if (arg == null) {
                expanded.add(null);
                continue;
            }
            if (afterTerm) {
                expanded.add(arg);
                continue;
            }
            if ("--".equals(arg)) {
                afterTerm = true;
                expanded.add(arg);
                continue;
            }

            int equals = arg.indexOf('=');
            if (equals <= 0 || arg.charAt(0) != '-') {
                expanded.add(arg);
                continue;
            }
            String flag = arg.substring(0, equals);
            if (!valueTakingFlags.contains(flag)) {
                expanded.add(arg);
                continue;
            }
            expanded.add(flag);
            expanded.add(arg.substring(equals + 1));
        }
        return expanded.toArray(new String[0]);
    }
}
