package com.eonmux.cadetcoder.ai;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What to ask a model for, when the caller did not say.
 *
 * <h2>Why this is decided in one place</h2>
 *
 * <p>{@code ai.temperature}, {@code ai.maxTokens} and {@code ai.completionTimeoutSeconds} are
 * settings the tool offers, saves, lists and documents. Almost every caller hands the model an empty
 * parameter map, and every backend answers an empty map with a literal of its own, so the three
 * settings were accepted, persisted, echoed back by {@code /config}, and read by nothing on the path
 * every configured provider takes. Only the legacy clients consulted them, and
 * they stop being the active client the moment a provider is configured.</p>
 *
 * <p>Filled in here, at the one point every request funnels through, rather than in each backend:
 * the backends' literals are what a request with no configuration at all should get, and a backend
 * added later would otherwise have to remember to ask.</p>
 */
public final class RequestSettings {

    /** Asked-for randomness. */
    public static final String TEMPERATURE = "temperature";

    /** Ceiling on what the model may say back. */
    public static final String MAX_TOKENS = "maxTokens";

    /**
     * Seconds to wait for the response to start.
     *
     * <p>Nothing here streams, and a provider sends no headers until the model has finished
     * writing, so for a completion this is the whole generation rather than a wait for the first
     * byte. See {@code Configuration.AiConfig.completionTimeoutSeconds}.</p>
     */
    public static final String COMPLETION_TIMEOUT = "completionTimeout";

    private RequestSettings() {
    }

    /**
     * Adds the configured values for anything the caller left unsaid.
     *
     * <p>A new map: the caller's is theirs, and one request's settings must not become the next
     * request's by having been written into a map somebody kept.</p>
     *
     * @param asked what the caller specified; may be {@code null} or empty
     * @return the same request with the configured defaults filled in
     */
    public static Map<String, Object> filledIn(Map<String, Object> asked) {
        Map<String, Object> settings = new LinkedHashMap<>();
        if (asked != null) {
            settings.putAll(asked);
        }
        Configuration.AiConfig ai = configured();
        if (ai == null) {
            return settings;
        }
        settings.putIfAbsent(TEMPERATURE, ai.getTemperature());
        settings.putIfAbsent(MAX_TOKENS, ai.getMaxTokens());
        settings.putIfAbsent(COMPLETION_TIMEOUT, ai.getCompletionTimeoutSeconds());
        return settings;
    }

    /**
     * @return the AI settings, or {@code null} when there is no readable configuration -- in which
     *         case each backend's own default stands, which is what an unconfigured run should get
     */
    private static Configuration.AiConfig configured() {
        try {
            return ConfigManager.getInstance().getConfig().getAi();
        } catch (RuntimeException unreadable) {
            return null;
        }
    }
}
