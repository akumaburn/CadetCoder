package com.eonmux.cadetcoder.ai.parsing;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.eonmux.cadetcoder.ai.parsing.ActionArguments.flagTrue;
import static com.eonmux.cadetcoder.ai.parsing.ParsedAction.asString;

/**
 * How the commands that look things up want to be asked: the two searchers, the globber, and the
 * two that go out to the web.
 *
 * <h2>Why every advertised flag is emitted</h2>
 *
 * <p>Each arm here emits every option its command advertises in the catalog the model is shown. A
 * flag the catalog offers and this class does not translate is a flag the model can ask for and
 * never get -- and the failure is silent, because the parameter is simply dropped on its way to the
 * argument list. Keeping the two in lockstep is what makes the advertised interface true.</p>
 */
final class SearchActionArguments {

    private SearchActionArguments() {}

    /**
     * {@code search <query>} and nothing else -- no path, no flags.
     *
     * <p>{@code SearchCommand} joins whatever it is given into the query string, so anything emitted
     * alongside the query is searched for literally. The front ends disagree on the key -- the
     * ACTION-block parser writes {@code query}, the XML and JSON parsers write {@code pattern} -- so
     * both are read here and converge on one argument.</p>
     */
    static ActionArgv search(ParsedAction action) {
        Map<String, Object> parameters = action.parameters();
        List<String>        args       = new ArrayList<>();
        String query = asString(parameters.get("query"));
        if (query == null) {
            query = asString(parameters.get("pattern"));
        }
        if (query != null) {
            args.add(query);
        }
        return new ActionArgv("search", args);
    }

    /**
     * {@code grep <pattern>} then flags.
     *
     * <p>Values are coerced rather than cast, because a model may supply an array where the command
     * wants one string.</p>
     */
    static ActionArgv grep(ParsedAction action) {
        Map<String, Object> parameters = action.parameters();
        List<String>        args       = new ArrayList<>();
        String pattern = asString(parameters.get("pattern"));
        addOption(args, "--include", asString(parameters.get("include")));
        addOption(args, "--exclude", asString(parameters.get("exclude")));
        addOption(args, "--path", asString(parameters.get("path")));
        if (flagTrue(parameters, "ignore_case", "ignore-case", "case_insensitive", "caseInsensitive")) {
            args.add("--ignore-case");
        }
        if (flagTrue(parameters, "line_number", "line-number", "line_numbers", "lineNumber")) {
            args.add("--line-number");
        }
        if (flagTrue(parameters, "count")) {
            args.add("--count");
        }
        if (flagTrue(parameters, "files_with_matches", "files-with-matches")) {
            args.add("--files-with-matches");
        }
        if (flagTrue(parameters, "invert", "invert_match", "invert-match")) {
            args.add("--invert-match");
        }
        if (flagTrue(parameters, "word", "whole_word", "whole-word")) {
            args.add("--word");
        }
        if (flagTrue(parameters, "line", "whole_line", "whole-line")) {
            args.add("--line");
        }
        if (flagTrue(parameters, "case_sensitive", "case-sensitive")) {
            args.add("--case-sensitive");
        }
        String maxDepth = asString(parameters.get("max-depth"));
        if (maxDepth == null) {
            maxDepth = asString(parameters.get("max_depth"));
        }
        addOption(args, "--max-depth", maxDepth);
        // The lines around a match. Advertised in the catalogue and implemented by the command, so
        // an action that asks for them has to arrive carrying them.
        addOption(args, "--after-context", asString(parameters.get("after")));
        addOption(args, "--before-context", asString(parameters.get("before")));
        addOption(args, "--context", asString(parameters.get("context")));
        if (flagTrue(parameters, "column")) {
            args.add("--column");
        }
        if (flagTrue(parameters, "no-line-number", "no_line_number")) {
            args.add("--no-line-number");
        }
        putPatternIn(args, pattern);
        return new ActionArgv("grep", args);
    }

    /**
     * {@code glob <pattern>} then everything else AS FLAGS.
     *
     * <p>{@code GlobCommand} accepts the base path only via {@code -p}/{@code --path}; a bare second
     * positional is rejected by its picocli parser.</p>
     */
    static ActionArgv glob(ParsedAction action) {
        Map<String, Object> parameters = action.parameters();
        List<String>        args       = new ArrayList<>();
        String pattern = asString(parameters.get("pattern"));
        addOption(args, "--path", asString(parameters.get("path")));
        addOption(args, "--limit", asString(parameters.get("limit")));
        addOption(args, "--sort", asString(parameters.get("sort")));
        addOption(args, "--max-depth", asString(parameters.get("max-depth")));
        if (flagTrue(parameters, "reverse")) {
            args.add("--reverse");
        }
        if (flagTrue(parameters, "include-dirs", "include_dirs", "includeDirs")) {
            args.add("--include-dirs");
        }
        if (flagTrue(parameters, "full-path", "full_path", "fullPath")) {
            args.add("--full-path");
        }
        putPatternIn(args, pattern);
        return new ActionArgv("glob", args);
    }

    /**
     * Puts the pattern into an argument list that already holds the options.
     *
     * <h2>Why a pattern that begins with a dash goes last</h2>
     *
     * <p>The pattern is the first argument, where the command's parser reads a leading {@code -} as
     * an option it does not have and fails the whole search. That makes an ordinary regex --
     * {@code -\d+}, or the name of a flag being looked for in a codebase -- impossible to ask for.
     * Such a pattern is written after a bare {@code --} instead, which every parser involved reads
     * as "no more options", and the pattern arrives as the text it is. A pattern that does not begin
     * with a dash keeps its place at the front, so the argument list is unchanged in the ordinary
     * case.</p>
     *
     * @param args    the options already written, in order
     * @param pattern the pattern, or {@code null} when the action carried none
     */
    private static void putPatternIn(List<String> args, String pattern) {
        if (pattern == null) {
            return;
        }
        if (pattern.startsWith("-")) {
            args.add(SearchParameters.END_OF_FLAGS);
            args.add(pattern);
        } else {
            args.add(0, pattern);
        }
    }

    /** {@code webfetch <url> [prompt] [-t <seconds>]}. The URL must be first. */
    static ActionArgv webfetch(ParsedAction action) {
        Map<String, Object> parameters = action.parameters();
        List<String>        args       = new ArrayList<>();
        String url = asString(parameters.get("url"));
        if (url == null) {
            url = asString(parameters.get("uri"));
        }
        if (url != null) {
            args.add(url);
        }
        String prompt = asString(parameters.get("prompt"));
        if (prompt == null) {
            prompt = asString(parameters.get("question"));
        }
        if (prompt != null) {
            args.add(prompt);
        }
        Integer timeout = action.getIntegerParameter("timeout");
        ActionArguments.addIntegerOption(args, "-t", timeout);
        return new ActionArgv("webfetch", args);
    }

    /** {@code websearch <query> [-n <count>]}. */
    static ActionArgv websearch(ParsedAction action) {
        Map<String, Object> parameters = action.parameters();
        List<String>        args       = new ArrayList<>();
        String query = asString(parameters.get("query"));
        if (query == null) {
            query = asString(parameters.get("q"));
        }
        if (query != null) {
            args.add(query);
        }
        Integer results = action.getIntegerParameter("num_results");
        if (results == null) {
            results = action.getIntegerParameter("num");
        }
        ActionArguments.addIntegerOption(args, "-n", results);
        return new ActionArgv("websearch", args);
    }

    /**
     * Appends {@code --flag=value} as one token when {@code value} is present.
     *
     * <p>The joined form, because these two commands take their options that way; the commands that
     * want two tokens are served by {@link ActionArguments#addIntegerOption}. Which form a command
     * accepts is a fact about that command, so it is stated once per command rather than guessed
     * per option.</p>
     */
    private static void addOption(List<String> args, String flag, String value) {
        if (value != null) {
            args.add(flag + "=" + value);
        }
    }
}
