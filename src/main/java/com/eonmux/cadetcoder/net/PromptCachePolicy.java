package com.eonmux.cadetcoder.net;

import com.eonmux.cadetcoder.logging.CadetLogger;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides whether a request should carry prompt-cache breakpoints, and remembers when a provider has
 * rejected them.
 *
 * <h2>Which providers need a marker at all</h2>
 *
 * <p>Prompt caching splits into two families, and only one of them takes anything in the request:</p>
 *
 * <ul>
 *   <li><b>Explicit</b> - the request must mark where the cacheable prefix ends.
 *       <b>Anthropic Messages</b> uses {@code "cache_control": {"type": "ephemeral"}} on a content
 *       block; <b>Bedrock Converse</b> uses a {@code {"cachePoint": {"type": "default"}}} block;
 *       <b>OpenRouter</b> accepts the Anthropic spelling inside content parts and forwards it to the
 *       models that support it. Without the marker these providers cache nothing, however stable the
 *       prefix is.</li>
 *   <li><b>Implicit</b> - the provider matches a shared leading prefix automatically and there is
 *       nothing to send. <b>OpenAI Chat Completions</b> and the OpenAI-compatible providers behave
 *       this way, as does <b>Google Generative AI</b> (its other mode, explicit context caching,
 *       is a separate stateful resource API rather than a per-request marker, so it is out of scope
 *       here). For these, a stable append-only prompt IS the whole implementation.</li>
 * </ul>
 *
 * <h2>Why this is defensive</h2>
 *
 * <p>Breakpoint support depends on the model, not just the provider: Bedrock rejects
 * {@code cachePoint} outright for models that do not support it, and a self-hosted endpoint speaking
 * a dialect of one of these protocols may reject an unfamiliar field. A hard failure there would turn
 * a working setup into a broken one, so the marker is treated as an optimisation that is allowed to
 * fail: {@link #looksLikeCacheRejection(String)} recognises the rejection, {@link #disableFor} latches
 * it off for the rest of the process, and the caller retries once without markers. The user sees one
 * warning instead of a dead tool.</p>
 *
 * <h2>Why the latch names a model and not just a provider</h2>
 *
 * <p>Because the rejection does. Bedrock refuses {@code cachePoint} for the models that have not
 * implemented it and serves it for the ones that have, from the same account and the same endpoint,
 * and the Anthropic wire is the same story one vendor down. Latched by provider, one request to one
 * unsupported model turned caching off for every model that provider serves, for the rest of the
 * process -- silently, and in the direction that costs money on every subsequent turn of a long
 * run. The pair is what was refused, so the pair is what is remembered.</p>
 *
 * <p>Everything can also be turned off up front with {@code -Dcadet.cache.breakpoints=false}.</p>
 */
public final class PromptCachePolicy {

    /** System property that disables breakpoints entirely. */
    public static final String ENABLED_PROPERTY = "cadet.cache.breakpoints";

    /**
     * Smallest prompt worth marking, in characters.
     *
     * <p>Providers impose a minimum cacheable prefix -- around 1024 tokens for most Anthropic and
     * Bedrock models, 2048 for the smaller ones. Below that a breakpoint is simply ignored, so this
     * is not a correctness gate; it just keeps short one-shot requests free of a field that could
     * only ever be a no-op. Four characters per token is the same approximation used everywhere else
     * in the codebase, so this is deliberately conservative.</p>
     */
    static final int MIN_CACHEABLE_CHARS = 4096;

    /** Provider and model pairs whose breakpoints were rejected and must not be sent again. */
    private static final Set<String> DISABLED = ConcurrentHashMap.newKeySet();

    /** Substrings that identify a rejection caused by the cache markers rather than by the prompt. */
    private static final String[] REJECTION_MARKERS = {
            "cache_control", "cachepoint", "cache point", "cache_point",
            "prompt caching", "prompt_caching", "caching is not supported"
    };

    private static final CadetLogger LOG = CadetLogger.getLogger(PromptCachePolicy.class);

    private PromptCachePolicy() {
    }

    /**
     * Whether this request should carry breakpoints.
     *
     * @param providerId  the provider being called
     * @param modelId     the model being asked, which is what support actually depends on
     * @param promptChars the total prompt size in characters
     * @return {@code true} when markers should be emitted
     */
    public static boolean shouldMark(String providerId, String modelId, int promptChars) {
        if (!Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "true"))) {
            return false;
        }
        if (isDisabledFor(providerId, modelId)) {
            return false;
        }
        return promptChars >= MIN_CACHEABLE_CHARS;
    }

    /**
     * Whether a rejection body indicates the cache markers were the problem.
     *
     * <p>Deliberately narrow. A 400 caused by a bad model name or an oversized prompt must NOT be
     * mistaken for a cache rejection, because retrying without markers would waste a second request
     * and hide the real cause.</p>
     *
     * @param responseBody the provider's error body (may be {@code null})
     * @return {@code true} when the message names a cache field
     */
    public static boolean looksLikeCacheRejection(String responseBody) {
        if (responseBody == null || responseBody.isEmpty()) {
            return false;
        }
        String lower = responseBody.toLowerCase(Locale.ROOT);
        for (String marker : REJECTION_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Latches breakpoints off for one model after that model rejected them.
     *
     * @param providerId the provider that rejected the markers
     * @param modelId    the model the rejected request named
     */
    public static void disableFor(String providerId, String modelId) {
        if (providerId == null) {
            return;
        }
        if (DISABLED.add(latchKey(providerId, modelId))) {
            LOG.warn("Prompt-cache breakpoints are not supported by provider '" + providerId
                    + "' for model '" + modelId + "'; continuing without them for that model.");
        }
    }

    /**
     * @param providerId the provider
     * @param modelId    the model
     * @return {@code true} when breakpoints have been latched off for that pair
     */
    public static boolean isDisabledFor(String providerId, String modelId) {
        return providerId != null && DISABLED.contains(latchKey(providerId, modelId));
    }

    /**
     * The key one provider-and-model pair is remembered under.
     *
     * <p>Case-folded because a provider id reaches this from configuration and a model id from
     * whatever the user typed, and {@code Claude-Sonnet-4-5} is not a second model.</p>
     *
     * @param providerId the provider, never {@code null} here
     * @param modelId    the model; {@code null} is a pair of its own rather than a wildcard
     * @return the latch key
     */
    private static String latchKey(String providerId, String modelId) {
        return (providerId + "/" + modelId).toLowerCase(Locale.ROOT);
    }

    /** Clears the latch. Intended for tests. */
    static void resetForTesting() {
        DISABLED.clear();
    }
}
