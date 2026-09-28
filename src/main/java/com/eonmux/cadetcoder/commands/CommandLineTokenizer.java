package com.eonmux.cadetcoder.commands;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a command line into argv tokens, honoring single and double quotes so a quoted multi-word
 * argument survives as ONE token.
 *
 * <p>This is the one tokenizer, shared by every place a human- or model-authored line is turned
 * into {@code String[] args}. Where a caller kept its own instead, the copies drifted and the
 * difference was invisible: the interactive shell split on whitespace alone, so
 * {@code write notes.txt "hello world"} reached {@code WriteCommand} as four tokens and wrote the
 * file content {@code hello}.</p>
 *
 * <p>Semantics:</p>
 * <ul>
 *   <li>Unquoted runs of whitespace separate tokens.</li>
 *   <li>A quote character opens a quoted run that ends at the next matching quote; the quotes
 *       themselves are removed. The other quote character is literal inside a quoted run.</li>
 *   <li>An unterminated quote consumes the rest of the line (lenient rather than an error, because
 *       the input is frequently model-authored and rejecting it would abort the turn).</li>
 *   <li>An empty quoted run ({@code ""}) is a real, empty token.</li>
 *   <li>A backslash follows the shell's rules. Inside double quotes it escapes {@code "},
 *       {@code \}, {@code $} and a backtick, and is kept before anything else. Outside quotes it
 *       escapes a quote, a backslash or whitespace, and is kept before anything else. Inside single
 *       quotes it is an ordinary character.</li>
 * </ul>
 *
 * <h2>Why a backslash is kept before other characters</h2>
 *
 * <p>The shell drops a backslash outside quotes before any character. Most arguments here are search
 * patterns and paths, where {@code kernel\(} and {@code a\.b} mean the backslash; dropping it would
 * change what is searched for. Only the characters that change how the line splits are escaped:
 * a model writing {@code "case \"kernel\""} means a quote inside the argument, and before this rule
 * the run ended at that quote and the pattern was lost.</p>
 */
public final class CommandLineTokenizer {

    private CommandLineTokenizer() {
    }

    /**
     * Tokenizes {@code line} into argv.
     *
     * @param line the raw line (may be {@code null} or blank)
     * @return the tokens, never {@code null}; empty for a {@code null}/blank line
     */
    public static String[] tokenize(String line) {
        if (line == null || line.trim().isEmpty()) {
            return new String[0];
        }
        List<String>  tokens  = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char          quote   = 0;
        boolean       started = false;
        for (int i = 0; i < line.length(); i++) {
            char c    = line.charAt(i);
            char next = i + 1 < line.length() ? line.charAt(i + 1) : 0;
            if (c == '\\' && quote != '\'' && escapes(quote, next)) {
                current.append(next);
                started = true;
                i++;
            } else if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    current.append(c);
                }
            } else if (c == '"' || c == '\'') {
                quote   = c;
                started = true;
            } else if (Character.isWhitespace(c)) {
                if (started) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    started = false;
                }
            } else {
                current.append(c);
                started = true;
            }
        }
        if (started) {
            tokens.add(current.toString());
        }
        return tokens.toArray(new String[0]);
    }

    /**
     * Whether a backslash escapes the character after it.
     *
     * @param quote the quote the backslash is inside, or {@code 0} when it is outside quotes
     * @param next  the character after the backslash, or {@code 0} at the end of the line
     * @return whether the pair stands for {@code next} alone
     */
    private static boolean escapes(char quote, char next) {
        if (quote == '"') {
            return next == '"' || next == '\\' || next == '$' || next == '`';
        }
        return next == '"' || next == '\'' || next == '\\'
               || (next != 0 && Character.isWhitespace(next));
    }
}
