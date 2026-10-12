package com.eonmux.cadetcoder.ai.metrics;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How many characters one token covers for each model, as its provider counted them.
 *
 * <h2>Why the standard ratio is not enough for a limit</h2>
 *
 * <p>{@link TokenEstimator} takes four characters per token for every model. Tokenizers differ, and
 * one that splits code, logs and paths finer counts more tokens than that for the same text. A
 * limit measured with the standard ratio is then reached later than the model's own count reaches
 * it, and a prompt can pass the model's window before it passes the compaction trigger. The
 * provider then refuses the request.</p>
 *
 * <h2>Where the counts come from</h2>
 *
 * <p>Every request whose provider reports usage gives the prompt's length in characters and in the
 * model's own tokens. The latest such pair sets the model's ratio. A provider that refuses an
 * over-long prompt often says how many tokens it counted, and that count is a lower bound, so it
 * can only lower the ratio. The standard ratio stays the most a token is taken to cover: a coarser
 * tokenizer would otherwise let a prompt be undercounted until the next count arrives.</p>
 */
public final class TokenCalibration {

    /**
     * Characters a prompt needs before its count says anything about the text.
     *
     * <p>A short request is mostly the fixed overhead the chat template and the wire format add,
     * which would set a ratio far below what a long prompt of the same text has.</p>
     */
    static final int SMALLEST_SAMPLE_CHARS = 4_000;

    private static final Map<String, Double> CHARS_PER_TOKEN = new ConcurrentHashMap<>();

    private TokenCalibration() {
    }

    /**
     * Records what a provider counted for a prompt it accepted.
     *
     * @param model      the model the prompt went to, as {@code PromptSize} names it
     * @param characters the prompt's length in characters
     * @param tokens     the tokens the provider counted in it
     */
    public static void observe(String model, long characters, long tokens) {
        Double ratio = ratio(model, characters, tokens);
        if (ratio != null) {
            CHARS_PER_TOKEN.put(model, ratio);
        }
    }

    /**
     * Records the least a provider counted for a prompt it refused.
     *
     * @param model      the model the prompt went to, as {@code PromptSize} names it
     * @param characters the prompt's length in characters
     * @param atLeast    the fewest tokens the provider said the prompt has
     */
    public static void observeAtLeast(String model, long characters, long atLeast) {
        Double ratio = ratio(model, characters, atLeast);
        if (ratio != null) {
            CHARS_PER_TOKEN.merge(model, ratio, Math::min);
        }
    }

    /**
     * @param model the model, as {@code PromptSize} names it; {@code null} for none
     * @return the characters one token covers for it
     */
    public static double charsPerToken(String model) {
        Double counted = model == null ? null : CHARS_PER_TOKEN.get(model);
        return counted == null ? TokenEstimator.CHARS_PER_TOKEN
                               : Math.min(TokenEstimator.CHARS_PER_TOKEN, counted);
    }

    /**
     * How many of the model's tokens a text takes, rounded up.
     *
     * @param model the model, as {@code PromptSize} names it; {@code null} for none
     * @param text  the text; {@code null} for none
     * @return the tokens; {@code 0} for blank text
     */
    public static int estimate(String model, String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return (int) Math.ceil(text.length() / charsPerToken(model));
    }

    /** Forgets every count. Intended for tests. */
    public static void clear() {
        CHARS_PER_TOKEN.clear();
    }

    private static Double ratio(String model, long characters, long tokens) {
        if (model == null || model.isBlank() || characters < SMALLEST_SAMPLE_CHARS || tokens <= 0) {
            return null;
        }
        return (double) characters / tokens;
    }
}
