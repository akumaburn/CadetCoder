package com.eonmux.cadetcoder.commands;

import java.util.Map;

/**
 * Which argument of a dispatched command names a file.
 *
 * <h2>Why this is per-command and not a predicate</h2>
 *
 * <p>"Is this a file operation?" is the wrong question. A file-touching command does not
 * necessarily take its path FIRST ({@code suggest <type> <path>}), and a command that searches the
 * filesystem may take no path positional at all ({@code grep <pattern>}, {@code glob <pattern>} --
 * the path is an option). Validating argv[0] of every such command as a path is what broke
 * {@code grep}: the search PATTERN was existence-checked and then run through a path sanitizer
 * that stripped {@code ".."} out of a regex.</p>
 *
 * <h2>Why the table is exhaustive</h2>
 *
 * <p>It covers every registered command name plus the {@code cat}/{@code find} aliases the loop may
 * still emit, so no path check is silently dropped when a command is added:
 * {@code ChatCommandTest#testPathArgumentIndex_coversEveryRegisteredCommand} fails if a registered
 * command is missing here.</p>
 */
final class PathArgument {

    /** Sentinel meaning "this command has no filesystem-path positional argument". */
    static final int NONE = -1;

    private static final Map<String, Integer> INDEX = Map.ofEntries(
        // Commands whose FIRST positional is a path
        Map.entry("read", 0),
        // multiread takes N paths; argv[0] is the first of them, so validating it as a path is right.
        Map.entry("multiread", 0),
        Map.entry("write", 0),
        Map.entry("multiedit", 0),
        Map.entry("analyze", 0),
        Map.entry("explain", 0),
        Map.entry("refactor", 0),
        Map.entry("ls", 0),
        Map.entry("index", 0),
        Map.entry("notebookread", 0),
        Map.entry("notebookedit", 0),
        Map.entry("cat", 0),
        Map.entry("stat", 0),
        Map.entry("diff", 0),
        Map.entry("find", 0),
        // suggest <type> <filepath>: the path is the SECOND argument
        Map.entry("suggest", 1),
        // Commands with no path positional (pattern, query, message, shell command, sub-action, ...)
        // edit takes an edit REQUEST, not a path: EditCommand joins its whole argv into one sentence
        // and asks the model for SEARCH/REPLACE blocks (usage: edit "<edit request>"), and never
        // opens argv[0]. Gating it as a path refused any instruction containing ".." -- "raise the
        // ceiling from 3..5" -- for the same reason it refused grep's patterns. The files an edit
        // writes are policed by WritePathPolicy, which EditCommand consults for every one of them.
        Map.entry("edit", NONE),
        Map.entry("grep", NONE),
        // The one argument is the patch itself, which names its own files inside.
        Map.entry("patch", NONE),
        Map.entry("glob", NONE),
        Map.entry("search", NONE),
        Map.entry("bash", NONE),
        // job takes a subcommand, then either an id or a whole command line. None of those is a
        // path, and a command line validated as one would reject every job ever started.
        Map.entry("job", NONE),
        Map.entry("execute", NONE),
        Map.entry("shell", NONE),
        Map.entry("agent", NONE),
        // workers takes one task DESCRIPTION per worker. Validating argv[0] as a path would reject
        // every run with "File not found: review error handling".
        Map.entry("workers", NONE),
        Map.entry("chat", NONE),
        // loop and loopfresh take a GOAL, which is prose. As a path it would be refused for the
        // same reason a worker's task description would be.
        Map.entry("loop", NONE),
        Map.entry("loopfresh", NONE),
        Map.entry("commit", NONE),
        Map.entry("clear", NONE),
        Map.entry("compact", NONE),
        Map.entry("config", NONE),
        Map.entry("context", NONE),
        Map.entry("copilot", NONE),
        Map.entry("help", NONE),
        Map.entry("login", NONE),
        Map.entry("models", NONE),
        Map.entry("plan", NONE),
        Map.entry("prompt", NONE),
        Map.entry("push", NONE),
        Map.entry("quit", NONE),
        // runs takes a sub-action word and then a run NAME, which names a directory the harness
        // made under .cadet/runs rather than a path the caller chose; RecordedRun resolves it.
        Map.entry("runs", NONE),
        // session's argument is an action word, or a session identifier after "resume".
        Map.entry("session", NONE),
        Map.entry("theme", NONE),
        // timer's first argument is a sub-action word, and the rest of a create is the instruction
        // it will be reminded of -- prose, which as a path would be refused for the same reason
        // workers' task descriptions would be.
        Map.entry("timer", NONE),
        Map.entry("todoread", NONE),
        Map.entry("todowrite", NONE),
        Map.entry("ubermode", NONE),
        Map.entry("undo", NONE),
        Map.entry("webfetch", NONE),
        Map.entry("websearch", NONE)
    );

    private PathArgument() {
    }

    /**
     * The index of {@code command}'s filesystem-path argument.
     *
     * <p>An unmapped command defaults to "no path argument" -- checking an unknown command's argv[0]
     * as a path is what corrupted grep -- and is logged, so a newly registered path-taking command is
     * noticed rather than silently left unchecked.</p>
     *
     * @param command the command about to be dispatched
     * @param log     where to report a command the table does not know
     * @return zero-based argument index of the path, or {@link #NONE}
     */
    static int indexFor(String command, LoggingCommandSupport log) {
        if (command == null || command.isEmpty()) {
            return NONE;
        }
        Integer index = INDEX.get(command);
        if (index == null) {
            log.logWarning("Command Validation",
                    "No path-argument mapping for command '" + command
                    + "'; skipping file-path validation");
            return NONE;
        }
        return index;
    }

    /** Every command the table covers, so a coverage check can compare it against the registry. */
    static java.util.Set<String> mappedCommands() {
        return INDEX.keySet();
    }
}
