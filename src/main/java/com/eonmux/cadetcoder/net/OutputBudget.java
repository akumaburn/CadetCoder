package com.eonmux.cadetcoder.net;

/**
 * How much the model is allowed to say back, as the wire states it.
 *
 * <h2>Why the default is no limit</h2>
 *
 * <p>Nobody knows how long an answer needs to be before it is written. A number chosen in advance is
 * a guess, and the guess is only ever wrong in one direction: too small cuts the answer off, and the
 * caller gets a reply that stops mid-sentence, or no reply at all.</p>
 *
 * <p>The cost of guessing rose with reasoning models, which charge their thinking to the same budget
 * as their answer. Measured against {@code deepseek/deepseek-v4-flash} through Command Code with one
 * short question: at 64 tokens it returned {@code finish_reason: length} and 250 characters of
 * reasoning with no answer; at 256 tokens, 1,013 characters of reasoning and no answer; at 4,096 it
 * answered. How much a model thinks varies with what it is asked, so one budget serves some turns
 * and not others, which reads as an unreliable provider.</p>
 *
 * <p>So nothing is asked for unless somebody asks. Every one of these APIs treats the field as
 * optional and falls back to what the model can actually produce, which is a better number than any
 * this tool could pick. A user who wants a ceiling still sets {@code ai.maxTokens} and gets it.</p>
 *
 * <h2>The one wire that cannot be told "no limit"</h2>
 *
 * <p>Anthropic's Messages API requires {@code max_tokens} and rejects a request without it. That
 * backend sends the model's own published output limit instead, which it reads from
 * {@link com.eonmux.cadetcoder.ai.OutputWindow}. A fixed number would not do: the ceiling is per
 * model, and asking above a model's ceiling is a 400.</p>
 *
 * <p>{@link com.eonmux.cadetcoder.ai.OutputWindow} answers the related question of how many tokens
 * the model may produce even when no ceiling is sent, which the compactor needs so it can hold room
 * back in the input window for a reply.</p>
 */
public final class OutputBudget {

    /**
     * Ask for no limit.
     *
     * <p>Zero rather than a negative number, matching {@code cadet.iterative.maxIterations}, where
     * zero already means "unbounded" in this tool.</p>
     */
    public static final int UNLIMITED = 0;

    private OutputBudget() {
    }

    /**
     * @param maxTokens the budget as configured or requested
     * @return whether a ceiling was actually asked for
     */
    public static boolean isLimited(int maxTokens) {
        return maxTokens > 0;
    }
}
