package com.eonmux.cadetcoder.ai.parsing;

import com.eonmux.cadetcoder.commands.CommandLineTokenizer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A command line as the model wrote it, read back into the parameters the dispatcher expects.
 *
 * <h2>Why each command is read by its own rule</h2>
 *
 * <p>These commands were once read by one rule that stored every token as {@code arg0},
 * {@code arg1}, and so on. The dispatcher reads NAMED parameters, so a numbered one is a parameter
 * it never looks for: every argument of an {@code ls}, {@code suggest}, {@code todowrite},
 * {@code commit}, {@code webfetch}, {@code websearch}, {@code notebook*} or {@code context} action
 * was silently discarded on its way to the command. The names produced here are exactly the ones
 * {@link ActionArguments} re-emits, which is what keeps the reader and the writer in lockstep.</p>
 *
 * <h2>Why two commands are not tokenized at all</h2>
 *
 * <p>{@code write} and {@code multiedit} carry a verbatim payload -- file content, edit blocks --
 * as their trailing argument. Splitting one on whitespace destroys the structure that is the whole
 * point of it, so both are read from the raw string instead.</p>
 */
final class ActionParameters {

    private ActionParameters() {}

    /**
     * Reads a positional or shell-style argument line into the parameters its command expects.
     *
     * @param argsStr    the raw ARGS payload (may be multi-line)
     * @param command    the canonical command the arguments are for
     * @param parameters parameter map to populate
     */
    static void fromPositional(String argsStr, String command, Map<String, Object> parameters) {
        // Commands whose trailing argument is a VERBATIM payload (file content, edit blocks) are parsed
        // from the raw string: whitespace tokenization would destroy the payload's structure.
        switch (command) {
            case "write":
                parseWriteArgs(argsStr, parameters);
                return;
            case "multiedit":
                parseMultiEditArgs(argsStr, parameters);
                return;
            case "patch":
                // A diff is the payload, not a list of words. Tokenised, its `---`, `+++` and
                // removed lines read as options, no token held a newline, and PatchCommand -- which
                // recognises a patch by its line breaks -- answered "That is no patch." to every
                // patch ever proposed.
                if (!argsStr.isBlank()) {
                    parameters.put("patch", argsStr);
                }
                return;
            default:
                break;
        }

        // The one tokenizer every other line goes through. A copy kept here knew nothing of
        // backslashes, so a grep pattern written "case \"kernel\"" lost its quotes.
        String[] args = CommandLineTokenizer.tokenize(argsStr);

        // Map positional arguments based on command
        switch (command) {
            case "read":
                // read has real flags (--limit/--offset, in both "--flag=value" and "--flag value"
                // forms). They used to fall through to the positional branch, where "--limit=50" was
                // stored as the limit VALUE and then silently dropped by the dispatcher - the whole
                // file was returned instead of the requested range.
                parseReadArgs(args, parameters);
                break;

            case "multiread":
                parseMultiReadArgs(args, parameters);
                break;

            case "edit":
            case "analyze":
            case "explain": {
                // First positional is the target; anything after it is free text (the edit request,
                // the aspect to analyze) and is kept as ONE joined argument. Storing only args[1] -
                // under the misleading key "limit" - silently truncated every multi-word request to a
                // single word before it reached the command.
                List<String> positionals = Arrays.asList(args);
                if (!positionals.isEmpty()) {
                    parameters.put("file_path", positionals.get(0));
                }
                putJoinedTail(positionals, 1, parameters, "request");
                break;
            }

            case "grep":
                // Handle grep arguments which can be positional or flag-based
                SearchParameters.grep(args, parameters);
                break;

            case "search":
                // NOT the grep arm. grep's positionals are <pattern> <path>; search takes a single
                // free-text query and has no path or flags at all, so sharing the arm split the
                // query on whitespace -- first word to the pattern, second to a --path=, the rest
                // discarded. A query worth asking is almost always longer than two words, which
                // made the command unusable exactly where it is most useful.
                putJoinedTail(scanFlags(args, NO_FLAGS, NO_FLAGS, parameters), 0, parameters, "query");
                break;
                
            case "glob":
                SearchParameters.glob(args, parameters);
                break;
                
            case "bash":
                // The line exactly as it was written. Tokenising it and joining the pieces back
                // with single spaces is not the same command: `grep -r "foo bar" .` came back as
                // `grep -r foo bar .`, which searches for one word in two files, and which the
                // shell screens were then shown in place of what the model asked for.
                if (!argsStr.isBlank()) {
                    parameters.put("command", argsStr.trim());
                }
                break;

            case "job":
                // Whole-line for the same reason, and kept under the key the job arm reads: what a
                // job is to run is a command line, and JobCommand decides which parts of it are
                // this command's own options and which belong to the command.
                if (!argsStr.isBlank()) {
                    parameters.put(ParsedAction.RAW_ARGS_KEY, argsStr.trim());
                }
                break;

            case "ls":
                mapPositionals(scanFlags(args, LS_BOOLEAN_FLAGS, LS_VALUE_FLAGS, parameters),
                               parameters, "path");
                break;

            case "suggest":
                mapPositionals(scanFlags(args, NO_FLAGS, NO_FLAGS, parameters),
                               parameters, "type", "file_path");
                break;

            case "todowrite": {
                List<String> positionals = scanFlags(args, NO_FLAGS, TODO_VALUE_FLAGS, parameters);
                if (!positionals.isEmpty()) {
                    parameters.put("action", positionals.get(0));
                }
                putJoinedTail(positionals, 1, parameters, "content");
                break;
            }

            case "commit":
                putJoinedTail(scanFlags(args, COMMIT_BOOLEAN_FLAGS, NO_FLAGS, parameters), 0,
                              parameters, "message");
                break;

            case "webfetch": {
                List<String> positionals = scanFlags(args, NO_FLAGS, WEBFETCH_VALUE_FLAGS, parameters);
                if (!positionals.isEmpty()) {
                    parameters.put("url", positionals.get(0));
                }
                putJoinedTail(positionals, 1, parameters, "prompt");
                break;
            }

            case "websearch":
                putJoinedTail(scanFlags(args, NO_FLAGS, WEBSEARCH_VALUE_FLAGS, parameters), 0,
                              parameters, "query");
                break;

            case "notebookread":
                mapPositionals(scanFlags(args, NO_FLAGS, NOTEBOOK_READ_VALUE_FLAGS, parameters),
                               parameters, "file_path");
                break;

            case "notebookedit": {
                List<String> positionals = scanFlags(args, NO_FLAGS, NOTEBOOK_EDIT_VALUE_FLAGS, parameters);
                mapPositionals(positionals, parameters, "file_path", "cell_id");
                putJoinedTail(positionals, 2, parameters, "source");
                break;
            }

            case "context":
                mapPositionals(scanFlags(args, CONTEXT_BOOLEAN_FLAGS, NO_FLAGS, parameters),
                               parameters, "action");
                break;

            default:
                // For unknown commands, store args as numbered parameters
                for (int i = 0; i < args.length; i++) {
                    parameters.put("arg" + i, args[i]);
                }
                break;
        }
    }

    /** Empty flag table, for commands that take positionals only. */
    private static final Map<String, String> NO_FLAGS = Collections.emptyMap();

    // Flag token -> parameter name tables. The parameter names match what ActionArguments re-emits.
    private static final Map<String, String> LS_BOOLEAN_FLAGS = Map.of(
        "-a", "all", "--all", "all",
        "-l", "long", "--long", "long",
        "-R", "recursive", "--recursive", "recursive",
        "-r", "reverse", "--reverse", "reverse",
        "-d", "dirs_first", "--dirs-first", "dirs_first");

    private static final Map<String, String> LS_VALUE_FLAGS = Map.of("--max-depth", "max_depth");

    private static final Map<String, String> TODO_VALUE_FLAGS = Map.of(
        "-i", "id", "--id", "id",
        "-s", "status", "--status", "status",
        "-p", "priority", "--priority", "priority");

    private static final Map<String, String> COMMIT_BOOLEAN_FLAGS = Map.of(
        "-a", "all", "--all", "all",
        "-n", "no_verify", "--no-verify", "no_verify");

    private static final Map<String, String> WEBFETCH_VALUE_FLAGS = Map.of(
        "-t", "timeout", "--timeout", "timeout");

    private static final Map<String, String> WEBSEARCH_VALUE_FLAGS = Map.of(
        "-n", "num_results", "--num", "num_results");

    private static final Map<String, String> NOTEBOOK_READ_VALUE_FLAGS = Map.of("--cell", "cell");

    private static final Map<String, String> NOTEBOOK_EDIT_VALUE_FLAGS = Map.of(
        "--type", "type", "--mode", "mode");

    private static final Map<String, String> CONTEXT_BOOLEAN_FLAGS = Map.of(
        "-v", "verbose", "--verbose", "verbose");

    /**
     * Splits tokens into flags and positionals.
     *
     * <p>Boolean flags are stored as {@code true}; value flags consume their value in BOTH the
     * {@code --key=value} and {@code --key value} spellings; any other token starting with {@code -}
     * is dropped rather than being mistaken for a positional (the same rule grep already follows -
     * an unrecognized flag folded into a positional is how {@code --line-number} once became a search
     * path).</p>
     *
     * @param args          the tokenized ARGS payload
     * @param booleanFlags  flag token -> parameter name for valueless flags
     * @param valueFlags    flag token -> parameter name for flags that take a value
     * @param parameters    parameter map to populate with the recognized flags
     * @return the positional tokens, in the order they appeared
     */
    private static List<String> scanFlags(String[] args, Map<String, String> booleanFlags,
                                   Map<String, String> valueFlags, Map<String, Object> parameters) {
        List<String> positionals = new ArrayList<>();
        boolean      flagsAreOver = false;

        for (int i = 0; i < args.length; i++) {
            if (flagsAreOver) {
                positionals.add(args[i]);
                continue;
            }
            if (args[i].equals(SearchParameters.END_OF_FLAGS)) {
                // A bare "--" ends the flags, as it does in a shell: what follows is text, even
                // when it begins with a dash. Without it an argument such as a commit message that
                // opens with "-" was read as an unknown flag and dropped.
                flagsAreOver = true;
                continue;
            }
            String arg = unbundle(args[i], booleanFlags, parameters);
            if (arg == null) {
                continue; // A bundle of short boolean flags: every letter is already recorded
            }
            int equals = arg.indexOf('=');
            String flagName = (arg.startsWith("-") && equals > 0) ? arg.substring(0, equals) : arg;

            if (valueFlags.containsKey(flagName)) {
                if (equals > 0) {
                    parameters.put(valueFlags.get(flagName), arg.substring(equals + 1));
                } else if (hasValueAt(args, i)) {
                    parameters.put(valueFlags.get(flagName), args[++i]);
                }
                // A value flag whose next token is itself a flag takes no value: consuming it would
                // store a flag as the value and fail the command. The "--flag=value" form above is
                // unaffected, so a value that genuinely begins with "-" is still expressible.
            } else if (booleanFlags.containsKey(arg)) {
                parameters.put(booleanFlags.get(arg), true);
            } else if (arg.startsWith("-")) {
                continue; // Unrecognized flag: dropped, never folded into a positional
            } else {
                positionals.add(arg);
            }
        }

        return positionals;
    }

    /**
     * Records a bundle of short boolean flags, such as {@code -la}, as the flags it stands for.
     *
     * <h2>Why a bundle is taken apart before the flag tables are consulted</h2>
     *
     * <p>{@code -la} is not a key in any table, so it took the "unrecognized flag" path and was
     * dropped -- {@code ls -la src} listed {@code src} as though neither flag had been written, and
     * the model was given a plain listing it had not asked for with no sign that anything was
     * ignored. A bundle is taken apart only when EVERY letter in it is a known valueless flag;
     * anything else (a short flag that takes a value, an unknown letter) is left alone for the
     * caller's existing rules, so a bundle is never half-read.</p>
     *
     * @param arg          the token as it was written
     * @param booleanFlags flag token -> parameter name for valueless flags
     * @param parameters   parameter map to populate when the token is such a bundle
     * @return {@code null} when the token was a bundle and is now recorded, the token otherwise
     */
    private static String unbundle(String arg, Map<String, String> booleanFlags,
                                   Map<String, Object> parameters) {
        if (arg.length() <= 2 || arg.charAt(0) != '-' || arg.charAt(1) == '-'
                || arg.indexOf('=') >= 0) {
            return arg;
        }
        for (int i = 1; i < arg.length(); i++) {
            if (!booleanFlags.containsKey("-" + arg.charAt(i))) {
                return arg;
            }
        }
        for (int i = 1; i < arg.length(); i++) {
            parameters.put(booleanFlags.get("-" + arg.charAt(i)), true);
        }
        return null;
    }

    /**
     * Assigns positionals to parameter names by position; extra positionals are ignored.
     *
     * @param positionals the positional tokens
     * @param parameters  parameter map to populate
     * @param names       parameter name for each position, in order
     */
    private static void mapPositionals(List<String> positionals, Map<String, Object> parameters, String... names) {
        for (int i = 0; i < names.length && i < positionals.size(); i++) {
            parameters.put(names[i], positionals.get(i));
        }
    }

    /**
     * Joins the positionals from {@code from} onwards into a single parameter (the free-text tail a
     * command takes as one argument, e.g. a commit message or a fetch prompt). Does nothing when there
     * is no such tail.
     *
     * @param positionals the positional tokens
     * @param from        index of the first token belonging to the tail
     * @param parameters  parameter map to populate
     * @param name        parameter name for the joined tail
     */
    private static void putJoinedTail(List<String> positionals, int from, Map<String, Object> parameters, String name) {
        if (positionals.size() > from) {
            parameters.put(name, String.join(" ", positionals.subList(from, positionals.size())));
        }
    }
    
    /**
     * Parses {@code write} arguments: {@code <file_path> <content> [-f]}.
     *
     * <p>The content is captured VERBATIM as the {@code content} parameter - never as {@code limit},
     * which is what the shared read/write branch used to do. That mis-mapping dropped the content
     * entirely (the dispatcher emits no {@code limit} for a write), so every write was rejected with
     * "requires at least two arguments". A payload spanning several lines is taken whole: the first
     * line carries the path (and optionally the start of the content), every following line is
     * content.</p>
     *
     * @param argsStr    the raw ARGS payload (may be multi-line)
     * @param parameters parameter map to populate
     */
    private static void parseWriteArgs(String argsStr, Map<String, Object> parameters) {
        String[] payload = splitFirstLine(argsStr);

        List<String> headTokens = new ArrayList<>();
        for (String token : CommandLineTokenizer.tokenize(payload[0])) {
            if (token.equals("-f") || token.equalsIgnoreCase("--force")) {
                parameters.put("force", true);
            } else {
                headTokens.add(token);
            }
        }

        if (headTokens.isEmpty()) {
            return; // No path: leave parameters empty so validation reports INVALID_PARAMETERS
        }
        parameters.put("file_path", headTokens.get(0));

        String inlineContent = headTokens.size() > 1
            ? String.join(" ", headTokens.subList(1, headTokens.size()))
            : "";
        String content = joinPayload(inlineContent, payload[1]);
        if (content != null) {
            parameters.put("content", content);
        }
    }

    /**
     * Parses {@code multiedit} arguments: {@code <file_path> <edits>}. The edit blocks are kept
     * verbatim (including their line structure) as the {@code edits} parameter; previously a
     * multi-line block was dropped altogether and the {@code edits} key never appeared.
     *
     * @param argsStr    the raw ARGS payload (may be multi-line)
     * @param parameters parameter map to populate
     */
    private static void parseMultiEditArgs(String argsStr, Map<String, Object> parameters) {
        String[] payload = splitFirstLine(argsStr);
        String[] headTokens = CommandLineTokenizer.tokenize(payload[0]);

        if (headTokens.length == 0) {
            return;
        }
        parameters.put("file_path", headTokens[0]);

        String inlineEdits = headTokens.length > 1
            ? String.join(" ", Arrays.copyOfRange(headTokens, 1, headTokens.length))
            : "";
        String edits = joinPayload(inlineEdits, payload[1]);
        if (edits != null) {
            parameters.put("edits", edits);
        }
    }

    /**
     * Parses {@code read} arguments: {@code <file_path> [--limit <n>] [--offset <n>]}, accepting both
     * the {@code --flag=value} and {@code --flag value} spellings. Unrecognized flags are dropped
     * rather than being mistaken for the file path, and a bare second positional integer is still
     * honoured as the limit (the historical shorthand).
     *
     * @param args       the tokenized ARGS payload
     * @param parameters parameter map to populate
     */
    private static void parseReadArgs(String[] args, Map<String, Object> parameters) {
        List<String> positionals = scanFlags(args, NO_FLAGS, READ_VALUE_FLAGS, parameters);

        if (!positionals.isEmpty()) {
            parameters.put("file_path", positionals.get(0));
        }
        // Historical shorthand: a bare second positional is the line limit.
        if (positionals.size() > 1 && !parameters.containsKey("limit")) {
            parameters.put("limit", positionals.get(1));
        }

        // Store the range as numbers where possible so the dispatcher emits clean CLI tokens.
        for (String key : new String[] {"limit", "offset"}) {
            Object value = parameters.get(key);
            if (value instanceof String) {
                parameters.put(key, tryParseInteger((String) value));
            }
        }
    }

    private static final Map<String, String> READ_VALUE_FLAGS = Map.of(
        "--limit", "limit", "-n", "limit",
        "--offset", "offset", "-o", "offset");

    private static final Map<String, String> MULTIREAD_VALUE_FLAGS = Map.of(
        "--limit", "limit", "-l", "limit",
        "--offset", "offset", "-o", "offset",
        "--max-files", "max_files",
        "--max-total-lines", "max_total_lines");

    /**
     * Parses {@code multiread}'s arguments: EVERY positional is a file path.
     *
     * <p>Unlike {@code read}, a second positional here is another file, never a line limit -- applying
     * read's "bare second positional is the limit" shorthand would silently drop the second file of
     * every batch.</p>
     *
     * @param args       the tokenized ARGS payload
     * @param parameters the parameter map to populate
     */
    private static void parseMultiReadArgs(String[] args, Map<String, Object> parameters) {
        List<String> positionals = scanFlags(args, NO_FLAGS, MULTIREAD_VALUE_FLAGS, parameters);
        if (!positionals.isEmpty()) {
            parameters.put("paths", new ArrayList<>(positionals));
            // Keep file_path pointing at the first path so anything that inspects a single target
            // (logging, the caller's path validation) still sees a sensible value.
            parameters.put("file_path", positionals.get(0));
        }
    }

    /**
     * Splits a payload into its first line and the remaining body.
     *
     * @param text the raw payload
     * @return a two-element array: [first line, body or {@code null} when there is no non-blank body]
     */
    private static String[] splitFirstLine(String text) {
        int newline = text.indexOf('\n');
        if (newline < 0) {
            return new String[] {text, null};
        }
        String firstLine = text.substring(0, newline);
        if (firstLine.endsWith("\r")) {
            firstLine = firstLine.substring(0, firstLine.length() - 1);
        }
        String body = text.substring(newline + 1);
        return new String[] {firstLine, body.trim().isEmpty() ? null : body};
    }

    /**
     * Combines the part of a payload that sat on the first line with the remaining body.
     *
     * @param inline the first line's remainder (possibly empty)
     * @param body   the remaining lines, or {@code null}
     * @return the combined payload, or {@code null} when both parts are empty
     */
    private static String joinPayload(String inline, String body) {
        if (body == null) {
            return inline.isEmpty() ? null : inline;
        }
        return inline.isEmpty() ? body : inline + "\n" + body;
    }

    private static Object tryParseInteger(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return value;
        }
    }
    

    /**
     * Whether the token after {@code index} can serve as a value for the flag at {@code index}.
     *
     * <p>A value flag must not consume a token that is itself a flag. {@code --path -p} is malformed,
     * and taking {@code -p} as the path produces {@code --path=-p}, which the command's parser
     * rejects -- turning a recoverable typo into a failed action.</p>
     */
    static boolean hasValueAt(String[] args, int index) {
        return index + 1 < args.length && !args[index + 1].startsWith("-");
    }
}
