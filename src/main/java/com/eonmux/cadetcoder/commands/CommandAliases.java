package com.eonmux.cadetcoder.commands;

import java.util.Map;
import java.util.Set;

/**
 * Canonicalizes the action verbs an LLM emits in the agentic loops to the actual registered command
 * name the harness should dispatch.
 *
 * <p>The agentic command catalog advertises one name per capability, but models routinely pick a
 * synonym: {@code execute} or {@code run} for {@code bash}, {@code create} for {@code write},
 * {@code modify} for {@code edit}, {@code show} for {@code read}, {@code list} for {@code ls}.
 * Dispatching those literally is wrong twice over. {@code execute} is a <em>different</em>,
 * registered command ({@link ExecuteCommand}) that runs a <em>script file</em> and resolves its
 * first argument as a file path -- so a shell command such as {@code find . -name "*.java"} was
 * reported as "File not found" instead of being executed, and the shell-command security validation
 * (which keys on {@code "bash"}) was silently skipped. The rest are not registered at all, so
 * {@link com.eonmux.cadetcoder.CommandRegistry#executeCommand(String, String[])} fell through to its
 * chat fallback: a tool call the model had already made became another billed round trip that
 * re-derived it.</p>
 *
 * <p>This helper is the single place every agentic dispatch point shares -- {@link ChatCommand} via
 * {@link com.eonmux.cadetcoder.ai.parsing.ParsedAction#toLegacyAction()},
 * {@link AgentCommand}, and {@link com.eonmux.cadetcoder.ai.parsing.FuzzyParser}'s prose-guessing
 * strategy -- so a verb cannot mean one command in one of them and another somewhere else. It
 * deliberately does NOT touch the CLI {@code cadet execute <script>} path, which dispatches through
 * the registry directly and never passes through here.</p>
 */
public final class CommandAliases {

    private CommandAliases() {
    }

    /**
     * Synonym to registered command name.
     *
     * <p>Each entry is a verb that is <em>not</em> itself a registered command and that has exactly
     * one thing it can mean; the read/write/edit/shell groupings are the ones
     * {@link com.eonmux.cadetcoder.ai.parsing.ParsedAction} already treats as the same operation in
     * {@code isReadOperation}, {@code isWriteOperation}, {@code isModifyOperation} and
     * {@code isExecutionOperation}.</p>
     *
     * <p>Two families are deliberately absent. {@code shell} and {@code sh} are excluded because
     * {@code shell} is a distinct registered command (the interactive REPL). {@code find} and
     * {@code locate} are excluded because they are genuinely ambiguous -- {@code grep} searches file
     * contents and {@code glob} searches file names -- and picking one would run a command the model
     * did not ask for, which is worse than saying the verb was not understood.</p>
     */
    private static final Map<String, String> ALIASES = Map.ofEntries(
            // Run a shell command.
            Map.entry("execute", "bash"),
            Map.entry("exec", "bash"),
            Map.entry("run", "bash"),
            // Run a shell command without waiting for it.
            Map.entry("jobs", "job"),
            Map.entry("background", "job"),
            // Read a file.
            Map.entry("cat", "read"),
            Map.entry("show", "read"),
            Map.entry("view", "read"),
            Map.entry("display", "read"),
            // Write a file.
            Map.entry("create", "write"),
            Map.entry("save", "write"),
            // Change a file.
            Map.entry("modify", "edit"),
            Map.entry("update", "edit"),
            Map.entry("change", "edit"),
            // List a directory.
            Map.entry("list", "ls"),
            Map.entry("dir", "ls"));

    /**
     * Returns the canonical registered command name for an agentic action verb. A leading slash is
     * stripped, known synonyms map to the command they mean, and every other verb is returned
     * lower-cased and trimmed (matching the registry's case-insensitive, lower-cased keys).
     * {@code null} in yields {@code null} out.
     *
     * <p>The slash is stripped because models routinely write tool calls in slash-command form
     * ({@code COMMAND: /read}) -- that spelling is now what humans type at the interactive prompt (see
     * {@link InputRouter}), so it appears in transcripts and the model imitates it. Dispatching
     * {@code "/read"} literally found no registered command and fell through to the chat fallback,
     * turning a tool call into another model round trip. Stripping it here covers every agentic
     * dispatch point, since all of them canonicalize through this method.</p>
     *
     * @param command the verb the model emitted (may be {@code null})
     * @return the command name to dispatch, or {@code null} if {@code command} was {@code null}
     */
    public static String canonicalize(String command) {
        if (command == null) {
            return null;
        }
        String normalized = command.trim().toLowerCase();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1).trim();
        }
        return ALIASES.getOrDefault(normalized, normalized);
    }

    /**
     * The synonyms this class rewrites. Exposed so a test can check every one of them against the
     * live command registry rather than against a second copy of the list.
     *
     * @return the alias verbs, unmodifiable
     */
    public static Set<String> aliases() {
        return ALIASES.keySet();
    }
}
