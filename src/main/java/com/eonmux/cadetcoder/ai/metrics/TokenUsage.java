package com.eonmux.cadetcoder.ai.metrics;

import java.util.Map;
import java.util.Optional;

/**
 * The token counts a provider reported for one request.
 *
 * <h2>Why this is worth parsing</h2>
 *
 * <p>Every provider returns exact usage in the same JSON body the completion is read from, and until
 * this existed none of it was read: every token figure the tool showed came from
 * {@link TokenEstimator}'s four-characters-per-token approximation. That is fine for comparing two
 * prompts and wrong for everything else it was being used for — what a session cost, when to compact
 * a transcript, how much history a resume can afford. Those are decisions, and they were being made
 * on a guess while the real number sat unread two keys away.</p>
 *
 * <h2>Why each provider needs its own arithmetic</h2>
 *
 * <p>The three wire formats do not merely spell the fields differently, they mean different things by
 * them, and treating them alike would report cache hits that never happened:</p>
 *
 * <ul>
 *   <li><b>OpenAI</b> — {@code prompt_tokens} is the whole input, and
 *       {@code prompt_tokens_details.cached_tokens} is the part of it served from cache. The new
 *       portion is the difference.</li>
 *   <li><b>Anthropic</b> — {@code input_tokens} <i>excludes</i> both cache figures, so the total is
 *       the sum of all three. Cache <i>creation</i> counts as new: it is content being written to the
 *       cache for the first time, billed as fresh input, and calling it cached would claim a saving
 *       that was not made.</li>
 *   <li><b>Gemini</b> — {@code promptTokenCount} includes {@code cachedContentTokenCount}, like
 *       OpenAI rather than like Anthropic.</li>
 *   <li><b>Bedrock Converse</b> — the same fields as Anthropic in camel case, and counted the same
 *       way, because for the Claude models this backend targets it is Anthropic's accounting behind
 *       a different spelling.</li>
 * </ul>
 *
 * <p>Immutable. A usage block that is absent or unrecognisable yields an empty {@link Optional}
 * rather than zeros, so a provider that reports nothing falls back to estimates instead of claiming
 * a request cost nothing.</p>
 */
public record TokenUsage(int cachedInputTokens, int newInputTokens, int outputTokens) {

    public TokenUsage {
        cachedInputTokens = Math.max(0, cachedInputTokens);
        newInputTokens    = Math.max(0, newInputTokens);
        outputTokens      = Math.max(0, outputTokens);
    }

    /** @return total input tokens, cached and new */
    public int inputTokens() {
        return cachedInputTokens + newInputTokens;
    }

    /**
     * What two attempts at the same request cost together.
     *
     * <h2>Why attempts add up</h2>
     *
     * <p>A provider bills what it served, not what the caller kept. An attempt that reached the
     * model and came back in a shape this tool rejected -- a truncated reply, a legacy
     * {@code "Error: ..."} sentinel, an empty completion -- was paid for exactly like the attempt
     * that followed it, so the session's cost is the sum. Taking only the last attempt's figures
     * understated every retried request, and it understated them by the most on the requests that
     * cost the most, which are the ones that needed retrying.</p>
     *
     * @param other what another attempt at the same request reported; {@code null} for none
     * @return the combined usage
     */
    public TokenUsage plus(TokenUsage other) {
        return other == null ? this : new TokenUsage(cachedInputTokens + other.cachedInputTokens,
                                                     newInputTokens + other.newInputTokens,
                                                     outputTokens + other.outputTokens);
    }

    /**
     * Reads whichever usage block the parsed response body carries.
     *
     * @param body the response body as parsed by the backend; {@code null} is tolerated
     * @return the reported usage, or empty when the body carries none
     */
    public static Optional<TokenUsage> from(Map<String, Object> body) {
        if (body == null) {
            return Optional.empty();
        }
        Map<String, Object> gemini = mapAt(body, "usageMetadata");
        if (gemini != null) {
            return fromGemini(gemini);
        }
        Map<String, Object> usage = mapAt(body, "usage");
        if (usage == null) {
            return Optional.empty();
        }
        if (usage.containsKey("input_tokens") || usage.containsKey("output_tokens")) {
            return fromAnthropic(usage);
        }
        if (usage.containsKey("prompt_tokens") || usage.containsKey("completion_tokens")) {
            return fromOpenAI(usage);
        }
        if (usage.containsKey("inputTokens") || usage.containsKey("outputTokens")) {
            return fromBedrock(usage);
        }
        return Optional.empty();
    }

    /** {@code prompt_tokens} is the whole input; {@code cached_tokens} is the part of it from cache. */
    private static Optional<TokenUsage> fromOpenAI(Map<String, Object> usage) {
        int input  = intAt(usage, "prompt_tokens");
        int output = intAt(usage, "completion_tokens");
        Map<String, Object> details = mapAt(usage, "prompt_tokens_details");
        int cached = details == null ? 0 : intAt(details, "cached_tokens");
        cached = Math.min(cached, input);
        return present(cached, input - cached, output);
    }

    /**
     * {@code input_tokens} excludes both cache figures, so the input is the sum of all three.
     *
     * <p>Cache creation is counted as new rather than cached: those tokens are being written to the
     * cache, not read from it, and are billed as fresh input.</p>
     */
    private static Optional<TokenUsage> fromAnthropic(Map<String, Object> usage) {
        int fresh    = intAt(usage, "input_tokens");
        int output   = intAt(usage, "output_tokens");
        int cacheRead = intAt(usage, "cache_read_input_tokens");
        int cacheWrite = intAt(usage, "cache_creation_input_tokens");
        return present(cacheRead, fresh + cacheWrite, output);
    }

    /** Bedrock's Converse API: Anthropic's accounting, spelled in camel case. */
    private static Optional<TokenUsage> fromBedrock(Map<String, Object> usage) {
        int fresh      = intAt(usage, "inputTokens");
        int output     = intAt(usage, "outputTokens");
        int cacheRead  = intAt(usage, "cacheReadInputTokens");
        int cacheWrite = intAt(usage, "cacheWriteInputTokens");
        return present(cacheRead, fresh + cacheWrite, output);
    }

    /** {@code promptTokenCount} includes the cached count, as OpenAI does. */
    private static Optional<TokenUsage> fromGemini(Map<String, Object> usage) {
        int input  = intAt(usage, "promptTokenCount");
        int output = intAt(usage, "candidatesTokenCount");
        int cached = Math.min(intAt(usage, "cachedContentTokenCount"), input);
        return present(cached, input - cached, output);
    }

    /**
     * A usage block whose every figure is zero or missing is treated as absent.
     *
     * <p>A provider that sends {@code "usage": {}} — or a field set this parser does not recognise —
     * has told us nothing, and reporting that as a request costing zero tokens would be worse than
     * falling back to an estimate that is at least the right order of magnitude.
     */
    private static Optional<TokenUsage> present(int cached, int fresh, int output) {
        if (cached <= 0 && fresh <= 0 && output <= 0) {
            return Optional.empty();
        }
        return Optional.of(new TokenUsage(cached, fresh, output));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapAt(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : null;
    }

    /** Jackson gives an integral JSON number as Integer or Long depending on magnitude. */
    private static int intAt(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        if (value instanceof Number number) {
            long asLong = number.longValue();
            return asLong < 0 ? 0 : (int) Math.min(Integer.MAX_VALUE, asLong);
        }
        return 0;
    }
}
