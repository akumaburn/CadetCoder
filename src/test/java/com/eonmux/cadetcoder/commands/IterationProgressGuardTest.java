package com.eonmux.cadetcoder.commands;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Detecting an iterative run that has stopped moving, and the nudge that tries to restart it. */
public class IterationProgressGuardTest {

    /** A guard whose uniqueness token is predictable, so the nudge can be asserted on. */
    private static IterationProgressGuard guardWithCountingTokens() {
        AtomicInteger next = new AtomicInteger();
        return new IterationProgressGuard(() -> "token-" + next.incrementAndGet());
    }

    @Test
    public void aRunThatKeepsSayingSomethingNewIsLeftAlone() {
        IterationProgressGuard guard = guardWithCountingTokens();

        guard.record("first");
        guard.record("second");
        guard.record("third");

        assertThat(guard.consecutiveRepeats()).isZero();
        assertThat(guard.perturbation()).isEmpty();
        assertThat(guard.isStuck()).isFalse();
    }

    @Test
    public void theSameAnswerTwiceAsksForTheNextRequestToBeMadeDistinguishable() {
        IterationProgressGuard guard = guardWithCountingTokens();

        guard.record("I will look at the code and get back to you.");
        guard.record("I will look at the code and get back to you.");

        assertThat(guard.consecutiveRepeats()).isEqualTo(1);
        assertThat(guard.perturbation()).isNotEmpty();
        // Not yet stuck: identical bytes in can legitimately mean identical bytes out, so the first
        // response to a repeat is to change the request, not to give up on the run.
        assertThat(guard.isStuck()).isFalse();
    }

    @Test
    public void theNudgeSaysWhatIsWrongAndWhatToDoInsteadOfBeingAnOpaqueToken() {
        IterationProgressGuard guard = guardWithCountingTokens();
        guard.record("same");
        guard.record("same");

        String nudge = guard.perturbation();

        assertThat(nudge).contains("repeated the one before it");
        assertThat(nudge).contains("different, concrete step");
        assertThat(nudge).contains("token-1");
    }

    @Test
    public void everyNudgeCarriesADifferentToken() {
        IterationProgressGuard guard = guardWithCountingTokens();
        guard.record("same");
        guard.record("same");

        // The whole point: a provider that answered identical bytes with identical bytes cannot do
        // so again, because the bytes are no longer identical.
        assertThat(guard.perturbation()).isNotEqualTo(guard.perturbation());
    }

    @Test
    public void theRunStopsOnceNudgingHasNotHelped() {
        IterationProgressGuard guard = guardWithCountingTokens();

        guard.record("same");
        for (int i = 0; i < IterationProgressGuard.REPEATS_BEFORE_STOP - 1; i++) {
            guard.record("same");
            assertThat(guard.isStuck()).isFalse();
        }
        guard.record("same");

        assertThat(guard.isStuck()).isTrue();
        assertThat(guard.stopReason()).contains("same response").contains("did not advance");
    }

    @Test
    public void oneNewAnswerClearsTheCount() {
        IterationProgressGuard guard = guardWithCountingTokens();
        guard.record("same");
        guard.record("same");
        guard.record("same");
        assertThat(guard.consecutiveRepeats()).isEqualTo(2);

        guard.record("finally something else");

        assertThat(guard.consecutiveRepeats()).isZero();
        assertThat(guard.perturbation()).isEmpty();
    }

    @Test
    public void rewrappingTheSameAnswerIsStillTheSameAnswer() {
        IterationProgressGuard guard = guardWithCountingTokens();

        guard.record("I will look at\nthe code.");
        guard.record("I will look at   the code.");

        assertThat(guard.consecutiveRepeats()).isEqualTo(1);
    }

    @Test
    public void alternatingBetweenTwoAnswersIsTheSameStandstillSpreadOut() {
        IterationProgressGuard guard = guardWithCountingTokens();

        guard.record("A");
        guard.record("B");
        guard.record("A");
        guard.record("B");

        assertThat(guard.consecutiveRepeats()).isGreaterThan(0);
    }

    @Test
    public void aThreeAnswerCycleIsCaughtToo() {
        IterationProgressGuard guard = guardWithCountingTokens();

        for (String answer : new String[] {"A", "B", "C", "A", "B", "C"}) {
            guard.record(answer);
        }

        assertThat(guard.consecutiveRepeats()).isGreaterThan(0);
    }

    @Test
    public void anEmptyAnswerRepeatsLikeAnyOther() {
        IterationProgressGuard guard = guardWithCountingTokens();

        guard.record(null);
        guard.record("");
        guard.record("   ");

        assertThat(guard.consecutiveRepeats()).isEqualTo(2);
    }

    @Test
    public void twoEnormousAnswersThatAgreeForTheComparedLengthAreTreatedAsEqual() {
        IterationProgressGuard guard = guardWithCountingTokens();
        String head = "x".repeat(IterationProgressGuard.FINGERPRINT_LIMIT + 100);

        guard.record(head + "ending one");
        guard.record(head + "a completely different ending");

        // Near-identical is still a standstill, and comparing a bounded prefix keeps one huge
        // response from pinning a multiple of itself in memory.
        assertThat(guard.consecutiveRepeats()).isEqualTo(1);
    }

    @Test
    public void historyIsBoundedSoALongRunDoesNotGrowWithoutLimit() {
        IterationProgressGuard guard = guardWithCountingTokens();

        for (int i = 0; i < IterationProgressGuard.HISTORY_LIMIT * 3; i++) {
            guard.record("answer " + i);
        }

        assertThat(guard.observedCount()).isEqualTo(IterationProgressGuard.HISTORY_LIMIT);
    }

    @Test
    public void resettingForgetsEverything() {
        IterationProgressGuard guard = guardWithCountingTokens();
        guard.record("same");
        guard.record("same");

        guard.reset();

        assertThat(guard.observedCount()).isZero();
        assertThat(guard.consecutiveRepeats()).isZero();
        assertThat(guard.perturbation()).isEmpty();
    }
}
