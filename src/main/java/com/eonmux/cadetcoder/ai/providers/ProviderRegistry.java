package com.eonmux.cadetcoder.ai.providers;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry of first-class connectors, mirroring opencode's
 * {@code packages/llm/src/providers} set plus the popular OpenAI-compatible profiles.
 * The {@link com.eonmux.cadetcoder.ai.catalog.ModelCatalog} supplies the full model list
 * (144 providers); this registry supplies the connectors that know how to actually call a
 * provider's API.
 */
public final class ProviderRegistry {

    private static volatile ProviderRegistry instance;

    private final Map<String, ProviderConnector> connectors = new LinkedHashMap<>();

    private ProviderRegistry() {
        registerDefaults();
    }

    public static ProviderRegistry getInstance() {
        ProviderRegistry local = instance;
        if (local == null) {
            synchronized (ProviderRegistry.class) {
                local = instance;
                if (local == null) {
                    local = new ProviderRegistry();
                    instance = local;
                }
            }
        }
        return local;
    }

    public ProviderConnector get(String id) {
        return id == null ? null : connectors.get(id);
    }

    public boolean has(String id) {
        return id != null && connectors.containsKey(id);
    }

    public Collection<ProviderConnector> all() {
        return connectors.values();
    }

    public void register(ProviderConnector connector) {
        connectors.put(connector.getId(), connector);
    }

    private void add(String id, String name, List<String> env, String baseUrl,
                     AuthScheme auth, ConnectorProtocol protocol, Map<String, String> headers) {
        add(id, name, env, baseUrl, auth, protocol, headers, null);
    }

    /** As above, for a gateway whose models do not all answer on the same wire. */
    private void add(String id, String name, List<String> env, String baseUrl,
                     AuthScheme auth, ConnectorProtocol protocol, Map<String, String> headers,
                     ModelWire wire) {
        add(id, name, env, baseUrl, auth, protocol, headers, wire, null);
    }

    /** As above, for a provider that can also be asked not to retain a request. */
    private void add(String id, String name, List<String> env, String baseUrl,
                     AuthScheme auth, ConnectorProtocol protocol, Map<String, String> headers,
                     ModelWire wire, DataRetentionControl retention) {
        connectors.put(id, new ProviderConnector(id, name, env, baseUrl, auth, protocol, headers,
                                                 wire, retention));
    }

    /**
     * A GLM connector: OpenAI Chat Completions with a bearer key, its models listed by the platform
     * and described by every GLM entry in the catalog.
     */
    private void addGlm(String id, String name, List<String> env, String baseUrl) {
        connectors.put(id, new ProviderConnector(id, name, env, baseUrl, AuthScheme.BEARER,
                                                 ConnectorProtocol.OPENAI_CHAT, null)
                .listingModelsLive(ZaiPlatform.catalogIdsBesides(id)));
    }

    private void registerDefaults() {
        // --- bespoke protocols ---
        add("anthropic", "Anthropic", List.of("ANTHROPIC_API_KEY"),
                "https://api.anthropic.com/v1", AuthScheme.X_API_KEY,
                ConnectorProtocol.ANTHROPIC_MESSAGES, null);

        add("google", "Google", List.of("GEMINI_API_KEY", "GOOGLE_GENERATIVE_AI_API_KEY"),
                "https://generativelanguage.googleapis.com/v1beta", AuthScheme.X_GOOG_API_KEY,
                ConnectorProtocol.GOOGLE_GENERATIVE_AI, null);

        add("amazon-bedrock", "Amazon Bedrock", List.of("AWS_BEARER_TOKEN_BEDROCK"),
                null, AuthScheme.AWS_SIGV4, ConnectorProtocol.BEDROCK_CONVERSE, null);

        // --- OpenAI Chat Completions compatible ---
        // store=false is the only retention control an OpenAI request carries, and it is not zero
        // retention: it keeps the exchange out of the storage the account can later read back, while
        // abuse-monitoring logs are kept for thirty days with no parameter that turns them off.
        // OpenAI's actual zero retention is arranged per organisation, not per request. It is sent
        // anyway because it is the one thing that is ours to ask; it is recorded as NO_STORAGE so
        // nothing downstream reports it as the promise the other two connectors can make.
        add("openai", "OpenAI", List.of("OPENAI_API_KEY"),
                "https://api.openai.com/v1", AuthScheme.BEARER, ConnectorProtocol.OPENAI_CHAT, null,
                null, DataRetentionControl.body(DataRetentionControl.Promise.NO_STORAGE,
                                                Map.of("store", false)));

        // provider.zdr restricts routing to upstream endpoints that have agreed not to retain the
        // request; OpenRouter answers 404 when none of them serves the model. Deliberately not
        // data_collection=deny, which is a promise about training rather than retention -- asking
        // for the wrong one would report a guarantee that had not been given.
        add("openrouter", "OpenRouter", List.of("OPENROUTER_API_KEY"),
                "https://openrouter.ai/api/v1", AuthScheme.BEARER, ConnectorProtocol.OPENAI_CHAT,
                Map.of("HTTP-Referer", "https://github.com/EonMux/cadetcoder", "X-Title", "CadetCoder"),
                null, DataRetentionControl.body(DataRetentionControl.Promise.ZERO_RETENTION,
                                                Map.of("provider", Map.of("zdr", true))));

        add("xai", "xAI", List.of("XAI_API_KEY"),
                "https://api.x.ai/v1", AuthScheme.BEARER, ConnectorProtocol.OPENAI_CHAT, null);

        add("azure", "Azure OpenAI", List.of("AZURE_OPENAI_API_KEY", "AZURE_API_KEY"),
                null, AuthScheme.API_KEY_HEADER, ConnectorProtocol.OPENAI_CHAT, null);

        add("cloudflare-ai-gateway", "Cloudflare AI Gateway",
                List.of("CLOUDFLARE_API_TOKEN", "CF_AIG_TOKEN"),
                null, AuthScheme.BEARER, ConnectorProtocol.OPENAI_CHAT, null);

        add("cloudflare-workers-ai", "Cloudflare Workers AI",
                List.of("CLOUDFLARE_API_KEY", "CLOUDFLARE_WORKERS_AI_TOKEN"),
                null, AuthScheme.BEARER, ConnectorProtocol.OPENAI_CHAT, null);

        // GitHub Copilot: the bearer token is the short-lived Copilot token resolved via
        // the OAuth device flow; these editor headers are required by the API.
        add("github-copilot", "GitHub Copilot", List.of(),
                "https://api.githubcopilot.com", AuthScheme.BEARER, ConnectorProtocol.OPENAI_CHAT,
                Map.of("Editor-Version", "CadetCoder/1.0", "Copilot-Integration-Id", "vscode-chat"));

        // OpenCode's gateways: opencode's hosted model gateways, "Zen" for the open catalog and
        // "Go" for its subscription plans. Both authenticate any route with one key -- Bearer
        // OPENCODE_API_KEY from https://opencode.ai/console -- and partition their models by
        // protocol across distinct paths: the Chat Completions endpoint for most models, an
        // Anthropic Messages endpoint for the Claude (and a few Qwen/MiniMax) families, and a
        // Google endpoint for Gemini. OpencodeGatewayWire decides the path per model; the catalog
        // at models.dev (providers "opencode" and "opencode-go") describes the models.
        //
        // The client contract their gateway checks: every request names its conversation with a
        // stable session id, and names its client. A request without the session id is refused
        // outright with HTTP 400 MissingSessionID. OpenCode's own client sends the conversation id
        // under four names distinguished in its data model but carrying one value for a client
        // with a single conversation per run, as this tool has; those four ride on every
        // completion and on the model listing. x-opencode-client is the fixed client name;
        // x-opencode-project OpenCode also sends, but its value is OpenCode's own project id,
        // which this tool does not have -- sending the workspace path in its place would leak
        // local paths to the gateway for no routing benefit, so it is not sent.
        register(new ProviderConnector(OpencodeGatewayWire.GO_ID, "OpenCode Go",
                List.of("OPENCODE_API_KEY"),
                "https://opencode.ai/inference/go/openai/v1", AuthScheme.BEARER,
                ConnectorProtocol.OPENAI_CHAT, Map.of("x-opencode-client", "cadet"))
                .withModelWire(new OpencodeGatewayWire())
                .withProtocolBaseUrls(Map.of(
                        ConnectorProtocol.ANTHROPIC_MESSAGES,
                                "https://opencode.ai/inference/go/anthropic/v1",
                        ConnectorProtocol.GOOGLE_GENERATIVE_AI,
                                "https://opencode.ai/inference/go/google/v1beta"))
                .withSessionHeaders(List.of("x-opencode-session", "x-opencode-session-id",
                                            "x-session-affinity", "X-Session-Id")));
        register(new ProviderConnector(OpencodeGatewayWire.ZEN_ID, "OpenCode Zen",
                List.of("OPENCODE_API_KEY"),
                "https://opencode.ai/inference/openai/v1", AuthScheme.BEARER,
                ConnectorProtocol.OPENAI_CHAT, Map.of("x-opencode-client", "cadet"))
                .withModelWire(new OpencodeGatewayWire())
                .withProtocolBaseUrls(Map.of(
                        ConnectorProtocol.ANTHROPIC_MESSAGES,
                                "https://opencode.ai/inference/anthropic/v1",
                        ConnectorProtocol.GOOGLE_GENERATIVE_AI,
                                "https://opencode.ai/inference/google/v1beta"))
                .withSessionHeaders(List.of("x-opencode-session", "x-opencode-session-id",
                                            "x-session-affinity", "X-Session-Id")));

        // Command Code's Provider API: one hosted gateway in front of the Anthropic, OpenAI,
        // DeepSeek, Qwen, GLM and Gemini families, reached with the same key that authenticates
        // their CLI. It publishes both wires at one root and partitions its models between them,
        // which is what CommandCodeWire exists to decide; OPENAI_CHAT is named here because it
        // serves all but the Claude models and is the wire {base}/models is read on. The catalog at
        // models.dev carries no entry for this provider, so its models are discovered from that
        // endpoint by ProviderModelListing.
        // x-cmd-zdr makes the gateway refuse the request outright rather than route it to an
        // upstream that will not promise zero retention. Sent on both of its wires: the header is
        // documented against /chat/completions, and a promise that silently lapsed for the Claude
        // models would be worth less than no promise at all.
        add("commandcode", "Command Code", List.of("COMMAND_CODE_API_KEY", "CMD_API_KEY"),
                "https://api.commandcode.ai/provider/v1", AuthScheme.BEARER,
                ConnectorProtocol.OPENAI_CHAT, null, new CommandCodeWire(),
                DataRetentionControl.header(DataRetentionControl.Promise.ZERO_RETENTION,
                                            "x-cmd-zdr", "1"));

        // --- popular OpenAI-compatible profiles (opencode openai-compatible-profile.ts) ---
        add("groq", "Groq", List.of("GROQ_API_KEY"),
                "https://api.groq.com/openai/v1", AuthScheme.BEARER, ConnectorProtocol.OPENAI_CHAT, null);
        add("deepseek", "DeepSeek", List.of("DEEPSEEK_API_KEY"),
                "https://api.deepseek.com/v1", AuthScheme.BEARER, ConnectorProtocol.OPENAI_CHAT, null);
        add("cerebras", "Cerebras", List.of("CEREBRAS_API_KEY"),
                "https://api.cerebras.ai/v1", AuthScheme.BEARER, ConnectorProtocol.OPENAI_CHAT, null);
        add("fireworks-ai", "Fireworks AI", List.of("FIREWORKS_API_KEY"),
                "https://api.fireworks.ai/inference/v1", AuthScheme.BEARER, ConnectorProtocol.OPENAI_CHAT, null);
        add("togetherai", "Together AI", List.of("TOGETHER_API_KEY"),
                "https://api.together.xyz/v1", AuthScheme.BEARER, ConnectorProtocol.OPENAI_CHAT, null);
        add("baseten", "Baseten", List.of("BASETEN_API_KEY"),
                "https://inference.baseten.co/v1", AuthScheme.BEARER, ConnectorProtocol.OPENAI_CHAT, null);
        add("deepinfra", "DeepInfra", List.of("DEEPINFRA_API_KEY"),
                "https://api.deepinfra.com/v1/openai", AuthScheme.BEARER, ConnectorProtocol.OPENAI_CHAT, null);
        add("mistral", "Mistral", List.of("MISTRAL_API_KEY"),
                "https://api.mistral.ai/v1", AuthScheme.BEARER, ConnectorProtocol.OPENAI_CHAT, null);
        add("perplexity", "Perplexity", List.of("PERPLEXITY_API_KEY"),
                "https://api.perplexity.ai", AuthScheme.BEARER, ConnectorProtocol.OPENAI_CHAT, null);

        // Z.ai and Zhipu AI: the GLM models, from the international and the mainland platform, each
        // billed either to the account balance or to a GLM Coding Plan. See ZaiPlatform for why
        // that makes four connectors. Both platforms read the key as a bearer token. ZAI_API_KEY
        // and ZHIPUAI_API_KEY are the names each platform's own SDK reads; ZHIPU_API_KEY is the
        // name the models.dev catalog gives for all four. Neither platform offers a per-request
        // retention control. Which GLM models a key may call depends on its plan, so the list comes
        // from the platform's /models; see ProviderConnector.listingModelsLive.
        addGlm(ZaiPlatform.ZAI, "Z.AI", List.of("ZAI_API_KEY", "ZHIPU_API_KEY"),
                "https://api.z.ai/api/paas/v4");
        addGlm(ZaiPlatform.ZAI_CODING_PLAN, "Z.AI Coding Plan", List.of("ZAI_API_KEY", "ZHIPU_API_KEY"),
                "https://api.z.ai/api/coding/paas/v4");
        addGlm(ZaiPlatform.ZHIPUAI, "Zhipu AI", List.of("ZHIPUAI_API_KEY", "ZHIPU_API_KEY"),
                "https://open.bigmodel.cn/api/paas/v4");
        addGlm(ZaiPlatform.ZHIPUAI_CODING_PLAN, "Zhipu AI Coding Plan",
                List.of("ZHIPUAI_API_KEY", "ZHIPU_API_KEY"),
                "https://open.bigmodel.cn/api/coding/paas/v4");

        // Local OpenAI-compatible servers (llama-server / LM Studio / Ollama).
        add("local", "Local (OpenAI-compatible)", List.of(),
                "http://localhost:8012/v1", AuthScheme.NONE, ConnectorProtocol.OPENAI_CHAT, null);
    }
}
