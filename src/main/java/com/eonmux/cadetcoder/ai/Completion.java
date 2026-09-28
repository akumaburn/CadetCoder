package com.eonmux.cadetcoder.ai;

import com.eonmux.cadetcoder.ai.metrics.RequestMetrics;

/**
 * What the model said, and what saying it cost.
 *
 * <h2>Why the cost travels with the words</h2>
 *
 * <p>Every request already produces these figures: the provider's own usage block when it sent one,
 * and an estimate standing in for it when it did not. They were printed once and then dropped,
 * which left anything that has to know what a run has spent -- a token allowance, a decision about
 * when a transcript has outgrown the context window -- re-counting the characters it sent. That is
 * a different number from the one the provider billed, so a run stops in the wrong place, in
 * whichever direction the guess happens to be wrong.</p>
 *
 * <h2>Why it is honest about which it is</h2>
 *
 * <p>An estimate and a provider's own count are both usable, and they are not equally trustworthy.
 * Carrying {@link #estimated} means a caller that has to be exact -- billing, a hard ceiling -- can
 * tell, rather than treating the tokeniser's approximation as though the provider had confirmed
 * it.</p>
 *
 * @param text      what the model said, never {@code null}
 * @param tokensIn  how many tokens went in, cached and new together
 * @param tokensOut how many came back
 * @param estimated whether these were counted here rather than reported by the provider
 */
public record Completion(String text, long tokensIn, long tokensOut, boolean estimated) {

    public Completion {
        if (tokensIn < 0 || tokensOut < 0) {
            throw new IllegalArgumentException("a completion cannot have cost less than nothing");
        }
        text = text == null ? "" : text;
    }

    /**
     * The completion for one request whose figures have already been measured.
     *
     * @param text     what the model said
     * @param measured the request's finished metrics
     * @return the two halves of one answer, together
     */
    public static Completion of(String text, RequestMetrics measured) {
        return new Completion(text, measured.inputTokens(), measured.outputTokens(),
                              measured.isEstimated());
    }
}
