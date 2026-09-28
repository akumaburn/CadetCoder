package com.eonmux.cadetcoder.net;

import com.eonmux.cadetcoder.ai.parsing.toolcalls.ToolCalls;
import com.eonmux.cadetcoder.ai.metrics.ReportedUsage;
import com.eonmux.cadetcoder.ai.metrics.TokenUsage;
import com.eonmux.cadetcoder.ai.PromptData;
import com.eonmux.cadetcoder.ai.PromptImage;
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
 * Google Generative AI (Gemini) backend
 * ({@code POST {base}/models/{model}:generateContent}). Authenticates with the
 * {@code x-goog-api-key} header, mirroring opencode's google connector. The system prompt
 * is sent via {@code systemInstruction}.
 */
public class GoogleGenerativeAIBackend extends AbstractLLMBackend {

    private static final CadetLogger LOG = CadetLogger.getLogger(GoogleGenerativeAIBackend.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Connector id this backend serves; used in failure messages. */
    private static final String PROVIDER_ID = "google";

    /** The base detail, worded as the other backends word theirs. */
    private static final String NO_CONTENT = "no content found in message";

    /** What Gemini calls a turn that ran out of output budget. */
    private static final String MAX_TOKENS = "MAX_TOKENS";

    private final String apiKey;

    public GoogleGenerativeAIBackend(String modelName, String baseUrl, String apiKey) {
        super(modelName, normalize(baseUrl));
        this.apiKey    = apiKey;
        this.available = apiEndpoint != null && !apiEndpoint.isEmpty()
                && apiKey != null && !apiKey.isEmpty();
        setProviderId(PROVIDER_ID);
    }

    @Override
    public String complete(PromptData promptData, Map<String, Object> parameters) throws Exception {
        float temperature = OpenAICompatibleBackend.numberParam(parameters, "temperature", 0.7f).floatValue();
        int   maxTokens   = OpenAICompatibleBackend.numberParam(parameters, "maxTokens",
                                                          OutputBudget.UNLIMITED).intValue();
        int   timeout     = OpenAICompatibleBackend.numberParam(parameters, "completionTimeout", 60).intValue();

        Map<String, Object> body = new LinkedHashMap<>();

        String system = promptData.getSystemPrompt();
        if (system != null && !system.isEmpty()) {
            body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", system))));
        }

        List<Map<String, Object>> contents = new ArrayList<>();
        Map<String, Object> userTurn = new LinkedHashMap<>();
        userTurn.put("role", "user");
        userTurn.put("parts", userParts(promptData));
        contents.add(userTurn);
        body.put("contents", contents);

        Map<String, Object> generationConfig = new LinkedHashMap<>();
        generationConfig.put("temperature", temperature);
        // Optional; omitted when no ceiling was asked for.
        if (OutputBudget.isLimited(maxTokens)) {
            generationConfig.put("maxOutputTokens", maxTokens);
        }
        body.put("generationConfig", generationConfig);

        String json = MAPPER.writeValueAsString(body);
        String url  = apiEndpoint + "/models/" + modelName + ":generateContent";
        HttpRequest request = HttpRequests.to(URI.create(url))
                .timeout(Duration.ofSeconds(timeout))
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        LOG.info("Sending request to Google Generative AI (model: " + modelName + ")");
        HttpResponse<String> response = sendWithRetry(request);

        if (response.statusCode() != 200) {
            // Typed failure instead of completion text: see OpenAICompatibleBackend for the rationale.
            LLMException failure = LLMErrorMapper.fromResponse(PROVIDER_ID, modelName, url, response);
            LOG.error("GoogleGenerativeAIBackend: " + failure.getMessage());
            throw failure;
        }
        return extractContent(response.body());
    }

    /**
     * The image types Gemini takes as inline data.
     *
     * <p>The one list here that is not the other three. Gemini documents no GIF and adds HEIC and
     * HEIF, the two formats an iPhone photograph arrives in. A GIF sent here is refused with the
     * whole request, so {@code ai/ImageChannel} drops it and asks the question without it.</p>
     *
     * @return PNG, JPEG, WebP, HEIC and HEIF
     */
    @Override
    public Set<String> imageMediaTypes() {
        return ImageMediaTypes.GEMINI;
    }

    /**
     * The parts of the user turn: each image, then the text.
     *
     * <p>Gemini takes an image as {@code inlineData} beside the text in the same turn, with the
     * bytes base64-encoded and the type named separately. Images come first because Google's own
     * guidance is to put the image before the text it asks about.</p>
     *
     * <p>The text part is omitted when the turn brought a picture and no words, which is what
     * dropping a screenshot on an empty prompt line produces. An empty part is not a way of saying
     * that -- {@code generateContent} rejects it -- so the one request made entirely of pictures
     * was the one that could not be sent. A turn with neither pictures nor words still sends its
     * (empty) text part, because a turn has to have at least one.</p>
     *
     * @param promptData the request
     * @return the parts, in the order they are sent
     */
    static List<Map<String, Object>> userParts(PromptData promptData) {
        List<Map<String, Object>> parts = new ArrayList<>();
        for (PromptImage image : promptData.getImages()) {
            parts.add(Map.of("inlineData", Map.of("mimeType", image.mediaType(),
                                                  "data", image.base64())));
        }
        String user = promptData.getUserPrompt();
        if (user != null && !user.isEmpty()) {
            parts.add(Map.of("text", user));
        } else if (parts.isEmpty()) {
            parts.add(Map.of("text", ""));
        }
        return parts;
    }

    @SuppressWarnings ("unchecked")
    private String extractContent(String responseBody) {
        try {
            Map<String, Object> result = MAPPER.readValue(responseBody, Map.class);
            // The provider's own token counts, so what a session cost is reported rather
            // than estimated. Absent for providers that report none; see TokenUsage.
            ReportedUsage.report(TokenUsage.from(result).orElse(null));
            Object candidatesObj = result.get("candidates");
            if (candidatesObj instanceof List<?> candidates && !candidates.isEmpty()) {
                Map<?, ?> candidate = candidates.get(0) instanceof Map<?, ?> first ? first : null;
                String text = candidateText(candidate);
                if (!text.isEmpty()) {
                    return text;
                }
                // A functionCall part is an answer, not an absence. See ToolCalls#textFor.
                String toolCalls = ToolCalls.textFor(candidateParts(candidate));
                if (!toolCalls.isEmpty()) {
                    return toolCalls;
                }
                throw new LLMProtocolException(PROVIDER_ID, modelName, apiEndpoint,
                                               absenceDetail(candidate));
            }
            throw new LLMProtocolException(PROVIDER_ID, modelName, apiEndpoint,
                                           "no completion candidates returned");
        } catch (LLMException e) {
            throw e;
        } catch (Exception e) {
            LOG.error("GoogleGenerativeAIBackend: failed to parse response", e);
            throw new LLMProtocolException(PROVIDER_ID, modelName, apiEndpoint,
                                           "failed to parse response: " + e.getMessage(), e);
        }
    }


    /**
     * The text of a candidate's content parts.
     *
     * @param candidate the first candidate, or {@code null} when it was not an object
     * @return the joined text, empty when the candidate carries none
     */
    private static String candidateText(Map<?, ?> candidate) {
        StringBuilder joined = new StringBuilder();
        for (Object part : candidateParts(candidate)) {
            if (part instanceof Map<?, ?> p && p.get("text") instanceof String text) {
                joined.append(text);
            }
        }
        return joined.toString();
    }

    /**
     * The content parts of a candidate.
     *
     * @param candidate the first candidate, or {@code null} when it was not an object
     * @return its parts, empty when it carries none
     */
    private static List<?> candidateParts(Map<?, ?> candidate) {
        if (candidate != null && candidate.get("content") instanceof Map<?, ?> content
            && content.get("parts") instanceof List<?> parts) {
            return parts;
        }
        return List.of();
    }

    /**
     * Why a candidate carries no text, phrased for a protocol error.
     *
     * <h2>Why the finish reason is read rather than ignored</h2>
     *
     * <p>Gemini reports a turn that ran out of output budget as a candidate with a
     * {@code finishReason} and no {@code content} key at all -- there is nothing to write when the
     * model never reached an answer. Read only for its parts, that candidate was indistinguishable
     * from a malformed payload, so the one fact worth reporting (the budget ran out, and which
     * setting raises it) was replaced by a flat "no content found in message" naming nothing the
     * reader could act on. {@code SAFETY} and {@code PROHIBITED_CONTENT} have the same shape and a
     * completely different remedy, which is the other half of the reason to name it.</p>
     *
     * @param candidate the first candidate, or {@code null} when it was not an object
     * @return a detail string for the protocol error
     */
    private static String absenceDetail(Map<?, ?> candidate) {
        Object reason = candidate == null ? null : candidate.get("finishReason");
        if (MAX_TOKENS.equals(reason)) {
            return NO_CONTENT + ": the model reached its output limit before it wrote any"
                   + " (finishReason: " + MAX_TOKENS + "). Raise ai.maxTokens, or set it to 0 to ask"
                   + " for no ceiling at all";
        }
        if ("SAFETY".equals(reason) || "PROHIBITED_CONTENT".equals(reason)
            || "BLOCKLIST".equals(reason)) {
            return NO_CONTENT + ": the provider filtered the reply (finishReason: " + reason + ")";
        }
        if (reason instanceof String named && !named.isBlank()) {
            return NO_CONTENT + " (finishReason: " + named + ")";
        }
        return NO_CONTENT;
    }

    private static String normalize(String baseUrl) {
        String url = (baseUrl == null || baseUrl.isBlank())
                ? "https://generativelanguage.googleapis.com/v1beta" : baseUrl;
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
