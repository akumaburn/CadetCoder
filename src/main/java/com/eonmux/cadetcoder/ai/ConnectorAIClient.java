package com.eonmux.cadetcoder.ai;

import com.eonmux.cadetcoder.ai.providers.ProviderConnector;
import com.eonmux.cadetcoder.ai.providers.ProviderRegistry;
import com.eonmux.cadetcoder.auth.CredentialResolver;
import com.eonmux.cadetcoder.auth.GitHubCopilotAuth;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.logging.CadetLogger;
import com.eonmux.cadetcoder.net.AbstractLLMBackend;
import com.eonmux.cadetcoder.net.LLMBackend;

import java.util.Map;

/**
 * An {@link AIClient} backed by a {@link ProviderConnector} from the registry. This is the
 * runtime bridge between the opencode-style connector/catalog system and CadetCoder's
 * existing AI pipeline: when {@code ai.provider} is configured, this client routes the
 * request to the matching connector (Anthropic, Bedrock, Google, any OpenAI-compatible
 * provider, GitHub Copilot, ...).
 */
public class ConnectorAIClient implements AIClient {

    private static final CadetLogger LOG = CadetLogger.getLogger(ConnectorAIClient.class);

    private final LLMBackend backend;
    private final String     modelName;
    private final String     providerId;

    public ConnectorAIClient(ProviderConnector connector,
                             String model,
                             String apiKey,
                             Map<String, String> options) {
        this(connector, model, apiKey, options, null);
    }

    /**
     * @param apiKeySource where to re-read the credential for each request, for a provider whose
     *                     credential expires during a run; {@code null} when the key is fixed
     */
    public ConnectorAIClient(ProviderConnector connector,
                             String model,
                             String apiKey,
                             Map<String, String> options,
                             java.util.function.Supplier<String> apiKeySource) {
        this.providerId = connector.getId();
        this.modelName  = model;
        this.backend    = connector.createBackend(model, apiKey, options, zeroDataRetention());

        // The generic OpenAI-compatible backend only knows its endpoint host, so failures would name
        // "api.deepseek.com" instead of the connector the user configured. We know the connector id
        // here, so hand it down for the failure messages.
        if (this.backend instanceof AbstractLLMBackend httpBackend) {
            httpBackend.setProviderId(this.providerId);
        }
        // Likewise: which credentials expire is the connector's business, not the wire's.
        if (apiKeySource != null
            && this.backend instanceof com.eonmux.cadetcoder.net.OpenAICompatibleBackend openAiWire) {
            openAiWire.setApiKeySource(apiKeySource);
        }
    }

    @Override
    public String complete(PromptData promptData, Map<String, Object> parameters) throws Exception {
        // What the wire and the model can actually take. An image the provider has nowhere to put,
        // or that this model does not read, is dropped here with a warning rather than sent and
        // answered about blind. See ImageChannel.
        return backend.complete(fit(promptData), parameters);
    }

    @Override
    public boolean deliversImages(PromptData promptData) {
        return fit(promptData).hasImages();
    }

    /**
     * The request as this connector will really send it.
     *
     * <p>Asked twice per request -- once by {@link #deliversImages}, once here -- and answering
     * twice costs nothing: the second call has nothing left to drop, and {@code ImageChannel}
     * remembers what it has already said so the reason is not printed again.</p>
     *
     * @param promptData the request
     * @return the request, with anything this provider and model cannot take removed
     */
    private PromptData fit(PromptData promptData) {
        return ImageChannel.fit(promptData, backend, providerId, modelName);
    }

    @Override
    public boolean isAvailable() {
        return backend != null && backend.isAvailable();
    }

    @Override
    public String getModelName() {
        return providerId + "/" + modelName;
    }

    /**
     * Builds a connector client from configuration, or returns null when no provider is
     * configured / the provider id is unknown. Resolves credentials from the per-provider
     * config map, legacy fields, environment variables, or (for Copilot) the OAuth flow.
     */
    public static ConnectorAIClient fromConfig(Configuration.AiConfig ai) {
        return forModel(ai, ai == null ? null : ai.getModel());
    }

    /**
     * Builds a connector client for a model other than the configured one.
     *
     * <p>Everything except the model comes from the same configuration the active client was built
     * from: the provider, its credentials and its options. A run escalating to a stronger model is
     * still the same account talking to the same provider, and resolving a second set of
     * credentials for it would mean a hand-over could fail for a reason that has nothing to do with
     * the model that was named.</p>
     *
     * @param ai    the AI configuration, whose provider decides where the request goes
     * @param model the model to ask
     * @return the client, or {@code null} when no provider is configured or its id is not one this
     *         tool knows
     */
    public static ConnectorAIClient forModel(Configuration.AiConfig ai, String model) {
        if (ai == null) {
            return null;
        }
        String providerId = ai.getProvider();
        if (providerId == null || providerId.isBlank()) {
            return null;
        }
        ProviderConnector connector = ProviderRegistry.getInstance().get(providerId);
        if (connector == null) {
            LOG.warn("Configured AI provider '" + providerId + "' is not a known connector");
            return null;
        }

        Map<String, String> options = ai.getProviderOptions() != null
                ? ai.getProviderOptions().get(providerId) : null;

        String apiKey;
        java.util.function.Supplier<String> apiKeySource = null;
        if ("github-copilot".equals(providerId)) {
            // One auth object, held by the supplier below, so the token it caches is reused until
            // it is nearly spent and re-minted after that. A Copilot token lives about twenty-five
            // minutes and this client is cached until the model or the login changes, so a token
            // read once here was still being sent long after it had expired -- which is a 401 the
            // retry policy rightly refuses to retry, on every request for the rest of the session.
            GitHubCopilotAuth auth = new GitHubCopilotAuth();
            GitHubCopilotAuth.CopilotToken token = auth.getValidCopilotToken();
            apiKey = token != null ? token.token : null;
            if (apiKey == null) {
                LOG.warn("GitHub Copilot is not authenticated; run the Copilot login flow first");
            }
            apiKeySource = () -> {
                GitHubCopilotAuth.CopilotToken fresh = auth.getValidCopilotToken();
                return fresh != null ? fresh.token : null;
            };
        } else {
            String legacyKey = legacyKeyFor(providerId, ai);
            apiKey = CredentialResolver.resolveApiKey(
                    providerId, connector.getEnv(), ai.getProviderApiKeys(), legacyKey);
        }

        return new ConnectorAIClient(connector, model, apiKey, options, apiKeySource);
    }

    /**
     * Whether the provider should be asked not to retain the request.
     *
     * <p>Falls back to asking for it when the configuration cannot be read. The alternative default
     * would hand a provider a request the user believed was covered by a promise, on the strength of
     * a file that failed to load -- a failure with no visible symptom and nothing to undo it.</p>
     */
    private static boolean zeroDataRetention() {
        try {
            return ConfigManager.getInstance().getConfig().getAi().isZeroDataRetention();
        } catch (Exception e) {
            LOG.warn("Could not read the zero-data-retention setting; asking for it: " + e.getMessage());
            return true;
        }
    }

    private static String legacyKeyFor(String providerId, Configuration.AiConfig ai) {
        if ("openai".equals(providerId)) {
            return ai.getApiKey();
        }
        return null;
    }
}
