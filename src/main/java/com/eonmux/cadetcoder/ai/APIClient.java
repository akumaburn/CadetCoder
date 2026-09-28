package com.eonmux.cadetcoder.ai;

import com.eonmux.cadetcoder.net.BoundedHttp;
import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.net.HttpRequests;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI client implementation using remote API
 */
public class APIClient implements AIClient {
    private final AtomicBoolean                        available = new AtomicBoolean(false);
    private final String                               apiKey;
    private final HttpClient                           httpClient;
    private       String                               apiEndpoint;
    private       String                               modelName;
    private       com.eonmux.cadetcoder.net.LLMBackend backend;

    /**
     * Which connector's catalogue entry describes the model this client is talking to.
     *
     * <h2>Why it is not simply "openai"</h2>
     *
     * <p>This client exists for the case where no connector is configured: it posts to whatever
     * {@code ai.apiEndpoint} names, which is an OpenAI-shaped endpoint and frequently not OpenAI's.
     * Asking models.dev about "openai" therefore answered a question about a different provider's
     * model that happens to share an id -- and since the catalogue is consulted to decide whether a
     * model reads pictures, a custom endpoint serving its own {@code gpt-3.5-turbo} had every image
     * dropped on the strength of what OpenAI's model of that name cannot do.</p>
     *
     * <p>{@code null} when nothing says which provider this is, which is the ordinary case here.
     * The catalogue then has nothing to match and the endpoint answers for itself, which is what
     * {@code ImageChannel} does for every model it has never heard of.</p>
     */
    private       String                               catalogueProviderId;

    public APIClient() {
        Configuration          config   = ConfigManager.getInstance().getConfig();
        Configuration.AiConfig aiConfig = config.getAi();
        this.apiKey = aiConfig.getApiKey();

        this.httpClient = HttpClient.newBuilder()
                                    .connectTimeout(Duration.ofSeconds(10))
                                    .build();

        // Try the local endpoint first, then the configured API endpoint.
        String candidateEndpoint = aiConfig.getLocalEndpoint();
        String candidateModel    = aiConfig.getLocalModel();
        this.apiEndpoint = candidateEndpoint;
        this.modelName   = candidateModel;
        // A model loaded by a local server is in no catalogue, so there is no provider to name.
        this.catalogueProviderId = null;
        backend          = new com.eonmux.cadetcoder.net.OpenAIBackend(modelName, apiEndpoint, apiKey);
        checkAvailability();
        if (!isAvailable()) {
            // Which endpoint this client settled on is internal bookkeeping; the user asked a
            // question, not for a tour of the selection algorithm.
            com.eonmux.cadetcoder.logging.DebugLogger.getInstance().debug("APIClient",
                    "Local endpoint unavailable, falling back to the API endpoint");
            candidateEndpoint = aiConfig.getApiEndpoint();
            candidateModel    = aiConfig.getModel();
            this.apiEndpoint  = candidateEndpoint;
            this.modelName    = candidateModel;
            this.catalogueProviderId = named(aiConfig.getProvider());
            backend           = new com.eonmux.cadetcoder.net.OpenAIBackend(modelName, apiEndpoint, apiKey);
            checkAvailability();
        }
    }

    /**
     * @param providerId what configuration says the provider is
     * @return the id, or {@code null} when nothing was configured
     */
    private static String named(String providerId) {
        return providerId == null || providerId.isBlank() ? null : providerId.trim();
    }

    /**
     * Ceiling for the startup availability probe, body included.
     *
     * <p>The request timeout above it bounds the wait for HEADERS only, so without this a probe
     * that stops mid-body blocks the caller forever. A probe that hangs stops the CLI before it has run anything.</p>
     */
    private static final int PROBE_DEADLINE_SECONDS = 15;

    private void checkAvailability() {
        // Skip availability check in test mode
        if (Boolean.getBoolean("cadet.test.mode")) {
            available.set(true);
            return;
        }

        try {
            HttpRequest request = HttpRequests.to(URI.create(apiEndpoint + "/health"))
                                             .timeout(Duration.ofSeconds(5))
                                             .GET()
                                             .build();

            HttpResponse<String> response = BoundedHttp.send(httpClient, request, PROBE_DEADLINE_SECONDS);

            if (response.statusCode() == 200) {
                available.set(true);
                OutputFormatter.printSuccess("API client connected successfully");
            } else {
                available.set(false);
                com.eonmux.cadetcoder.logging.DebugLogger.getInstance().debug("APIClient",
                        "Availability probe returned HTTP " + response.statusCode());
            }
        } catch (Exception e) {
            available.set(false);
            // getMessage() is null for plenty of exceptions -- a bare ConnectException among them --
            // and "API client connection failed: null" is the least useful thing a terminal can say.
            // The type is the part a reader can act on when there is no message.
            String cause = e.getMessage() == null || e.getMessage().isBlank()
                           ? e.getClass().getSimpleName()
                           : e.getMessage();
            com.eonmux.cadetcoder.logging.DebugLogger.getInstance()
                    .error("APIClient", "Connection probe failed: " + cause, e);
        }
    }

    @Override
    public String complete(PromptData promptData, Map<String, Object> parameters) throws Exception {
        String systemPrompt = promptData.getSystemPrompt();
        String prompt       = promptData.getUserPrompt();
        if (!isAvailable()) {
            throw new IllegalStateException("API client is not available");
        }

        // What was asked for, with the configured values filled in for whatever was not. The
        // caller's map is left as it was found: a request's settings written into a map somebody
        // kept became the next request's, silently.
        Map<String, Object> settings = RequestSettings.filledIn(parameters);
        // Gated on VERBOSE, matching LocalAIClient. Ungated, this dumped the ENTIRE system prompt,
        // the entire user prompt (which carries file contents and command output) and then the entire
        // response to the terminal on every single request -- burying the run, and putting the
        // project's source on screen for anyone looking over the user's shoulder or reading a pasted
        // transcript. It is a debugging aid, so it is now asked for rather than assumed.
        if (isVerbose()) {
            OutputFormatter.printInfo("HTTP Request to LLM: prompt=" +
                                      prompt +
                                      ", systemPrompt=" +
                                      systemPrompt +
                                      ", parameters=" +
                                      settings);
        }
        // Whether the model this endpoint serves reads images. The wire itself carries them; see
        // ImageChannel for why the model is asked here rather than by the provider.
        String response = backend.complete(fit(promptData), settings);
        if (isVerbose()) {
            OutputFormatter.printInfo("HTTP Response from LLM: " + response);
        }
        return response;
    }

    /** Whether the user asked for full request/response tracing. */
    private static boolean isVerbose() {
        try {
            return ConfigManager.getInstance().getConfig().getUi().getVerbosityLevel()
                   >= com.eonmux.cadetcoder.config.Configuration.UiConfig.VERBOSITY.VERBOSE.ordinal();
        } catch (RuntimeException e) {
            // A half-initialised configuration must not decide to start printing prompts.
            return false;
        }
    }

    @Override
    public boolean deliversImages(PromptData promptData) {
        return fit(promptData).hasImages();
    }

    /**
     * The request as this endpoint will really be sent it.
     *
     * @param promptData the request
     * @return the request, with anything this endpoint's model cannot read removed
     */
    private PromptData fit(PromptData promptData) {
        return ImageChannel.fit(promptData, backend, catalogueProviderId, modelName);
    }

    @Override
    public boolean isAvailable() {
        return available.get();
    }

    @Override
    public String getModelName() {
        return modelName;
    }
}
