package com.eonmux.cadetcoder.harness.loop;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How a run ends is the one thing about it that everything downstream reads.
 *
 * <p>An agent declares two of the endings itself, in prose, and prose is where a driver is easiest to
 * fool: a reply that merely mentions being done is not a declaration that it is. The rest are counts
 * of what happened, and a count that can be negative, or a result that says the run is still going,
 * is a report nothing can act on.</p>
 */
public class WhatARunReportsWhenItIsOverTest {

    @Test
    public void aReplyCannotCostLessThanNothing() {
        assertThatThrownBy(() -> new Reply("said", -1L, 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void aReplyWithNothingInItIsEmptyRatherThanNull() {
        assertThat(new Reply(null, 0L, 0L).text()).isEmpty();
    }

    @Test
    public void theAgentSaysItIsFinishedByBeginningItsReplyWithDone() {
        assertThat(RunStatus.declared("DONE: the corridor adds one to pos"))
                .isEqualTo(RunStatus.DONE);
    }

    @Test
    public void theAgentSaysItIsBlockedByBeginningItsReplyWithStuck() {
        assertThat(RunStatus.declared("STUCK: what does move mean past the wall?"))
                .isEqualTo(RunStatus.STUCK);
    }

    /**
     * A prefix match on four letters ends a run on any word that happens to start with them. The
     * declaration is a word, so what follows it has to be the end of the reply or something that is
     * not part of the word.
     */
    @Test
    public void aWordThatMerelyBeginsWithThoseLettersIsNotADeclaration() {
        assertThat(RunStatus.declared("DONENESS is not something I can measure"))
                .isEqualTo(RunStatus.RUNNING);
    }

    @Test
    public void blankSpaceBeforeADeclarationDoesNotHideIt() {
        assertThat(RunStatus.declared("\n  DONE")).isEqualTo(RunStatus.DONE);
    }

    @Test
    public void aReplyThatDeclaresNothingLeavesTheRunRunning() {
        assertThat(RunStatus.declared("I am not done yet")).isEqualTo(RunStatus.RUNNING);
        assertThat(RunStatus.declared(null)).isEqualTo(RunStatus.RUNNING);
    }

    @Test
    public void everyWayARunEndsKnowsThatItIsOver() {
        assertThat(RunStatus.RUNNING.over()).isFalse();
        for (RunStatus status : RunStatus.values()) {
            assertThat(status.over()).isEqualTo(status != RunStatus.RUNNING);
        }
    }

    /**
     * A result is what whoever asked for the run is handed. One that says the run is still going is a
     * question rather than an answer, and there is no moment at which the driver has one to give.
     */
    @Test
    public void aResultCannotSayTheRunIsStillGoing() {
        assertThatThrownBy(() -> new RunResult(RunStatus.RUNNING, 1, 1, 1, 0, ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void aResultCannotCountSomethingThatDidNotHappen() {
        assertThatThrownBy(() -> new RunResult(RunStatus.DONE, -1, 0, 0, 0, ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void aDeliberationThatContinuesIsNotOver() {
        assertThat(Deliberation.CONTINUES.over()).isFalse();
        assertThat(new Deliberation(RunStatus.GOAL, "").over()).isTrue();
    }

    @Test
    public void theLimitsARunGetsByDefaultAreAllWorthHaving() {
        HarnessLimits standard = HarnessLimits.standard();

        assertThat(standard.compactionTokens()).isPositive();
        assertThat(standard.keepRecentAnswers()).isNotNegative();
        assertThat(standard.maxCallsPerDeliberation()).isPositive();
        assertThat(standard.maxSilentReplies()).isPositive();
    }

    /**
     * A run allowed no calls in a deliberation can never act, and one allowed no silent replies is
     * stopped by the nudge that was meant to correct it. Both are limits that make the loop
     * impossible rather than bounded.
     */
    @Test
    public void aLimitThatWouldStopTheRunDoingAnythingIsRefused() {
        assertThatThrownBy(() -> new HarnessLimits(0L, 12, 60, 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HarnessLimits(500L, -1, 60, 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HarnessLimits(500L, 12, 0, 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HarnessLimits(500L, 12, 60, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void changingOneLimitLeavesTheRestAsTheyWere() {
        HarnessLimits standard = HarnessLimits.standard();

        HarnessLimits quieter = standard.withMaxSilentReplies(1);

        assertThat(quieter.maxSilentReplies()).isEqualTo(1);
        assertThat(standard.maxSilentReplies()).isEqualTo(HarnessLimits.standard().maxSilentReplies());
        assertThat(quieter.compactionTokens()).isEqualTo(standard.compactionTokens());
        assertThat(quieter.keepRecentAnswers()).isEqualTo(standard.keepRecentAnswers());
        assertThat(quieter.maxCallsPerDeliberation()).isEqualTo(standard.maxCallsPerDeliberation());
    }
}
