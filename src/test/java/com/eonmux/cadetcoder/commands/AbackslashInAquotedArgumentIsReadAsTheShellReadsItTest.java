package com.eonmux.cadetcoder.commands;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A backslash in an argument means what it means to a shell.
 *
 * <h2>The defect</h2>
 *
 * <p>A model searched for {@code case "kernel"} and wrote the pattern the way a shell expects it:
 * {@code "case \"kernel\"|kernel\("}. Backslashes had no meaning to the tokenizer, so the quoted run
 * ended at the first escaped quote. {@code grep} was handed {@code case \kernel\|...}, which is not
 * a regular expression, and found nothing.</p>
 *
 * <p>The rules are the shell's. Inside double quotes a backslash escapes only {@code "}, {@code \},
 * {@code $} and a backtick, so {@code "kernel\("} keeps its backslash for the regular expression.
 * Outside quotes it escapes a quote, a backslash or a space, and is kept before anything else, so
 * {@code kernel\(} and {@code a\.b} reach the command as written. Inside single quotes nothing is
 * escaped.</p>
 */
class AbackslashInAquotedArgumentIsReadAsTheShellReadsItTest {

    @Test
    void anEscapedQuoteInsideDoubleQuotesIsAquote() {
        assertThat(CommandLineTokenizer.tokenize("\"case \\\"kernel\\\"|kernel\\(\" --path=a.java"))
                .containsExactly("case \"kernel\"|kernel\\(", "--path=a.java");
    }

    @Test
    void aBackslashBeforeAnythingElseInsideDoubleQuotesIsKept() {
        assertThat(CommandLineTokenizer.tokenize("\"\\d+\\.\\w\"")).containsExactly("\\d+\\.\\w");
    }

    @Test
    void anEscapedBackslashInsideDoubleQuotesIsOneBackslash() {
        assertThat(CommandLineTokenizer.tokenize("\"a\\\\b\"")).containsExactly("a\\b");
    }

    @Test
    void outsideQuotesAnEscapedSpaceDoesNotSplit() {
        assertThat(CommandLineTokenizer.tokenize("read my\\ file.txt")).containsExactly("read", "my file.txt");
    }

    @Test
    void outsideQuotesAnEscapedQuoteOpensNothing() {
        assertThat(CommandLineTokenizer.tokenize("say it\\'s")).containsExactly("say", "it's");
    }

    @Test
    void outsideQuotesAregexBackslashIsKept() {
        assertThat(CommandLineTokenizer.tokenize("grep kernel\\( a\\.b")).containsExactly("grep", "kernel\\(", "a\\.b");
    }

    @Test
    void insideSingleQuotesNothingIsEscaped() {
        assertThat(CommandLineTokenizer.tokenize("'a\\\"b\\\\'")).containsExactly("a\\\"b\\\\");
    }
}
