package com.eonmux.cadetcoder.net;

import com.eonmux.cadetcoder.ai.metrics.ReportedUsage;
import com.eonmux.cadetcoder.ai.metrics.TokenUsage;
import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.ai.PromptData;
import com.eonmux.cadetcoder.logging.CadetLogger;
import com.eonmux.cadetcoder.logging.DebugLogger;
import com.eonmux.cadetcoder.logging.ObservabilityLogger;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Concrete implementation of LLMBackend for interacting with the OpenAI API.
 */
public class OpenAIBackend extends AbstractLLMBackend {
    private static CadetLogger logger;
    private static DebugLogger debugLogger;
    private static ObservabilityLogger observabilityLogger;
    /** Connector id this backend serves; used in failure messages. */
    private static final String PROVIDER_ID = "openai";
    private final  String      apiKey;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public OpenAIBackend(String modelName, String apiEndpoint, String apiKey) {
        super(modelName, apiEndpoint);
        this.apiKey = apiKey;
        setProviderId(PROVIDER_ID);

        // Initialize loggers lazily to avoid issues during testing
        if (logger == null) {
            logger = CadetLogger.getLogger(OpenAIBackend.class);
        }
        if (debugLogger == null) {
            debugLogger = DebugLogger.getInstance();
        }
        if (observabilityLogger == null) {
            observabilityLogger = ObservabilityLogger.forComponent("OpenAIBackend");
        }
        if (apiEndpoint != null && !apiEndpoint.isEmpty() && apiKey != null && !apiKey.isEmpty()) {
            available = true;
            OutputFormatter.printInfo("Connected to OpenAI at " + apiEndpoint);
        }
    }

    /**
     * The image types the Chat Completions API takes in an {@code image_url} part.
     *
     * <p>OpenAI documents these four for vision input. Whether the model asked for reads a
     * picture varies inside the account, and {@code ai/ImageChannel} settles that separately
     * against the catalogue.</p>
     *
     * @return PNG, JPEG, GIF and WebP
     */
    @Override
    public Set<String> imageMediaTypes() {
        return ImageMediaTypes.PNG_JPEG_GIF_WEBP;
    }

    @Override
    public String complete(PromptData promptData, Map<String, Object> parameters) throws Exception {
        float temperature = parameters.containsKey("temperature")
                            ? ((Number) parameters.get("temperature")).floatValue()
                            : 0.7f;
        int maxTokens = parameters.containsKey("maxTokens")
                        ? (int) parameters.get("maxTokens")
                        : OutputBudget.UNLIMITED;
        int timeout = parameters.containsKey("completionTimeout")
                      ? (int) parameters.get("completionTimeout")
                      : 30;

        String json = MAPPER.writeValueAsString(buildBody(promptData, temperature, maxTokens));
        // What the log records. Identical to the body above unless the request carries an image, in
        // which case the bytes are left out: the whole payload is redacted, measured and written to
        // a file that outlives the session, and several megabytes of base64 there is a cost with no
        // reader. See ChatCompletionMessages.
        String logged = promptData.hasImages()
                        ? MAPPER.writeValueAsString(
                                elidedBody(promptData, temperature, maxTokens))
                        : json;

        // Log the request
        long startTime = System.currentTimeMillis();
        if (logger != null) {
            logger.info("Sending request to OpenAI API endpoint: " + apiEndpoint + "/v1/chat/completions");
            logger.debug("Request model: " +
                         modelName +
                         ", temperature: " +
                         temperature +
                         ", maxTokens: " +
                         maxTokens);
        }
        
        // Enhanced observability logging
        String endpoint = apiEndpoint + "/v1/chat/completions";
        Map<String, Object> requestParams = new HashMap<>();
        requestParams.put("model", modelName);
        requestParams.put("temperature", temperature);
        requestParams.put("maxTokens", maxTokens);
        requestParams.put("timeout", timeout);
        
        // What the log records is what the request carries. It used to record a chat-template
        // rendering of the whole conversation, which this wire has never sent; see
        // ChatCompletionMessages.
        String requestPrompt = promptData.getUserPrompt();
        if (observabilityLogger != null) {
            observabilityLogger.aiRequestStart(modelName, requestPrompt, requestParams);
        }
        
        // Full debug trace logging
        if (debugLogger != null && debugLogger.isDebugEnabled()) {
            debugLogger.logAIRequestTrace(requestPrompt, modelName, temperature, logged);
        }

        HttpRequest request = HttpRequests.to(URI.create(apiEndpoint +
                                                         "/v1/chat/completions")) // Use chat completions endpoint
                                         .timeout(Duration.ofSeconds(timeout))
                                         .header("Content-Type", "application/json")
                                         .header("Authorization", "Bearer " + apiKey)
                                         .POST(HttpRequest.BodyPublishers.ofString(json))
                                         .build();

        // Log LLM request with full payload
        com.eonmux.cadetcoder.logging.SessionLogger.getInstance().logLLMRequest(
            "OpenAIBackend", modelName, apiEndpoint + "/v1/chat/completions", logged, parameters);

        HttpResponse<String> response = sendWithRetry(request);

        long duration = System.currentTimeMillis() - startTime;
        
        // Log LLM response with full response body
        Map<String, String> responseHeaders = new HashMap<>();
        if (response.headers() != null) {
            response.headers().map().forEach((key, values) -> 
                responseHeaders.put(key, String.join(", ", values)));
        }
        com.eonmux.cadetcoder.logging.SessionLogger.getInstance().logLLMResponse(
            "OpenAIBackend", modelName, response.body(), duration, response.statusCode(), responseHeaders);
        
        if (logger != null) {
            logger.info("Received response from OpenAI API (status: " +
                        response.statusCode() +
                        ") in " +
                        duration +
                        "ms");
        }
        
        // Log protocol operation
        boolean responseSuccess = response.statusCode() == 200;
        if (observabilityLogger != null) {
            observabilityLogger.protocolOperation("HTTPS", endpoint, "POST", responseSuccess, duration);
        }
        
        // Full debug trace logging
        if (debugLogger != null && debugLogger.isDebugEnabled()) {
            debugLogger.logHTTPResponse(response.statusCode(), response.body(), null);
        }

        if (response.statusCode() == 200) {
            @SuppressWarnings ("unchecked")
            Map<String, Object> result = MAPPER.readValue(response.body(), Map.class);
            // The provider's own token counts, so what a session cost is reported rather
            // than estimated. Absent for providers that report none; see TokenUsage.
            ReportedUsage.report(TokenUsage.from(result).orElse(null));
            if (ChatCompletionContent.hasChoices(result)) {
                // Read by shape rather than by key, and in one place: "content" is PRESENT and null
                // on every response whose finish_reason is tool_calls, on a content-filter refusal,
                // and on a reasoning model that answered under reasoning_content instead -- and the
                // shipped default model is a reasoning model. Providers that return content as an
                // array of blocks are not a String either. See ChatCompletionContent.
                java.util.Optional<String> text = ChatCompletionContent.firstChoiceText(result);
                if (text.isPresent()) {
                    String content = text.get();
                    if (logger != null) {
                        logger.debug("Response content length: " + content.length() + " characters");
                    }
                    // Log completion through observability logger only (avoids duplication)
                    if (observabilityLogger != null) {
                        observabilityLogger.aiRequestComplete(modelName, content, duration);
                    }
                    // Full debug trace logging only
                    if (debugLogger != null && debugLogger.isDebugEnabled()) {
                        debugLogger.logAIResponseTrace(content, duration, response.body());
                    }
                    return content;
                }
                String detail   = ChatCompletionContent.absenceDetail(result);
                String errorMsg = "OpenAIBackend: " + detail;
                OutputFormatter.printError(errorMsg);
                if (logger != null) {
                    logger.error(errorMsg);
                }
                if (debugLogger != null) {
                    debugLogger.error("OpenAIBackend", errorMsg);
                }
                throw new LLMProtocolException(PROVIDER_ID, modelName, endpoint, detail);
            }
            String errorMsg = "OpenAIBackend: No completion choices returned.";
            OutputFormatter.printError(errorMsg);
            if (logger != null) {
                logger.error(errorMsg);
            }
            if (debugLogger != null) {
                debugLogger.error("OpenAIBackend", errorMsg);
            }
            throw new LLMProtocolException(PROVIDER_ID, modelName, endpoint,
                                           "no completion choices returned");
        } else {
            // A failed provider call is not a completion: raise it on the typed failure channel so
            // callers cannot mistake it for the model's answer. The full body still reaches the log;
            // only a short, redacted excerpt reaches the user.
            LLMException failure = LLMErrorMapper.fromResponse(PROVIDER_ID, modelName, endpoint, response);
            String errorMsg = "OpenAIBackend: " + failure.getMessage();
            OutputFormatter.printError(errorMsg);
            if (logger != null) {
                logger.error(errorMsg);
            }
            if (debugLogger != null) {
                debugLogger.error("OpenAIBackend", errorMsg);
            }
            throw failure;
        }
    }


    /**
     * Builds the request body.
     *
     * <p>Serialized by Jackson rather than concatenated. The body was assembled as a string with a
     * hand-written escaper, which left no place for a content part: an image is an object inside an
     * array inside the content, and a protocol written out by hand can only ever carry what its
     * author spelled. It also meant this class escaped JSON its own way, while every other backend
     * used the mapper.</p>
     *
     * @param promptData  the request, images included
     * @param temperature sampling temperature
     * @param maxTokens   output cap
     * @return the request body, ready to serialize
     */
    Map<String, Object> buildBody(PromptData promptData, float temperature, int maxTokens) {
        return body(temperature, maxTokens, ChatCompletionMessages.of(promptData, false));
    }

    /** The same body, with each image's bytes replaced by a note, for the log. */
    private Map<String, Object> elidedBody(PromptData promptData, float temperature, int maxTokens) {
        return body(temperature, maxTokens, ChatCompletionMessages.elided(promptData, false));
    }

    private Map<String, Object> body(float temperature, int maxTokens,
                                     List<Map<String, Object>> messages) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelName);
        body.put("temperature", temperature);
        // Omitted when no ceiling was asked for; the field is optional here. See OutputBudget.
        if (OutputBudget.isLimited(maxTokens)) {
            body.put("max_tokens", maxTokens);
        }
        body.put("messages", messages);
        return body;
    }
}
