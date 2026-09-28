package com.eonmux.cadetcoder.ai.metrics;

/**
 * Estimates token counts from text.
 *
 * <h2>This is an estimate, and says so everywhere it is surfaced</h2>
 *
 * <p>CadetCoder talks to four different wire protocols across many models, each with its own
 * tokenizer, and it does not ship any of them. The approximation used here -- roughly four
 * characters per token, with a floor of one token for any non-blank text -- is deliberately the only
 * one in the codebase, so that two parts of a single request cannot disagree about how large it is,
 * and so every number derived from it can be labelled as approximate rather than presented as
 * fact.</p>
 *
 * <p>It is accurate enough for what it is used for: comparing the sizes of two prompts, showing
 * relative cache effectiveness, and computing a throughput rate. It must NOT be used for billing,
 * for hard context-window limits, or anywhere an off-by-20% error would be a correctness problem.
 * When a provider reports real usage, that always wins -- see
 * {@link RequestMetrics#isEstimated()}.</p>
 */
public final class TokenEstimator {

    /** Average characters per token for English text and source code. */
    static final double CHARS_PER_TOKEN = 4.0;

    /**
     * How many characters a token budget corresponds to.
     *
     * <p>The inverse of {@link #estimateFromLength(int)}, for callers sizing storage against a budget
     * expressed in tokens rather than measuring text they already have.</p>
     *
     * @param tokens a token budget
     * @return the equivalent number of characters, never negative
     */
    public static int charsFor(int tokens) {
        return tokens <= 0 ? 0 : (int) Math.round(tokens * CHARS_PER_TOKEN);
    }

    private TokenEstimator() {
    }

    /**
     * Estimates how many tokens {@code text} would occupy.
     *
     * @param text the text to measure (may be {@code null})
     * @return the estimated token count; {@code 0} for null/blank input, otherwise at least 1
     */
    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        if (text.trim().isEmpty()) {
            return 0;
        }
        return Math.max(1, (int) Math.round(text.length() / CHARS_PER_TOKEN));
    }

    /**
     * Estimates the tokens in a character span, used to size a portion of a larger prompt without
     * substringing it.
     *
     * @param characters the number of characters
     * @return the estimated token count, never negative
     */
    public static int estimateFromLength(int characters) {
        if (characters <= 0) {
            return 0;
        }
        return Math.max(1, (int) Math.round(characters / CHARS_PER_TOKEN));
    }

    /**
     * Returns the length of the longest common prefix of two strings.
     *
     * <p>This is how the cached portion of a request is determined: prompt caching matches on a
     * shared leading prefix, so the part of this request that is byte-identical to the previous one
     * is the part a provider can serve from cache.</p>
     *
     * @param first  the earlier text (may be {@code null})
     * @param second the later text (may be {@code null})
     * @return the number of leading characters the two share
     */
    public static int commonPrefixLength(String first, String second) {
        if (first == null || second == null) {
            return 0;
        }
        int limit = Math.min(first.length(), second.length());
        int index = 0;
        while (index < limit && first.charAt(index) == second.charAt(index)) {
            index++;
        }
        return index;
    }
}
