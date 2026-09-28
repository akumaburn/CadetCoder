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
 * Concrete implementation of LLMBackend for interacting with a local llama-server.
 */
public class LlamaServerBackend extends AbstractLLMBackend {
    private static CadetLogger logger;
    private static DebugLogger debugLogger;
    private static ObservabilityLogger observabilityLogger;
    /** Backend id used in failure messages; the local llama-server has no connector id. */
    private static final String PROVIDER_ID = "llama-server";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public LlamaServerBackend(String modelName, String apiEndpoint) {
        super(modelName, apiEndpoint);
        setProviderId(PROVIDER_ID);

        // Initialize loggers lazily to avoid issues during testing
        if (logger == null) {
            logger = CadetLogger.getLogger(LlamaServerBackend.class);
        }
        if (debugLogger == null) {
            debugLogger = DebugLogger.getInstance();
        }
        if (observabilityLogger == null) {
            observabilityLogger = ObservabilityLogger.forComponent("LlamaServerBackend");
        }

        // An endpoint being CONFIGURED is not a connection. This used to announce "Connected to
        // local llama.cpp server at ..." on the strength of a non-empty string, with nothing having
        // been contacted -- and the probe that ran a moment later would print "not available" for
        // the same address. Whichever client actually ends up answering says so; that is the line
        // worth printing, and it is printed once.
        if (apiEndpoint != null && !apiEndpoint.isEmpty()) {
            available = true;
            if (logger != null) {
                logger.info("llama.cpp endpoint configured: " + apiEndpoint);
            }
        }
    }

    /**
     * The image types this wire takes.
     *
     * <p>llama-server implements the OpenAI Chat Completions API, {@code image_url} parts included,
     * and serves them to a multimodal model loaded with a projector. Whether the loaded model reads
     * images is the server's business: a local model is never in the models.dev catalogue, so
     * {@code ai/ImageChannel} lets the request go and the server answers for it.</p>
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
                            : 0.2f;
        int maxTokens = parameters.containsKey("maxTokens")
                        ? (int) parameters.get("maxTokens")
                        : OutputBudget.UNLIMITED;
        int timeout = parameters.containsKey("completionTimeout")
                      ? (int) parameters.get("completionTimeout")
                      : 300;

        String requestBody = MAPPER.writeValueAsString(
                buildBody(promptData, temperature, maxTokens));
        // What the log records. Identical to the body above unless the request carries an image, in
        // which case the bytes are left out: the whole payload is redacted, measured and written to
        // a file that outlives the session. See ChatCompletionMessages.
        String loggedBody = promptData.hasImages()
                            ? MAPPER.writeValueAsString(
                                    elidedBody(promptData, temperature, maxTokens))
                            : requestBody;

        // Log the request
        long startTime = System.currentTimeMillis();
        logger.info("Sending request to llama-server endpoint: " + apiEndpoint + "/v1/chat/completions");
        logger.debug("Request model: " + modelName + ", temperature: " + temperature + ", maxTokens: " + maxTokens);
        // What the log records is what the request carries. It used to record a chat-template
        // rendering of the whole conversation, which this wire has never sent; see
        // ChatCompletionMessages.
        String logPrompt = promptData.getUserPrompt();
        
        // Enhanced observability logging
        String endpoint = apiEndpoint + "/v1/chat/completions";
        Map<String, Object> requestParams = new HashMap<>();
        requestParams.put("model", modelName);
        requestParams.put("temperature", temperature);
        requestParams.put("maxTokens", maxTokens);
        requestParams.put("timeout", timeout);
        
        if (observabilityLogger != null) {
            observabilityLogger.aiRequestStart(modelName, logPrompt, requestParams);
        }
        
        // Reduced debug trace logging
        if (debugLogger != null && debugLogger.isDebugEnabled()) {
            // Only log detailed traces if explicitly enabled via system property
            boolean verboseTrace = Boolean.getBoolean("cadet.debug.trace.verbose");
            if (verboseTrace) {
                debugLogger.logAIRequestTrace(logPrompt, modelName, temperature, loggedBody);
            }
        }

        HttpRequest request = HttpRequests.to(URI.create(apiEndpoint + "/v1/chat/completions"))
                                         .timeout(Duration.ofSeconds(timeout))
                                         .header("Content-Type", "application/json")
                                         .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                                         .build();

        // Log LLM request with full payload
        com.eonmux.cadetcoder.logging.SessionLogger.getInstance().logLLMRequest(
            "LlamaServerBackend", modelName, apiEndpoint + "/v1/chat/completions", loggedBody, parameters);

        HttpResponse<String> response = sendWithRetry(request);

        long duration = System.currentTimeMillis() - startTime;
        
        // Log LLM response with full response body
        Map<String, String> responseHeaders = new HashMap<>();
        if (response.headers() != null) {
            response.headers().map().forEach((key, values) -> 
                responseHeaders.put(key, String.join(", ", values)));
        }
        com.eonmux.cadetcoder.logging.SessionLogger.getInstance().logLLMResponse(
            "LlamaServerBackend", modelName, response.body(), duration, response.statusCode(), responseHeaders);
        
        logger.info("Received response from llama-server (status: " +
                    response.statusCode() +
                    ") in " +
                    duration +
                    "ms");
        
        // Log protocol operation
        boolean responseSuccess = response.statusCode() == 200;
        if (observabilityLogger != null) {
            observabilityLogger.protocolOperation("HTTPS", endpoint, "POST", responseSuccess, duration);
        }
        
        // Reduced debug trace logging - only log response details in verbose mode
        if (debugLogger != null && debugLogger.isDebugEnabled()) {
            boolean verboseTrace = Boolean.getBoolean("cadet.debug.trace.verbose");
            if (verboseTrace) {
                debugLogger.logHTTPResponse(response.statusCode(), response.body(), null);
            }
        }

        if (response.statusCode() == 200) {
            ObjectMapper mapper = new ObjectMapper();
            @SuppressWarnings ("unchecked")
            Map<String, Object> result = mapper.readValue(response.body(), Map.class);
            // The provider's own token counts, so what a session cost is reported rather
            // than estimated. Absent for providers that report none; see TokenUsage.
            ReportedUsage.report(TokenUsage.from(result).orElse(null));
            if (ChatCompletionContent.hasChoices(result)) {
                // Read by the same code as every other Chat Completions provider. This used to cast
                // message.content to String and hand back whatever it found, which meant two things
                // went unreported. A reasoning model that puts its answer under reasoning_content --
                // which llama-server does for the models that emit one -- left content null, and a
                // null completion was returned as the reply and read downstream as an empty
                // response. And a turn cut off at the output limit was indistinguishable from one
                // that finished, because finish_reason was never looked at. See
                // ChatCompletionContent for both.
                String content = ChatCompletionContent.firstChoiceText(result)
                        .orElseThrow(() -> new LLMProtocolException(
                                PROVIDER_ID, modelName, endpoint,
                                ChatCompletionContent.absenceDetail(result)));
                logger.debug("Response content length: " + content.length() + " characters");
                // Backend-level debug logging (centralized for this backend)
                if (debugLogger != null) {
                    debugLogger.logAIResponse(content, duration);
                }
                // Log completion through observability logger (command-level observability)
                if (observabilityLogger != null) {
                    observabilityLogger.aiRequestComplete(modelName, content, duration);
                }
                // Full debug trace logging only
                if (debugLogger.isDebugEnabled() && Boolean.getBoolean("cadet.debug.trace.verbose")) {
                    debugLogger.logAIResponseTrace(content, duration, response.body());
                }
                return content;
            }
            String errorMsg = "LlamaServerBackend: No completion choices returned.";
            OutputFormatter.printError(errorMsg);
            logger.error(errorMsg);
            debugLogger.error("LlamaServerBackend", errorMsg);
            throw new LLMProtocolException(PROVIDER_ID, modelName, endpoint,
                                           "no completion choices returned");
        } else {
            // A failed provider call is not a completion: raise it on the typed failure channel so
            // callers cannot mistake it for the model's answer.
            LLMException failure = LLMErrorMapper.fromResponse(PROVIDER_ID, modelName, endpoint, response);
            String errorMsg = "LlamaServerBackend: " + failure.getMessage();
            OutputFormatter.printError(errorMsg);
            logger.error(errorMsg);
            debugLogger.error("LlamaServerBackend", errorMsg);
            throw failure;
        }
    }


    /**
     * Builds the request body.
     *
     * <p>Serialized by Jackson rather than concatenated. The body was assembled as a string with a
     * hand-written escaper, which left no place for a content part: an image is an object inside an
     * array inside the content, and a protocol written out by hand can only ever carry what its
     * author spelled.</p>
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
        body.put("messages", messages);
        body.put("temperature", temperature);
        // Omitted when no ceiling was asked for; the field is optional here. See OutputBudget.
        if (OutputBudget.isLimited(maxTokens)) {
            body.put("max_tokens", maxTokens);
        }
        body.put("stream", false);
        return body;
    }
}
