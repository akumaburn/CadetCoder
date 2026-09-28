package com.eonmux.cadetcoder.net;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads the reply text out of a Chat Completions response.
 *
 * <p>One place decides what counts as the model's answer, because the two backends that speak this
 * protocol -- {@link OpenAIBackend} and {@link OpenAICompatibleBackend} -- otherwise each keep their
 * own copy, and a shape one of them learns to read stays unreadable on the other. Twenty-one of the
 * twenty-three connectors reach a provider through one of those two classes.</p>
 *
 * <h2>Why {@code message.content} is not the only place the answer lives</h2>
 *
 * <p>The field is documented as a string and is frequently not one:</p>
 *
 * <ul>
 *   <li><b>An array of content parts.</b> The same shape this client sends when it marks a cache
 *       breakpoint, mirrored back by providers that echo the request shape. A cast to String fails
 *       and {@code toString()} on the list yields {@code [{type=text, text=hi}]}, which is worse:
 *       it is not an error, so the caller feeds that text to the model as the reply.</li>
 *   <li><b>Null, with the text under {@code reasoning_content}.</b> DeepSeek's own API splits a
 *       reasoning model's output into that field and {@code content}, and a gateway that resells
 *       such a model forwards the split.</li>
 *   <li><b>Null, with the text under {@code reasoning}.</b> The spelling OpenRouter and several
 *       gateways use for the same thing.</li>
 * </ul>
 *
 * <p>Content wins whenever it carries text. A reasoning field is read only when it does not, which
 * is the case that would otherwise fail the request outright. Handing back reasoning text costs
 * nothing when the model really did answer only to itself: the reply does not parse as an action,
 * the loop says so and asks again, which is what it already does for any reply in the wrong shape.
 * Failing the request instead ends the run.</p>
 *
 * <h2>The one reply that is not salvaged</h2>
 *
 * <p>A turn that stopped at the output limit, which is {@code finish_reason: length}. What sits in
 * its reasoning field is an unfinished thought rather than an answer the model chose not to put
 * under {@code content}, and passing it back as the reply hides the only fact worth reporting: the
 * budget ran out. Measured against {@code deepseek/deepseek-v4-flash} through Command Code, a
 * 16-token budget returned 250 characters of reasoning and no content, a 256-token budget returned
 * 1,013 and no content, and 4,096 tokens returned the answer. A reasoning model charges its
 * reasoning to the same budget as its answer, so a long turn can spend all of it before writing
 * anything -- which is why the same request can work and then not work.</p>
 *
 * <h2>When there is no text anywhere</h2>
 *
 * <p>{@link #absenceDetail} names the reason rather than repeat "no content". A user who is told
 * only that the message was empty cannot tell an exhausted output budget from a filtered reply from
 * a model that answered with a tool call, and those three want three different actions.</p>
 */
public final class ChatCompletionContent {

    /** The base detail, kept so a caller can match on it whatever the reason turns out to be. */
    static final String NO_CONTENT = "no content found in message";

    private ChatCompletionContent() {
    }

    /**
     * Whether the response carries a first choice to read at all.
     *
     * @param response the parsed response body
     * @return {@code true} when {@code choices} is a non-empty list
     */
    public static boolean hasChoices(Map<String, ?> response) {
        return response != null
               && response.get("choices") instanceof List<?> choices
               && !choices.isEmpty();
    }

    /**
     * The reply text carried by the first choice.
     *
     * @param response the parsed response body
     * @return the text, or empty when the message carries none
     */
    public static Optional<String> firstChoiceText(Map<String, ?> response) {
        Map<?, ?> message = firstMessage(response);
        if (message == null) {
            return Optional.empty();
        }
        String content = textOf(message.get("content"));
        if (content != null && !content.isBlank()) {
            return Optional.of(content);
        }
        // A turn cut off at the output limit yields nothing, whatever else is in the message.
        //
        // Neither of the two fallbacks below is true of it. Its reasoning field holds a thought the
        // model was still in the middle of, and handing that back as the reply makes the run act on
        // half an idea: it does not parse as an action, the loop asks for the format again, and the
        // next turn truncates in the same place. Its blank content is not a model choosing to say
        // nothing either; it is a model that never got to the point of saying anything. Reported
        // instead, by absenceDetail, which names the budget and the setting that raises it.
        if ("length".equals(finishReason(response))) {
            return Optional.empty();
        }
        for (String field : new String[]{"reasoning_content", "reasoning"}) {
            String reasoning = textOf(message.get(field));
            if (reasoning != null && !reasoning.isBlank()) {
                return Optional.of(reasoning);
            }
        }
        String toolCalls = toolCallText(message);
        if (!toolCalls.isEmpty()) {
            return Optional.of(toolCalls);
        }
        // A present but blank content is still an answer: the model chose to say nothing, and a
        // caller that asked a yes/no question of a terse model gets that verbatim today.
        return content != null ? Optional.of(content) : Optional.empty();
    }

    /**
     * Why the first choice carries no text, phrased for a protocol error.
     *
     * @param response the parsed response body
     * @return a detail string that always begins with {@link #NO_CONTENT}
     */
    public static String absenceDetail(Map<String, ?> response) {
        Map<?, ?> message = firstMessage(response);
        String    reason  = finishReason(response);
        if ("length".equals(reason)) {
            // Named separately because the two want the same action for different reasons, and a
            // user who is told only "output limit" on a reasoning model will raise the limit by a
            // little and hit it again. A model that thinks before it answers charges the thinking
            // to the same budget, so the answer needs room for both.
            return NO_CONTENT + (hasReasoning(message)
                    ? ": the model spent its whole output budget thinking and never reached an"
                      + " answer (finish_reason: length). A reasoning model charges its reasoning"
                      + " to the same budget. Raise ai.maxTokens, or set it to 0 to ask for no"
                      + " ceiling at all"
                    : ": the model reached its output limit before it wrote any"
                      + " (finish_reason: length). Raise ai.maxTokens, or set it to 0 to ask for"
                      + " no ceiling at all");
        }
        if ("content_filter".equals(reason)) {
            return NO_CONTENT + ": the provider filtered the reply (finish_reason: content_filter)";
        }
        if (message != null && message.get("tool_calls") instanceof List<?> calls
            && !calls.isEmpty()) {
            // Reached only when the calls named no tool; one that did was handed back as text.
            return NO_CONTENT + ": the model answered with a tool call that names no tool";
        }
        StringBuilder detail = new StringBuilder(NO_CONTENT);
        String        fields = fieldNames(message);
        if (!fields.isEmpty()) {
            detail.append(": the message carried ").append(fields).append(" and no text");
        }
        if (reason != null) {
            detail.append(" (finish_reason: ").append(reason).append(')');
        }
        return detail.toString();
    }

    /**
     * The message's tool calls, written as the text the model would have written them as.
     *
     * <h2>Why a tool call is an answer rather than an absence</h2>
     *
     * <p>A model trained to call tools answers with a {@code tool_calls} array and no text, even
     * though no tools were declared to it, because the command catalogue in its prompt reads to it
     * as a list of tools. Read only for text, that reply carried none: the turn failed with "the
     * model answered with a tool call, which this client never asks for" and the run ended, over a
     * reply that named the command and its arguments. The array says the same thing a reply written
     * in DSML or in an ACTION block says, so it is handed on as text and parsed with the rest.</p>
     *
     * @param message the first choice's message
     * @return the calls as JSON text, or an empty string when the message holds none
     */
    private static String toolCallText(Map<?, ?> message) {
        return message.get("tool_calls") instanceof List<?> calls
                ? com.eonmux.cadetcoder.ai.parsing.toolcalls.ToolCalls.textFor(calls)
                : "";
    }

    /**
     * @param message the first choice's message, may be {@code null}
     * @return whether it carries a reasoning field with text in it
     */
    private static boolean hasReasoning(Map<?, ?> message) {
        if (message == null) {
            return false;
        }
        for (String field : new String[]{"reasoning_content", "reasoning"}) {
            String text = textOf(message.get(field));
            if (text != null && !text.isBlank()) {
                return true;
            }
        }
        return false;
    }

    /** The first choice's message, or {@code null} when the response does not carry one. */
    private static Map<?, ?> firstMessage(Map<String, ?> response) {
        if (!hasChoices(response)) {
            return null;
        }
        List<?> choices = (List<?>) response.get("choices");
        if (choices.get(0) instanceof Map<?, ?> choice
            && choice.get("message") instanceof Map<?, ?> message) {
            return message;
        }
        return null;
    }

    /** The first choice's {@code finish_reason}, or {@code null} when it reports none. */
    private static String finishReason(Map<String, ?> response) {
        if (!hasChoices(response)) {
            return null;
        }
        List<?> choices = (List<?>) response.get("choices");
        if (choices.get(0) instanceof Map<?, ?> choice
            && choice.get("finish_reason") instanceof String reason && !reason.isBlank()) {
            return reason;
        }
        return null;
    }

    /**
     * Reads one content value in any of the shapes this protocol produces.
     *
     * @param value the raw value of a message field
     * @return its text, or {@code null} when the value carries none
     */
    private static String textOf(Object value) {
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof List<?> parts) {
            StringBuilder joined = new StringBuilder();
            for (Object part : parts) {
                String text = partText(part);
                if (text != null) {
                    joined.append(text);
                }
            }
            // Parts are contiguous pieces of one message, so they are joined with nothing between
            // them. An empty result means the list held no text part, which is not the same as a
            // list that was never there.
            return joined.length() > 0 ? joined.toString() : null;
        }
        if (value instanceof Map<?, ?> part) {
            return partText(part);
        }
        return null;
    }

    /** The text of one content part, or {@code null} when the part carries none. */
    private static String partText(Object part) {
        if (part instanceof String text) {
            return text;
        }
        if (part instanceof Map<?, ?> map && map.get("text") instanceof String text) {
            return text;
        }
        return null;
    }

    /** The message's own field names, minus the ones that say nothing about the absence. */
    private static String fieldNames(Map<?, ?> message) {
        if (message == null || message.isEmpty()) {
            return "";
        }
        Set<String> names = new LinkedHashSet<>();
        for (Object key : message.keySet()) {
            String name = String.valueOf(key);
            if (!"role".equals(name)) {
                names.add(name);
            }
        }
        return names.isEmpty() ? "" : String.join(", ", names);
    }
}
