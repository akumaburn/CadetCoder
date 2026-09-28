package com.eonmux.cadetcoder.ai.providers;

import com.eonmux.cadetcoder.auth.AwsSigV4Signer;
import com.eonmux.cadetcoder.net.AmazonBedrockBackend;
import com.eonmux.cadetcoder.net.AnthropicBackend;
import com.eonmux.cadetcoder.net.GoogleGenerativeAIBackend;
import com.eonmux.cadetcoder.net.LLMBackend;
import com.eonmux.cadetcoder.net.OpenAICompatibleBackend;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A connector definition: provider identity, credential sources, default endpoint, auth
 * scheme, wire protocol, and the logic to construct a runtime {@link LLMBackend}. This is
 * the Java equivalent of an opencode provider module in {@code packages/llm/src/providers}.
 */
public final class ProviderConnector {

    private final String              id;
    private final String              displayName;
    private final List<String>        env;
    private final String              defaultBaseUrl; // may be null when constructed from options
    private final AuthScheme          authScheme;
    private final ConnectorProtocol   protocol;
    private final ModelWire           wire;
    private final Map<String, String> staticHeaders;
    private final DataRetentionControl retention;

    public ProviderConnector(String id,
                             String displayName,
                             List<String> env,
                             String defaultBaseUrl,
                             AuthScheme authScheme,
                             ConnectorProtocol protocol,
                             Map<String, String> staticHeaders) {
        this(id, displayName, env, defaultBaseUrl, authScheme, protocol, staticHeaders, null);
    }

    /**
     * @param protocol the protocol this connector speaks by default, and the one reported to
     *                 anything asking what it is
     * @param wire     which protocol a particular model needs, for a gateway whose models do not
     *                 all answer on one; {@code null} for the usual case of a provider that speaks
     *                 {@code protocol} for everything it offers
     */
    public ProviderConnector(String id,
                             String displayName,
                             List<String> env,
                             String defaultBaseUrl,
                             AuthScheme authScheme,
                             ConnectorProtocol protocol,
                             Map<String, String> staticHeaders,
                             ModelWire wire) {
        this(id, displayName, env, defaultBaseUrl, authScheme, protocol, staticHeaders, wire, null);
    }

    /**
     * @param retention what this provider can be asked about keeping the request, and how to ask;
     *                  {@code null} for a provider that offers no such control
     */
    public ProviderConnector(String id,
                             String displayName,
                             List<String> env,
                             String defaultBaseUrl,
                             AuthScheme authScheme,
                             ConnectorProtocol protocol,
                             Map<String, String> staticHeaders,
                             ModelWire wire,
                             DataRetentionControl retention) {
        this.id             = id;
        this.displayName    = displayName;
        this.env            = env != null ? env : List.of();
        this.defaultBaseUrl = defaultBaseUrl;
        this.authScheme     = authScheme;
        this.protocol       = protocol;
        this.wire           = wire != null ? wire : ModelWire.always(protocol);
        this.staticHeaders  = staticHeaders != null ? staticHeaders : Map.of();
        this.retention      = retention != null ? retention : DataRetentionControl.none();
    }

    /** What this provider can be asked about keeping the request, and how to ask it. */
    public DataRetentionControl retentionControl() {
        return retention;
    }

    /** Whether this provider offers any control over keeping the request. */
    public boolean supportsZeroDataRetention() {
        return retention.isAvailable();
    }

    /**
     * How this connector's wire reads in a listing.
     *
     * <p>A connector that chooses per model cannot honestly be described by one protocol name, and
     * the connector table is where someone looks to find out what a provider speaks. Printing only
     * the default told a reader of the table that every Command Code model answers on Chat
     * Completions, which is the belief the gateway refuses at the first Claude request.</p>
     *
     * @return the protocol, or all of them when the models are split between several
     */
    public String protocolLabel() {
        Set<ConnectorProtocol> spoken = wire.protocols();
        if (spoken.size() <= 1) {
            return String.valueOf(protocol);
        }
        return spoken.stream().map(String::valueOf).collect(Collectors.joining("/"));
    }

    /**
     * The protocol to speak for one particular model.
     *
     * <p>The same as {@link #getProtocol()} for every provider that speaks one protocol, which is
     * nearly all of them. {@link ModelWire} records why a gateway can be the exception.</p>
     *
     * @param model the model id about to be called
     * @return the protocol its request must be shaped as
     */
    public ConnectorProtocol protocolFor(String model) {
        return wire.forModel(model);
    }

    public String getId()                  { return id; }
    public String getDisplayName()         { return displayName; }
    public List<String> getEnv()           { return env; }
    public String getDefaultBaseUrl()      { return defaultBaseUrl; }
    public AuthScheme getAuthScheme()      { return authScheme; }
    public ConnectorProtocol getProtocol() { return protocol; }
    public Map<String, String> getStaticHeaders() { return staticHeaders; }

    /**
     * Builds a backend for a model of this provider.
     *
     * @param model   the model id
     * @param apiKey  the resolved credential (bearer/api-key/copilot token), may be null for NONE/SigV4
     * @param options per-provider options (baseURL, region, resourceName, apiVersion, accountId, gatewayId)
     */
    public LLMBackend createBackend(String model, String apiKey, Map<String, String> options) {
        return createBackend(model, apiKey, options, true);
    }

    /**
     * Builds a backend, saying whether the provider should be asked not to retain the request.
     *
     * <p>The preference is passed in rather than read here: it is a setting of the user's, and the
     * caller assembling the request is where the rest of that configuration is already resolved.
     * The argument-less form asks for retention to be refused, because that is the answer that
     * cannot quietly cost a user something they did not choose.</p>
     *
     * @param model             the model id
     * @param apiKey            the resolved credential, may be null for NONE/SigV4
     * @param options           per-provider options
     * @param zeroDataRetention whether to ask this provider not to retain the request; ignored by a
     *                          provider that offers no such promise
     */
    public LLMBackend createBackend(String model, String apiKey, Map<String, String> options,
                                    boolean zeroDataRetention) {
        Map<String, String> opts = options != null ? options : Map.of();
        String base = firstNonBlank(opts.get("baseURL"), defaultBaseUrl);
        Map<String, String> headers = headersFor(zeroDataRetention);
        Map<String, Object> extraBody = bodyFor(zeroDataRetention);

        switch (protocolFor(model)) {
            case ANTHROPIC_MESSAGES:
                // authScheme too: this wire serves gateways as well as Anthropic itself, and a
                // gateway reselling claude-* may authenticate the way the rest of its API does.
                return new AnthropicBackend(model, base, apiKey, id, headers, extraBody, authScheme);

            case GOOGLE_GENERATIVE_AI:
                return new GoogleGenerativeAIBackend(model, base, apiKey);

            case BEDROCK_CONVERSE: {
                String region = firstNonBlank(opts.get("region"), System.getenv("AWS_REGION"), "us-east-1");
                AwsSigV4Signer.Credentials creds = AwsSigV4Signer.Credentials.fromEnvironment();
                return new AmazonBedrockBackend(model, base, region, apiKey, creds);
            }

            case OPENAI_CHAT:
            default: {
                String resolvedBase = base;
                Map<String, String> query = new LinkedHashMap<>();
                if ("azure".equals(id)) {
                    String resourceName = opts.get("resourceName");
                    if (isBlank(resolvedBase) && resourceName != null && !resourceName.isBlank()) {
                        resolvedBase = "https://" + resourceName + ".openai.azure.com/openai/v1";
                    }
                    query.put("api-version", firstNonBlank(opts.get("apiVersion"), "2024-06-01"));
                } else if ("cloudflare-ai-gateway".equals(id) && isBlank(resolvedBase)) {
                    // Both parts required, exactly as azure requires its resourceName above. With
                    // the ids defaulted to "" this produced
                    // "https://gateway.ai.cloudflare.com/v1///compat", which is not blank -- so the
                    // backend called itself available, the run announced "Using connector:
                    // cloudflare-ai-gateway", and every request 404'd. A provider that has not been
                    // told where it lives is not configured, and has to say so.
                    String account = cloudflareAccount(opts);
                    String gateway = firstNonBlank(opts.get("gatewayId"), System.getenv("CLOUDFLARE_GATEWAY_ID"));
                    if (!isBlank(account) && !isBlank(gateway)) {
                        resolvedBase = "https://gateway.ai.cloudflare.com/v1/"
                                + account + "/" + gateway + "/compat";
                    }
                } else if ("cloudflare-workers-ai".equals(id) && isBlank(resolvedBase)) {
                    String account = cloudflareAccount(opts);
                    if (!isBlank(account)) {
                        resolvedBase = "https://api.cloudflare.com/client/v4/accounts/"
                                + account + "/ai/v1";
                    }
                }
                return new OpenAICompatibleBackend(model, resolvedBase, apiKey, authScheme, headers,
                                                   query, extraBody);
            }
        }
    }

    /**
     * The headers a request carries: the connector's own, plus the ones that refuse retention.
     *
     * <p>A new map per call rather than one held on the connector, because the preference can differ
     * between two backends built from the same connector -- a run may deliberately turn it off for
     * one model whose upstream will not agree to it.</p>
     *
     * @param zeroDataRetention whether to add the refusal
     * @return the headers to send, never null
     */
    private Map<String, String> headersFor(boolean zeroDataRetention) {
        if (!zeroDataRetention || retention.headers().isEmpty()) {
            return staticHeaders;
        }
        Map<String, String> merged = new LinkedHashMap<>(staticHeaders);
        merged.putAll(retention.headers());
        return merged;
    }

    /**
     * The request-body fields a backend should merge in, for a provider that reads the body rather
     * than a header.
     *
     * @param zeroDataRetention whether the user asked for it
     * @return the fields, empty when nothing is to be asked
     */
    /**
     * The request-body fields this connector needs, given the preference in force.
     *
     * <p>Where a provider reads its data-retention control from the body rather than a header:
     * OpenRouter restricts routing through {@code provider.zdr}, OpenAI suppresses stored history
     * through {@code store}. Nothing else is carried here, so an empty map is the ordinary case.</p>
     *
     * @param zeroDataRetention whether to ask for it
     * @return the fields to merge into the request body, never null
     */
    private Map<String, Object> bodyFor(boolean zeroDataRetention) {
        return zeroDataRetention ? retention.body() : Map.of();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    /**
     * The Cloudflare account id, from the configured option or the variable Cloudflare's own tools
     * export.
     *
     * <p>The connector is described as backed by the models.dev catalog, which names
     * {@code CLOUDFLARE_ACCOUNT_ID}; reading only the option meant a machine already set up for
     * Cloudflare still had to be told its own account id by hand.</p>
     *
     * @param opts the provider options as configured
     * @return the account id, or {@code null} when neither source has one
     */
    private static String cloudflareAccount(Map<String, String> opts) {
        return firstNonBlank(opts.get("accountId"), System.getenv("CLOUDFLARE_ACCOUNT_ID"));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
