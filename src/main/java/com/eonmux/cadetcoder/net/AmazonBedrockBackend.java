package com.eonmux.cadetcoder.net;

import com.eonmux.cadetcoder.ai.parsing.toolcalls.ToolCalls;
import com.eonmux.cadetcoder.ai.metrics.ReportedUsage;
import com.eonmux.cadetcoder.ai.metrics.TokenUsage;
import com.eonmux.cadetcoder.ai.PromptData;
import com.eonmux.cadetcoder.ai.PromptImage;
import com.eonmux.cadetcoder.auth.AwsSigV4Signer;
import com.eonmux.cadetcoder.logging.CadetLogger;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Amazon Bedrock Converse API backend
 * ({@code POST https://bedrock-runtime.{region}.amazonaws.com/model/{modelId}/converse}).
 * Authenticates with a bearer API key when provided, otherwise with AWS Signature V4 using
 * the supplied credentials (mirroring opencode's amazon-bedrock connector).
 */
public class AmazonBedrockBackend extends AbstractLLMBackend {

    private static final CadetLogger LOG = CadetLogger.getLogger(AmazonBedrockBackend.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SERVICE = "bedrock";
    /** Connector id this backend serves; used in failure messages. */
    private static final String PROVIDER_ID = "amazon-bedrock";
    /** Region used when none is configured, and the one the public endpoint is derived from. */
    private static final String DEFAULT_REGION = "us-east-1";

    /** The base detail, worded as the other backends word theirs. */
    private static final String NO_CONTENT = "no content found in message";

    /** What Converse calls a turn that ran out of output budget. */
    private static final String MAX_TOKENS_STOP = "max_tokens";

    private final String                   region;
    private final String                   bearerApiKey; // nullable
    private final AwsSigV4Signer.Credentials credentials; // nullable

    /**
     * @param modelName    the Bedrock model id
     * @param baseUrl      where to send requests; blank means the public endpoint for the region
     * @param region       the AWS region, which the signature is computed for; blank means
     *                     {@value #DEFAULT_REGION}
     * @param bearerApiKey a Bedrock API key, or null to sign with {@code credentials}
     * @param credentials  AWS credentials to sign with, or null when a bearer key is supplied
     */
    public AmazonBedrockBackend(String modelName,
                                String baseUrl,
                                String region,
                                String bearerApiKey,
                                AwsSigV4Signer.Credentials credentials) {
        super(modelName, endpointFor(baseUrl, region));
        this.region       = regionOrDefault(region);
        this.bearerApiKey = bearerApiKey;
        this.credentials  = credentials;
        this.available    = (bearerApiKey != null && !bearerApiKey.isEmpty()) || credentials != null;
        setProviderId(PROVIDER_ID);
    }

    @Override
    public String complete(PromptData promptData, Map<String, Object> parameters) throws Exception {
        float temperature = OpenAICompatibleBackend.numberParam(parameters, "temperature", 0.7f).floatValue();
        int   maxTokens   = OpenAICompatibleBackend.numberParam(parameters, "maxTokens",
                                                          OutputBudget.UNLIMITED).intValue();
        int   timeout     = OpenAICompatibleBackend.numberParam(parameters, "completionTimeout", 60).intValue();

        String system = promptData.getSystemPrompt();
        String user   = promptData.getUserPrompt() != null ? promptData.getUserPrompt() : "";
        boolean cacheBreakpoints = PromptCachePolicy.shouldMark(PROVIDER_ID, modelName,
                (system == null ? 0 : system.length()) + user.length());

        List<PromptImage> images = promptData.getImages();

        URI uri = converseUri();
        HttpResponse<String> response =
                send(uri, system, user, images, temperature, maxTokens, timeout, cacheBreakpoints);

        // cachePoint support is per model. When Converse rejects it, drop the markers and try once
        // more: caching is an optimisation and must never be the reason a request fails.
        if (response.statusCode() == 400 && cacheBreakpoints
                && PromptCachePolicy.looksLikeCacheRejection(response.body())) {
            PromptCachePolicy.disableFor(PROVIDER_ID, modelName);
            response = send(uri, system, user, images, temperature, maxTokens, timeout, false);
        }

        if (response.statusCode() != 200) {
            // Typed failure instead of completion text: see OpenAICompatibleBackend for the rationale.
            LLMException failure = LLMErrorMapper.fromResponse(
                    PROVIDER_ID, modelName, uri.toString(), response);
            LOG.error("AmazonBedrockBackend: " + failure.getMessage());
            throw failure;
        }
        return extractContent(response.body());
    }

    /**
     * Where Converse requests go: the configured base URL, or the region's public endpoint.
     *
     * <p>A private VPC endpoint, a gateway or a test double is reached by setting {@code baseURL}
     * for this provider -- the same option every other connector honours. It was accepted, saved
     * and reported as set, and then discarded here, so the request went to the public AWS host
     * regardless. The region is still taken separately because it is what the signature is
     * computed over, and a custom host does not tell you which region it fronts.</p>
     */
    private static String endpointFor(String baseUrl, String region) {
        if (baseUrl != null && !baseUrl.isBlank()) {
            String trimmed = baseUrl.trim();
            return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
        }
        return "https://bedrock-runtime." + regionOrDefault(region) + ".amazonaws.com";
    }

    private static String regionOrDefault(String region) {
        return region != null && !region.isBlank() ? region : DEFAULT_REGION;
    }

    /** The Converse endpoint for the configured model. */
    private URI converseUri() {
        String path = "/model/" + java.net.URLEncoder.encode(modelName, StandardCharsets.UTF_8)
                .replace("+", "%20") + "/converse";
        return URI.create(apiEndpoint + path);
    }

    /**
     * Builds, signs and sends one Converse request.
     *
     * <p>The signature covers the payload, so a retry without cache markers must rebuild AND re-sign
     * rather than reuse the previous request.</p>
     */
    private HttpResponse<String> send(URI uri, String system, String user, List<PromptImage> images,
                                      float temperature, int maxTokens, int timeout,
                                      boolean cacheBreakpoints)
            throws Exception {
        byte[] payload = MAPPER.writeValueAsBytes(
                buildBody(system, user, images, temperature, maxTokens, cacheBreakpoints));

        if (bearerApiKey == null || bearerApiKey.isEmpty()) {
            if (credentials == null) {
                // No credentials at all is an authentication failure, not a completion.
                throw new LLMAuthException(PROVIDER_ID, modelName, apiEndpoint,
                                           "set a bearer key or AWS credentials");
            }
        }

        LOG.info("Sending request to Amazon Bedrock Converse (model: " + modelName + ", region: " + region + ")");
        return sendWithRetry(() -> signedRequest(uri, payload, timeout));
    }

    /**
     * One request, signed as of now.
     *
     * <p>Built per attempt rather than once: the signature covers the instant it was made, and AWS
     * refuses one more than a few minutes old with a 403 that no amount of retrying resolves. The
     * payload does not change between attempts, so only the signing is redone.</p>
     *
     * @param uri     the Converse endpoint
     * @param payload the serialized body, unchanged across attempts
     * @param timeout the per-attempt timeout in seconds
     * @return a request ready to send
     */
    private HttpRequest signedRequest(URI uri, byte[] payload, int timeout) {
        HttpRequest.Builder builder = HttpRequests.to(uri)
                .timeout(Duration.ofSeconds(timeout))
                .header("Content-Type", "application/json");

        if (bearerApiKey != null && !bearerApiKey.isEmpty()) {
            builder.header("Authorization", "Bearer " + bearerApiKey);
        } else {
            Map<String, String> base = new TreeMap<>();
            base.put("content-type", "application/json");
            Map<String, String> signed = AwsSigV4Signer.signedHeaders(
                    "POST", uri, base, payload, region, SERVICE, credentials, Instant.now());
            for (Map.Entry<String, String> h : signed.entrySet()) {
                // Content-Type is already set above; avoid duplicate header.
                if (!"content-type".equalsIgnoreCase(h.getKey())) {
                    builder.header(h.getKey(), h.getValue());
                }
            }
        }

        return builder.POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build();
    }

    /**
     * Builds the Converse request body.
     *
     * <p>With {@code cacheBreakpoints}, a {@code {"cachePoint": {"type": "default"}}} block is
     * appended after the system text and after the user text. Converse expresses a cache breakpoint as
     * its own block in the content list rather than as a field on the preceding block, which is the
     * only structural difference from Anthropic's native API.</p>
     *
     * <p>Support is per MODEL, not per provider: Converse rejects {@code cachePoint} outright for
     * models that do not implement it, which is why the caller retries once without markers when the
     * rejection names the field.</p>
     *
     * @param system           the system prompt (may be {@code null})
     * @param user             the user prompt
     * @param temperature      sampling temperature
     * @param maxTokens        output cap
     * @param cacheBreakpoints whether to emit cachePoint blocks
     * @return the request body, ready to serialize
     */
    Map<String, Object> buildBody(String system, String user, float temperature, int maxTokens,
                                  boolean cacheBreakpoints) {
        return buildBody(system, user, List.of(), temperature, maxTokens, cacheBreakpoints);
    }

    /**
     * Builds the Converse request body, images included.
     *
     * <p>Converse carries an image as its own content block in the user message, with the format
     * named on its own and the bytes base64-encoded under {@code source.bytes}. The blocks go
     * before the text, and before any {@code cachePoint}: a breakpoint marks everything ahead of
     * it, so an image after one would be outside the part that is cached.</p>
     *
     * @param system           the system prompt (may be {@code null})
     * @param user             the user prompt
     * @param images           what the request carries besides its text; may be empty
     * @param temperature      sampling temperature
     * @param maxTokens        output cap
     * @param cacheBreakpoints whether to emit cachePoint blocks
     * @return the request body, ready to serialize
     */
    Map<String, Object> buildBody(String system, String user, List<PromptImage> images,
                                  float temperature, int maxTokens, boolean cacheBreakpoints) {
        Map<String, Object> body = new LinkedHashMap<>();

        List<Map<String, Object>> userContent = new ArrayList<>();
        if (images != null) {
            for (PromptImage image : images) {
                userContent.add(Map.of("image", Map.of(
                        "format", image.bedrockFormat(),
                        "source", Map.of("bytes", image.base64()))));
            }
        }
        // Omitted when there is nothing to say -- a picture dropped on an empty prompt line is a
        // request on its own, Converse rejects a text block with no text in it, and Map.of refuses
        // a null value outright, so this line was a NullPointerException before it was a rejection.
        if (user != null && !user.isEmpty()) {
            userContent.add(Map.of("text", user));
        }
        if (cacheBreakpoints) {
            userContent.add(cachePoint());
        }
        Map<String, Object> userMsg = new LinkedHashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", userContent);
        body.put("messages", List.of(userMsg));

        if (system != null && !system.isEmpty()) {
            List<Map<String, Object>> systemContent = new ArrayList<>();
            systemContent.add(Map.of("text", system));
            if (cacheBreakpoints) {
                systemContent.add(cachePoint());
            }
            body.put("system", systemContent);
        }

        Map<String, Object> inferenceConfig = new LinkedHashMap<>();
        // Optional in Converse's inferenceConfig; omitted when no ceiling was asked for.
        if (OutputBudget.isLimited(maxTokens)) {
            inferenceConfig.put("maxTokens", maxTokens);
        }
        inferenceConfig.put("temperature", temperature);
        body.put("inferenceConfig", inferenceConfig);
        return body;
    }

    /** A Converse cache breakpoint block. */
    private static Map<String, Object> cachePoint() {
        return Map.of("cachePoint", Map.of("type", "default"));
    }

    /**
     * The image types Converse takes in an image block.
     *
     * <p>The four that an image block's {@code format} accepts as values, which
     * {@link com.eonmux.cadetcoder.ai.PromptImage#bedrockFormat()} writes without the
     * {@code image/} part.</p>
     *
     * @return PNG, JPEG, GIF and WebP
     */
    @Override
    public Set<String> imageMediaTypes() {
        return ImageMediaTypes.PNG_JPEG_GIF_WEBP;
    }

    /**
     * The text of every content block in the reply's message.
     *
     * @param result the parsed Converse response
     * @return the joined text, empty when the reply carries none
     */
    private static String replyText(Map<String, Object> result) {
        StringBuilder joined = new StringBuilder();
        for (Object block : replyBlocks(result)) {
            if (block instanceof Map<?, ?> b && b.get("text") instanceof String text) {
                joined.append(text);
            }
        }
        return joined.toString();
    }

    /**
     * The content blocks of the reply's message.
     *
     * @param result the parsed Converse response
     * @return the blocks, empty when the reply is not shaped as one
     */
    private static List<?> replyBlocks(Map<String, Object> result) {
        if (result.get("output") instanceof Map<?, ?> output
            && output.get("message") instanceof Map<?, ?> message
            && message.get("content") instanceof List<?> blocks) {
            return blocks;
        }
        return List.of();
    }

    /**
     * Why a reply carries no text, phrased for a protocol error.
     *
     * <h2>Why the stop reason is read rather than ignored</h2>
     *
     * <p>Converse says why it stopped in {@code stopReason}, and the answer changes what the reader
     * should do about it. A turn that spent its whole budget before writing anything comes back as
     * {@code max_tokens} with an empty content list, and reported as a bare "no content found in
     * message" it reads as a broken provider rather than as a limit that can be raised. This wire
     * omits {@code maxTokens} entirely when no ceiling was asked for, so reaching it means the
     * model's own ceiling was reached and the request is what has to get smaller.</p>
     *
     * @param result the parsed Converse response
     * @return a detail string for the protocol error
     */
    private static String absenceDetail(Map<String, Object> result) {
        Object stopReason = result.get("stopReason");
        if (MAX_TOKENS_STOP.equals(stopReason)) {
            return NO_CONTENT + ": the model reached its output limit before it wrote any"
                   + " (stopReason: " + MAX_TOKENS_STOP + "). Raise ai.maxTokens if you set one;"
                   + " otherwise the model's own ceiling was reached and the request has to be made"
                   + " smaller";
        }
        if ("content_filtered".equals(stopReason) || "guardrail_intervened".equals(stopReason)) {
            return NO_CONTENT + ": the provider filtered the reply (stopReason: " + stopReason + ")";
        }
        if ("tool_use".equals(stopReason)) {
            // Reached only when the blocks named no tool; one that did was handed back as text.
            return NO_CONTENT + ": the model stopped to call a tool but named none"
                   + " (stopReason: tool_use)";
        }
        if (stopReason instanceof String reason && !reason.isBlank()) {
            return NO_CONTENT + " (stopReason: " + reason + ")";
        }
        return NO_CONTENT;
    }

    @SuppressWarnings ("unchecked")
    private String extractContent(String responseBody) {
        try {
            Map<String, Object> result = MAPPER.readValue(responseBody, Map.class);
            // The provider's own token counts, so what a session cost is reported rather
            // than estimated. Absent for providers that report none; see TokenUsage.
            ReportedUsage.report(TokenUsage.from(result).orElse(null));
            String text = replyText(result);
            if (!text.isEmpty()) {
                return text;
            }
            // A toolUse block is an answer, not an absence. See ToolCalls#textFor.
            String toolCalls = ToolCalls.textFor(replyBlocks(result));
            if (!toolCalls.isEmpty()) {
                return toolCalls;
            }
            throw new LLMProtocolException(PROVIDER_ID, modelName, apiEndpoint,
                                           absenceDetail(result));
        } catch (LLMException e) {
            throw e;
        } catch (Exception e) {
            LOG.error("AmazonBedrockBackend: failed to parse response", e);
            throw new LLMProtocolException(PROVIDER_ID, modelName, apiEndpoint,
                                           "failed to parse response: " + e.getMessage(), e);
        }
    }

}
