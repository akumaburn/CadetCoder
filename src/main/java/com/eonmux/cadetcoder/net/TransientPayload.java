package com.eonmux.cadetcoder.net;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.regex.Pattern;

/**
 * Decides whether an HTTP 200 that carries no usable completion is a transient failure.
 *
 * <h2>Why a 200 can be a transport failure</h2>
 *
 * <p>Status codes describe the HTTP exchange, not the provider behind it. Two things routinely
 * arrive under a 200 and mean "this did not work, try again":</p>
 *
 * <ul>
 *   <li>A gateway in front of the model — Cloudflare, nginx, a provider's own edge — answering with
 *       an error payload it generated itself. The model was never reached.</li>
 *   <li>A body that was cut off in flight. The headers arrived, so the status is 200, but the
 *       connection died before the payload was complete and what is left is unparseable.</li>
 * </ul>
 *
 * <p>Classified by status alone, both look like "the model replied with nothing", which is terminal
 * — so a run that has already made twenty successful requests dies on a blip that a single retry
 * would have cleared.</p>
 *
 * <h2>Why the check is structural rather than a body scan</h2>
 *
 * <p>The obvious implementation — search the whole body for "overloaded", "rate limit", and friends
 * — is wrong, and dangerously so. A completion's text is arbitrary: ask this tool why a provider is
 * rate-limiting and the model's perfectly good answer contains every one of those words. Retrying it
 * would discard a correct response and pay for it twice.</p>
 *
 * <p>So the wording is only ever consulted inside a top-level {@code error} node. A successful
 * response from any of these APIs has no such node; the model's own prose lives under
 * {@code choices}, {@code content}, or {@code candidates}, and is never examined here.</p>
 */
public final class TransientPayload {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Wording that marks a provider-side error as worth retrying.
     *
     * <p>Matched only against a top-level {@code error} node — see the class note. The set is
     * deliberately broad: providers describe the same overload a dozen ways, and the cost of missing
     * one is a dead run, while the cost of an extra retry is a few seconds.</p>
     */
    private static final Pattern[] TRANSIENT_WORDING = {
            Pattern.compile("(?i)overloaded|over capacity|at capacity|capacity constraints"),
            Pattern.compile("(?i)rate[ _-]?limit|too many requests|quota exceeded|resource[ _-]?exhausted"),
            Pattern.compile("(?i)unavailable|temporarily|try again|retry|timeout|timed out"),
            Pattern.compile("(?i)internal (?:server )?error|server[ _-]?error|upstream|bad gateway"),
            Pattern.compile("(?i)connection (?:error|reset|refused|closed)|socket|econnreset|etimedout"),
    };

    private TransientPayload() {
    }

    /**
     * Whether a 200 body that yielded no completion should be retried.
     *
     * @param body the provider's response body, may be null
     * @return {@code true} when the payload indicates a transient condition rather than a genuine
     *         empty answer
     */
    public static boolean isTransient(String body) {
        if (body == null || body.isBlank()) {
            // A completions endpoint answering 200 with nothing at all did not answer.
            return true;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (Exception e) {
            // Unparseable, so the question is why. A body that STARTS like a JSON document and then
            // stops was cut off in flight -- a transport failure, and worth another attempt. A body
            // that was never JSON means this endpoint does not speak the protocol at all, which is a
            // misconfiguration: retrying it ten times only delays telling the user so.
            return looksLikeTruncatedJson(body);
        }
        if (root == null || !root.isObject()) {
            // A well-formed document of the wrong shape parsed cleanly, so nothing was lost in
            // transit. That is the wrong endpoint, not a blip.
            return false;
        }
        JsonNode error = root.get("error");
        if (error == null || error.isNull()) {
            // A well-formed response with no error node and no content is a genuine empty answer.
            // Retrying would produce the same thing.
            return false;
        }
        return matchesTransientWording(error.toString());
    }

    /**
     * Whether a 200 body carries the provider's own error object instead of a completion.
     *
     * <h2>Why this is asked separately from {@link #isTransient}</h2>
     *
     * <p>{@code isTransient} answers "is this worth another attempt?", and for an error whose
     * wording matches none of the patterns above the answer is no. That left the body to be parsed
     * by a backend, which found no {@code choices} in it and said "no completion choices returned"
     * -- discarding the sentence the provider had written explaining what was wrong. A gateway
     * answering 200 with {@code {"error":{"message":"model X is not enabled for this key"}}} told
     * the user nothing but that their provider had returned an unusable response.</p>
     *
     * <p>The message is already extracted by {@link #describe}; what was missing was anyone asking
     * for it on the path where retrying is not the answer.</p>
     *
     * @param body the provider's response body, may be null
     * @return {@code true} when the body is an object with a non-null top-level {@code error}
     */
    public static boolean carriesProviderError(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            if (root == null || !root.isObject()) {
                return false;
            }
            JsonNode error = root.get("error");
            return error != null && !error.isNull();
        } catch (Exception unparseable) {
            // Not an error object; whether it is worth retrying is isTransient's question.
            return false;
        }
    }

    /**
     * A short reason suitable for a retry notice.
     *
     * @param body the provider's response body, may be null
     * @return why the payload was treated as transient
     */
    public static String describe(String body) {
        if (body == null || body.isBlank()) {
            return "empty response body";
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            if (root != null && root.isObject()) {
                JsonNode error = root.get("error");
                if (error != null && !error.isNull()) {
                    JsonNode message = error.get("message");
                    String text = message != null && message.isTextual() ? message.asText()
                                                                         : error.toString();
                    return "provider error under HTTP 200: " + LLMErrorMapper.excerpt(text);
                }
            }
        } catch (Exception ignored) {
            // Falls through to the truncation description below.
        }
        return "response body ended mid-payload (truncated in flight)";
    }

    /**
     * Whether an unparseable body is a JSON document that was cut short.
     *
     * @param body a non-blank body that failed to parse
     * @return {@code true} when it opens as JSON, which means the payload began arriving and stopped
     */
    private static boolean looksLikeTruncatedJson(String body) {
        String trimmed = body.stripLeading();
        return !trimmed.isEmpty() && (trimmed.charAt(0) == '{' || trimmed.charAt(0) == '[');
    }

    private static boolean matchesTransientWording(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (Pattern pattern : TRANSIENT_WORDING) {
            if (pattern.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }
}
