package com.eonmux.cadetcoder.ai.parsing;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two strategies that run when nothing structured was found.
 *
 * <h2>Why this is tested</h2>
 *
 * <p>These are the last two rungs of the parsing chain and they are the only ones that <em>guess</em>
 * -- one from the verbs in the model's prose, the other from anything at all that resembles a command
 * or a path. Between them they are the reason a reply with no structure in it can still end up
 * dispatching a command against somebody's files, and neither had a test of its own.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>That both declare themselves fallbacks, which is what stops their output being dispatched as
 * though it were structured; that prose with no actionable intent produces no action rather than a
 * plausible-looking one; that an empty or blank reply is reported as empty rather than parsed; and
 * that what a guess does produce comes from the reply itself, since a guess assembled from the
 * user's own request would be the tool answering its own question.</p>
 */
public class TheFallbackParsersGuessAndSaySoTest {

    private static ParsingContext context(String request) {
        return new ParsingContext.Builder(request).build();
    }

    private static ParsedResponse semantic(String reply, String request) {
        return new SemanticParser().parse(reply, context(request));
    }

    private static ParsedResponse fuzzy(String reply, String request) {
        return new FuzzyParser().parse(reply, context(request));
    }

    // ---------------------------------------------------------------- both are fallbacks

    @Test
    public void bothDeclareThemselvesFallbacks() {
        // The engine reads this to decide whether an action may be dispatched or has to be shown to
        // the user as a guess first. A strategy that lied here would have its guesses executed.
        assertThat(new SemanticParser().isFallbackStrategy()).isTrue();
        assertThat(new FuzzyParser().isFallbackStrategy()).isTrue();
    }

    @Test
    public void bothRankBelowTheStructuredStrategies() {
        assertThat(new SemanticParser().getExpectedConfidence())
                .isLessThan(new JSONSchemaParser().getExpectedConfidence());
        assertThat(new FuzzyParser().getExpectedConfidence())
                .isLessThan(new JSONSchemaParser().getExpectedConfidence());
    }

    @Test
    public void theFuzzyParserIsTheLastResortOfTheTwo() {
        assertThat(new FuzzyParser().getExpectedConfidence())
                .isLessThanOrEqualTo(new SemanticParser().getExpectedConfidence());
    }

    // ---------------------------------------------------------------- nothing to parse

    @Test
    public void anEmptyReplyIsReportedAsEmptyByBoth() {
        assertThat(semantic("", "read the file").getResult())
                .isEqualTo(ParsedResponse.ParseResult.EMPTY_RESPONSE);
        assertThat(fuzzy("", "read the file").getResult())
                .isEqualTo(ParsedResponse.ParseResult.EMPTY_RESPONSE);
    }

    @Test
    public void anabsentReplyIsReportedAsEmptyByBoth() {
        assertThat(semantic(null, "read the file").getResult())
                .isEqualTo(ParsedResponse.ParseResult.EMPTY_RESPONSE);
        assertThat(fuzzy(null, "read the file").getResult())
                .isEqualTo(ParsedResponse.ParseResult.EMPTY_RESPONSE);
    }

    @Test
    public void areplyOfNothingButWhitespaceIsAlsoEmpty() {
        assertThat(semantic("   \n\t  ", "read the file").getResult())
                .isEqualTo(ParsedResponse.ParseResult.EMPTY_RESPONSE);
        assertThat(fuzzy("   \n\t  ", "read the file").getResult())
                .isEqualTo(ParsedResponse.ParseResult.EMPTY_RESPONSE);
    }

    @Test
    public void neitherWillDeclareItCannotHandleAReplyItWasGiven() {
        // Both are reached only after everything structured has declined, so declining here would
        // leave the engine with nothing at all to say about the reply.
        assertThat(new SemanticParser().canHandle("anything at all")).isTrue();
        assertThat(new FuzzyParser().canHandle("anything at all")).isTrue();
        assertThat(new SemanticParser().canHandle("")).isFalse();
        assertThat(new FuzzyParser().canHandle("")).isFalse();
    }

    // ---------------------------------------------------------------- guessing from prose

    /** The engine's acceptance threshold: anything below this is not dispatched. */
    private static final double ACCEPTED = 0.7;

    @Test
    public void averbInPassingIsNotAnInstruction() {
        // "anti-pattern" contains a word this parser associates with grep, and a parser whose whole
        // job is guessing will duly guess. What stops an explanation of singletons from running a
        // search is that such a guess scores far below what the engine will accept -- so what is
        // locked is the score, which is the part the engine reads.
        ParsedResponse response = semantic(
                "Singletons are generally considered an anti-pattern in modern Java.",
                "what do you think of singletons");

        assertThat(response.getConfidence()).isLessThan(ACCEPTED);
        for (ParsedAction action : response.getActions()) {
            assertThat(action.getConfidence()).isLessThan(ACCEPTED);
        }
    }

    @Test
    public void anexplanationRunsNothingWhenItReachesTheWholeChain() {
        // The same sentence through the engine, which is where the guarantee actually lives: a turn
        // that only explained something must not come back having searched the project.
        ParsedResponse response = new ResponseParsingEngine().parseResponse(
                "Singletons are generally considered an anti-pattern in modern Java.",
                "what do you think of singletons");

        assertThat(response.isSuccessful() && response.hasActions())
                .as("an explanation is not an instruction")
                .isFalse();
    }

    @Test
    public void aguessIsAttributedToTheStrategyThatMadeIt() {
        // So a caller can tell a guess from a structured parse without knowing how either works.
        ParsedResponse response = semantic("read the file src/Main.java", "look at the code");

        assertThat(response.getStrategyUsed())
                .isEqualTo(ParsedResponse.ParsingStrategy.SEMANTIC_PARSING);
    }

    @Test
    public void afuzzyGuessIsAttributedToTheFuzzyStrategy() {
        ParsedResponse response = fuzzy("raed src/Main.java", "look at the code");

        assertThat(response.getStrategyUsed())
                .isEqualTo(ParsedResponse.ParsingStrategy.FUZZY_PARSING);
    }

    @Test
    public void whatAGuessProducesNamesTheFileTheReplyNamed() {
        // Not the one the user's request named: a parser that read the request back would be the
        // tool answering its own question with the model's turn discarded.
        ParsedResponse response = semantic("Let me read src/Chosen.java to check.",
                                           "have a look at src/Requested.java");

        if (!response.getActions().isEmpty()) {
            ParsedAction action = response.getActions().get(0);
            assertThat(action.getParameters().toString()).doesNotContain("Requested.java");
        }
    }

    @Test
    public void everyGuessCarriesAConfidenceBelowCertainty() {
        // Whatever they produce, it is never allowed to look as good as a structured parse.
        for (ParsedResponse response : java.util.List.of(
                semantic("read the file src/Main.java", "look at the code"),
                fuzzy("raed src/Main.java", "look at the code"))) {
            assertThat(response.getConfidence()).isLessThan(1.0);
        }
    }

    @Test
    public void neitherParserThrowsOnAReplyDesignedToConfuseIt() {
        // Model output is arbitrary text: an exception here would surface as a crashed turn rather
        // than as a reply the tool could not read.
        for (String reply : java.util.List.of("{{{{", "</>", "\\\\\\", "read read read read",
                                              "'''", "....", " ", "a".repeat(5000))) {
            assertThat(semantic(reply, "do something")).isNotNull();
            assertThat(fuzzy(reply, "do something")).isNotNull();
        }
    }

    @Test
    public void bothDescribeWhatTheyCouldNotDo() {
        // The text reaches the user when nothing in the chain succeeded, so an empty explanation is
        // a dead end for whoever has to work out what the model said wrong.
        assertThat(new SemanticParser().getParsingErrorDetails("some unreadable reply"))
                .isNotEmpty();
        assertThat(new FuzzyParser().getParsingErrorDetails("some unreadable reply"))
                .isNotEmpty();
    }

    @Test
    public void alongReplyIsSummarisedRatherThanQuotedWholeInTheError() {
        String reply = "x".repeat(500);

        assertThat(new SemanticParser().getParsingErrorDetails(reply).length())
                .isLessThan(reply.length());
    }
}
