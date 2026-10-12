package com.eonmux.cadetcoder.harness.loop;

/**
 * A reasoner's backend refused the request as longer than the model's context window.
 *
 * <p>The one failure of a reasoner the driver answers itself: it gives up older tool answers and
 * asks again, as it does when a request grows past its limit. Every other failure still ends the
 * run. When nothing is left to give up, the driver throws the refusal it carries.</p>
 */
public final class PromptTooLong extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient RuntimeException refusal;
    private final long                       promptTokens;

    /**
     * @param refusal      the failure the backend reported
     * @param promptTokens the fewest tokens the backend said the prompt has; {@code 0} when it gave
     *                     no count
     */
    public PromptTooLong(RuntimeException refusal, long promptTokens) {
        super(refusal == null ? "the prompt is longer than the model's context window"
                              : refusal.getMessage(), refusal);
        this.refusal      = refusal == null ? this : refusal;
        this.promptTokens = Math.max(0, promptTokens);
    }

    /** @return the failure the backend reported, which ends the run when nothing more can go */
    public RuntimeException refusal() {
        return refusal;
    }

    /** @return the fewest tokens the backend said the prompt has; {@code 0} when it gave none */
    public long promptTokens() {
        return promptTokens;
    }
}
