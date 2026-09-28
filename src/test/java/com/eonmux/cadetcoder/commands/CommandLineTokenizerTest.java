package com.eonmux.cadetcoder.commands;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Pins the shared tokenizer that the shell and both script runners now use. */
public class CommandLineTokenizerTest {

    @Test
    public void splitsOnWhitespaceRuns() {
        // A plain split(" ") produced an EMPTY leading token here, which reached ReadCommand as the
        // file path and dropped the real one.
        assertThat(CommandLineTokenizer.tokenize("read  a.java")).containsExactly("read", "a.java");
        assertThat(CommandLineTokenizer.tokenize("read\ta.java")).containsExactly("read", "a.java");
    }

    @Test
    public void keepsADoubleQuotedArgumentAsOneToken() {
        assertThat(CommandLineTokenizer.tokenize("write notes.txt \"hello world\""))
                .containsExactly("write", "notes.txt", "hello world");
    }

    @Test
    public void keepsASingleQuotedArgumentAsOneToken() {
        assertThat(CommandLineTokenizer.tokenize("grep 'class Foo'"))
                .containsExactly("grep", "class Foo");
    }

    @Test
    public void theOtherQuoteCharacterIsLiteralInsideAQuotedRun() {
        assertThat(CommandLineTokenizer.tokenize("grep \"it's here\""))
                .containsExactly("grep", "it's here");
    }

    @Test
    public void anUnterminatedQuoteConsumesTheRestOfTheLine() {
        // Lenient rather than an error: this input is frequently model-authored and rejecting it
        // would abort the turn.
        assertThat(CommandLineTokenizer.tokenize("write a.txt \"unterminated"))
                .containsExactly("write", "a.txt", "unterminated");
    }

    @Test
    public void anEmptyQuotedRunIsARealToken() {
        assertThat(CommandLineTokenizer.tokenize("write a.txt \"\""))
                .containsExactly("write", "a.txt", "");
    }

    @Test
    public void blankInputYieldsNoTokens() {
        assertThat(CommandLineTokenizer.tokenize(null)).isEmpty();
        assertThat(CommandLineTokenizer.tokenize("")).isEmpty();
        assertThat(CommandLineTokenizer.tokenize("   ")).isEmpty();
    }

    @Test
    public void adjacentQuotedAndUnquotedTextJoinIntoOneToken() {
        assertThat(CommandLineTokenizer.tokenize("--path=\"my dir\""))
                .containsExactly("--path=my dir");
    }
}
