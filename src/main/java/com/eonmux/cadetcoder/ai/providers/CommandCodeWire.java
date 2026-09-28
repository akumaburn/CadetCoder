package com.eonmux.cadetcoder.ai.providers;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which of Command Code's two endpoints a model has to be called on.
 *
 * <h2>What the gateway requires</h2>
 *
 * <p>The Provider API publishes both wires at one root, and they do not overlap. Sending a Claude
 * model to {@code /chat/completions} is refused with <em>"must be called via /provider/v1/messages
 * (Anthropic Messages shape)"</em>, and sending anything else to {@code /messages} is refused with
 * <em>"Use /provider/v1/chat/completions for OpenAI and OSS models"</em>. Both were confirmed
 * against the live API; neither endpoint falls back to the other.</p>
 *
 * <h2>Why the model id is what decides</h2>
 *
 * <p>The gateway's own model listing does not say. Every entry in it carries the same four fields,
 * and none of them distinguishes a Claude model from a GPT or a Qwen one -- so the id is the only
 * thing available at the moment the choice has to be made. That is workable because the gateway
 * names the Anthropic models after themselves: at this root they are exactly the ids beginning
 * {@code claude-}, while every other model is either prefixed with its vendor ({@code deepseek/},
 * {@code zai-org/}, {@code xai/}) or begins {@code gpt-}.</p>
 *
 * <p>If the gateway ever adds a Claude model under some other id, this sends it to the OpenAI
 * endpoint and the gateway answers with the message quoted above, naming the endpoint it wanted.
 * That is the failure worth having: it is specific, it arrives from the authority on the question,
 * and it says what to change.</p>
 */
public final class CommandCodeWire implements ModelWire {

    /** The registry id of the connector this belongs to. */
    public static final String PROVIDER_ID = "commandcode";

    /**
     * The prefix the gateway gives its Anthropic models. Matched case-insensitively because a model
     * id typed by hand into {@code models use} is the one place it will not always be lower case.
     */
    private static final String ANTHROPIC_PREFIX = "claude-";

    /** Both endpoints the gateway publishes, the one serving most of the catalog first. */
    private static final Set<ConnectorProtocol> BOTH = Collections.unmodifiableSet(
            new LinkedHashSet<>(List.of(ConnectorProtocol.OPENAI_CHAT,
                                        ConnectorProtocol.ANTHROPIC_MESSAGES)));

    @Override
    public ConnectorProtocol forModel(String model) {
        return isAnthropic(model) ? ConnectorProtocol.ANTHROPIC_MESSAGES : ConnectorProtocol.OPENAI_CHAT;
    }

    @Override
    public Set<ConnectorProtocol> protocols() {
        return BOTH;
    }

    /**
     * Whether this id names one of the gateway's Anthropic models.
     *
     * @param model the model id, which may be {@code null} when a caller is asking what the
     *              connector does by default
     * @return whether it has to go to the Anthropic Messages endpoint
     */
    public static boolean isAnthropic(String model) {
        return model != null && model.toLowerCase(Locale.ROOT).startsWith(ANTHROPIC_PREFIX);
    }
}
