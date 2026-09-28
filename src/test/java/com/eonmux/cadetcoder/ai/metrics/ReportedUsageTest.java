package com.eonmux.cadetcoder.ai.metrics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** The hand-off must not let one request read another's figures. */
class ReportedUsageTest {

    @AfterEach
    void tearDown() {
        ReportedUsage.clear();
    }

    @Test
    @DisplayName("Taking consumes, so the next request cannot re-read the last one's usage")
    void takeConsumes() {
        ReportedUsage.report(new TokenUsage(10, 20, 30));

        assertThat(ReportedUsage.take()).contains(new TokenUsage(10, 20, 30));
        assertThat(ReportedUsage.take()).isEmpty();
    }

    /**
     * <b>Why this test changed</b>: it used to assert that reporting nothing erased what had already
     * been reported. That is what lost the figures for every attempt that was billed and then
     * rejected -- a provider reporting no usage on the second attempt is saying nothing about the
     * first, and it was being read as "the first cost nothing".
     */
    @Test
    @DisplayName("An attempt that reports nothing leaves an earlier attempt's figures alone")
    void reportingNullKeepsWhatWasAlreadyReported() {
        ReportedUsage.report(new TokenUsage(1, 2, 3));
        ReportedUsage.report(null);

        assertThat(ReportedUsage.take()).contains(new TokenUsage(1, 2, 3));
    }

    @Test
    @DisplayName("Two attempts at one request cost what both of them cost")
    void attemptsOfOneRequestAddUp() {
        ReportedUsage.report(new TokenUsage(100, 10, 5));
        ReportedUsage.report(new TokenUsage(100, 20, 300));

        assertThat(ReportedUsage.take()).contains(new TokenUsage(200, 30, 305));
    }

    @Test
    @DisplayName("Clearing starts a new request, so nothing carries across one")
    void clearingStartsAfreshRequest() {
        ReportedUsage.report(new TokenUsage(1, 2, 3));
        ReportedUsage.clear();
        ReportedUsage.report(new TokenUsage(4, 5, 6));

        assertThat(ReportedUsage.take()).contains(new TokenUsage(4, 5, 6));
    }

    @Test
    @DisplayName("One thread's usage is never visible to another")
    void slotsAreIsolatedPerThread() throws Exception {
        ReportedUsage.report(new TokenUsage(111, 222, 333));

        BlockingQueue<Optional<TokenUsage>> seen = new ArrayBlockingQueue<>(1);
        Thread other = new Thread(() -> seen.add(ReportedUsage.take()));
        other.start();
        other.join(5_000);

        assertThat(seen.take()).isEmpty();
        // ...and the other thread's take() did not consume this one's.
        assertThat(ReportedUsage.take()).contains(new TokenUsage(111, 222, 333));
    }

    @Test
    @DisplayName("Negative figures are clamped rather than propagated")
    void negativesAreClamped() {
        TokenUsage usage = new TokenUsage(-5, -1, -2);

        assertThat(usage.cachedInputTokens()).isZero();
        assertThat(usage.newInputTokens()).isZero();
        assertThat(usage.outputTokens()).isZero();
    }
}
