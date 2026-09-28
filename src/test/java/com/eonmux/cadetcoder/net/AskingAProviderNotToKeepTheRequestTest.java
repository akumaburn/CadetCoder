package com.eonmux.cadetcoder.net;

import com.eonmux.cadetcoder.ai.PromptData;
import com.eonmux.cadetcoder.ai.providers.DataRetentionControl.Promise;
import com.eonmux.cadetcoder.ai.providers.ProviderConnector;
import com.eonmux.cadetcoder.ai.providers.ProviderRegistry;
import com.eonmux.cadetcoder.config.ConfigOverrides;
import com.eonmux.cadetcoder.config.Configuration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.sun.net.httpserver.HttpServer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Zero data retention: who is asked for it, on which wire, and what happens by default.
 *
 * <h2>Why this is tested against a server</h2>
 *
 * <p>The promise not to retain a request is carried in the request -- a header for Command Code,
 * body fields for OpenRouter and OpenAI -- and one that is never sent fails invisibly: the provider
 * answers normally, the run succeeds, and nothing anywhere reports that the promise was not asked
 * for. Asserting that a backend HOLDS the header would not have caught the defect that prompted
 * this -- the Anthropic backend accepted connector headers and then built its request without them,
 * so the field was right and the wire was wrong. What is checked here is what actually arrives at a
 * server.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>That the setting is on unless it is turned off; that the header reaches the server on both of
 * the gateway's wires and the body fields reach it in the body; that turning it off actually stops
 * sending either; that merging body fields leaves the request the backend built intact; that the
 * two forms do not leak onto providers reading the other one; that each connector records the
 * promise it can really make rather than the strongest one available; that a provider offering no
 * such promise is unaffected either way; and that a connector's own identifying headers survive the
 * merge, since losing those would fail for a reason unrelated to retention.</p>
 */
public class AskingAProviderNotToKeepTheRequestTest {

    private static final String ZDR_HEADER = "x-cmd-zdr";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private int        port;

    private volatile Map<String, String> lastHeaders;
    private volatile JsonNode            lastBody;

    @Before
    public void startServer() throws Exception {
        System.setProperty(RetryPolicy.BASE_DELAY_MS_PROPERTY, "1");
        System.setProperty(RetryPolicy.MAX_DELAY_MS_PROPERTY, "5");
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            try (InputStream in = exchange.getRequestBody()) {
                lastBody = MAPPER.readTree(in.readAllBytes());
            }
            Map<String, String> headers = new HashMap<>();
            exchange.getRequestHeaders()
                    .forEach((k, v) -> headers.put(k.toLowerCase(), String.join(",", v)));
            lastHeaders = headers;

            // Enough of each wire's shape for the backend to read an answer rather than raise.
            byte[] out = ("{\"choices\":[{\"message\":{\"content\":\"ok\"}}],"
                          + "\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @After
    public void stopServer() {
        System.clearProperty(RetryPolicy.BASE_DELAY_MS_PROPERTY);
        System.clearProperty(RetryPolicy.MAX_DELAY_MS_PROPERTY);
        if (server != null) {
            server.stop(0);
        }
    }

    /** Sends one completion through the connector and returns the headers the server saw. */
    private Map<String, String> headersSentBy(String providerId, String model, boolean zdr)
            throws Exception {
        ProviderConnector connector = ProviderRegistry.getInstance().get(providerId);
        Map<String, String> options = Map.of("baseURL", "http://localhost:" + port + "/v1");

        connector.createBackend(model, "key", options, zdr)
                 .complete(new PromptData("sys", "hi"), new HashMap<>());
        return lastHeaders;
    }

    /** Sends one completion through the connector and returns the body the server saw. */
    private JsonNode bodySentBy(String providerId, String model, boolean zdr) throws Exception {
        headersSentBy(providerId, model, zdr);
        return lastBody;
    }

    // ---------------------------------------------------------------- the default

    @Test
    public void afreshConfigurationAsksForZeroRetention() {
        // The interesting default is the private one: a user who never opens the setting still gets
        // the promise, on every provider able to make it.
        assertThat(new Configuration().getAi().isZeroDataRetention()).isTrue();
    }

    @Test
    public void buildingABackendWithoutSayingAsksForIt() throws Exception {
        ProviderConnector commandcode = ProviderRegistry.getInstance().get("commandcode");

        commandcode.createBackend("deepseek/deepseek-v4-flash", "key",
                                  Map.of("baseURL", "http://localhost:" + port + "/v1"))
                   .complete(new PromptData("sys", "hi"), new HashMap<>());

        assertThat(lastHeaders).containsEntry(ZDR_HEADER, "1");
    }

    // ---------------------------------------------------------------- both wires

    @Test
    public void theOpenAiWireCarriesItToTheServer() throws Exception {
        assertThat(headersSentBy("commandcode", "deepseek/deepseek-v4-flash", true))
                .containsEntry(ZDR_HEADER, "1");
    }

    @Test
    public void theAnthropicWireCarriesItToTheServerToo() throws Exception {
        // The defect this closes: the Anthropic backend took connector headers and then built its
        // request without them, so the promise lapsed for the Claude models alone -- and a perfectly
        // normal answer came back either way.
        assertThat(headersSentBy("commandcode", "claude-sonnet-5", true))
                .containsEntry(ZDR_HEADER, "1");
    }

    @Test
    public void theAnthropicWireStillSendsWhatItAlwaysSent() throws Exception {
        // Anthropic's own connector, which is the one that declares x-api-key.
        Map<String, String> sent = headersSentBy("anthropic", "claude-sonnet-5", true);

        assertThat(sent).containsEntry("x-api-key", "key");
        assertThat(sent).containsKey("anthropic-version");
    }

    @Test
    public void aGatewayOnTheAnthropicWireIsAuthenticatedItsOwnWay() throws Exception {
        // This used to assert x-api-key, which is Anthropic's scheme and not Command Code's: the
        // backend hardcoded the header instead of reading the scheme the connector declares, so
        // the gateway's Claude models were authenticated wrongly while the rest of the same
        // connector -- going out over the OpenAI wire -- was authenticated correctly.
        Map<String, String> sent = headersSentBy("commandcode", "claude-sonnet-5", true);

        assertThat(sent).containsEntry("authorization", "Bearer key");
        assertThat(sent).doesNotContainKey("x-api-key");
        // The wire is still the Messages wire, whatever carries the credential on it.
        assertThat(sent).containsKey("anthropic-version");
    }

    // ---------------------------------------------------------------- turning it off

    @Test
    public void turningItOffStopsAskingOnTheOpenAiWire() throws Exception {
        assertThat(headersSentBy("commandcode", "deepseek/deepseek-v4-flash", false))
                .doesNotContainKey(ZDR_HEADER);
    }

    @Test
    public void turningItOffStopsAskingOnTheAnthropicWire() throws Exception {
        assertThat(headersSentBy("commandcode", "claude-sonnet-5", false))
                .doesNotContainKey(ZDR_HEADER);
    }

    // ---------------------------------------------------------------- asked for in the body

    @Test
    public void openRouterIsAskedToRouteOnlyToUpstreamsThatWillNotKeepIt() throws Exception {
        // OpenRouter has no header for this: the control is a routing preference in the body, and
        // it answers 404 when no upstream serving the model has agreed to it.
        JsonNode provider = bodySentBy("openrouter", "some-model", true).get("provider");

        assertThat(provider).isNotNull();
        assertThat(provider.get("zdr").asBoolean()).isTrue();
    }

    @Test
    public void openAiIsAskedNotToStoreTheExchange() throws Exception {
        JsonNode store = bodySentBy("openai", "gpt-5", true).get("store");

        assertThat(store).isNotNull();
        assertThat(store.asBoolean()).isFalse();
    }

    @Test
    public void turningItOffStopsAskingInTheBodyToo() throws Exception {
        assertThat(bodySentBy("openrouter", "some-model", false).has("provider")).isFalse();
        assertThat(bodySentBy("openai", "gpt-5", false).has("store")).isFalse();
    }

    @Test
    public void therestOfTheRequestIsUntouchedByTheMerge() throws Exception {
        // Merging extra fields into the body is one putAll away from redefining the request. What
        // the backend built has to still be there, and still be what it built.
        JsonNode body = bodySentBy("openrouter", "some-model", true);

        assertThat(body.get("model").asText()).isEqualTo("some-model");
        assertThat(body.get("messages").isArray()).isTrue();
        assertThat(body.get("messages")).isNotEmpty();
        assertThat(body.has("temperature")).isTrue();
        // Not max_tokens: nothing asks for a ceiling unless the user set one, so the field is
        // absent here by design rather than lost by the merge. See net/OutputBudget.
        assertThat(body.has("max_tokens")).isFalse();
    }

    @Test
    public void abodyLevelControlIsNotSmuggledIntoAHeader() throws Exception {
        // And the reverse, so neither form leaks onto providers that read the other one.
        assertThat(headersSentBy("openrouter", "some-model", true)).doesNotContainKey(ZDR_HEADER);
        assertThat(bodySentBy("commandcode", "deepseek/deepseek-v4-flash", true).has("provider"))
                .isFalse();
    }

    /**
     * The Anthropic wire carries body fields too, though no connector needs it there yet.
     *
     * <p>Headers were accepted and dropped on this wire once already, and the failure was silent
     * both times it could have been noticed. The symmetry is what stops that recurring: a gateway
     * that reads its control from the body would otherwise honour it for the models answering on one
     * endpoint and quietly not for the models answering on the other.</p>
     */
    @Test
    public void theAnthropicWireCarriesBodyFieldsAsWell() throws Exception {
        new AnthropicBackend("claude-sonnet-5", "http://localhost:" + port, "key", "somegateway",
                             Map.of(), Map.of("store", false))
                .complete(new PromptData("sys", "hi"), new HashMap<>());

        assertThat(lastBody.get("store")).isNotNull();
        assertThat(lastBody.get("store").asBoolean()).isFalse();
        assertThat(lastBody.get("model").asText()).isEqualTo("claude-sonnet-5");
        assertThat(lastBody.get("messages")).isNotEmpty();
    }

    // ---------------------------------------------------------------- the promises differ

    /**
     * The three connectors do not promise the same thing, and the difference is recorded.
     *
     * <p>Command Code refuses the request rather than retain it and OpenRouter routes only where it
     * will not be retained; OpenAI has no per-request zero retention at all -- {@code store} keeps
     * the exchange out of stored history while abuse-monitoring logs are kept for thirty days, and
     * true zero retention is arranged for an organisation. Collapsing all three into one boolean
     * would have the tool report a guarantee two of them never gave.</p>
     */
    @Test
    public void whatEachProviderCanActuallyPromiseIsRecorded() {
        ProviderRegistry registry = ProviderRegistry.getInstance();

        assertThat(registry.get("commandcode").retentionControl().promise())
                .isEqualTo(Promise.ZERO_RETENTION);
        assertThat(registry.get("openrouter").retentionControl().promise())
                .isEqualTo(Promise.ZERO_RETENTION);
        assertThat(registry.get("openai").retentionControl().promise())
                .isEqualTo(Promise.NO_STORAGE);
        assertThat(registry.get("xai").retentionControl().promise()).isEqualTo(Promise.NONE);
    }

    // ---------------------------------------------------------------- everybody else

    @Test
    public void aProviderWithNoSuchPromiseSaysSo() {
        ProviderRegistry registry = ProviderRegistry.getInstance();

        assertThat(registry.get("commandcode").supportsZeroDataRetention()).isTrue();
        assertThat(registry.get("xai").supportsZeroDataRetention()).isFalse();
        assertThat(registry.get("anthropic").supportsZeroDataRetention()).isFalse();
    }

    @Test
    public void aProviderWithNoSuchPromiseSendsNothingExtraEitherWay() throws Exception {
        assertThat(headersSentBy("xai", "grok-4", true)).doesNotContainKey(ZDR_HEADER);
        assertThat(bodySentBy("xai", "grok-4", true).has("store")).isFalse();
        assertThat(bodySentBy("xai", "grok-4", true).has("provider")).isFalse();
    }

    @Test
    public void aConnectorsOwnHeadersSurviveTheMerge() throws Exception {
        // OpenRouter identifies the caller with two headers of its own; losing them to the merge
        // would fail for a reason that has nothing to do with retention.
        assertThat(headersSentBy("openrouter", "some-model", true))
                .containsKeys("http-referer", "x-title");
    }

    // ---------------------------------------------------------------- being refused

    /** What the gateway actually answers when an upstream will not promise zero retention. */
    private static final String REFUSAL =
            "{\"error\":{\"code\":\"cmd_zdr_no_providers\",\"message\":\"This model has no "
            + "zero-data-retention upstream. Remove the x-cmd-zdr header or choose another model.\"}}";

    private static LLMException refused(String body) {
        return LLMErrorMapper.fromStatus("commandcode", "Qwen/Qwen3.8-Flash",
                                         "https://api.commandcode.ai/provider/v1", 422, body, null);
    }

    @Test
    public void arefusalOverRetentionSaysThatIsWhatHappened() {
        // The generic 4xx message sends the reader to check the model name and request settings,
        // and both are fine: the request was refused over a promise the tool asked for on their
        // behalf, which they may not know it asks for at all.
        assertThat(refused(REFUSAL).getMessage())
                .contains("no zero-retention upstream")
                .contains("Qwen/Qwen3.8-Flash");
    }

    @Test
    public void itNamesBothWaysOut() {
        String message = refused(REFUSAL).getMessage();

        assertThat(message).contains("choose a model");
        assertThat(message).contains("ai.zeroDataRetention false");
    }

    @Test
    public void itIsRecognisedByTheDocumentedCodeAndByTheProse() {
        // Either alone is one string owned by somebody else.
        assertThat(refused("{\"error\":{\"code\":\"cmd_zdr_no_providers\"}}").getMessage())
                .contains("no zero-retention upstream");
        assertThat(refused("{\"error\":{\"message\":\"no zero-data-retention upstream\"}}").getMessage())
                .contains("no zero-retention upstream");
    }

    @Test
    public void anunrelated422IsStillTheOrdinaryRejection() {
        assertThat(refused("{\"error\":{\"message\":\"messages: field required\"}}").getMessage())
                .doesNotContain("zero-retention")
                .contains("rejected the request");
    }

    @Test
    public void arefusalIsNotWorthSendingAgain() {
        // Retrying asks for the same promise of the same upstream.
        assertThat(refused(REFUSAL)).isInstanceOf(LLMBadRequestException.class);
        assertThat(refused(REFUSAL).isRetryable()).isFalse();
    }

    // ---------------------------------------------------------------- turning it off is possible

    /**
     * The setting named in the refusal message has to be one the tool will accept.
     *
     * <p>This is the defect that reached a live run: the setting existed on the configuration, the
     * header was sent, the refusal explained itself and named {@code ai.zeroDataRetention false} as
     * the way out -- and {@code config} answered "Unknown property", because settings are applied
     * through a typed switch that had not been told about this one. Advice that does not work is
     * worse than no advice, so the message and the setting are checked together.</p>
     */
    @Test
    public void thesettingTheRefusalNamesIsOneTheToolAccepts() {
        Configuration config = new Configuration();

        ConfigOverrides.apply(config, "ai.zeroDataRetention", "false");
        assertThat(config.getAi().isZeroDataRetention()).isFalse();

        ConfigOverrides.apply(config, "ai.zeroDataRetention", "true");
        assertThat(config.getAi().isZeroDataRetention()).isTrue();
    }

    @Test
    public void thesettingIsRefusedAnythingThatIsNotYesOrNo() {
        // "Boolean.parseBoolean maps everything that is not true to false", which would turn a typo
        // into a silent opt-out of the promise.
        assertThatThrownBy(() -> ConfigOverrides.apply(new Configuration(),
                                                       "ai.zeroDataRetention", "off"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void thesettingIsOfferedWhenSomebodyMisspellsIt() {
        assertThatThrownBy(() -> ConfigOverrides.apply(new Configuration(), "ai.zeroDataRetension", "true"))
                .hasMessageContaining("zeroDataRetention");
    }
}
