package com.eonmux.cadetcoder.net;

import com.eonmux.cadetcoder.ai.parsing.toolcalls.ToolCalls;
import com.eonmux.cadetcoder.ai.metrics.ReportedUsage;
import com.eonmux.cadetcoder.ai.metrics.TokenUsage;
import com.eonmux.cadetcoder.ai.PromptData;
import com.eonmux.cadetcoder.ai.PromptImage;
import com.eonmux.cadetcoder.ai.providers.AuthHeaders;
import com.eonmux.cadetcoder.ai.providers.AuthScheme;
import com.eonmux.cadetcoder.logging.CadetLogger;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Anthropic Messages API backend ({@code POST {base}/messages}). Authenticates with the
 * {@code x-api-key} header plus a pinned {@code anthropic-version}, mirroring opencode's
 * anthropic connector. The system prompt is sent in the top-level {@code system} field.
 */
public class AnthropicBackend extends AbstractLLMBackend {

    private static final CadetLogger LOG = CadetLogger.getLogger(AnthropicBackend.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    /**
     * The connector this backend serves when no other is named.
     *
     * <p>Also the vendor whose catalogue entry describes the model whatever connector is in front of
     * it, which is what {@link com.eonmux.cadetcoder.ai.OutputWindow#forRequiredCeiling} is given so
     * that a gateway reselling Claude is not treated as a vendor nobody has published anything
     * about.</p>
     */
    static final String DEFAULT_PROVIDER_ID = "anthropic";

    private final String apiKey;

    /**
     * How the credential is carried.
     *
     * <p>{@code x-api-key} is Anthropic's own scheme and stays the default, but this wire is not
     * only Anthropic's: a gateway can speak the Messages shape for the Claude models it resells
     * while authenticating the way the rest of its API does. Command Code declares
     * {@link AuthScheme#BEARER} and routes {@code claude-*} here, so the hardcoded header sent that
     * gateway a credential under a name it does not read -- for those models only, while the rest
     * of the same connector worked. The connector already knows the answer; it just was not asked
     * on this wire.</p>
     */
    private final AuthScheme authScheme;

    /** Extra headers the connector requires on every request; empty for the Anthropic API itself. */
    private final Map<String, String> extraHeaders;

    /** Extra body fields the connector requires on every request; empty for Anthropic itself. */
    private final Map<String, Object> extraBody;

    public AnthropicBackend(String modelName, String baseUrl, String apiKey) {
        this(modelName, baseUrl, apiKey, DEFAULT_PROVIDER_ID, null, null);
    }

    public AnthropicBackend(String modelName, String baseUrl, String apiKey, String providerId) {
        this(modelName, baseUrl, apiKey, providerId, null, null);
    }

    public AnthropicBackend(String modelName, String baseUrl, String apiKey, String providerId,
                            Map<String, String> extraHeaders) {
        this(modelName, baseUrl, apiKey, providerId, extraHeaders, null);
    }

    /**
     * @param providerId   the connector this backend is serving
     *
     *                     <p>Not always {@code anthropic}. A gateway can require the Anthropic
     *                     Messages shape for the Anthropic models it resells while being a different
     *                     provider entirely, and the id is what every failure message names and what
     *                     the prompt cache policy is keyed on. Fixed at {@code anthropic}, a gateway
     *                     rejecting a model told the user to check the API key of a provider they
     *                     may never have configured, and a cache rejection from the gateway latched
     *                     the real Anthropic connector into sending no cache breakpoints.</p>
     * @param extraHeaders headers the connector requires on every request
     *
     *                     <p>A gateway can require them -- Command Code reads {@code x-cmd-zdr} to
     *                     decide whether it may retain the request. They were accepted by the
     *                     OpenAI-compatible backend and silently dropped here, so a header meant to
     *                     hold a provider to a promise was simply not sent on this wire, and nothing
     *                     said so.</p>
     * @param extraBody    body fields the connector requires on every request
     *
     *                     <p>Carried for the same reason as the headers. A provider that reads its
     *                     data-retention control from the body rather than a header would otherwise
     *                     have that control apply to whichever of its models happened to answer on
     *                     the other wire, which is the defect above wearing different clothes.
     *                     Merged after the fields this backend builds, so a connector cannot
     *                     redefine the model, the messages or the sampling settings through it.</p>
     */
    public AnthropicBackend(String modelName, String baseUrl, String apiKey, String providerId,
                            Map<String, String> extraHeaders, Map<String, Object> extraBody) {
        this(modelName, baseUrl, apiKey, providerId, extraHeaders, extraBody, AuthScheme.X_API_KEY);
    }

    /**
     * @param authScheme how the connector carries its credential; {@link AuthScheme#X_API_KEY} is
     *                   Anthropic's own and the default for every other constructor here
     */
    public AnthropicBackend(String modelName, String baseUrl, String apiKey, String providerId,
                            Map<String, String> extraHeaders, Map<String, Object> extraBody,
                            AuthScheme authScheme) {
        super(modelName, normalize(baseUrl));
        this.apiKey       = apiKey;
        this.authScheme   = authScheme != null ? authScheme : AuthScheme.X_API_KEY;
        this.extraHeaders = extraHeaders != null ? Map.copyOf(extraHeaders) : Map.of();
        this.extraBody    = extraBody    != null ? Map.copyOf(extraBody)    : Map.of();
        this.available    = apiEndpoint != null && !apiEndpoint.isEmpty()
                && apiKey != null && !apiKey.isEmpty();
        setProviderId(providerId != null && !providerId.isBlank() ? providerId : DEFAULT_PROVIDER_ID);
    }

    /**
     * The image types the Messages API takes in an {@code image} block.
     *
     * <p>The four Anthropic documents for vision. An animation is read as its first frame.</p>
     *
     * @return PNG, JPEG, GIF and WebP
     */
    @Override
    public Set<String> imageMediaTypes() {
        return ImageMediaTypes.PNG_JPEG_GIF_WEBP;
    }

    @Override
    public String complete(PromptData promptData, Map<String, Object> parameters) throws Exception {
        float temperature = OpenAICompatibleBackend.numberParam(parameters, "temperature", 0.7f).floatValue();
        int   maxTokens   = OpenAICompatibleBackend.numberParam(parameters, "maxTokens",
                                                          OutputBudget.UNLIMITED).intValue();
        int   timeout     = OpenAICompatibleBackend.numberParam(parameters, "completionTimeout", 60).intValue();

        String system = promptData.getSystemPrompt();
        String user   = promptData.getUserPrompt() != null ? promptData.getUserPrompt() : "";
        int    promptChars = (system == null ? 0 : system.length()) + user.length();
        List<PromptImage> images = promptData.getImages();

        boolean cacheable = PromptCachePolicy.shouldMark(providerId(), modelName, promptChars);
        HttpResponse<String> response =
                send(system, user, images, temperature, maxTokens, timeout, cacheable);

        // A model that does not support cache breakpoints rejects the request outright. Caching is an
        // optimisation, never a requirement, so drop the markers and try once more rather than
        // failing a request that would otherwise have worked.
        if (response.statusCode() == 400 && cacheable
                && PromptCachePolicy.looksLikeCacheRejection(response.body())) {
            PromptCachePolicy.disableFor(providerId(), modelName);
            response = send(system, user, images, temperature, maxTokens, timeout, false);
        }

        if (response.statusCode() != 200) {
            // Typed failure instead of completion text: see OpenAICompatibleBackend for the rationale.
            LLMException failure = LLMErrorMapper.fromResponse(
                    providerId(), modelName, apiEndpoint + "/messages", response);
            LOG.error("AnthropicBackend: " + failure.getMessage());
            throw failure;
        }
        return extractContent(response.body());
    }

    /**
     * Builds and sends one Messages request.
     *
     * @param cacheBreakpoints when {@code true}, the system block and the user block each carry
     *                         {@code cache_control: {"type":"ephemeral"}}
     */
    private HttpResponse<String> send(String system, String user, List<PromptImage> images,
                                      float temperature, int maxTokens,
                                      int timeout, boolean cacheBreakpoints) throws Exception {
        String json = MAPPER.writeValueAsString(
                buildBody(system, user, images, temperature, maxTokens, cacheBreakpoints));
        HttpRequest.Builder builder = HttpRequests.to(URI.create(apiEndpoint + "/messages"))
                .timeout(Duration.ofSeconds(timeout))
                .header("Content-Type", "application/json")
                .header("anthropic-version", ANTHROPIC_VERSION);
        AuthHeaders.apply(builder, authScheme, apiKey);
        for (Map.Entry<String, String> header : extraHeaders.entrySet()) {
            builder.header(header.getKey(), header.getValue());
        }
        HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(json)).build();

        LOG.info("Sending request to Anthropic Messages API (model: " + modelName + ")");
        return sendWithRetry(request);
    }

    /**
     * Builds the Messages request body.
     *
     * <p>With {@code cacheBreakpoints}, {@code system} and the user message are written as content
     * BLOCK ARRAYS rather than plain strings so each can carry a {@code cache_control} marker. Both
     * spellings are valid Anthropic input; the array form is required only because a marker has
     * nowhere to live on a bare string.</p>
     *
     * <p>Two breakpoints, well inside Anthropic's limit of four. The system block is the large stable
     * prefix that never changes within a run. The user block is the end of the conversation so far:
     * marking it writes the whole prompt into the cache, so the NEXT turn -- which appends to it --
     * reads everything sent this turn from cache and pays only for what it added. That is the moving
     * breakpoint pattern, and it is why the append-only prompt in {@code IterativeExecutor} matters.</p>
     *
     * @return the request body, ready to serialize
     */
    Map<String, Object> buildBody(String system, String user, float temperature, int maxTokens,
                                  boolean cacheBreakpoints) {
        return buildBody(system, user, List.of(), temperature, maxTokens, cacheBreakpoints);
    }

    /**
     * Builds the Messages request body, images included.
     *
     * <p>An image makes the user content a block array whether or not there are cache breakpoints,
     * because a block is the only place an image can go. The image blocks come first: Anthropic's
     * own guidance is that a model reads an image better when the question about it follows.</p>
     *
     * <h2>A picture with nothing typed after it</h2>
     *
     * <p>Dropping a screenshot on an empty prompt line is a request in its own right, and this API
     * rejects a text block whose text is empty -- so the one request made entirely of pictures was
     * the one request that could not be sent. The block is omitted instead, and when there are
     * breakpoints to place the last image carries the marker, which is a place Anthropic accepts
     * {@code cache_control} and which keeps the pictures inside the cached prefix rather than
     * leaving the most expensive part of the request outside it.</p>
     *
     * @param images what the request carries besides its text; empty for a text request
     */
    Map<String, Object> buildBody(String system, String user, List<PromptImage> images,
                                  float temperature, int maxTokens, boolean cacheBreakpoints) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelName);
        // Required by Anthropic, so "no limit" cannot be expressed here and a number is chosen for
        // the caller. The model's own published ceiling is that number: it is the most the model
        // will produce anyway, and anything above it is refused with a 400. Asked for with the
        // vendor named, because this wire is not only Anthropic's and a reselling gateway is not in
        // the catalog under its own id -- see OutputWindow.forRequiredCeiling.
        body.put("max_tokens", OutputBudget.isLimited(maxTokens)
                ? maxTokens
                : com.eonmux.cadetcoder.ai.OutputWindow.forRequiredCeiling(
                        providerId(), modelName, DEFAULT_PROVIDER_ID));
        body.put("temperature", temperature);

        if (system != null && !system.isEmpty()) {
            body.put("system", cacheBreakpoints
                    ? List.of(textBlock(system, true))
                    : system);
        }

        Map<String, Object> userMsg = new LinkedHashMap<>();
        userMsg.put("role", "user");
        if (images == null || images.isEmpty()) {
            userMsg.put("content", cacheBreakpoints ? List.of(textBlock(user, true)) : user);
        } else {
            boolean hasText = user != null && !user.isEmpty();
            List<Map<String, Object>> content = new ArrayList<>();
            for (PromptImage image : images) {
                content.add(imageBlock(image));
            }
            if (hasText) {
                content.add(textBlock(user, cacheBreakpoints));
            } else if (cacheBreakpoints) {
                content.set(content.size() - 1, marked(content.get(content.size() - 1)));
            }
            userMsg.put("content", content);
        }
        body.put("messages", List.of(userMsg));
        body.putAll(extraBody);
        return body;
    }

    /**
     * An image content block.
     *
     * <p>Base64 rather than a URL: the file is on this machine, and the {@code url} source would
     * have Anthropic fetch something it cannot reach.</p>
     */
    private static Map<String, Object> imageBlock(PromptImage image) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("type", "base64");
        source.put("media_type", image.mediaType());
        source.put("data", image.base64());
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "image");
        block.put("source", source);
        return block;
    }

    /**
     * The same block with an ephemeral cache breakpoint on it.
     *
     * <p>For the request that carries pictures and no text, where there is no text block for the
     * marker to sit on and the images are what the breakpoint has to cover.</p>
     *
     * @param block a content block
     * @return a copy of it carrying {@code cache_control}
     */
    private static Map<String, Object> marked(Map<String, Object> block) {
        Map<String, Object> copy = new LinkedHashMap<>(block);
        copy.put("cache_control", Map.of("type", "ephemeral"));
        return copy;
    }

    /** A text content block, optionally carrying an ephemeral cache breakpoint. */
    private static Map<String, Object> textBlock(String text, boolean cacheBreakpoint) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "text");
        block.put("text", text);
        if (cacheBreakpoint) {
            block.put("cache_control", Map.of("type", "ephemeral"));
        }
        return block;
    }

    private String extractContent(String responseBody) {
        try {
            @SuppressWarnings ("unchecked")
            Map<String, Object> result = MAPPER.readValue(responseBody, Map.class);
            // The provider's own token counts, so what a session cost is reported rather
            // than estimated. Absent for providers that report none; see TokenUsage.
            ReportedUsage.report(TokenUsage.from(result).orElse(null));
            Object contentObj = result.get("content");
            if (contentObj instanceof List<?> blocks && !blocks.isEmpty()) {
                String text = blockText(blocks, "text");
                if (!text.isEmpty()) {
                    return text;
                }
                // A reply of thinking blocks alone, which extended thinking produces when it spends
                // the output budget before it writes an answer. Handing the thinking back reads as
                // a reply in the wrong shape, which the loop already knows how to answer; failing
                // the request instead ends the run. Same call as ChatCompletionContent makes for
                // reasoning_content on the other protocol.
                String thinking = blockText(blocks, "thinking");
                if (!thinking.isEmpty()) {
                    return thinking;
                }
                // A tool_use block is an answer, not an absence: it names the command and its
                // arguments as plainly as any reply, and this API returns one whenever the model
                // reads the command catalogue in its prompt as a list of tools. Failing here ended
                // the run over a reply that said exactly what to do. Handed on as text, so the
                // parsing engine sees it the way it sees a call written in the reply itself.
                String toolCalls = ToolCalls.textFor(blocks);
                if (!toolCalls.isEmpty()) {
                    return toolCalls;
                }
                throw new LLMProtocolException(providerId(), modelName, apiEndpoint,
                                               absenceDetail(result, blocks));
            }
            throw new LLMProtocolException(providerId(), modelName, apiEndpoint,
                                           "no completion content returned");
        } catch (LLMException e) {
            throw e;
        } catch (Exception e) {
            LOG.error("AnthropicBackend: failed to parse response", e);
            throw new LLMProtocolException(providerId(), modelName, apiEndpoint,
                                           "failed to parse response: " + e.getMessage(), e);
        }
    }

    /**
     * Joins the text of every block of one type.
     *
     * @param blocks the reply's content blocks
     * @param type   the block type to read, {@code text} or {@code thinking}
     * @return the joined text, empty when no block of that type carries any
     */
    private static String blockText(List<?> blocks, String type) {
        StringBuilder joined = new StringBuilder();
        for (Object block : blocks) {
            if (block instanceof Map<?, ?> b && type.equals(b.get("type"))) {
                Object text = b.get(type);
                if (text instanceof String s) {
                    joined.append(s);
                }
            }
        }
        return joined.toString();
    }

    /**
     * Why a reply with blocks carries no readable text.
     *
     * <p>Named rather than reported as "no content", because an exhausted output budget and a
     * refusal want different actions from whoever reads the message.</p>
     *
     * @param result the parsed reply
     * @param blocks its content blocks
     * @return a detail string for the protocol error
     */
    private static String absenceDetail(Map<String, Object> result, List<?> blocks) {
        Object stopReason = result.get("stop_reason");
        if ("max_tokens".equals(stopReason)) {
            // Not "raise ai.maxTokens" unconditionally: this API requires the field, so when no
            // ceiling was asked for the number sent is already the model's own published limit, and
            // asking above that is a 400. Raising the setting only helps when it is what capped the
            // reply.
            return "no content found in message: the model reached its output limit before it wrote"
                   + " any (stop_reason: max_tokens). This API always receives a limit. Raise"
                   + " ai.maxTokens if you set one; otherwise the model's own ceiling was reached"
                   + " and the request has to be made smaller";
        }
        StringBuilder types = new StringBuilder();
        for (Object block : blocks) {
            if (block instanceof Map<?, ?> b && b.get("type") instanceof String t) {
                types.append(types.length() == 0 ? "" : ", ").append(t);
            }
        }
        StringBuilder detail = new StringBuilder("no content found in message");
        if (types.length() > 0) {
            detail.append(": the reply carried ").append(types).append(" blocks and no text");
        }
        if (stopReason instanceof String reason && !reason.isBlank()) {
            detail.append(" (stop_reason: ").append(reason).append(')');
        }
        return detail.toString();
    }


    private static String normalize(String baseUrl) {
        String url = (baseUrl == null || baseUrl.isBlank()) ? "https://api.anthropic.com/v1" : baseUrl;
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
