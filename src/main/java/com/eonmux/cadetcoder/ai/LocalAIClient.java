package com.eonmux.cadetcoder.ai;

import com.eonmux.cadetcoder.net.BoundedHttp;
import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.logging.CadetLogger;
import com.eonmux.cadetcoder.net.HttpRequests;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI client implementation using local Llama.cpp
 */
public class LocalAIClient implements AIClient {

    /**
     * The catalogue id this client asks about its model.
     *
     * <p>Not a models.dev provider, deliberately: whatever the local server has loaded is not in
     * any catalogue, so the lookup misses and {@code ImageChannel} lets the request through for the
     * server itself to answer.</p>
     */
    private static final String LOCAL_PROVIDER_ID = "local";

    private static       CadetLogger                                  logger;
    private final        AtomicBoolean                                initialized        = new AtomicBoolean(false);
    private final        AtomicBoolean                                available          = new AtomicBoolean(false);
    private              String                                       modelName;
    private              String                                       apiEndpoint;
    private              com.eonmux.cadetcoder.net.LlamaServerBackend backend;

    public LocalAIClient() {
        // Initialize the logger lazily to avoid issues during testing
        try {
            if (logger == null) {
                logger = CadetLogger.getLogger(LocalAIClient.class);
            }
        } catch (Exception e) {
            // Logger initialization might fail in tests
        }

        Configuration config = ConfigManager.getInstance().getConfig();
        this.modelName = config.getAi().getModel();
        initializeModel();
    }

    private void initializeModel() {
        try {
            // Get configuration
            Configuration          config   = ConfigManager.getInstance().getConfig();
            Configuration.AiConfig aiConfig = config.getAi();

            if (aiConfig == null) {
                throw new IllegalStateException("AI configuration not available");
            }

            // Initialize local model settings
            String modelPath = aiConfig.getLocalEndpoint();
            if (modelPath == null || modelPath.isEmpty()) {
                modelPath = "http://localhost:8012";
            }

            this.apiEndpoint = modelPath;
            this.modelName   = aiConfig.localModel;
            if (this.modelName == null) {
                // Fallback to model from getModel() if localModel is null
                this.modelName = config.getAi().getModel();
            }
            backend = new com.eonmux.cadetcoder.net.LlamaServerBackend(modelName, apiEndpoint);

            // Check if llama-server is actually running
            if (checkLlamaServerAvailability()) {
                OutputFormatter.printSuccess("Connected to local llama-server endpoint: " + modelPath);
                if (logger != null) {
                    logger.info("Successfully connected to local llama-server at: " + modelPath);
                }
                initialized.set(true);
                available.set(true);
            } else {
                // Not on the terminal. A local endpoint that is not running is the ordinary case
                // for anyone using a hosted provider, and warning about it on every start told them
                // about a fallback path they never chose. The client that does answer announces
                // itself; if none does, selectActiveClient says so once, and says what to do.
                if (logger != null) {
                    logger.warnToFile("Local llama-server not available at: " + modelPath);
                }
                initialized.set(true);
                available.set(false);
            }
        } catch (Exception e) {
            OutputFormatter.printError("Failed to initialize local AI model: " + e.getMessage());
            if (logger != null) {
                logger.error("Failed to initialize local AI model", e);
            }
            initialized.set(true);
            available.set(false);
        }
    }

    /**
     * Ceiling for a startup availability probe, body included.
     *
     * <p>The request timeout above it bounds the wait for HEADERS only, so without this a probe
     * that stops mid-body blocks the caller forever. A probe that hangs stops the CLI before it has run anything.</p>
     */
    private static final int PROBE_DEADLINE_SECONDS = 15;

    private boolean checkLlamaServerAvailability() {
        try {
            HttpClient httpClient = HttpClient.newBuilder()
                                              .connectTimeout(Duration.ofSeconds(5))
                                              .build();

            // Try the health endpoint first
            HttpRequest healthRequest = HttpRequests.to(URI.create(apiEndpoint + "/health"))
                                                   .timeout(Duration.ofSeconds(5))
                                                   .GET()
                                                   .build();

            try {
                HttpResponse<String> healthResponse =
                        BoundedHttp.send(httpClient, healthRequest, PROBE_DEADLINE_SECONDS);
                if (healthResponse.statusCode() == 200) {
                    return true;
                }
            } catch (Exception ignored) {
                // Health endpoint might not exist, try models endpoint
            }

            // Try the models endpoint as a fallback
            HttpRequest modelsRequest = HttpRequests.to(URI.create(apiEndpoint + "/v1/models"))
                                                   .timeout(Duration.ofSeconds(5))
                                                   .GET()
                                                   .build();

            HttpResponse<String> modelsResponse =
                    BoundedHttp.send(httpClient, modelsRequest, PROBE_DEADLINE_SECONDS);
            return modelsResponse.statusCode() == 200;

        } catch (Exception e) {
            // Connection failed
            return false;
        }
    }

    @Override
    public String complete(PromptData promptData, Map<String, Object> parameters) throws Exception {
        if (!isAvailable()) {
            throw new IllegalStateException("Local AI model is not available");
        }

        long startTime = System.currentTimeMillis();

        // Get temperature from parameters or config
        double temperature = parameters.containsKey("temperature") ?
                             ((Number) parameters.get("temperature")).doubleValue() :
                             ConfigManager.getInstance().getConfig().getAi().getTemperature();

        // Log AI request
        if (logger != null) {
            logger.info("Sending request to local AI model: " + modelName);
            logger.debug("Request temperature: " + temperature);
        }

        if (ConfigManager.getInstance().getConfig().getUi().getVerbosityLevel() ==
            Configuration.UiConfig.VERBOSITY.VERBOSE.ordinal()) {
            OutputFormatter.printInfo("HTTP Request to local LLM:" +
                                      "\n\n Prompt: " +
                                      promptData.toString() +
                                      "\n\n Parameters=" +
                                      parameters);
        }

        // llama-server carries an image; whether the loaded model reads one is the server's
        // business, and a local model is in no catalogue, so this lets the request through. See
        // ImageChannel.
        String response = backend.complete(fit(promptData), parameters);

        long duration = System.currentTimeMillis() - startTime;
        if (logger != null) {
            logger.info("Received response from local AI model in " + duration + "ms");
            logger.debug("Response length: " + response.length() + " characters");
        }

        if (ConfigManager.getInstance().getConfig().getUi().getVerbosityLevel() ==
            Configuration.UiConfig.VERBOSITY.VERBOSE.ordinal()) {
            OutputFormatter.printInfo("HTTP Response from local LLM: " + response);
        }
        return response;
    }

    @Override
    public boolean deliversImages(PromptData promptData) {
        return fit(promptData).hasImages();
    }

    /**
     * The request as the local server will really be sent it.
     *
     * @param promptData the request
     * @return the request, with anything this wire cannot carry removed
     */
    private PromptData fit(PromptData promptData) {
        return ImageChannel.fit(promptData, backend, LOCAL_PROVIDER_ID, modelName);
    }

    @Override
    public boolean isAvailable() {
        return initialized.get() && available.get();
    }

    @Override
    public String getModelName() {
        return modelName;
    }
}
