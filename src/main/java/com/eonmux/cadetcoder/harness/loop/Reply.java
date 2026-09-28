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
 * @param text      what was said, which may be prose, calls, or both
 * @param tokensIn  what was sent to produce it
 * @param tokensOut what came back
 */
public record Reply(String text, long tokensIn, long tokensOut) {

    public Reply {
        if (tokensIn < 0 || tokensOut < 0) {
            throw new IllegalArgumentException("a reply cannot cost less than nothing");
        }
        text = text == null ? "" : text;
    }
}
