package com.eonmux.cadetcoder.security;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A shell command line, taken apart the way a shell takes it apart.
 *
 * <h2>Why the line is parsed rather than scanned for characters</h2>
 *
 * <p>The screens {@code bash} passes through used to refuse the line whenever it contained any of
 * {@code ; & | < > $ ` { } [ ]}. That is a rule about characters, and the work people ask for is
 * made of them: {@code awk '{print $1}'}, {@code grep -r todo . | wc -l}, {@code ls target/*.xml},
 * {@code mvn test > build.log}. Every one of those was refused before it ran, and the refusal named
 * no reason, so a model that proposed one had nothing to correct.</p>
 *
 * <p>What the screens actually need to know is which programs a line runs and which files it
 * touches. Both are questions about the line's structure, so the structure is what this recovers:
 * the segments an operator separates, the program each one starts, the redirections each one
 * performs, and the command substitutions nested inside it. A character that appears inside quotes
 * is text and is treated as text, which is what makes an {@code awk} script ordinary again.</p>
 *
 * <h2>What it is not</h2>
 *
 * <p>Not a shell. There is no expansion, no alias, no function, no job control and no arithmetic.
 * A token built from a variable ({@code $CMD}, {@code ${tool}}) cannot be known here at all, and is
 * reported as hidden rather than guessed at -- the caller decides what to do about a program it
 * cannot see. Treat a parse as "this is what the line appears to say", never as proof of what the
 * shell will do with it.</p>
 */
public final class ShellCommandLine {

    /** A file the line reads from or writes to by redirection. */
    public record Redirect(String target, boolean writes) {
    }

    /**
     * One command in the line: the part an operator separates from its neighbours.
     *
     * @param tokens        the words it is made of, quotes removed, redirections taken out
     * @param redirects     the files it redirects to or from
     * @param substitutions the command lines it substitutes the output of
     * @param hiddenTokens  the positions in {@code tokens} whose text is built by expansion, and so
     *                      is not knowable from the line alone
     */
    public record Segment(List<String> tokens, List<Redirect> redirects,
                          List<ShellCommandLine> substitutions, List<Integer> hiddenTokens) {

        /** @return whether the token at {@code index} is built by expansion */
        public boolean isHidden(int index) {
            return hiddenTokens.contains(index);
        }

        /** @return whether this segment names no program at all */
        public boolean isEmpty() {
            return tokens.isEmpty();
        }
    }

    private final List<Segment> segments;
    private final boolean       background;

    private ShellCommandLine(List<Segment> segments, boolean background) {
        this.segments   = Collections.unmodifiableList(segments);
        this.background = background;
    }

    /**
     * Takes a command line apart.
     *
     * @param line the line as written; {@code null} and blank parse to nothing
     * @return the parse, never {@code null}
     */
    public static ShellCommandLine parse(String line) {
        return new Parser(line).run();
    }

    /**
     * The commands the line runs, in the order it writes them.
     *
     * @return the segments, never {@code null}
     */
    public List<Segment> segments() {
        return segments;
    }

    /**
     * Whether the line puts a command into the background, where nothing waiting on it can tell
     * when it finished or stop it.
     *
     * @return whether a lone {@code &} separates or ends a command
     */
    public boolean isBackground() {
        return background;
    }

    /**
     * Every segment on the line, including the ones nested inside command substitutions.
     *
     * <p>Flattened because a screen has to apply to all of them equally: {@code echo $(rm -rf /)}
     * runs {@code rm}, and a check that only read the outermost segment would see {@code echo}.</p>
     *
     * @return the segments, outermost first
     */
    public List<Segment> allSegments() {
        List<Segment> all = new ArrayList<>();
        collectInto(all);
        return all;
    }

    private void collectInto(List<Segment> out) {
        for (Segment segment : segments) {
            out.add(segment);
            for (ShellCommandLine nested : segment.substitutions()) {
                nested.collectInto(out);
            }
        }
    }

    /** Builds the segments of one line, character by character. */
    private static final class Parser {

        private final String line;

        private final List<Segment> segments = new ArrayList<>();

        private List<String>            tokens        = new ArrayList<>();
        private List<Redirect>          redirects     = new ArrayList<>();
        private List<ShellCommandLine>  substitutions = new ArrayList<>();
        private List<Integer>           hidden        = new ArrayList<>();

        private final StringBuilder token   = new StringBuilder();
        private boolean             started;
        private boolean             expanded;

        /** Set while a redirection operator is waiting for the file name that follows it. */
        private boolean redirectPending;
        private boolean redirectWrites;

        private boolean background;

        private int at;

        Parser(String line) {
            this.line = line == null ? "" : line;
        }

        ShellCommandLine run() {
            char quote = 0;
            while (at < line.length()) {
                char c = line.charAt(at);

                if (quote == '\'') {
                    // Nothing expands inside single quotes, which is what makes an awk script text.
                    if (c == '\'') {
                        quote = 0;
                    } else {
                        append(c);
                    }
                    at++;
                    continue;
                }
                if (c == '\\' && at + 1 < line.length()) {
                    append(line.charAt(at + 1));
                    at += 2;
                    continue;
                }
                if (quote == '"') {
                    if (c == '"') {
                        quote = 0;
                        at++;
                    } else if (isSubstitutionStart(c)) {
                        readSubstitution();
                    } else if (c == '$') {
                        markExpanded();
                        append(c);
                        at++;
                    } else {
                        append(c);
                        at++;
                    }
                    continue;
                }
                if (c == '\'' || c == '"') {
                    quote = c;
                    started = true;
                    at++;
                    continue;
                }
                if (isSubstitutionStart(c)) {
                    readSubstitution();
                    continue;
                }
                if (c == '$') {
                    markExpanded();
                    append(c);
                    at++;
                    continue;
                }
                if (c == '<' || c == '>') {
                    readRedirect(c);
                    continue;
                }
                if (c == '|' || c == ';' || c == '&' || c == '\n' || c == '\r'
                    || c == '(' || c == ')') {
                    readSeparator(c);
                    continue;
                }
                if (Character.isWhitespace(c)) {
                    endToken();
                    at++;
                    continue;
                }
                append(c);
                at++;
            }
            endSegment();
            return new ShellCommandLine(segments, background);
        }

        private boolean isSubstitutionStart(char c) {
            return c == '`' || (c == '$' && at + 1 < line.length() && line.charAt(at + 1) == '(');
        }

        /**
         * Reads {@code $(...)} or a backtick pair, and parses what it contains as its own line.
         *
         * <p>The substituted text becomes part of the token being built, so the token stops being
         * knowable; the commands inside it, though, are perfectly readable and are exactly what a
         * screen has to see.</p>
         */
        private void readSubstitution() {
            markExpanded();
            String inner;
            if (line.charAt(at) == '`') {
                int close = line.indexOf('`', at + 1);
                inner = close < 0 ? line.substring(at + 1) : line.substring(at + 1, close);
                at    = close < 0 ? line.length() : close + 1;
            } else {
                int close = matchingParen(at + 1);
                inner = line.substring(at + 2, Math.min(close, line.length()));
                at    = close < line.length() ? close + 1 : line.length();
            }
            substitutions.add(ShellCommandLine.parse(inner));
        }

        /**
         * Finds the parenthesis that closes the one at {@code open}.
         *
         * @param open the index of the opening parenthesis
         * @return the index of its match, or the length of the line when it has none
         */
        private int matchingParen(int open) {
            int depth = 0;
            for (int i = open; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                    if (depth == 0) {
                        return i;
                    }
                }
            }
            return line.length();
        }

        /**
         * Reads a redirection operator and remembers that the next word names its file.
         *
         * <p>A file descriptor written in front of the operator ({@code 2>}) belongs to the
         * redirection rather than to the command, and a duplication written after it
         * ({@code 2>&1}) names no file at all.</p>
         */
        private void readRedirect(char operator) {
            if (isFileDescriptor(token.toString())) {
                token.setLength(0);
                started = false;
            } else {
                endToken();
            }
            at++;
            if (at < line.length() && line.charAt(at) == operator) {
                at++; // ">>" appends; "<<" opens a here-document, whose body is text
            }
            if (at < line.length() && line.charAt(at) == '&') {
                at++;
                while (at < line.length() && Character.isDigit(line.charAt(at))) {
                    at++;
                }
                if (at < line.length() && line.charAt(at) == '-') {
                    at++;
                }
                return; // a descriptor was pointed at another descriptor; no file is named
            }
            redirectPending = true;
            redirectWrites  = operator == '>';
        }

        /** Whether a token written in front of a redirection is a file descriptor number. */
        private static boolean isFileDescriptor(String text) {
            if (text.isEmpty() || text.length() > 2) {
                return false;
            }
            for (int i = 0; i < text.length(); i++) {
                if (!Character.isDigit(text.charAt(i))) {
                    return false;
                }
            }
            return true;
        }

        /** Ends the current command, and notes when the operator sent it to the background. */
        private void readSeparator(char c) {
            boolean doubled = at + 1 < line.length() && line.charAt(at + 1) == c;
            if (c == '&' && !doubled) {
                background = true;
            }
            endSegment();
            at += doubled ? 2 : 1;
        }

        private void append(char c) {
            token.append(c);
            started = true;
        }

        /** Notes that the token being built contains text the line does not spell out. */
        private void markExpanded() {
            expanded = true;
            started  = true;
        }

        private void endToken() {
            if (!started) {
                return;
            }
            String text = token.toString();
            token.setLength(0);
            started = false;
            if (redirectPending) {
                redirects.add(new Redirect(text, redirectWrites));
                redirectPending = false;
                expanded        = false;
                return;
            }
            if (expanded) {
                hidden.add(tokens.size());
                expanded = false;
            }
            tokens.add(text);
        }

        private void endSegment() {
            endToken();
            if (tokens.isEmpty() && redirects.isEmpty() && substitutions.isEmpty()) {
                return;
            }
            segments.add(new Segment(List.copyOf(tokens), List.copyOf(redirects),
                                     List.copyOf(substitutions), List.copyOf(hidden)));
            tokens        = new ArrayList<>();
            redirects     = new ArrayList<>();
            substitutions = new ArrayList<>();
            hidden        = new ArrayList<>();
        }
    }
}
