package com.eonmux.cadetcoder.ai.metrics;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** The per-request figures, the trailing-window rate, and how both are rendered. */
public class RequestMetricsTest {

    // ------------------------------------------------------------------ estimator

    @Test
    public void tokenEstimatesScaleWithLengthAndIgnoreBlankInput() {
        assertThat(TokenEstimator.estimate(null)).isZero();
        assertThat(TokenEstimator.estimate("")).isZero();
        assertThat(TokenEstimator.estimate("   ")).isZero();
        assertThat(TokenEstimator.estimate("a")).isEqualTo(1);
        assertThat(TokenEstimator.estimate("a".repeat(400))).isEqualTo(100);
    }

    @Test
    public void commonPrefixLengthIsHowTheCacheableShareIsMeasured() {
        assertThat(TokenEstimator.commonPrefixLength("abcdef", "abcXYZ")).isEqualTo(3);
        assertThat(TokenEstimator.commonPrefixLength("abc", "abcdef")).isEqualTo(3);
        assertThat(TokenEstimator.commonPrefixLength("xyz", "abc")).isZero();
        assertThat(TokenEstimator.commonPrefixLength(null, "abc")).isZero();
        assertThat(TokenEstimator.commonPrefixLength("abc", null)).isZero();
    }

    // ------------------------------------------------------------------ the rate meter

    @Test
    public void theRateIsTokensInTheWindowDividedByTheWindow() {
        AtomicLong     now   = new AtomicLong(0);
        TokenRateMeter meter = new TokenRateMeter(5_000, now::get);

        meter.record(500);

        assertThat(meter.tokensInWindow()).isEqualTo(500);
        assertThat(meter.tokensPerSecond()).isEqualTo(100.0);
    }

    @Test
    public void samplesLeaveTheWindowAsTimePasses() {
        AtomicLong     now   = new AtomicLong(0);
        TokenRateMeter meter = new TokenRateMeter(5_000, now::get);

        meter.record(500);
        now.set(4_999);
        assertThat(meter.tokensPerSecond()).as("still inside the window").isEqualTo(100.0);

        now.set(5_000);
        assertThat(meter.tokensPerSecond()).as("exactly at the edge, now expired").isZero();
        assertThat(meter.tokensInWindow()).isZero();
    }

    @Test
    public void concurrentSamplesInsideTheWindowAccumulate() {
        AtomicLong     now   = new AtomicLong(0);
        TokenRateMeter meter = new TokenRateMeter(5_000, now::get);

        meter.record(100);
        now.set(1_000);
        meter.record(150);
        now.set(2_000);
        meter.record(250);

        assertThat(meter.tokensInWindow()).isEqualTo(500);
        assertThat(meter.tokensPerSecond()).isEqualTo(100.0);
    }

    @Test
    public void nonPositiveSamplesAreIgnoredAndResetClearsTheWindow() {
        AtomicLong     now   = new AtomicLong(0);
        TokenRateMeter meter = new TokenRateMeter(5_000, now::get);

        meter.record(0);
        meter.record(-5);
        assertThat(meter.tokensPerSecond()).isZero();

        meter.record(50);
        meter.reset();
        assertThat(meter.tokensPerSecond()).isZero();
    }

    // ------------------------------------------------------------------ per-request figures

    @Test
    public void cacheHitRatioAndPerRequestRateAreDerivedFromTheCounts() {
        RequestMetrics metrics = new RequestMetrics(900, 100, 200, 2_000, true);

        assertThat(metrics.inputTokens()).isEqualTo(1000);
        assertThat(metrics.cacheHitRatio()).isEqualTo(0.9);
        assertThat(metrics.tokensPerSecond()).isEqualTo(100.0);
    }

    @Test
    public void aRequestWithNoInputOrOutputReportsZeroRatherThanDividingByZero() {
        RequestMetrics metrics = new RequestMetrics(0, 0, 0, 0, true);

        assertThat(metrics.cacheHitRatio()).isZero();
        assertThat(metrics.tokensPerSecond()).isZero();
    }

    @Test
    public void estimatedFiguresAreMarkedSoTheyAreNotMistakenForBilledUsage() {
        assertThat(new RequestMetrics(900, 100, 200, 2_000, true).toSummaryLine(-1, 0))
                .startsWith("~");
        assertThat(new RequestMetrics(900, 100, 200, 2_000, false).toSummaryLine(-1, 0))
                .doesNotStartWith("~");
    }

    @Test
    public void theSummaryLineCarriesEveryFigureThatWasAskedFor() {
        String line = new RequestMetrics(900, 100, 200, 2_000, true).toSummaryLine(47.0, 5);

        assertThat(line)
                .contains("1,000 in")
                .contains("900 cached")
                .contains("100 new")
                .contains("200 out")
                .contains("2.0s")
                // The rate is named for the side it measures. With "in" and "out" labelled
                // everywhere else, an unqualified "tok/s" was the last figure on the line that
                // silently belonged to one of them.
                .contains("100 out/s")
                // The windowed rate is nested inside the per-request rate: two bare "N tok/s" terms
                // side by side read as a duplicated figure rather than two different measurements.
                .contains("(47 over 5s)");
    }

    @Test
    public void durationsAreFormattedByMagnitude() {
        assertThat(RequestMetrics.formatDuration(840)).isEqualTo("840ms");
        assertThat(RequestMetrics.formatDuration(4_200)).isEqualTo("4.2s");
        assertThat(RequestMetrics.formatDuration(65_000)).isEqualTo("1m 05s");
    }

    // ------------------------------------------------------------------ the recorder

    @Test
    public void aRepeatedPrefixIsReportedAsCachedAndOnlyTheTailAsNew() {
        AtomicLong             now      = new AtomicLong(0);
        RequestMetricsRecorder recorder = new RequestMetricsRecorder(
                new TokenRateMeter(5_000, now::get), now::get);

        String system = "S".repeat(400);
        String turnOne = "A".repeat(400);

        RequestMetricsRecorder.InFlight first = recorder.begin(system, turnOne);
        now.set(1_000);
        RequestMetrics firstMetrics = recorder.complete(first, "out");

        assertThat(firstMetrics.cachedInputTokens())
                .as("the very first request shares nothing with anything")
                .isZero();

        // Second turn EXTENDS the first: same system prompt, same first turn, plus new text.
        RequestMetricsRecorder.InFlight second = recorder.begin(system, turnOne + "B".repeat(400));
        now.set(2_000);
        RequestMetrics secondMetrics = recorder.complete(second, "out");

        assertThat(secondMetrics.cachedInputTokens())
                .as("everything sent last time is a reusable prefix")
                .isGreaterThan(150);
        assertThat(secondMetrics.newInputTokens())
                .as("only the appended tail is new")
                .isBetween(90, 110);
    }

    @Test
    public void aChangedPrefixIsReportedAsAlmostEntirelyNew() {
        AtomicLong             now      = new AtomicLong(0);
        RequestMetricsRecorder recorder = new RequestMetricsRecorder(
                new TokenRateMeter(5_000, now::get), now::get);

        recorder.complete(recorder.begin("S", "A".repeat(400)), "out");

        // This is what a sliding window did on every turn: the prompt starts differently.
        RequestMetrics metrics = recorder.complete(recorder.begin("S", "Z".repeat(400)), "out");

        assertThat(metrics.cacheHitRatio())
                .as("a prompt whose opening bytes changed cannot be served from cache")
                .isLessThan(0.05);
    }

    @Test
    public void durationIsMeasuredFromBeginToComplete() {
        AtomicLong             now      = new AtomicLong(0);
        RequestMetricsRecorder recorder = new RequestMetricsRecorder(
                new TokenRateMeter(5_000, now::get), now::get);

        RequestMetricsRecorder.InFlight inFlight = recorder.begin("S", "U");
        now.set(3_500);

        assertThat(recorder.complete(inFlight, "out").durationMillis()).isEqualTo(3_500);
    }

    @Test
    public void theInFlightTimerRunsOnlyWhileARequestIsOutstanding() {
        AtomicLong             now      = new AtomicLong(0);
        RequestMetricsRecorder recorder = new RequestMetricsRecorder(
                new TokenRateMeter(5_000, now::get), now::get);

        assertThat(recorder.inFlightElapsedMillis()).isEmpty();

        RequestMetricsRecorder.InFlight inFlight = recorder.begin("S", "U");
        now.set(1_200);
        assertThat(recorder.inFlightElapsedMillis()).hasValue(1_200);

        recorder.complete(inFlight, "out");
        assertThat(recorder.inFlightElapsedMillis())
                .as("a completed request must stop the counter")
                .isEmpty();
    }

    @Test
    public void theLiveCounterCarriesTheTokenSplitAlongsideTheElapsedTime() {
        AtomicLong             now      = new AtomicLong(0);
        RequestMetricsRecorder recorder = new RequestMetricsRecorder(
                new TokenRateMeter(5_000, now::get), now::get);

        String system = "S".repeat(4000);
        recorder.complete(recorder.begin(system, "A".repeat(400)), "out");

        recorder.begin(system, "A".repeat(400) + "B".repeat(400));
        now.set(4_200);

        var status = recorder.inFlightStatus();
        assertThat(status).isPresent();
        assertThat(status.get().elapsedMillis()).isEqualTo(4_200);
        assertThat(status.get().inputTokens()).isGreaterThan(1_000);
        assertThat(status.get().cachedInputTokens()).isGreaterThan(0);

        String rendered = status.get().render();
        assertThat(rendered)
                .as("the timer and the token stats appear together")
                .contains("4.2s")
                .contains(" in")
                .contains("% cached)");
    }

    @Test
    public void theLiveCounterDisappearsOnceTheRequestFinishes() {
        AtomicLong             now      = new AtomicLong(0);
        RequestMetricsRecorder recorder = new RequestMetricsRecorder(
                new TokenRateMeter(5_000, now::get), now::get);

        RequestMetricsRecorder.InFlight inFlight = recorder.begin("S", "U");
        assertThat(recorder.inFlightStatus()).isPresent();

        recorder.complete(inFlight, "out");
        assertThat(recorder.inFlightStatus()).isEmpty();
    }

    @Test
    public void aFailedRequestStopsTheInFlightTimerToo() {
        AtomicLong             now      = new AtomicLong(0);
        RequestMetricsRecorder recorder = new RequestMetricsRecorder(
                new TokenRateMeter(5_000, now::get), now::get);

        RequestMetricsRecorder.InFlight inFlight = recorder.begin("S", "U");
        recorder.abandon(inFlight);

        assertThat(recorder.inFlightElapsedMillis()).isEmpty();
    }

    @Test
    public void startingANewRunForgetsThePreviousRunsPrefix() {
        AtomicLong             now      = new AtomicLong(0);
        RequestMetricsRecorder recorder = new RequestMetricsRecorder(
                new TokenRateMeter(5_000, now::get), now::get);

        String prompt = "A".repeat(400);
        recorder.complete(recorder.begin("S", prompt), "out");

        recorder.resetConversation();
        RequestMetrics metrics = recorder.complete(recorder.begin("S", prompt), "out");

        assertThat(metrics.cachedInputTokens())
                .as("the provider will not have a cache entry from a previous run")
                .isZero();
    }

    @Test
    public void providerReportedUsageIsNotMarkedAsEstimated() {
        AtomicLong             now      = new AtomicLong(0);
        RequestMetricsRecorder recorder = new RequestMetricsRecorder(
                new TokenRateMeter(5_000, now::get), now::get);

        RequestMetrics metrics =
                recorder.completeWithUsage(recorder.begin("S", "U"), 800, 200, 300);

        assertThat(metrics.isEstimated()).isFalse();
        assertThat(metrics.cachedInputTokens()).isEqualTo(800);
        assertThat(metrics.newInputTokens()).isEqualTo(200);
        assertThat(metrics.outputTokens()).isEqualTo(300);
    }

    @Test
    public void completedRequestsFeedTheWindowRate() {
        AtomicLong             now      = new AtomicLong(0);
        TokenRateMeter         meter    = new TokenRateMeter(5_000, now::get);
        RequestMetricsRecorder recorder = new RequestMetricsRecorder(meter, now::get);

        recorder.complete(recorder.begin("S", "U"), "o".repeat(2_000));

        assertThat(recorder.windowTokensPerSecond()).isGreaterThan(0.0);
        assertThat(recorder.windowSeconds()).isEqualTo(5);
    }

    @Test
    public void sessionTotalsAccumulateAndReportNothingBeforeTheFirstRequest() {
        AtomicLong             now      = new AtomicLong(0);
        RequestMetricsRecorder recorder = new RequestMetricsRecorder(
                new TokenRateMeter(5_000, now::get), now::get);

        assertThat(recorder.sessionSummary()).isNull();

        recorder.complete(recorder.begin("S", "U"), "out");
        now.set(1_000);
        recorder.complete(recorder.begin("S", "UU"), "out");

        assertThat(recorder.requestCount()).isEqualTo(2);
        assertThat(recorder.sessionSummary()).contains("2 requests");

        recorder.resetAll();
        assertThat(recorder.sessionSummary()).isNull();
    }

    @Test
    public void inputAndOutputAreNamedPeersOnEveryLine() {
        String line = new RequestMetrics(900, 100, 200, 2_000, true).toSummaryLine(47.0, 5);

        // The user's complaint: the two sides were not separately legible. Input carries its
        // cached/new split, output stands on its own, and the rate says which side it counts.
        assertThat(line).contains("in (").contains("out").contains("out/s");
        assertThat(line.indexOf(" in ")).isLessThan(line.indexOf(" out"));
    }

    @Test
    public void compactCountsKeepTheLiveLineNarrow() {
        assertThat(RequestMetrics.formatCompact(812)).isEqualTo("812");
        assertThat(RequestMetrics.formatCompact(5_554)).isEqualTo("5.6k");
        assertThat(RequestMetrics.formatCompact(124_000)).isEqualTo("124k");
        assertThat(RequestMetrics.formatCompact(1_200_000)).isEqualTo("1.2m");
    }
}
