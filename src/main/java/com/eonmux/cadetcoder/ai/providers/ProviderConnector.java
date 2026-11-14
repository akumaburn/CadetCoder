package com.eonmux.cadetcoder.ai.providers;

import com.eonmux.cadetcoder.auth.AwsSigV4Signer;
import com.eonmux.cadetcoder.build.BuildInfo;
import com.eonmux.cadetcoder.net.AmazonBedrockBackend;
import com.eonmux.cadetcoder.net.AnthropicBackend;
import com.eonmux.cadetcoder.net.GoogleGenerativeAIBackend;
import com.eonmux.cadetcoder.net.LLMBackend;
import com.eonmux.cadetcoder.net.OpenAICompatibleBackend;
import com.eonmux.cadetcoder.session.SessionManager;

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
    private final boolean             modelsListedLive;
    private final List<String>        relatedCatalogIds;
    private final List<String>        sessionHeaderNames;
    private final Map<ConnectorProtocol, String> protocolBaseUrls;

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
        this(id, displayName, env, defaultBaseUrl, authScheme, protocol, staticHeaders, wire,
             retention, false, List.of(), null, null);
    }

    private ProviderConnector(String id,
                              String displayName,
                              List<String> env,
                              String defaultBaseUrl,
                              AuthScheme authScheme,
                              ConnectorProtocol protocol,
                              Map<String, String> staticHeaders,
                              ModelWire wire,
                              DataRetentionControl retention,
                              boolean modelsListedLive,
                              List<String> relatedCatalogIds,
                              List<String> sessionHeaderNames,
                              Map<ConnectorProtocol, String> protocolBaseUrls) {
        this.id             = id;
        this.displayName    = displayName;
        this.env            = env != null ? env : List.of();
        this.defaultBaseUrl = defaultBaseUrl;
        this.authScheme     = authScheme;
        this.protocol       = protocol;
        this.wire           = wire != null ? wire : ModelWire.always(protocol);
        this.staticHeaders  = staticHeaders != null ? staticHeaders : Map.of();
        this.retention      = retention != null ? retention : DataRetentionControl.none();
        this.modelsListedLive  = modelsListedLive;
        this.relatedCatalogIds = relatedCatalogIds != null ? List.copyOf(relatedCatalogIds) : List.of();
        this.sessionHeaderNames = sessionHeaderNames != null ? List.copyOf(sessionHeaderNames) : List.of();
        this.protocolBaseUrls  = protocolBaseUrls != null ? Map.copyOf(protocolBaseUrls) : Map.of();
    }

    /**
     * This connector, taking its model ids from the provider's own {@code /models}.
     *
     * <h2>Why the catalog is not enough for some providers</h2>
     *
     * <p>The catalog lists what a provider offers in general. A provider whose offer depends on the
     * account -- a subscription plan that covers some models and not others -- is the only authority
     * on what one key may call, and the catalog falls behind it as models are added. A plan can
     * include a model that the catalog's entry for the plan does not name, and a list taken from
     * the catalog then leaves out a model the user pays for.</p>
     *
     * <p>The provider's list names models and nothing more, so each one is still described from the
     * catalog: from this connector's own entry, or from a related entry that publishes the same model
     * -- the plan's models are the platform's models, and the platform's entry names them.</p>
     *
     * @param related catalog ids whose entries describe the same models, in the order to ask them
     * @return a copy of this connector that lists its models live
     */
    public ProviderConnector listingModelsLive(List<String> related) {
        return new ProviderConnector(id, displayName, env, defaultBaseUrl, authScheme, protocol,
                                     staticHeaders, wire, retention, true, related,
                                     sessionHeaderNames, protocolBaseUrls);
    }

    /**
     * This connector, deciding which protocol to speak per model with the given wire.
     *
     * <p>For a gateway whose models do not all answer on one: the wire names the protocol for
     * each model id, and {@link #withProtocolBaseUrls(Map)} can name the endpoint per protocol
     * when they are not all served at one root. See {@link OpencodeGatewayWire} for the case that
     * needs both.</p>
     *
     * @param wire the per-model protocol choice
     * @return a copy of this connector speaking through the wire
     */
    public ProviderConnector withModelWire(ModelWire wire) {
        return new ProviderConnector(id, displayName, env, defaultBaseUrl, authScheme, protocol,
                                     staticHeaders, wire, retention, modelsListedLive,
                                     relatedCatalogIds, sessionHeaderNames, protocolBaseUrls);
    }

    /**
     * This connector, carrying the conversation's session id on every completion under each of the
     * named headers.
     *
     * <h2>Why a gateway would ask</h2>
     *
     * <p>OpenCode's gateways route by conversation and prime their prompt cache along the same
     * line: a client that sends a stable identifier per conversation gets its requests kept
     * together, and one that does not is refused outright with {@code MissingSessionID}. The
     * headers are not static properties of the connector -- their value is the id of the
     * conversation the request belongs to -- so they are merged at backend build time, from the
     * session this process is running, rather than filed beside the connector's fixed headers.</p>
     *
     * <h2>Why there is more than one name</h2>
     *
     * <p>OpenCode's own client sends the same conversation id under four names -- {@code
     * x-opencode-session}, {@code x-opencode-session-id}, {@code x-session-affinity} and {@code
     * X-Session-Id} -- distinguished in its data model (its own session id, the subagent parent's
     * id, and the cache affinity) but carrying one value for a client with a single conversation
     * per run, as this tool has. Sending all four is what its gateway is tested against.</p>
     *
     * @param headerNames the headers to carry the session id in, as {@code x-opencode-session}
     * @return a copy of this connector that sends the headers
     */
    public ProviderConnector withSessionHeaders(List<String> headerNames) {
        return new ProviderConnector(id, displayName, env, defaultBaseUrl, authScheme, protocol,
                                     staticHeaders, wire, retention, modelsListedLive,
                                     relatedCatalogIds, headerNames, protocolBaseUrls);
    }

    /** As {@link #withSessionHeaders(List)}, for a connector that asks for one. */
    public ProviderConnector withSessionHeader(String headerName) {
        return headerName == null ? this : withSessionHeaders(List.of(headerName));
    }

    /**
     * This connector, answering on a different endpoint per protocol.
     *
     * <h2>Why a gateway would split its hosts</h2>
     *
     * <p>OpenCode's gateways partition their models by protocol across distinct paths: the Claude
     * families answer on an Anthropic Messages endpoint, the Gemini families on a Google
     * endpoint, and the rest on an OpenAI-compatible one. Each model answers on exactly one of
     * them, so a connector serving more than one protocol needs a base URL per protocol, not one
     * URL for everything. A model whose protocol has no entry here, and every connector without
     * this map, uses {@link #getDefaultBaseUrl()}.</p>
     *
     * @param baseUrls the endpoint to call per protocol
     * @return a copy of this connector with the per-protocol endpoints
     */
    public ProviderConnector withProtocolBaseUrls(Map<ConnectorProtocol, String> baseUrls) {
        return new ProviderConnector(id, displayName, env, defaultBaseUrl, authScheme, protocol,
                                     staticHeaders, wire, retention, modelsListedLive,
                                     relatedCatalogIds, sessionHeaderNames, baseUrls);
    }

    /**
     * The endpoint to call when speaking a particular protocol, when that differs from the
     * connector's default.
     *
     * @param protocol the protocol about to be spoken
     * @return the base URL for it, or {@code null} for the connector's default
     */
    public String baseUrlFor(ConnectorProtocol protocol) {
        return protocolBaseUrls.get(protocol);
    }

    /**
     * @return the headers that carry the conversation's session id on every completion; empty
     *         when this connector asks for none
     */
    public List<String> getSessionHeaderNames() {
        return sessionHeaderNames;
    }

    /**
     * Whether the provider's own {@code /models} decides which models this connector offers.
     *
     * @return {@code true} when it does; {@code false} when the catalog does
     */
    public boolean listsModelsLive() {
        return modelsListedLive;
    }

    /**
     * Catalog ids whose entries describe this connector's models, beyond its own.
     *
     * @return the ids, in the order to ask them; empty for nearly every connector
     */
    public List<String> getRelatedCatalogIds() {
        return relatedCatalogIds;
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
        // The protocol decides the endpoint, not the other way round: a gateway whose models are
        // partitioned by protocol calls each family on its own path, so the base is resolved after
        // the wire has spoken. An option the user set wins over everything.
        ConnectorProtocol spoke = protocolFor(model);
        String base = firstNonBlank(opts.get("baseURL"), baseUrlFor(spoke), defaultBaseUrl);
        Map<String, String> headers = headersFor(zeroDataRetention);
        Map<String, Object> extraBody = bodyFor(zeroDataRetention);

        switch (spoke) {
            case ANTHROPIC_MESSAGES:
                // authScheme too: this wire serves gateways as well as Anthropic itself, and a
                // gateway reselling claude-* may authenticate the way the rest of its API does.
                return new AnthropicBackend(model, base, apiKey, id, headers, extraBody, authScheme);

            case GOOGLE_GENERATIVE_AI:
                // headers and authScheme as on the Anthropic wire: a gateway serving Gemini reads
                // the credential the way it reads it everywhere else, and the same session
                // identification rides along. Anthropic's own connector sends neither, and the
                // headers map is empty for it.
                return new GoogleGenerativeAIBackend(model, base, apiKey, authScheme, headers);

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
                    // "v1" is the api-version OpenAI's current client generation sends and the one
                    // opencode's Azure connector defaults to; the older calendar versions answer on
                    // the previous deployment-shaped paths.
                    query.put("api-version", firstNonBlank(opts.get("apiVersion"), "v1"));
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
     * The headers a completion from this connector carries, merged in the order that decides
     * which word wins when two sources name the same header: the connector's own static headers,
     * then the client identification, then the retention preference, then the session. Package
     * visible because the merge order is a contract the gateway tests hold.
     *
     * <p>A new map per call rather than one held on the connector, because the preference can differ
     * between two backends built from the same connector -- a run may deliberately turn it off for
     * one model whose upstream will not agree to it.</p>
     *
     * @param zeroDataRetention whether to add the retention refusal
     * @return the headers to send, never null
     */
    Map<String, String> headersFor(boolean zeroDataRetention) {
        Map<String, String> merged = new LinkedHashMap<>(staticHeaders);
        // The one identification the wire asks of a client: its own name, rather than the HTTP
        // library's default. A connector that names a user agent of its own keeps theirs.
        merged.putIfAbsent("User-Agent", "CadetCoder/" + BuildInfo.current().version());
        if (zeroDataRetention && !retention.headers().isEmpty()) {
            merged.putAll(retention.headers());
        }
        // Every name the connector asked for carries the same value: this process's one
        // conversation id, which is what routing and prompt caching key on.
        for (String headerName : sessionHeaderNames) {
            merged.put(headerName, SessionManager.getInstance().getCurrentSessionId());
        }
        return merged;
    }

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
