package com.eonmux.cadetcoder.ai.parsing;

import java.util.Map;

/**
 * How {@code grep} and {@code glob} argument lines are read back into parameters.
 *
 * <h2>Why every flag is recognised</h2>
 *
 * <p>Both commands take flags in two spellings, and both once recognised only a couple of them.
 * Anything unrecognised fell through to the positional branch and was assigned as the search path:
 * a bare {@code --line-number} became {@code --path=--line-number} and failed the whole grep; for
 * glob, {@code -p} became {@code --path=-p} and {@code --path=src} became {@code --path=--path=src},
 * so only a bare positional path ever worked. Every flag the command accepts is listed here, value
 * flags consume their value in both spellings, and an unrecognised {@code -} token is dropped
 * rather than corrupting a positional.</p>
 *
 * <h2>Why {@code --} ends the flags</h2>
 *
 * <p>Dropping every unrecognised {@code -} token makes a pattern that begins with {@code -} -- a
 * character class complement, a command-line flag being searched for -- impossible to ask for: it
 * was read as a flag, dropped, and the grep ran against whatever came next, or against nothing at
 * all. A bare {@code --} ends the flags here exactly as it does in a shell, so everything after it
 * is a positional however it is spelled. {@link SearchActionArguments} writes that separator back
 * out for the same pattern, which keeps the reader and the writer agreeing.</p>
 */
final class SearchParameters {

    private SearchParameters() {}

    /** The token that ends the flags, as every shell spells it. */
    static final String END_OF_FLAGS = "--";

    /**
     * Reads {@code glob}'s pattern, path, limit, sort, depth and boolean flags.
     *
     * @param args       the tokenized ARGS payload
     * @param parameters parameter map to populate
     */
    static void glob(String[] args, Map<String, Object> parameters) {
        String  pattern      = null;
        String  path         = null;
        boolean flagsAreOver = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];

            if (flagsAreOver || arg.equals(END_OF_FLAGS)) {
                if (!flagsAreOver) {
                    flagsAreOver = true;
                    continue;
                }
                if (pattern == null) {
                    pattern = arg;
                } else if (path == null) {
                    path = arg;
                }
            } else if (arg.startsWith("--path=")) {
                path = arg.substring("--path=".length());
            } else if (arg.startsWith("--limit=")) {
                parameters.put("limit", arg.substring("--limit=".length()));
            } else if (arg.startsWith("--sort=")) {
                parameters.put("sort", arg.substring("--sort=".length()));
            } else if (arg.startsWith("--max-depth=")) {
                parameters.put("max-depth", arg.substring("--max-depth=".length()));

            } else if ((arg.equals("--path") || arg.equals("-p")) && ActionParameters.hasValueAt(args, i)) {
                path = args[++i];
            } else if ((arg.equals("--limit") || arg.equals("-l")) && ActionParameters.hasValueAt(args, i)) {
                parameters.put("limit", args[++i]);
            } else if ((arg.equals("--sort") || arg.equals("-s")) && ActionParameters.hasValueAt(args, i)) {
                parameters.put("sort", args[++i]);
            } else if (arg.equals("--max-depth") && ActionParameters.hasValueAt(args, i)) {
                parameters.put("max-depth", args[++i]);

            } else if (arg.equals("--reverse") || arg.equals("-r")) {
                parameters.put("reverse", true);
            } else if (arg.equals("--include-dirs") || arg.equals("-d")) {
                parameters.put("include-dirs", true);
            } else if (arg.equals("--full-path") || arg.equals("-f")) {
                parameters.put("full-path", true);

            } else if (arg.startsWith("-")) {
                continue;

            } else if (pattern == null) {
                pattern = arg;
            } else if (path == null) {
                path = arg;
            }
        }

        if (pattern != null) {
            parameters.put("pattern", pattern);
        }
        if (path != null) {
            parameters.put("path", path);
        }
    }

    /**
     * Reads {@code grep}'s pattern, path, include/exclude filters and boolean flags.
     *
     * <p>Boolean flags are stored under the keys {@link ActionArguments} re-emits, so what is read
     * here is what is written back out; value flags consume their value in both the
     * {@code --key=value} and {@code --key value} spellings.</p>
     *
     * @param args       the tokenized ARGS payload
     * @param parameters parameter map to populate
     */
    static void grep(String[] args, Map<String, Object> parameters) {
        String  pattern      = null;
        String  path         = null;
        String  include      = null;
        String  exclude      = null;
        boolean flagsAreOver = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];

            // Everything after a bare "--" is a positional, whatever it is spelled like.
            if (flagsAreOver || arg.equals(END_OF_FLAGS)) {
                if (!flagsAreOver) {
                    flagsAreOver = true;
                    continue;
                }
                if (pattern == null) {
                    pattern = arg;
                } else if (path == null) {
                    path = arg;
                }

            // Value flags: "--key=value" form
            } else if (arg.startsWith("--include=")) {
                include = arg.substring("--include=".length());
            } else if (arg.startsWith("--exclude=")) {
                exclude = arg.substring("--exclude=".length());
            } else if (arg.startsWith("--path=")) {
                path = arg.substring("--path=".length());
            } else if (arg.startsWith("--max-depth=")) {
                parameters.put("max-depth", arg.substring("--max-depth=".length()));

            // Value flags: "--key value" / "-k value" form
            } else if (arg.equals("--include") && ActionParameters.hasValueAt(args, i)) {
                include = args[++i];
            } else if ((arg.equals("--exclude") || arg.equals("-e")) && ActionParameters.hasValueAt(args, i)) {
                exclude = args[++i];
            } else if ((arg.equals("--path") || arg.equals("-p")) && ActionParameters.hasValueAt(args, i)) {
                path = args[++i];
            } else if (arg.equals("--max-depth") && ActionParameters.hasValueAt(args, i)) {
                parameters.put("max-depth", args[++i]);

            // The context flags GrepCommand implements and the catalogue advertises. Unrecognised,
            // their number was not merely dropped: it fell through to the positional branch and
            // became the PATH, so `grep "TODO" -C 3` searched a directory called 3.
            } else if ((arg.equals("--after") || arg.equals("-A")) && ActionParameters.hasValueAt(args, i)) {
                parameters.put("after", args[++i]);
            } else if ((arg.equals("--before") || arg.equals("-B")) && ActionParameters.hasValueAt(args, i)) {
                parameters.put("before", args[++i]);
            } else if ((arg.equals("--context") || arg.equals("-C")) && ActionParameters.hasValueAt(args, i)) {
                parameters.put("context", args[++i]);
            } else if (arg.startsWith("--after=")) {
                parameters.put("after", arg.substring("--after=".length()));
            } else if (arg.startsWith("--before=")) {
                parameters.put("before", arg.substring("--before=".length()));
            } else if (arg.startsWith("--context=")) {
                parameters.put("context", arg.substring("--context=".length()));
            } else if (arg.equals("--column")) {
                parameters.put("column", true);
            } else if (arg.equals("--no-line-number")) {
                parameters.put("no-line-number", true);

            // Boolean flags (long and short forms accepted by GrepCommand)
            } else if (arg.equals("--ignore-case") || arg.equals("-i") || arg.equals("-I")) {
                parameters.put("ignore-case", true);
            } else if (arg.equals("--case-sensitive")) {
                parameters.put("case-sensitive", true);
            } else if (arg.equals("--line-number") || arg.equals("-n")) {
                parameters.put("line-number", true);
            } else if (arg.equals("--count") || arg.equals("-c")) {
                parameters.put("count", true);
            } else if (arg.equals("--files-with-matches") || arg.equals("-l")) {
                parameters.put("files-with-matches", true);
            } else if (arg.equals("--invert-match") || arg.equals("-v")) {
                parameters.put("invert", true);
            } else if (arg.equals("--word") || arg.equals("-w")) {
                parameters.put("word", true);
            } else if (arg.equals("--line") || arg.equals("-x")) {
                parameters.put("line", true);

            // Any other flag token must NOT be folded into a positional (the bug being fixed).
            } else if (arg.startsWith("-")) {
                continue;

            // Positionals: first is the pattern, second is the path.
            } else if (pattern == null) {
                pattern = arg;
            } else if (path == null) {
                path = arg;
            }
        }

        if (pattern != null) {
            parameters.put("pattern", pattern);
        }
        if (path != null) {
            parameters.put("path", path);
        }
        if (include != null) {
            parameters.put("include", include);
        }
        if (exclude != null) {
            parameters.put("exclude", exclude);
        }
    }
}
