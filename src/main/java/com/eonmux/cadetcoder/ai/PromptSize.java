package com.eonmux.cadetcoder.ai;

import com.eonmux.cadetcoder.ai.metrics.TokenCalibration;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;

/**
 * How many of a model's own tokens a prompt takes, for deciding whether it fits the context window.
 *
 * <p>Measured with the ratio {@link TokenCalibration} has learned from the model's provider, so a
 * limit is reached when the model's own count reaches it. Each model is named the way
 * {@link ContextWindow} names it when it is the configured one, so the size and the window are
 * figures for the same model.</p>
 */
public final class PromptSize {

    private PromptSize() {
    }

    /** @return the configured model's name for measuring, or {@code null} when none is set */
    public static String configuredModel() {
        try {
            Configuration.AiConfig ai = ConfigManager.getInstance().getConfig().getAi();
            if (ai == null || ai.getModel() == null || ai.getModel().isBlank()) {
                return null;
            }
            return ContextWindow.keyFor(ai.getProvider(), ai.getModel());
        } catch (RuntimeException e) {
            // Reading configuration must not be able to fail a request.
            return null;
        }
    }

    /**
     * The name a request's model is measured under.
     *
     * @param chosen the model a caller named, or {@code null} for the configured one
     * @return the name
     */
    public static String modelOf(AIClient chosen) {
        return chosen == null ? configuredModel() : chosen.getModelName();
    }

    /**
     * @param text the text to measure; {@code null} for none
     * @return its size in the configured model's tokens
     */
    public static int tokens(String text) {
        return TokenCalibration.estimate(configuredModel(), text);
    }

    /**
     * @param tokens a size in the configured model's tokens
     * @return the characters that size covers, never fewer than none
     */
    public static int charsFor(int tokens) {
        double chars = Math.max(0, tokens) * TokenCalibration.charsPerToken(configuredModel());
        return (int) Math.min(Integer.MAX_VALUE, Math.floor(chars));
    }

    /**
     * @param model the model, as {@link #modelOf} names it
     * @param text  the text to measure; {@code null} for none
     * @return its size in that model's tokens
     */
    public static int tokens(String model, String text) {
        return TokenCalibration.estimate(model, text);
    }
}
