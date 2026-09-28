package com.eonmux.cadetcoder.commands;

import java.util.ArrayList;
import java.util.List;

/**
 * A search pattern as a model wrote it, with the quoting taken back off.
 *
 * <h2>Why a search unwraps its own pattern</h2>
 *
 * <p>A model quotes a pattern the way whatever it was trained on quoted one: backticks from
 * markdown, {@code /slashes/} from JavaScript and Perl, a {@code regex:} or {@code pattern:} prefix
 * from a schema it once read. Each of those is searched for literally, so the run finds nothing and
 * says nothing about why -- which costs a turn and teaches the model nothing.</p>
 *
 * <h2>Why unwrapping is refused far more often than it is done</h2>
 *
 * <p>Every wrapper listed above is also something a regular expression may legitimately contain, so
 * "there is a delimiter at each end" is a fact about the shape of the string and not about what the
 * caller meant by it. Read as a shape, {@code /tmp/} became {@code tmp} and matched every mention
 * of a temporary file, {@code "[^"]*"} became {@code [^"]*} -- which matches the empty string, so
 * every line in the project matched -- and an unconditional unescape rewrote a backslash followed
 * by a quote into a bare quote wherever the two happened to sit together.</p>
 *
 * <p>So each wrapper is removed only where it cannot be part of the expression it encloses: a quote
 * or a backtick whose partner does not also occur unescaped inside, and a {@code /.../} whose body
 * holds no further slash and does read as a regex rather than as a path. Where the two readings
 * cannot be told apart the pattern is searched for exactly as written, because a search that finds
 * nothing is a result the caller can act on and a search that quietly answers a different question
 * is not.</p>
 *
 * <h2>What is never removed</h2>
 *
 * <p>If unwrapping would leave nothing at all, the original is used instead: a search for something
 * that happens to look like a placeholder is still a search.</p>
 */
final class GrepPattern {

    /** The quote characters a model wraps a pattern in, each escapable by a backslash within it. */
    private static final char[] QUOTES = {'"', '\''};

    /** How markdown delimits a pattern; it carries no escape convention of its own. */
    private static final char BACKTICK = '`';

    /** How JavaScript and Perl delimit one. */
    private static final char SLASH = '/';

    /**
     * The characters that make a {@code /.../} body an expression rather than a path fragment.
     *
     * <p>Without this test the delimiters of {@code /tmp/} read as a JavaScript regex literal, and
     * a caller looking for a path found every bare mention of the word instead.</p>
     */
    private static final String REGEX_METACHARACTERS = "\\[](){}*+?|^$.";

    private GrepPattern() {}

    /**
     * The pattern with the quoting a model tends to add taken back off.
     *
     * @param patternParts the pattern as written, one array entry per argument
     * @param log          where the repair says what it did
     * @return the cleaned parts, or the originals when cleaning would leave nothing
     */
    static String[] cleaned(String[] patternParts, LoggingCommandSupport log) {
        if (patternParts == null || patternParts.length == 0) {
            return patternParts;
        }
        List<String> cleaned = new ArrayList<>();
        for (String part : patternParts) {
            // Empty rather than blank: the parts are rejoined with a space, so a part that is
            // nothing but spaces is the run of spaces the caller asked to search for. Dropped as
            // blank, `grep foo " " bar` went looking for `foo bar` and could not find `foo   bar`.
            if (part == null || part.isEmpty()) {
                continue;
            }
            String processed = unwrapped(part, log);
            if (isPatternPlaceholder(processed)) {
                log.logWarning("Pattern parsing", "Skipping placeholder pattern: " + processed);
                continue;
            }
            if (!processed.isEmpty()) {
                cleaned.add(processed);
            }
        }
        if (cleaned.isEmpty()) {
            log.logWarning("Pattern parsing",
                           "All pattern parts were filtered out, using original pattern");
            return patternParts;
        }
        return cleaned.toArray(new String[0]);
    }

    /** One pattern argument with its wrapping and its prefixes removed. */
    private static String unwrapped(String part, LoggingCommandSupport log) {
        String processed = part;

        String unquoted = withoutQuotes(processed);
        if (unquoted != null) {
            log.logStep("Pattern parsing",
                        "Removed quotes from pattern part: '" + part + "' → '" + unquoted + "'");
            processed = unquoted;
        }
        String unticked = withoutBackticks(processed);
        if (unticked != null) {
            log.logStep("Pattern parsing",
                        "Removed backticks from pattern part: '" + part + "' → '" + unticked + "'");
            processed = unticked;
        }
        if (processed.toLowerCase().startsWith("regex:")) {
            processed = processed.substring("regex:".length()).trim();
            log.logStep("Pattern parsing",
                        "Removed 'regex:' prefix: '" + part + "' → '" + processed + "'");
        }
        if (processed.toLowerCase().startsWith("pattern:")) {
            processed = processed.substring("pattern:".length()).trim();
            log.logStep("Pattern parsing",
                        "Removed 'pattern:' prefix: '" + part + "' → '" + processed + "'");
        }
        String unslashed = withoutRegexLiteralSlashes(processed);
        if (unslashed != null) {
            log.logStep("Pattern parsing",
                        "Converted /pattern/ format: '" + part + "' → '" + unslashed + "'");
            processed = unslashed;
        }
        return processed;
    }

    /**
     * The body of a quoted pattern, or {@code null} when the quotes are part of the expression.
     *
     * <p>A quote inside the body that nothing escapes means the outer pair is not a pair at all --
     * {@code "[^"]*"} opens and closes three times over -- so the part is left alone. Where the
     * body's own quotes are all escaped, the escaping was shell quoting a model carried in with the
     * delimiters, and it comes back off with them.</p>
     */
    private static String withoutQuotes(String part) {
        for (char quote : QUOTES) {
            String body = wrappedBody(part, quote);
            if (body != null && !containsUnescaped(body, quote) && !escapedAt(body, body.length())) {
                return body.replace("\\" + quote, String.valueOf(quote));
            }
        }
        return null;
    }

    /** The body of a backticked pattern, or {@code null} when a backtick is part of the regex. */
    private static String withoutBackticks(String part) {
        String body = wrappedBody(part, BACKTICK);
        return body != null && body.indexOf(BACKTICK) < 0 ? body : null;
    }

    /**
     * The body of a {@code /pattern/} literal, or {@code null} when the slashes are the caller's.
     *
     * <p>A second slash inside says the part is a path with more than one segment, and a body with
     * no metacharacter at all says it is a single segment: neither is a delimited regex.</p>
     */
    private static String withoutRegexLiteralSlashes(String part) {
        String body = wrappedBody(part, SLASH);
        return body != null && body.indexOf(SLASH) < 0 && looksLikeRegex(body) ? body : null;
    }

    /**
     * What sits between a matching pair of delimiters, or {@code null} when there is no pair.
     *
     * <p>Three characters at least, so the delimiter is never also the whole of the body: a search
     * for {@code ""} is a search for two quotes.</p>
     */
    private static String wrappedBody(String part, char delimiter) {
        int last = part.length() - 1;
        if (part.length() > 2 && part.charAt(0) == delimiter && part.charAt(last) == delimiter) {
            return part.substring(1, last);
        }
        return null;
    }

    /** Whether the delimiter also occurs inside the body without a backslash in front of it. */
    private static boolean containsUnescaped(String body, char delimiter) {
        for (int i = 0; i < body.length(); i++) {
            if (body.charAt(i) == delimiter && !escapedAt(body, i)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether whatever sits at {@code index} is escaped by the backslashes before it.
     *
     * <p>Counted rather than tested, because a backslash escapes a backslash: in {@code \\"} the
     * quote is not escaped at all. Asked with {@code index} one past the end, this says whether the
     * closing delimiter itself was escaped, which means it was never a closing delimiter.</p>
     */
    private static boolean escapedAt(String body, int index) {
        int backslashes = 0;
        for (int i = index - 1; i >= 0 && body.charAt(i) == '\\'; i--) {
            backslashes++;
        }
        return backslashes % 2 == 1;
    }

    /** Whether a body holds anything that only a regular expression would put there. */
    private static boolean looksLikeRegex(String body) {
        for (int i = 0; i < body.length(); i++) {
            if (REGEX_METACHARACTERS.indexOf(body.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
    }

    /** Whether a pattern is the placeholder from a usage line rather than something to look for. */
    private static boolean isPatternPlaceholder(String pattern) {
        return pattern.equals("<pattern>") || pattern.equals("[pattern]")
               || pattern.equals("{pattern}") || pattern.equals("PATTERN");
    }

    /**
     * The pattern with each {@code \|} read as "or", as GNU grep reads it.
     *
     * <p>Java reads {@code \|} as a literal bar. A model wrote {@code tv.args\|tv.heap} more
     * than ten times in one run and was told each time that nothing matched, while three files held
     * {@code tv.args}. A literal bar is written {@code [|]}. An escaped backslash, {@code \\},
     * is kept whole, so the bar after it is an "or" already.</p>
     *
     * @param pattern the pattern as written
     * @return the pattern as Java reads it
     */
    static String withBarsAsAlternation(String pattern) {
        StringBuilder read = new StringBuilder(pattern.length());
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\\' && i + 1 < pattern.length()) {
                char next = pattern.charAt(++i);
                if (next != '|') {
                    read.append(c);
                }
                read.append(next);
            } else {
                read.append(c);
            }
        }
        return read.toString();
    }
}
