package com.eonmux.cadetcoder.harness.loop;

/**
 * What a reasoner said, and what saying it cost.
 *
 * <h2>Why the cost comes back with the words</h2>
 *
 * <p>The budget is what stops a run, and compaction is what keeps one going; both are decided on
 * tokens. A backend that answers with text alone leaves the driver estimating what it just spent,
 * and an estimate that drifts either kills a run that had room left or lets one run past what it was
 * given. The figures belong to the call that incurred them, so they travel with it.</p>
 *
 * <p>What was sent is billed once per attempt, and every attempt sends the same prompt, so the
 * prompt's size comes back apart from it: the driver sizes the next request against the context
 * window by the prompt.</p>
 *
 * @param text         what was said, which may be prose, calls, or both
 * @param tokensIn     what was sent to produce it, over every attempt
 * @param tokensOut    what came back
 * @param promptTokens how many tokens the prompt is, as one attempt sent it
 */
public record Reply(String text, long tokensIn, long tokensOut, long promptTokens) {

    public Reply {
        if (tokensIn < 0 || tokensOut < 0 || promptTokens < 0) {
            throw new IllegalArgumentException("a reply cannot cost less than nothing");
        }
        text = text == null ? "" : text;
    }

    /** A reply of one attempt, whose prompt is everything that was sent. */
    public Reply(String text, long tokensIn, long tokensOut) {
        this(text, tokensIn, tokensOut, tokensIn);
    }
}
