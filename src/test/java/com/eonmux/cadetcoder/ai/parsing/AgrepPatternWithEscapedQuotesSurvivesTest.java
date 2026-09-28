package com.eonmux.cadetcoder.ai.parsing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A grep pattern written with escaped quotes reaches grep as the pattern that was meant.
 *
 * <h2>The defect</h2>
 *
 * <p>The ARGS of most actions were split by a second tokenizer, private to the parser, which knew
 * nothing of backslashes. {@code "case \"kernel\"|kernel\("} became {@code case \kernel\|kernel\(},
 * and the search found nothing. Arguments are now split by the one tokenizer every other line
 * goes through.</p>
 */
class AgrepPatternWithEscapedQuotesSurvivesTest {

    @Test
    void theEscapedQuotesAreQuotesInThePattern() {
        ParsedResponse response = new ActionBlockParser().parse(
                "ACTION_START\n"
                + "COMMAND: grep\n"
                + "ARGS: \"case \\\"kernel\\\"|kernel\\(\" --path=src/Cli.java\n"
                + "REASON: find the kernel command\n"
                + "ACTION_END",
                new ParsingContext.Builder("find it").build());

        assertThat(response.getActions()).hasSize(1);
        assertThat(response.getActions().get(0).getParameters().get("pattern"))
                .isEqualTo("case \"kernel\"|kernel\\(");
    }
}
