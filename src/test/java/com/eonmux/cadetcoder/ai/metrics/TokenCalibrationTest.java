package com.eonmux.cadetcoder.ai.metrics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A model's prompts are measured with its own tokenizer's ratio once its provider has counted one.
 *
 * <p>The fixed four characters per token undercounts a tokenizer that splits code and logs finer. A
 * Qwen model served by vLLM counted 393,217 tokens in a prompt the estimate put near 300,000, so
 * the compaction trigger was never reached and the provider refused the request.</p>
 */
class TokenCalibrationTest {

    private static final String MODEL = "local/qwen";

    @AfterEach
    void tearDown() {
        TokenCalibration.clear();
    }

    @Test
    @DisplayName("A model nobody has counted for is measured at four characters per token")
    void anUncountedModelUsesTheStandardRatio() {
        assertThat(TokenCalibration.charsPerToken(MODEL)).isEqualTo(4.0);
        assertThat(TokenCalibration.estimate(MODEL, "x".repeat(4_000))).isEqualTo(1_000);
    }

    @Test
    @DisplayName("A provider's count sets the ratio its model's prompts are measured with")
    void aReportedCountSetsTheRatio() {
        TokenCalibration.observe(MODEL, 30_000, 10_000);

        assertThat(TokenCalibration.charsPerToken(MODEL)).isEqualTo(3.0);
        assertThat(TokenCalibration.estimate(MODEL, "x".repeat(3_000))).isEqualTo(1_000);
    }

    @Test
    @DisplayName("The ratio belongs to the model it was counted for")
    void theRatioBelongsToOneModel() {
        TokenCalibration.observe(MODEL, 30_000, 10_000);

        assertThat(TokenCalibration.charsPerToken("other/model")).isEqualTo(4.0);
    }

    /** The standard ratio stays the most a token covers, so no prompt is undercounted. */
    @Test
    @DisplayName("A tokenizer coarser than four characters per token does not raise the ratio")
    void theRatioNeverExceedsTheStandardOne() {
        TokenCalibration.observe(MODEL, 50_000, 10_000);

        assertThat(TokenCalibration.charsPerToken(MODEL)).isEqualTo(4.0);
    }

    /** A short request is mostly the fixed overhead of the chat template, not text. */
    @Test
    @DisplayName("A short prompt is too small a sample to learn from")
    void aShortPromptIsNotASample() {
        TokenCalibration.observe(MODEL, TokenCalibration.SMALLEST_SAMPLE_CHARS - 1, 2_000);

        assertThat(TokenCalibration.charsPerToken(MODEL)).isEqualTo(4.0);
    }

    @Test
    @DisplayName("The latest count replaces an earlier one")
    void theLatestCountWins() {
        TokenCalibration.observe(MODEL, 30_000, 10_000);
        TokenCalibration.observe(MODEL, 35_000, 10_000);

        assertThat(TokenCalibration.charsPerToken(MODEL)).isEqualTo(3.5);
    }

    /**
     * A refusal says the prompt had at least so many tokens. That bound can only lower the ratio: a
     * ratio counted exactly on an earlier prompt is not raised by a bound on a later one.
     */
    @Test
    @DisplayName("A lower bound on the tokens only ever lowers the ratio")
    void aLowerBoundOnlyLowersTheRatio() {
        TokenCalibration.observe(MODEL, 30_000, 10_000);

        TokenCalibration.observeAtLeast(MODEL, 35_000, 10_000);
        assertThat(TokenCalibration.charsPerToken(MODEL)).isEqualTo(3.0);

        TokenCalibration.observeAtLeast(MODEL, 25_000, 10_000);
        assertThat(TokenCalibration.charsPerToken(MODEL)).isEqualTo(2.5);
    }

    @Test
    @DisplayName("Nothing and blank text measure as nothing")
    void blankTextIsFree() {
        TokenCalibration.observe(MODEL, 30_000, 10_000);

        assertThat(TokenCalibration.estimate(MODEL, null)).isZero();
        assertThat(TokenCalibration.estimate(MODEL, "   ")).isZero();
        assertThat(TokenCalibration.estimate(MODEL, "a")).isEqualTo(1);
    }

    @Test
    @DisplayName("No model means the standard ratio, never a failure")
    void noModelIsTheStandardRatio() {
        TokenCalibration.observe(null, 30_000, 10_000);

        assertThat(TokenCalibration.charsPerToken(null)).isEqualTo(4.0);
    }
}
