package com.eonmux.cadetcoder.commands;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Decides whether a line of input is a <em>command invocation</em> or a <em>natural-language message
 * for the AI</em>, and is the single source of truth for that decision across every entry point.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Both entry points used to make the decision by looking at the first whitespace-separated word
 * and dispatching it if it happened to name a registered command. Because roughly twenty command
 * names are also ordinary English sentence-openers ({@code read}, {@code write}, {@code edit},
 * {@code explain}, {@code commit}, {@code push}, {@code run}, {@code find}, {@code help}, ...), a
 * plain request was silently executed as a tool call against the words of the sentence:</p>
 *
 * <pre>
 *   write a test for Foo   -&gt; WriteCommand argv ["a","test","for","Foo"]  -&gt; creates a file named "a"
 *   commit the changes     -&gt; CommitCommand                               -&gt; a real git commit
 *   push to origin         -&gt; PushCommand                                 -&gt; a real git push
 *   explain how auth works -&gt; ExplainCommand                              -&gt; "File not found: how"
 * </pre>
 *
 * <p>Meanwhile {@code what does this do?} fell through to chat, so whether the user was talking to a
 * tool or to the model depended on an invisible property of their first word.</p>
 *
 * <h2>The rule</h2>
 *
 * <p>A leading {@code /} means "this is a command" and nothing else does. {@code /read Foo.java} is
 * always the read command; {@code read Foo.java and summarize it} is always a message. A leading
 * {@code //} escapes, so a message may itself begin with a slash.</p>
 *
 * <p>{@link Mode#ARGV} relaxes exactly one thing for the non-interactive CLI: a bare first token that
 * names a registered command still dispatches, because {@code cadet read Foo.java} is the documented
 * command-line contract and scripts depend on it. There is no ambiguity to resolve on a real argv --
 * the shell has already tokenized it and the user typed the command name deliberately. In
 * {@link Mode#CONVERSATION} (the interactive shell, where the primary activity is talking to the
 * model) only the explicit {@code /} form is a command.</p>
 *
 * <p>The router never decides whether a command <em>exists</em> beyond reporting {@link Routed#isKnown()};
 * dispatch and error reporting stay with {@link com.eonmux.cadetcoder.CommandRegistry}.</p>
 */
public final class InputRouter {

    /** The character that marks an explicit command invocation. */
    public static final char COMMAND_PREFIX = '/';

    private InputRouter() {
    }

    /** What a routed line turned out to be. */
    public enum Kind {
        /** Dispatch {@link Routed#getName()} with {@link Routed#getArgs()}. */
        COMMAND,
        /** Send {@link Routed#getText()} to the AI. */
        CHAT,
        /** The line was blank; do nothing. */
        EMPTY
    }

    /** How permissive the caller wants bare (unprefixed) input to be. */
    public enum Mode {
        /**
         * Non-interactive {@code cadet <args>} invocation: a bare first token naming a registered
         * command dispatches as that command. Preserves the existing command-line contract.
         */
        ARGV,
        /**
         * Interactive shell: only an explicit {@code /} prefix names a command; everything else is a
         * message for the AI.
         */
        CONVERSATION
    }

    /** An immutable routing decision. */
    public static final class Routed {

        private final Kind     kind;
        private final String   name;
        private final String[] args;
        private final String   text;
        private final boolean  explicit;
        private final boolean  known;
        private final String   shadowedCommand;

        private Routed(Kind kind, String name, String[] args, String text,
                       boolean explicit, boolean known, String shadowedCommand) {
            this.kind            = kind;
            this.name            = name;
            this.args            = args != null ? args.clone() : new String[0];
            this.text            = text;
            this.explicit        = explicit;
            this.known           = known;
            this.shadowedCommand = shadowedCommand;
        }

        public Kind getKind() {
            return kind;
        }

        /** @return the lower-cased command name, or {@code null} when this is not a command */
        public String getName() {
            return name;
        }

        /** @return a copy of the command arguments; empty when this is not a command */
        public String[] getArgs() {
            return args.clone();
        }

        /** @return the full message text, or {@code null} when this is not a chat message */
        public String getText() {
            return text;
        }

        /** @return {@code true} when the user wrote an explicit {@code /} prefix */
        public boolean isExplicit() {
            return explicit;
        }

        /** @return {@code true} when {@link #getName()} names a registered command */
        public boolean isKnown() {
            return known;
        }

        /**
         * For a chat message whose first word also names a registered command, that command name;
         * {@code null} otherwise. Callers use it to offer "did you mean {@code /write}?" without
         * having to re-tokenize the line.
         *
         * @return the shadowed command name, or {@code null}
         */
        public String getShadowedCommand() {
            return shadowedCommand;
        }

        public boolean isCommand() {
            return kind == Kind.COMMAND;
        }

        public boolean isChat() {
            return kind == Kind.CHAT;
        }

        public boolean isEmpty() {
            return kind == Kind.EMPTY;
        }
    }

    private static final Routed EMPTY_RESULT =
            new Routed(Kind.EMPTY, null, new String[0], null, false, false, null);

    /**
     * Routes a raw line of input.
     *
     * @param line           the line as typed (may be {@code null} or blank)
     * @param knownCommands  the registered command names, lower-cased; may be {@code null}/empty, in
     *                       which case nothing is considered "known" and bare input is always chat
     * @param mode           how permissive bare input should be
     * @return the routing decision, never {@code null}
     */
    public static Routed route(String line, Set<String> knownCommands, Mode mode) {
        if (line == null) {
            return EMPTY_RESULT;
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return EMPTY_RESULT;
        }
        Set<String> known = normalizeKnown(knownCommands);

        // "//..." escapes: the user wants a message that itself starts with a slash.
        if (trimmed.startsWith("//")) {
            return new Routed(Kind.CHAT, null, new String[0], trimmed.substring(1), false, false, null);
        }

        if (trimmed.charAt(0) == COMMAND_PREFIX) {
            String body = trimmed.substring(1).trim();
            if (body.isEmpty()) {
                // A lone "/" is not a command name and not worth sending to the model.
                return EMPTY_RESULT;
            }
            String[] tokens = CommandLineTokenizer.tokenize(body);
            if (tokens.length == 0) {
                return EMPTY_RESULT;
            }
            String name = normalizeName(tokens[0]);
            return new Routed(Kind.COMMAND, name, Arrays.copyOfRange(tokens, 1, tokens.length),
                    null, true, known.contains(name), null);
        }

        String[] tokens = CommandLineTokenizer.tokenize(trimmed);
        if (tokens.length == 0) {
            return EMPTY_RESULT;
        }
        String first      = normalizeName(tokens[0]);
        boolean isCommand = known.contains(first);

        if (mode == Mode.ARGV && isCommand) {
            return new Routed(Kind.COMMAND, first, Arrays.copyOfRange(tokens, 1, tokens.length),
                    null, false, true, null);
        }
        return new Routed(Kind.CHAT, null, new String[0], trimmed, false, false,
                isCommand ? first : null);
    }

    /**
     * Routes a line whose pasted markers have been put back to what they stand for.
     *
     * <h2>Why the decision is made on what was typed</h2>
     *
     * <p>A marker such as {@code [#1: image shot.png]} stands for the file's path, and the path is
     * absolute, so putting it back at the start of a line produces a leading slash that nobody
     * typed. That slash is the one thing this router treats as meaning "command", so dropping a
     * screenshot at an empty prompt and asking a question about it -- the whole point of dropping
     * one -- was dispatched as a command named after the file:</p>
     *
     * <pre>
     *   [#1: image shot.png] transcribe this   typed
     *   /tmp/shot.png transcribe this          sent
     *   Unknown command: tmp/shot.png          reported
     * </pre>
     *
     * <p>The slash that names a command is one the person typed. So the decision is taken from the
     * line as typed, and the line with the markers put back is what the command or the model is
     * given. Only that decision changes: a line whose markers make no difference to it is routed
     * exactly as it was before, arguments and all.</p>
     *
     * @param typed         the line as the person typed it, markers and all
     * @param expanded      the same line with each marker put back to what it stands for
     * @param knownCommands the registered command names, lower-cased
     * @param mode          how permissive bare input should be
     * @return the routing decision, never {@code null}
     */
    public static Routed route(String typed, String expanded, Set<String> knownCommands, Mode mode) {
        Routed asSent = route(expanded, knownCommands, mode);
        if (typed == null || typed.equals(expanded) || !asSent.isCommand()) {
            return asSent;
        }
        Routed asTyped = route(typed, knownCommands, mode);
        if (asTyped.isCommand()) {
            // The person named the command. What the markers stand for are its arguments, and those
            // are read from the line that carries them.
            return asSent;
        }
        return new Routed(Kind.CHAT, null, new String[0], expanded.trim(), false, false,
                          asTyped.getShadowedCommand());
    }

    /**
     * Routes an already-tokenized argument vector, as handed to {@code main}. The tokens are joined
     * with single spaces to reconstruct the line, which is lossless for routing purposes: the shell
     * has already resolved quoting, and only the FIRST token decides command-vs-chat.
     *
     * <p>Argument tokens are taken from {@code argv} directly rather than re-tokenized, so a single
     * argv element containing spaces (a shell-quoted {@code "class Foo"}) stays one argument. The
     * first token is the exception, and only when it is explicit: {@code cadet "/ls src"} hands over
     * a whole command line in one element, and reading that as a command <em>name</em> reported
     * "Unknown command: ls src" for something the tool had already understood was a command. It is
     * split by the tokenizer the shell prompt uses, so a quoted argument means the same thing
     * whichever door it arrives by. A bare {@code cadet "ls src"} is still a message for the model:
     * without the slash there is nothing to tell it apart from a sentence that opens with a command
     * name, and guessing would turn "read the design doc and summarise it" into a file called "the".</p>
     *
     * @param argv          the raw argument vector (may be {@code null} or empty)
     * @param knownCommands the registered command names, lower-cased
     * @param mode          how permissive bare input should be
     * @return the routing decision, never {@code null}
     */
    public static Routed route(String[] argv, Set<String> knownCommands, Mode mode) {
        if (argv == null || argv.length == 0 || argv[0] == null || argv[0].trim().isEmpty()) {
            return EMPTY_RESULT;
        }
        Set<String> known = normalizeKnown(knownCommands);
        String      head  = argv[0].trim();

        if (head.startsWith("//")) {
            String[] copy = argv.clone();
            copy[0] = head.substring(1);
            return new Routed(Kind.CHAT, null, new String[0], String.join(" ", copy), false, false, null);
        }

        boolean  explicit = head.charAt(0) == COMMAND_PREFIX;
        String[] rest     = Arrays.copyOfRange(argv, 1, argv.length);
        if (explicit) {
            head = head.substring(1).trim();
            if (head.isEmpty()) {
                return EMPTY_RESULT;
            }
            if (hasSpace(head)) {
                String[] tokens = CommandLineTokenizer.tokenize(head);
                if (tokens.length == 0) {
                    return EMPTY_RESULT;
                }
                head = tokens[0];
                rest = joined(Arrays.copyOfRange(tokens, 1, tokens.length), rest);
            }
        }
        String name = normalizeName(head);

        if (explicit || (mode == Mode.ARGV && known.contains(name))) {
            return new Routed(Kind.COMMAND, name, rest, null, explicit, known.contains(name), null);
        }
        return new Routed(Kind.CHAT, null, new String[0], String.join(" ", argv), false, false,
                known.contains(name) ? name : null);
    }

    /** Whether a token is really a whole line that a quoting shell handed over in one piece. */
    private static boolean hasSpace(String token) {
        for (int at = 0; at < token.length(); at++) {
            if (Character.isWhitespace(token.charAt(at))) {
                return true;
            }
        }
        return false;
    }

    /** The arguments found inside the first token, followed by the ones that came after it. */
    private static String[] joined(String[] first, String[] second) {
        if (second.length == 0) {
            return first;
        }
        String[] all = new String[first.length + second.length];
        System.arraycopy(first, 0, all, 0, first.length);
        System.arraycopy(second, 0, all, first.length, second.length);
        return all;
    }

    /**
     * Suggests registered command names close to {@code name}, best match first. Used to turn
     * "Unknown command: raed" into an actionable message instead of a silent fallback.
     *
     * @param name          the name the user typed
     * @param knownCommands the registered command names
     * @param limit         the maximum number of suggestions
     * @return matching names, closest first; never {@code null}
     */
    public static List<String> suggest(String name, Set<String> knownCommands, int limit) {
        if (name == null || name.trim().isEmpty() || knownCommands == null || limit <= 0) {
            return Collections.emptyList();
        }
        String          target  = normalizeName(name);
        Set<String>     ordered = new LinkedHashSet<>();
        List<String>    known   = new java.util.ArrayList<>(normalizeKnown(knownCommands));
        Collections.sort(known);

        // Prefix matches first: they are what a user who stopped typing early meant.
        for (String candidate : known) {
            if (candidate.startsWith(target) || target.startsWith(candidate)) {
                ordered.add(candidate);
            }
        }
        // Then near-misses by edit distance, scaled to the length of the word so short names do not
        // match everything.
        int tolerance = Math.max(1, target.length() / 3);
        List<String> byDistance = new java.util.ArrayList<>();
        for (String candidate : known) {
            if (!ordered.contains(candidate) && editDistance(target, candidate) <= tolerance) {
                byDistance.add(candidate);
            }
        }
        byDistance.sort((a, b) -> Integer.compare(editDistance(target, a), editDistance(target, b)));
        ordered.addAll(byDistance);

        List<String> result = new java.util.ArrayList<>(ordered);
        return result.size() > limit ? result.subList(0, limit) : result;
    }

    private static Set<String> normalizeKnown(Set<String> knownCommands) {
        if (knownCommands == null || knownCommands.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String command : knownCommands) {
            if (command != null && !command.trim().isEmpty()) {
                normalized.add(normalizeName(command));
            }
        }
        return normalized;
    }

    private static String normalizeName(String raw) {
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Optimal string alignment (Damerau-Levenshtein) distance, used only for short command names.
     *
     * <p>Transpositions count as ONE edit, not two. Plain Levenshtein scores the single most common
     * typing mistake -- swapping two adjacent letters -- as distance 2, which put {@code raed} out of
     * range of {@code read} and produced no suggestion at all for the very case suggestions exist
     * for.</p>
     */
    private static int editDistance(String a, String b) {
        int rows = a.length() + 1;
        int cols = b.length() + 1;
        int[][] distance = new int[rows][cols];

        for (int i = 0; i < rows; i++) {
            distance[i][0] = i;
        }
        for (int j = 0; j < cols; j++) {
            distance[0][j] = j;
        }
        for (int i = 1; i < rows; i++) {
            for (int j = 1; j < cols; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                distance[i][j] = Math.min(distance[i - 1][j - 1] + cost,
                        Math.min(distance[i - 1][j] + 1, distance[i][j - 1] + 1));
                if (i > 1 && j > 1
                        && a.charAt(i - 1) == b.charAt(j - 2)
                        && a.charAt(i - 2) == b.charAt(j - 1)) {
                    distance[i][j] = Math.min(distance[i][j], distance[i - 2][j - 2] + cost);
                }
            }
        }
        return distance[rows - 1][cols - 1];
    }
}
