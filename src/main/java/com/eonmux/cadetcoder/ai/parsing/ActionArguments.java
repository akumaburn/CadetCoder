package com.eonmux.cadetcoder.ai.parsing;

import com.eonmux.cadetcoder.commands.ChatCommand;
import com.eonmux.cadetcoder.commands.CommandLineTokenizer;
import com.eonmux.cadetcoder.commands.JobCommand;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.eonmux.cadetcoder.ai.parsing.ParsedAction.asString;
import static com.eonmux.cadetcoder.ai.parsing.ParsedAction.scalarText;

/**
 * One parsed action rendered as the command line that runs it.
 *
 * <h2>Why the shape is decided per command and not per verb</h2>
 *
 * <p>The arguments are shaped for the command that will actually RUN, not for the verb the model
 * happened to write. A synonym -- {@code create}, {@code modify}, {@code show}, {@code list},
 * {@code run} -- is dispatched under its canonical name by {@code CommandAliases}, so keying this
 * on the raw verb sent every synonym through the default arm, which emits leftover parameters as
 * bare positionals: {@code show <file> limit=20} became {@code read <file> 20}, and
 * {@code list <dir> recursive=true} became {@code ls <dir> true}. One name decides both, so the two
 * cannot drift.</p>
 *
 * <h2>Why this is not part of {@link ParsedAction}</h2>
 *
 * <p>What a model asked for and how each command wants to be asked are two different subjects that
 * change for different reasons: the first with the parsers, the second with the commands' own
 * options. Held together they were one class in which a command gaining a flag and a parser gaining
 * a spelling edited the same 500-line method.</p>
 */
final class ActionArguments {

    private ActionArguments() {}

    /**
     * The dispatchable form of one action.
     *
     * @param action what the model asked for
     * @return the command to run and the arguments to run it with
     */
    static ChatCommand.AIAction legacyActionOf(ParsedAction action) {
        ActionArgv   argv = argvFor(action, action.dispatchedCommand());
        List<String> args = new ArrayList<>(argv.args());
        appendRawArgumentLine(args, argv.command(), action.parameters());
        return new ChatCommand.AIAction(argv.command(), args.toArray(new String[0]),
                                        action.getReasoning());
    }

    /**
     * The argument list one command wants, and the command it is wanted by.
     *
     * @param action     what the model asked for
     * @param dispatched the canonical name of the command the verb resolves to
     * @return the rendered arguments
     */
    private static ActionArgv argvFor(ParsedAction action, String dispatched) {
        switch (dispatched) {
            case "search":       return SearchActionArguments.search(action);
            case "grep":         return SearchActionArguments.grep(action);
            case "glob":         return SearchActionArguments.glob(action);
            case "webfetch":     return SearchActionArguments.webfetch(action);
            case "websearch":    return SearchActionArguments.websearch(action);
            case "read":         return FileActionArguments.read(action);
            case "multiread":    return FileActionArguments.multiread(action);
            case "write":        return FileActionArguments.write(action);
            case "edit":         return FileActionArguments.edit(action);
            case "multiedit":    return FileActionArguments.multiedit(action);
            case "ls":           return FileActionArguments.ls(action);
            case "notebookread": return FileActionArguments.notebookread(action);
            case "notebookedit": return FileActionArguments.notebookedit(action);
            case "analyze":
            case "explain":
            case "refactor":     return aboutOneFile(action, dispatched);
            case "suggest":      return suggest(action);
            case "todowrite":    return todowrite(action);
            case "commit":       return commit(action);
            case "context":      return context(action);
            case "bash":         return bash(action);
            case "job":          return job(action);
            case "patch":        return patch(action);
            default:             return aboutOneFile(action, dispatched);
        }
    }

    /**
     * The file first, then whatever else was named, in stable order.
     *
     * <p>What {@code analyze}, {@code explain} and {@code refactor} each take, and the fallback for
     * a command with no arm of its own: a command this class has never heard of is still usually
     * about a file, and the parameters it was given are still what it was asked to do.</p>
     */
    private static ActionArgv aboutOneFile(ParsedAction action, String dispatched) {
        List<String> args = new ArrayList<>();
        String       path = action.getFilePath();
        if (path != null) {
            args.add(path);
        }
        appendRemainingPositionals(args, action.parameters());
        return new ActionArgv(dispatched, args);
    }

    /** {@code suggest <type> [filepath]}. The suggestion type must be first. */
    private static ActionArgv suggest(ParsedAction action) {
        Map<String, Object> parameters = action.parameters();
        List<String>        args       = new ArrayList<>();
        String type = asString(parameters.get("type"));
        if (type == null) {
            type = asString(parameters.get("suggestion_type"));
        }
        if (type != null) {
            args.add(type);
        }
        String path = action.getFilePath();
        if (path != null) {
            args.add(path);
        }
        return new ActionArgv("suggest", args);
    }

    /** {@code todowrite <add|update|remove|clear> [content] [-i id] [-s status] [-p priority]}. */
    private static ActionArgv todowrite(ParsedAction action) {
        Map<String, Object> parameters = action.parameters();
        List<String>        args       = new ArrayList<>();
        String what = asString(parameters.get("action"));
        if (what != null) {
            args.add(what);
        }
        String content = asString(parameters.get("content"));
        if (content == null) {
            content = asString(parameters.get("task"));
        }
        if (content != null) {
            args.add(content);
        }
        addOption(args, "-i", asString(parameters.get("id")));
        addOption(args, "-s", asString(parameters.get("status")));
        addOption(args, "-p", asString(parameters.get("priority")));
        return new ActionArgv("todowrite", args);
    }

    /** {@code commit [-a] [-n] [message]}. */
    private static ActionArgv commit(ParsedAction action) {
        Map<String, Object> parameters = action.parameters();
        List<String>        args       = new ArrayList<>();
        if (flagTrue(parameters, "all", "stage_all", "stage-all")) {
            args.add("-a");
        }
        if (flagTrue(parameters, "no_verify", "no-verify", "skip_verify")) {
            args.add("-n");
        }
        String message = asString(parameters.get("message"));
        if (message == null) {
            message = asString(parameters.get("msg"));
        }
        if (message != null) {
            args.add(message);
        }
        return new ActionArgv("commit", args);
    }

    /** {@code context [action] [-v]}, or the leftover parameters when no action was named. */
    private static ActionArgv context(ParsedAction action) {
        Map<String, Object> parameters = action.parameters();
        List<String>        args       = new ArrayList<>();
        String what = asString(parameters.get("action"));
        if (what != null) {
            args.add(what);
        } else {
            // "verbose" is translated into a flag below, so it must not also be swept up as a
            // positional here. It was: {"verbose": true} with no action rendered as
            // `context true -v`, and the command read "true" as the action it was asked for and
            // failed with "Unknown action: true".
            appendRemainingPositionals(args, parameters, "verbose");
        }
        if (flagTrue(parameters, "verbose")) {
            args.add("-v");
        }
        return new ActionArgv("context", args);
    }

    /**
     * The whole command line as one argument.
     *
     * <p>A model that emits the argv form ({@code "command": ["ls","-la"]}) means the joined command
     * line; taking only the first element would silently drop every flag and run something the model
     * never asked for. Built by {@link ParsedAction#shellCommandLine()}, so the string dispatched
     * here is, by construction, the string the security screens were given.</p>
     */
    private static ActionArgv bash(ParsedAction action) {
        List<String> args = new ArrayList<>();
        String       line = action.shellCommandLine();
        if (line != null) {
            args.add(line);
        }
        return new ActionArgv("bash", args);
    }

    /**
     * {@code patch <diff>}, with the diff as one argument.
     *
     * <p>An arm of its own because a diff is a payload: {@code PatchCommand} tells a patch from a
     * path by its line breaks, so the whole thing has to arrive as a single argument. Split into
     * words it is not a patch at all -- and its own {@code ---} and removed lines then read as
     * options, one of which ({@code -n}) means "work it out but change nothing".</p>
     */
    private static ActionArgv patch(ParsedAction action) {
        Map<String, Object> parameters = action.parameters();
        List<String>        args       = new ArrayList<>();
        String text = asString(parameters.get("patch"));
        if (text == null) {
            text = asString(parameters.get("diff"));
        }
        if (text == null) {
            text = asString(parameters.get("content"));
        }
        if (text != null && !text.isBlank()) {
            args.add(text);
        }
        String file = asString(parameters.get("file"));
        if (file == null) {
            file = asString(parameters.get("patch_file"));
        }
        addOption(args, "-f", file);
        if (flagTrue(parameters, "dry_run", "dry-run", "dryRun")) {
            args.add("--dry-run");
        }
        return new ActionArgv("patch", args);
    }

    /**
     * {@code job <subcommand> ...}, with anything it is to run left as one argument.
     *
     * <p>An arm of its own for the same reason {@code bash} has one: a command line split here and
     * re-joined with single spaces is a different command. The rule for which parts are tokens and
     * which are not belongs to the command, so {@link JobCommand#argvFor} answers it.</p>
     */
    private static ActionArgv job(ParsedAction action) {
        Map<String, Object> parameters = action.parameters();
        String              rawArgs    = asString(parameters.get(ParsedAction.RAW_ARGS_KEY));
        if (rawArgs == null || rawArgs.isBlank()) {
            // The structured front ends name the parts instead of writing a line.
            String subcommand = asString(parameters.get("subcommand"));
            if (subcommand == null) {
                subcommand = asString(parameters.get("action"));
            }
            String line = ParsedAction.asCommandLine(parameters.get("command"));
            rawArgs = String.join(" ", java.util.stream.Stream.of(subcommand, line)
                                                             .filter(java.util.Objects::nonNull)
                                                             .map(String::trim)
                                                             .filter(part -> !part.isEmpty())
                                                             .toList());
        }
        String description = asString(parameters.get("description"));
        return new ActionArgv("job", JobCommand.argvFor(rawArgs, description));
    }

    /**
     * Appends the argument line a recovered ACTION block carries under {@code args}.
     *
     * <p>{@code ErrorRecoveryManager} rewrites {@code ARGS: <line>} into that one parameter, and no
     * per-command arm reads it -- so without this a recovered {@code grep "TODO" src} dispatched as
     * a bare {@code grep} with no pattern, a recovery that produced a guaranteed usage error.
     * Appended last, so an arm that already placed its own arguments keeps them in front.</p>
     *
     * <p>Split the way {@code InputRouter} splits the same line when a user types it, so a recovered
     * action and a typed one reach the command as the same argv. The shell runner is the exception
     * and is handled by {@link ParsedAction#shellCommandLine()} instead: it re-joins its argv with a
     * single space, so tokenising here and letting {@code BashCommand} put the pieces back would
     * turn {@code grep -r "foo bar" .} into {@code grep -r foo bar .} -- a different command, run
     * without complaint.</p>
     *
     * @param args       the argument list being built (mutated in place)
     * @param dispatched the canonical command name the action will be dispatched under
     * @param parameters the action's parameters
     */
    private static void appendRawArgumentLine(List<String> args, String dispatched,
                                              Map<String, Object> parameters) {
        if (ParsedAction.SHELL_RUNNER_COMMAND.equals(dispatched)) {
            // Already folded in by shellCommandLine(), which is what the bash arm dispatched and what
            // the security screens were shown. Appending it again would run it twice.
            return;
        }
        if ("job".equals(dispatched)) {
            // Same again: the job arm reads the whole line itself, precisely so that what a job is
            // to run is not tokenised. Appending the tokens here would put the command in twice,
            // the second time split.
            return;
        }
        String rawArgs = asString(parameters.get(ParsedAction.RAW_ARGS_KEY));
        if (rawArgs == null) {
            return;
        }
        for (String token : CommandLineTokenizer.tokenize(rawArgs)) {
            args.add(token);
        }
    }

    /**
     * Appends every parameter not already consumed -- the file path, which each arm emits itself,
     * and the raw argument line -- as positional argument strings, in stable key order.
     *
     * <p>{@link NumberedNameOrder} makes the emitted order deterministic, replacing the
     * {@code HashMap} iteration whose order was unspecified and could scramble multi-argument
     * commands between runs -- and orders the numbered names a command with no arm of its own is
     * given by the number they carry, so the eleventh argument does not overtake the second.</p>
     *
     * @param args       the argument list being built (mutated in place)
     * @param parameters the action's parameters
     */
    static void appendRemainingPositionals(List<String> args, Map<String, Object> parameters,
                                           String... alreadyTranslated) {
        Set<String> translated = new HashSet<>();
        for (String key : alreadyTranslated) {
            translated.add(ParsedAction.normalizeKey(key));
        }
        List<String> names = new ArrayList<>(parameters.keySet());
        names.sort(NumberedNameOrder.INSTANCE);
        for (String name : names) {
            String key = ParsedAction.normalizeKey(name);
            if (ParsedAction.FILE_PATH_KEYS.contains(key)
                || ParsedAction.RAW_ARGS_KEY.equals(key)
                // A parameter an arm turns into a flag is that arm's to emit; dumping it here as
                // well put its VALUE on the command line as an argument in its own right.
                || translated.contains(key)) {
                continue;
            }
            Object value = parameters.get(name);
            if (value == null) {
                continue;
            }
            // A list is expanded into individual positional tokens rather than emitted as a single
            // "[a, b]" string, so an array the model supplied reaches the command as separate,
            // parseable arguments.
            if (value instanceof Collection) {
                for (Object element : (Collection<?>) value) {
                    String text = scalarText(element);
                    if (text != null) {
                        args.add(text);
                    }
                }
            } else {
                // A structured value is skipped rather than stringified: see scalarText.
                String text = scalarText(value);
                if (text != null) {
                    args.add(text);
                }
            }
        }
    }

    /**
     * Whether any of the given parameter keys is present and truthy -- a {@code Boolean} {@code true}
     * or a {@code String} parsing to {@code true}.
     *
     * <p>Lets an arm translate the boolean-flag aliases a model may emit ({@code ignore_case},
     * {@code ignore-case}) into the single CLI flag its command expects.</p>
     *
     * @param parameters the action's parameters
     * @param keys       the spellings to accept
     * @return whether the flag was asked for
     */
    static boolean flagTrue(Map<String, Object> parameters, String... keys) {
        for (String key : keys) {
            Object value = parameters.get(key);
            if (value instanceof Boolean && (Boolean) value) {
                return true;
            }
            if (value instanceof String && Boolean.parseBoolean(((String) value).trim())) {
                return true;
            }
        }
        return false;
    }

    /** Appends {@code --flag <value>} as two tokens when {@code value} is present. */
    static void addIntegerOption(List<String> args, String flag, Integer value) {
        addOption(args, flag, value == null ? null : value.toString());
    }

    /** Appends {@code -flag <value>} as two tokens when {@code value} is present. */
    private static void addOption(List<String> args, String flag, String value) {
        if (value != null) {
            args.add(flag);
            args.add(value);
        }
    }
}
