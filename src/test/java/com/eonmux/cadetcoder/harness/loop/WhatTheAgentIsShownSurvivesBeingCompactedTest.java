package com.eonmux.cadetcoder.harness.loop;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A long run outgrows any context, so the transcript is cut down while the run is still going.
 *
 * <p>What is cut decides what the agent can still think with. Cutting the agent's own words erases
 * the reasoning that led to the current theory; cutting the recent answers erases the evidence it is
 * reasoning about right now; cutting nothing ends the run early on a context that will not fit. Each
 * test here is one of those.</p>
 */
public class WhatTheAgentIsShownSurvivesBeingCompactedTest {

    private static String long_(String prefix) {
        return prefix + " ".repeat(Transcript.SQUASHED_ABOVE);
    }

    @Test
    public void addingATurnLeavesTheTranscriptItWasAddedToAlone() {
        Transcript one = Transcript.empty().plus(Turn.harness("begin"));
        Transcript two = one.plus(Turn.agent("thinking"));

        assertThat(one.turns()).hasSize(1);
        assertThat(two.turns()).hasSize(2);
    }

    @Test
    public void theMostRecentAnswersAreLeftExactlyAsTheyWere() {
        Transcript transcript = Transcript.empty()
                .plus(Turn.answer(long_("oldest")))
                .plus(Turn.answer(long_("newer")))
                .plus(Turn.answer(long_("newest")));

        Transcript kept = transcript.compacted(2);

        assertThat(kept.turns().get(1).text()).isEqualTo(long_("newer"));
        assertThat(kept.turns().get(2).text()).isEqualTo(long_("newest"));
    }

    @Test
    public void anOlderAnswerIsReplacedBySomethingThatSaysWhereToLookInstead() {
        Transcript transcript = Transcript.empty()
                .plus(Turn.answer(long_("the whole ledger")))
                .plus(Turn.answer(long_("recent")));

        Transcript kept = transcript.compacted(1);

        assertThat(kept.turns().get(0).text()).doesNotContain("the whole ledger");
        assertThat(kept.turns().get(0).text()).contains("beliefs");
    }

    /**
     * A refusal is one line and is the answer that says what to write instead. Squashing it spends a
     * placeholder to save nothing and takes away the correction the agent was given.
     */
    @Test
    public void aShortAnswerIsNotWorthSquashing() {
        Transcript transcript = Transcript.empty()
                .plus(Turn.answer("refused: there is no tool called teleport"))
                .plus(Turn.answer(long_("recent")));

        assertThat(transcript.compacted(0).turns().get(0).text())
                .isEqualTo("refused: there is no tool called teleport");
    }

    /**
     * The agent's own words are the theory it is working from, and for a backend that replays
     * reasoning verbatim they are also what the next request has to send back unchanged.
     */
    @Test
    public void whatTheAgentItselfSaidIsNeverSquashed() {
        Transcript transcript = Transcript.empty()
                .plus(Turn.agent(long_("the corridor runs east")))
                .plus(Turn.harness(long_("plateau detected")))
                .plus(Turn.answer(long_("recent")));

        Transcript kept = transcript.compacted(0);

        assertThat(kept.turns().get(0).text()).isEqualTo(long_("the corridor runs east"));
        assertThat(kept.turns().get(1).text()).isEqualTo(long_("plateau detected"));
    }

    @Test
    public void compactingWhatIsAlreadyCompactedChangesNothingFurther() {
        Transcript once = Transcript.empty()
                .plus(Turn.answer(long_("oldest")))
                .plus(Turn.answer(long_("recent")))
                .compacted(1);

        assertThat(once.compacted(1).turns()).isEqualTo(once.turns());
        assertThat(once.squashed()).isEqualTo(1);
    }

    @Test
    public void aTranscriptSaysHowMuchOfItHasAlreadyBeenGivenUp() {
        Transcript transcript = Transcript.empty()
                .plus(Turn.answer(long_("one")))
                .plus(Turn.answer(long_("two")))
                .plus(Turn.answer(long_("three")));

        assertThat(transcript.squashed()).isZero();
        assertThat(transcript.compacted(1).squashed()).isEqualTo(2);
    }

    @Test
    public void keepingMoreAnswersThanThereAreIsNotAnError() {
        Transcript transcript = Transcript.empty().plus(Turn.answer(long_("only")));

        assertThat(transcript.compacted(10).squashed()).isZero();
    }
}
