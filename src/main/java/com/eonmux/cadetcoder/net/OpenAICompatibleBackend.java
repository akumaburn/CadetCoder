package com.eonmux.cadetcoder.net;

import com.eonmux.cadetcoder.ai.metrics.ReportedUsage;
import com.eonmux.cadetcoder.ai.metrics.TokenUsage;
import com.eonmux.cadetcoder.ai.PromptData;
import com.eonmux.cadetcoder.ai.providers.AuthHeaders;
import com.eonmux.cadetcoder.ai.providers.AuthScheme;
import com.eonmux.cadetcoder.logging.CadetLogger;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Generic OpenAI Chat Completions backend, parameterized by base URL, auth scheme, and
 * extra headers/query params. Covers the large majority of opencode connectors that speak
 * the OpenAI protocol: OpenAI, OpenRouter, xAI, Groq, DeepSeek, Cerebras, Together,
 * Fireworks, Baseten, DeepInfra, Azure, Cloudflare, GitHub Copilot/Models, and the many
 * "openai-compatible" catalog providers.
 */
public class OpenAICompatibleBackend extends AbstractLLMBackend {

    private static final CadetLogger LOG = CadetLogger.getLogger(OpenAICompatibleBackend.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Where the credential comes from, asked again for every request.
     *
     * <p>Not a {@code String}. A credential that expires during a run is one this tool has: the
     * GitHub Copilot token lives about twenty-five minutes, and the client holding it is cached
     * until the model or the login changes. Frozen at construction, it was still being sent hours
     * later -- so any agent run longer than the token's life began failing with 401s that
     * {@code RetryPolicy} rightly refuses to retry, and nothing in the process could recover.</p>
     *
     * <p>A supplier is the seam that fixes it without every provider paying for it: a fixed key is
     * a supplier that returns the same string, and the one provider whose credential moves hands
     * down a source that refreshes. It is read in {@link #applyAuth} -- the single point where the
     * credential reaches the wire -- so the freshest token is used for each request.</p>
     */
    private volatile java.util.function.Supplier<String> apiKeySource;
    private final AuthScheme          authScheme;
    private final Map<String, String> extraHeaders;
    private final Map<String, String> queryParams;
    private final Map<String, Object> extraBody;

    public OpenAICompatibleBackend(String modelName,
                                   String baseUrl,
                                   String apiKey,
                                   AuthScheme authScheme,
                                   Map<String, String> extraHeaders,
                                   Map<String, String> queryParams) {
        this(modelName, baseUrl, apiKey, authScheme, extraHeaders, queryParams, null);
    }

    /**
     * @param extraBody request-body fields the connector requires on every request
     *
     *                  <p>Where a provider puts its data-retention control in the body rather than
     *                  in a header: OpenRouter reads {@code provider.zdr}, OpenAI reads
     *                  {@code store}. Merged last, so what the connector was configured to require
     *                  wins over the defaults assembled above it -- which is what makes a
     *                  retention setting binding rather than advisory.</p>
     */
    public OpenAICompatibleBackend(String modelName,
                                   String baseUrl,
                                   String apiKey,
                                   AuthScheme authScheme,
                                   Map<String, String> extraHeaders,
                                   Map<String, String> queryParams,
                                   Map<String, Object> extraBody) {
        super(modelName, stripTrailingSlash(baseUrl));
        this.extraBody    = extraBody != null ? Map.copyOf(extraBody) : Map.of();
        this.apiKeySource = () -> apiKey;
        this.authScheme   = authScheme != null ? authScheme : AuthScheme.BEARER;
        this.extraHeaders = extraHeaders != null ? extraHeaders : Map.of();
        this.queryParams  = queryParams != null ? queryParams : Map.of();
        // Available if we have an endpoint and (no auth needed, or a key/credentials present).
        boolean hasEndpoint = apiEndpoint != null && !apiEndpoint.isEmpty();
        boolean authSatisfied = this.authScheme == AuthScheme.NONE
                || (apiKey != null && !apiKey.isEmpty());
        this.available = hasEndpoint && authSatisfied;
    }

    /**
     * The image types the Chat Completions API takes in an {@code image_url} part.
     *
     * <p>Every connector on this wire speaks the Chat Completions API, so the list is the one
     * that API documents. A provider behind it that takes fewer answers for itself; a picture
     * it will not read is caught by the catalogue in {@code ai/ImageChannel}.</p>
     *
     * @return PNG, JPEG, GIF and WebP
     */
    @Override
    public Set<String> imageMediaTypes() {
        return ImageMediaTypes.PNG_JPEG_GIF_WEBP;
    }

    @Override
    public String complete(PromptData promptData, Map<String, Object> parameters) throws Exception {
        float temperature = numberParam(parameters, "temperature", 0.7f).floatValue();
        int   maxTokens   = numberParam(parameters, "maxTokens", OutputBudget.UNLIMITED).intValue();
        int   timeout     = numberParam(parameters, "completionTimeout", 60).intValue();

        String url = appendQuery(apiEndpoint + "/chat/completions");

        // Only the providers that FORWARD an Anthropic-style breakpoint need one in the body. The
        // rest of this protocol family caches a shared prefix automatically, so the correct
        // implementation for them is to send nothing extra.
        boolean cacheBreakpoints = supportsInlineCacheBreakpoints()
                && PromptCachePolicy.shouldMark(providerId(), modelName, promptChars(promptData));

        HttpResponse<String> response =
                send(url, promptData, temperature, maxTokens, timeout, cacheBreakpoints);

        if (response.statusCode() == 400 && cacheBreakpoints
                && PromptCachePolicy.looksLikeCacheRejection(response.body())) {
            PromptCachePolicy.disableFor(providerId(), modelName);
            response = send(url, promptData, temperature, maxTokens, timeout, false);
        }

        if (response.statusCode() != 200) {
            // A failed provider call is not a completion: raise it on the typed failure channel so
            // callers cannot mistake it for the model's answer. Only a short, redacted excerpt of
            // the body reaches the message; the full body stays in the log.
            LLMException failure = LLMErrorMapper.fromResponse(providerId(), modelName, url, response);
            LOG.error("OpenAICompatibleBackend: " + failure.getMessage());
            throw failure;
        }
        return extractContent(response.body(), url);
    }

    /**
     * Builds the Chat Completions request body.
     *
     * <p>Separate from sending it, so what goes on the wire can be asserted without reaching a
     * provider, the way {@code AnthropicBackend} and {@code AmazonBedrockBackend} already are.</p>
     *
     * @param promptData       the prompt
     * @param temperature      asked-for randomness
     * @param maxTokens        the output ceiling, or {@link OutputBudget#UNLIMITED} for none
     * @param cacheBreakpoints whether to mark an inline cache breakpoint
     * @return the request body, ready to serialize
     */
    Map<String, Object> buildBody(PromptData promptData, float temperature, int maxTokens,
                                  boolean cacheBreakpoints) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelName);
        body.put("temperature", temperature);
        // Omitted when no ceiling was asked for. The field is optional on this wire, and left out
        // the provider falls back to what the model can actually produce -- a better number than
        // any this tool could guess. See OutputBudget.
        if (OutputBudget.isLimited(maxTokens)) {
            body.put("max_tokens", maxTokens);
        }
        body.put("messages", buildMessages(promptData, cacheBreakpoints));
        body.putAll(extraBody);
        return body;
    }

    /** Builds and sends one Chat Completions request. */
    private HttpResponse<String> send(String url, PromptData promptData, float temperature,
                                      int maxTokens, int timeout, boolean cacheBreakpoints)
            throws Exception {
        String json = MAPPER.writeValueAsString(
                buildBody(promptData, temperature, maxTokens, cacheBreakpoints));

        HttpRequest.Builder builder = HttpRequests.to(URI.create(url))
                .timeout(Duration.ofSeconds(timeout))
                .header("Content-Type", "application/json");
        applyAuth(builder);
        for (Map.Entry<String, String> h : extraHeaders.entrySet()) {
            builder.header(h.getKey(), h.getValue());
        }
        HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(json)).build();

        LOG.info("Sending request to OpenAI-compatible endpoint: " + url + " (model: " + modelName + ")");
        return sendWithRetry(request);
    }

    /**
     * Whether this provider forwards an inline cache breakpoint.
     *
     * <p>OpenRouter proxies to Anthropic and Gemini models and documents the Anthropic
     * {@code cache_control} spelling inside content parts; for those models a breakpoint is the only
     * way to get a cache hit. Every other provider on this protocol -- OpenAI itself, Groq, DeepSeek,
     * Together, Fireworks, Cerebras and the rest -- caches a shared prefix automatically, and would
     * gain nothing from an extra field while risking a rejection from a stricter endpoint.</p>
     *
     * @return {@code true} when a breakpoint should be considered for this provider
     */
    boolean supportsInlineCacheBreakpoints() {
        return "openrouter".equalsIgnoreCase(providerId());
    }

    /** Total prompt size, used only to skip marking prompts too small to be cacheable. */
    private static int promptChars(PromptData promptData) {
        String system = promptData.getSystemPrompt();
        String user   = promptData.getUserPrompt();
        return (system == null ? 0 : system.length()) + (user == null ? 0 : user.length());
    }

    /**
     * Builds the messages array.
     *
     * <p>Without breakpoints each message carries a plain string, which is the ordinary Chat
     * Completions shape. With breakpoints the content becomes a parts array so a
     * {@code cache_control} marker has somewhere to live -- both forms are valid input, the array is
     * needed only to carry the marker. An image makes it a parts array too; see
     * {@link ChatCompletionMessages}, which is where this protocol's message shape lives.</p>
     */
    List<Map<String, Object>> buildMessages(PromptData promptData, boolean cacheBreakpoints) {
        return ChatCompletionMessages.of(promptData, cacheBreakpoints);
    }

    private String extractContent(String responseBody, String url) {
        try {
            @SuppressWarnings ("unchecked")
            Map<String, Object> result = MAPPER.readValue(responseBody, Map.class);
            // The provider's own token counts, so what a session cost is reported rather
            // than estimated. Absent for providers that report none; see TokenUsage.
            ReportedUsage.report(TokenUsage.from(result).orElse(null));
            if (ChatCompletionContent.hasChoices(result)) {
                // Every shape this protocol puts an answer in, read in one place: see
                // ChatCompletionContent for the ones that are not a plain string.
                return ChatCompletionContent.firstChoiceText(result)
                        .orElseThrow(() -> new LLMProtocolException(
                                providerId(), modelName, url,
                                ChatCompletionContent.absenceDetail(result)));
            }
            throw new LLMProtocolException(providerId(), modelName, url,
                                           "no completion choices returned");
        } catch (LLMException e) {
            throw e;
        } catch (Exception e) {
            LOG.error("OpenAICompatibleBackend: failed to parse response", e);
            throw new LLMProtocolException(providerId(), modelName, url,
                                           "failed to parse response: " + e.getMessage(), e);
        }
    }

    /**
     * Hands down a credential that has to be re-read rather than remembered.
     *
     * <p>Shaped like {@link AbstractLLMBackend#setProviderId}: what the connector knows and the
     * backend does not, told to it after construction rather than threaded through every
     * {@code createBackend} signature for the sake of one provider.</p>
     *
     * @param source where to get the credential for each request; ignored when {@code null}
     */
    public void setApiKeySource(java.util.function.Supplier<String> source) {
        if (source != null) {
            this.apiKeySource = source;
        }
    }

    private void applyAuth(HttpRequest.Builder builder) {
        AuthHeaders.apply(builder, authScheme, apiKeySource.get());
    }

    private String appendQuery(String url) {
        if (queryParams.isEmpty()) {
            return url;
        }
        StringBuilder sb = new StringBuilder(url);
        boolean first = !url.contains("?");
        for (Map.Entry<String, String> e : queryParams.entrySet()) {
            sb.append(first ? '?' : '&');
            sb.append(java.net.URLEncoder.encode(e.getKey(), java.nio.charset.StandardCharsets.UTF_8));
            sb.append('=');
            sb.append(java.net.URLEncoder.encode(e.getValue(), java.nio.charset.StandardCharsets.UTF_8));
            first = false;
        }
        return sb.toString();
    }

    static Number numberParam(Map<String, Object> params, String key, Number def) {
        if (params != null && params.get(key) instanceof Number n) {
            return n;
        }
        return def;
    }

    private static String stripTrailingSlash(String url) {
        if (url == null) {
            return null;
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
