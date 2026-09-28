package com.eonmux.cadetcoder.ai.metrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The session figures have to describe the whole session, workers included.
 *
 * <p>During a worker run the shell issues no completions of its own — every request belongs to a
 * worker thread. Figures that only counted the calling thread would therefore read as an idle
 * session at exactly the moment it was busiest.</p>
 */
class AggregateMetricsTest {

    /** A recorder with a controllable clock, so durations and windows are exact. */
    private static RequestMetricsRecorder recorderAt(long[] now) {
        return new RequestMetricsRecorder(new TokenRateMeter(5_000L, () -> now[0]), () -> now[0]);
    }

    @Test
    @DisplayName("Totals include requests made on other threads")
    void totalsAggregateAcrossThreads() throws Exception {
        long[] now = {1_000};
        RequestMetricsRecorder recorder = recorderAt(now);

        int workers = 4;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch go    = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(workers);

        for (int i = 0; i < workers; i++) {
            new Thread(() -> {
                RequestMetricsRecorder.InFlight handle = recorder.begin("system", "user");
                ready.countDown();
                try {
                    go.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                recorder.completeWithUsage(handle, 100, 50, 25);
                done.countDown();
            }, "metrics-worker-" + i).start();
        }

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();

        String summary = recorder.sessionSummary();
        assertThat(recorder.requestCount()).isEqualTo(workers);
        assertThat(summary).contains("4 requests");
        assertThat(summary).contains("600 in");   // 4 x (100 cached + 50 new)
        assertThat(summary).contains("100 out");  // 4 x 25
    }

    @Test
    @DisplayName("Several requests in flight at once are all reported, not just the last to start")
    void inFlightCoversEveryOutstandingRequest() {
        long[] now = {1_000};
        RequestMetricsRecorder recorder = recorderAt(now);

        RequestMetricsRecorder.InFlight first = recorder.begin("system", "aaaa");
        now[0] = 3_000;
        RequestMetricsRecorder.InFlight second = recorder.begin("system", "bbbb");

        RequestMetricsRecorder.InFlightStatus status = recorder.inFlightStatus().orElseThrow();

        assertThat(status.concurrentRequests()).isEqualTo(2);
        // Elapsed is the OLDEST request's, so the line does not reset each time one more starts.
        assertThat(status.elapsedMillis()).isEqualTo(2_000);
        assertThat(status.inputTokens()).isGreaterThan(0);
        assertThat(status.render()).contains("×2");

        // One finishing must not clear the counter while the other is still running.
        recorder.completeWithUsage(second, 1, 1, 1);
        assertThat(recorder.inFlightStatus()).isPresent();
        assertThat(recorder.inFlightStatus().orElseThrow().concurrentRequests()).isEqualTo(1);

        recorder.completeWithUsage(first, 1, 1, 1);
        assertThat(recorder.inFlightStatus()).isEmpty();
    }

    @Test
    @DisplayName("An abandoned request stops counting as in flight")
    void abandonReleasesItsSlot() {
        long[] now = {0};
        RequestMetricsRecorder recorder = recorderAt(now);

        RequestMetricsRecorder.InFlight a = recorder.begin("s", "a");
        RequestMetricsRecorder.InFlight b = recorder.begin("s", "b");
        recorder.abandon(a);

        assertThat(recorder.inFlightStatus().orElseThrow().concurrentRequests()).isEqualTo(1);
        recorder.abandon(b);
        assertThat(recorder.inFlightStatus()).isEmpty();
    }

    @Test
    @DisplayName("The cache split is measured per thread, not against another worker's prompt")
    void cacheSplitIsPerConversation() throws Exception {
        long[] now = {0};
        RequestMetricsRecorder recorder = recorderAt(now);

        // Thread A establishes a baseline and then sends the same prompt again: fully cacheable.
        recorder.complete(recorder.begin("SYSTEM-A", "the quick brown fox jumps"), "ok");

        List<RequestMetrics> seen = new CopyOnWriteArrayList<>();
        Thread other = new Thread(() -> {
            // A different conversation entirely. Sharing one baseline made this look like a near
            // total cache hit against thread A's prompt, which no provider would ever serve.
            seen.add(recorder.complete(recorder.begin("SYSTEM-B", "completely unrelated text"), "ok"));
        });
        other.start();
        other.join(5_000);

        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).cachedInputTokens())
                .as("a fresh thread has no prefix to reuse")
                .isZero();

        RequestMetrics repeat =
                recorder.complete(recorder.begin("SYSTEM-A", "the quick brown fox jumps"), "ok");
        assertThat(repeat.cachedInputTokens())
                .as("the same thread resending the same prompt is fully cacheable")
                .isGreaterThan(0);
    }

    @Test
    @DisplayName("Input and output rates are reported separately and both aggregate")
    void bothRatesAreTracked() {
        long[] now = {0};
        RequestMetricsRecorder recorder = recorderAt(now);

        recorder.completeWithUsage(recorder.begin("s", "u"), 400, 100, 50);

        // 500 input and 50 output inside a five-second window.
        assertThat(recorder.windowInputTokensPerSecond()).isEqualTo(100.0);
        assertThat(recorder.windowTokensPerSecond()).isEqualTo(10.0);

        now[0] = 60_000; // window has fully passed
        assertThat(recorder.windowInputTokensPerSecond()).isZero();
        assertThat(recorder.windowTokensPerSecond()).isZero();
    }

    @Test
    @DisplayName("A request line reports prompt-processing and generation speed separately")
    void perRequestLineCarriesBothSpeeds() {
        RequestMetrics metrics = new RequestMetrics(900, 100, 200, 2_000, false);

        assertThat(metrics.inputTokensPerSecond()).isEqualTo(500.0);
        assertThat(metrics.tokensPerSecond()).isEqualTo(100.0);

        String line = metrics.toSummaryLine(-1, 0);
        assertThat(line).contains("500 in/s");
        assertThat(line).contains("100 out/s");
    }
}
