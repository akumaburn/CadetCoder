package com.eonmux.cadetcoder.ai.providers;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which of OpenCode's gateway endpoints a model has to be called on.
 *
 * <h2>What the gateways require</h2>
 *
 * <p>The Zen and Go gateways partition their models by protocol across distinct paths, and every
 * model answers on exactly one of them. OpenCode's own client reads the partition from its
 * catalog, which records per model the protocol -- and therefore the path -- it is served on:
 * {@code /inference/{openai,anthropic,google}/...} for Zen and {@code /inference/go/{...}} for
 * Go. Sending a Messages-only model to the Chat Completions endpoint, or a Gemini model anywhere
 * but the Google endpoint, is answered with a model-not-found failure; the endpoints do not fall
 * back to one another.</p>
 *
 * <h2>Why the model id is what decides</h2>
 *
 * <p>The catalog this tool reads ({@code models.dev/api.json}) names the models but not their
 * protocol, so the id is the only thing available at the moment the choice has to be made. That
 * is workable because the partition follows the vendor: the Claude families ({@code claude-*})
 * sit on the Anthropic endpoint and the Gemini families ({@code gemini-*}) on the Google one,
 * with one enumerated exception -- a handful of Qwen and MiniMax chat models are served in
 * Anthropic Messages shape rather than on the OpenAI endpoint. Everything else, and every model
 * id this class does not recognise, is called on the OpenAI endpoint, which is the majority on
 * both gateways.</p>
 *
 * <h2>The failure worth having</h2>
 *
 * <p>A model that moves between endpoints as the gateways add them, or a new Messages-served
 * family outside the exception list, is called on the OpenAI endpoint and refused by the gateway
 * with a model-not-found naming the model. That is specific, it arrives from the authority on
 * the question, and it says what to change: this set.</p>
 *
 * <p>The Responses-only models ({@code gpt-5*}, {@code gpt-6*}, recent {@code grok-*}, {@code
 * muse-*}) are a boundary this wire cannot cross: this tool does not speak the Responses
 * protocol, and no endpoint choice fixes that. They fail as unknown to the OpenAI endpoint.</p>
 */
public final class OpencodeGatewayWire implements ModelWire {

    /** The registry ids of the connectors this belongs to. */
    public static final String ZEN_ID    = "opencode";
    public static final String GO_ID     = "opencode-go";

    /** Matched case-insensitively: a model id typed by hand is the one place it may not be. */
    private static final String ANTHROPIC_PREFIX = "claude-";
    private static final String GOOGLE_PREFIX    = "gemini-";

    /**
     * The non-Anthropic models the gateways serve in Anthropic Messages shape, as of the November
     * 2026 catalog. Both gateways' sets are here; a model named only on one gateway routes the
     * same way on the other, where it is simply not offered.
     */
    private static final Set<String> ON_ANTHROPIC_WIRE = Set.of(
            "qwen3.5-plus", "qwen3.6-plus", "qwen3.6-plus-free", "qwen3.7-plus",
            "qwen3.8-flash", "qwen3.8-max",
            "minimax-m2.1-free", "minimax-m2.5-free", "minimax-m2.7",
            "minimax-m3", "minimax-m3-free");

    /** The three endpoint families the gateways publish, in the order a reader meets them. */
    private static final Set<ConnectorProtocol> ALL_THREE = Collections.unmodifiableSet(
            new LinkedHashSet<>(List.of(ConnectorProtocol.OPENAI_CHAT,
                                        ConnectorProtocol.ANTHROPIC_MESSAGES,
                                        ConnectorProtocol.GOOGLE_GENERATIVE_AI)));

    @Override
    public ConnectorProtocol forModel(String model) {
        if (isAnthropic(model)) {
            return ConnectorProtocol.ANTHROPIC_MESSAGES;
        }
        if (isGoogle(model)) {
            return ConnectorProtocol.GOOGLE_GENERATIVE_AI;
        }
        return ConnectorProtocol.OPENAI_CHAT;
    }

    @Override
    public Set<ConnectorProtocol> protocols() {
        return ALL_THREE;
    }

    /**
     * Whether this id names a model the gateways serve on the Anthropic Messages endpoint.
     *
     * @param model the model id, which may be {@code null} when a caller is asking what the
     *              connector does by default
     * @return whether it has to go to the Anthropic endpoint
     */
    public static boolean isAnthropic(String model) {
        if (model == null) {
            return false;
        }
        String id = model.toLowerCase(Locale.ROOT);
        return id.startsWith(ANTHROPIC_PREFIX) || ON_ANTHROPIC_WIRE.contains(id);
    }

    /**
     * Whether this id names a model the gateways serve on the Google endpoint.
     *
     * @param model the model id, which may be {@code null}
     * @return whether it has to go to the Google endpoint
     */
    public static boolean isGoogle(String model) {
        return model != null && model.toLowerCase(Locale.ROOT).startsWith(GOOGLE_PREFIX);
    }
}
