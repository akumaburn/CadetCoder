package com.eonmux.cadetcoder.net;

import org.junit.After;
import org.junit.Test;

import java.time.Duration;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Decision matrix for the backend retry policy: which statuses may be repeated, how many times,
 * and how long the caller waits in between.
 */
public class RetryPolicyTest {

    @After
    public void clearProperties() {
        System.clearProperty(RetryPolicy.MAX_ATTEMPTS_PROPERTY);
        System.clearProperty(RetryPolicy.BASE_DELAY_MS_PROPERTY);
        System.clearProperty(RetryPolicy.MAX_DELAY_MS_PROPERTY);
    }

    // ---------- which statuses are retryable ----------

    @Test
    public void isRetryableStatus_retriesTimeoutRateLimitAndServerErrors() {
        assertThat(RetryPolicy.isRetryableStatus(408)).isTrue();
        assertThat(RetryPolicy.isRetryableStatus(429)).isTrue();
        assertThat(RetryPolicy.isRetryableStatus(500)).isTrue();
        assertThat(RetryPolicy.isRetryableStatus(502)).isTrue();
        assertThat(RetryPolicy.isRetryableStatus(503)).isTrue();
        assertThat(RetryPolicy.isRetryableStatus(599)).isTrue();
    }

    @Test
    public void isRetryableStatus_neverRetriesBadRequestOrAuthFailures() {
        // Repeating these can only fail the same way; retrying just delays the real error.
        assertThat(RetryPolicy.isRetryableStatus(400)).isFalse();
        assertThat(RetryPolicy.isRetryableStatus(401)).isFalse();
        assertThat(RetryPolicy.isRetryableStatus(403)).isFalse();
        assertThat(RetryPolicy.isRetryableStatus(404)).isFalse();
        assertThat(RetryPolicy.isRetryableStatus(422)).isFalse();
        assertThat(RetryPolicy.isRetryableStatus(200)).isFalse();
    }

    // ---------- shouldRetry ----------

    @Test
    public void shouldRetry_stopsAtTheAttemptCap() {
        RetryPolicy  policy  = new RetryPolicy(3, 1L, 10L, new Random(7));
        LLMException failure = new LLMServerException("openai", "gpt-4", "http://x", 503, null);

        assertThat(policy.shouldRetry(failure, 1)).isTrue();
        assertThat(policy.shouldRetry(failure, 2)).isTrue();
        assertThat(policy.shouldRetry(failure, 3)).isFalse();
        assertThat(policy.shouldRetry(failure, 4)).isFalse();
    }

    @Test
    public void shouldRetry_refusesNonRetryableKinds() {
        RetryPolicy policy = new RetryPolicy(3, 1L, 10L, new Random(7));

        assertThat(policy.shouldRetry(new LLMAuthException("openai", "m", "http://x", 401, null), 1)).isFalse();
        assertThat(policy.shouldRetry(new LLMAuthException("openai", "m", "http://x", 403, null), 1)).isFalse();
        assertThat(policy.shouldRetry(new LLMBadRequestException("openai", "m", "http://x", 400, null), 1))
                .isFalse();
        assertThat(policy.shouldRetry(new LLMProtocolException("openai", "m", "http://x", "no choices"), 1))
                .isFalse();
    }

    @Test
    public void shouldRetry_acceptsRateLimitTransportAndServerFailures() {
        RetryPolicy policy = new RetryPolicy(3, 1L, 10L, new Random(7));

        assertThat(policy.shouldRetry(
                new LLMRateLimitException("openai", "m", "http://x", 429, null, null), 1)).isTrue();
        assertThat(policy.shouldRetry(
                new LLMTransportException("openai", "m", "http://x", new java.io.IOException("refused")), 1))
                .isTrue();
        assertThat(policy.shouldRetry(new LLMServerException("openai", "m", "http://x", 500, null), 1)).isTrue();
    }

    @Test
    public void shouldRetry_declinesWhenRetryAfterExceedsTheCap() {
        RetryPolicy policy = new RetryPolicy(3, 100L, 5_000L, new Random(7));
        LLMException longWait = new LLMRateLimitException(
                "openai", "m", "http://x", 429, null, Duration.ofSeconds(120));

        // Waiting two minutes inside a CLI invocation is worse than failing with "retry later".
        assertThat(policy.shouldRetry(longWait, 1)).isFalse();
    }

    @Test
    public void shouldRetry_handlesNullFailure() {
        assertThat(new RetryPolicy(3, 1L, 10L).shouldRetry(null, 1)).isFalse();
    }

    // ---------- backoff ----------

    @Test
    public void delayMillis_growsExponentiallyWithinJitterBounds() {
        RetryPolicy policy = new RetryPolicy(5, 400L, 20_000L, new Random(42));

        for (int i = 0; i < 50; i++) {
            assertThat(policy.delayMillis(1, null)).isBetween(200L, 400L);
            assertThat(policy.delayMillis(2, null)).isBetween(400L, 800L);
            assertThat(policy.delayMillis(3, null)).isBetween(800L, 1600L);
        }
    }

    @Test
    public void delayMillis_isJitteredRatherThanConstant() {
        RetryPolicy policy = new RetryPolicy(5, 1_000L, 20_000L, new Random(1));

        boolean sawDifferentDelays = false;
        long    first              = policy.delayMillis(2, null);
        for (int i = 0; i < 50 && !sawDifferentDelays; i++) {
            sawDifferentDelays = policy.delayMillis(2, null) != first;
        }
        assertThat(sawDifferentDelays).isTrue();
    }

    @Test
    public void delayMillis_isCappedByMaxDelay() {
        RetryPolicy policy = new RetryPolicy(10, 1_000L, 2_000L, new Random(3));

        assertThat(policy.delayMillis(9, null)).isBetween(1_000L, 2_000L);
    }

    @Test
    public void delayMillis_honoursRetryAfter() {
        RetryPolicy policy = new RetryPolicy(3, 500L, 20_000L, new Random(3));

        assertThat(policy.delayMillis(1, Duration.ofSeconds(7))).isEqualTo(7_000L);
    }

    @Test
    public void delayMillis_clampsRetryAfterToMaxDelay() {
        RetryPolicy policy = new RetryPolicy(3, 500L, 2_000L, new Random(3));

        assertThat(policy.delayMillis(1, Duration.ofSeconds(30))).isEqualTo(2_000L);
    }

    // ---------- configuration ----------

    @Test
    public void fromSystemProperties_usesDefaultsWhenUnset() {
        RetryPolicy policy = RetryPolicy.fromSystemProperties();

        assertThat(policy.getMaxAttempts()).isEqualTo(RetryPolicy.DEFAULT_MAX_ATTEMPTS);
        assertThat(policy.getBaseDelayMillis()).isEqualTo(RetryPolicy.DEFAULT_BASE_DELAY_MS);
        assertThat(policy.getMaxDelayMillis()).isEqualTo(RetryPolicy.DEFAULT_MAX_DELAY_MS);

        // Pinned to the literals as well: the assertions above compare each constant with itself and
        // would hold at any value, so on their own they do not defend the shipped defaults.
        assertThat(policy.getMaxAttempts()).as("attempts before a run gives up").isEqualTo(10);
        assertThat(policy.getBaseDelayMillis()).isEqualTo(500L);
        assertThat(policy.getMaxDelayMillis()).isEqualTo(20_000L);
    }

    @Test
    public void fromSystemProperties_honoursOverrides() {
        System.setProperty(RetryPolicy.MAX_ATTEMPTS_PROPERTY, "5");
        System.setProperty(RetryPolicy.BASE_DELAY_MS_PROPERTY, "25");
        System.setProperty(RetryPolicy.MAX_DELAY_MS_PROPERTY, "250");

        RetryPolicy policy = RetryPolicy.fromSystemProperties();

        assertThat(policy.getMaxAttempts()).isEqualTo(5);
        assertThat(policy.getBaseDelayMillis()).isEqualTo(25L);
        assertThat(policy.getMaxDelayMillis()).isEqualTo(250L);
    }

    @Test
    public void fromSystemProperties_ignoresUnparseableAndOutOfRangeValues() {
        System.setProperty(RetryPolicy.MAX_ATTEMPTS_PROPERTY, "not-a-number");
        System.setProperty(RetryPolicy.BASE_DELAY_MS_PROPERTY, "-5");

        RetryPolicy policy = RetryPolicy.fromSystemProperties();

        assertThat(policy.getMaxAttempts()).isEqualTo(RetryPolicy.DEFAULT_MAX_ATTEMPTS);
        assertThat(policy.getBaseDelayMillis()).isEqualTo(RetryPolicy.DEFAULT_BASE_DELAY_MS);
    }

    @Test
    public void constructor_rejectsNonsenseAttemptCounts() {
        assertThatThrownBy(() -> new RetryPolicy(0, 10L, 20L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxAttempts");
    }
}
