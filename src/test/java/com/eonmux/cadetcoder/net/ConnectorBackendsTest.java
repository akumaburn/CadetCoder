package com.eonmux.cadetcoder.net;

import com.eonmux.cadetcoder.ai.PromptData;
import com.eonmux.cadetcoder.ai.providers.AuthScheme;
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
 * End-to-end tests for the connector backends against a local HTTP server. Validates the
 * request body, auth headers, URL shape, and response parsing without external network.
 */
public class ConnectorBackendsTest {

    private HttpServer server;
    private int        port;

    // Captured from the most recent request.
    private volatile String lastPath;
    private volatile String lastBody;
    private volatile Map<String, String> lastHeaders;
    private volatile String responseBody = "{}";
    private volatile int    status       = 200;

    @Before
    public void setUp() throws Exception {
        // Keep the retry backoff imperceptible; the delays themselves are covered by RetryPolicyTest.
        System.setProperty(RetryPolicy.BASE_DELAY_MS_PROPERTY, "1");
        System.setProperty(RetryPolicy.MAX_DELAY_MS_PROPERTY, "5");
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            try (InputStream in = exchange.getRequestBody()) {
                lastBody = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            lastPath = exchange.getRequestURI().toString();
            Map<String, String> headers = new HashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), String.join(",", v)));
            lastHeaders = headers;

            byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        port   = server.getAddress().getPort();
        status = 200;
    }

    @After
    public void tearDown() {
        System.clearProperty(RetryPolicy.BASE_DELAY_MS_PROPERTY);
        System.clearProperty(RetryPolicy.MAX_DELAY_MS_PROPERTY);
        if (server != null) {
            server.stop(0);
        }
    }

    private String base() {
        return "http://localhost:" + port;
    }

    @Test
    public void openAiCompatibleSendsBearerAndParsesContent() throws Exception {
        responseBody = "{\"choices\":[{\"message\":{\"content\":\"hello-oai\"}}]}";
        OpenAICompatibleBackend backend = new OpenAICompatibleBackend(
                "gpt-test", base() + "/v1", "testkey", AuthScheme.BEARER, Map.of(), Map.of());

        String result = backend.complete(new PromptData("sys", "hi"), new HashMap<>());

        assertThat(result).isEqualTo("hello-oai");
        assertThat(lastPath).isEqualTo("/v1/chat/completions");
        assertThat(lastHeaders.get("authorization")).isEqualTo("Bearer testkey");
        assertThat(lastBody).contains("\"model\":\"gpt-test\"");
        assertThat(lastBody).contains("\"messages\"");
    }

    @Test
    public void openAiCompatibleAppliesExtraHeadersAndQueryParams() throws Exception {
        responseBody = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}";
        OpenAICompatibleBackend backend = new OpenAICompatibleBackend(
                "m", base(), "k", AuthScheme.API_KEY_HEADER,
                Map.of("X-Title", "CadetCoder"), Map.of("api-version", "2024-06-01"));

        String result = backend.complete(new PromptData("sys", "hi"), new HashMap<>());

        assertThat(result).isEqualTo("ok");
        assertThat(lastPath).isEqualTo("/chat/completions?api-version=2024-06-01");
        assertThat(lastHeaders.get("api-key")).isEqualTo("k");
        assertThat(lastHeaders.get("x-title")).isEqualTo("CadetCoder");
        assertThat(lastHeaders).doesNotContainKey("authorization");
    }

    @Test
    public void anthropicSendsApiKeyVersionAndParsesContent() throws Exception {
        responseBody = "{\"content\":[{\"type\":\"text\",\"text\":\"hello-anthropic\"}]}";
        AnthropicBackend backend = new AnthropicBackend("claude-test", base(), "akey");

        String result = backend.complete(new PromptData("be helpful", "hi"), new HashMap<>());

        assertThat(result).isEqualTo("hello-anthropic");
        assertThat(lastPath).isEqualTo("/messages");
        assertThat(lastHeaders.get("x-api-key")).isEqualTo("akey");
        assertThat(lastHeaders.get("anthropic-version")).isEqualTo("2023-06-01");
        assertThat(lastBody).contains("\"max_tokens\"");
        assertThat(lastBody).contains("\"system\":\"be helpful\"");
    }

    @Test
    public void googleSendsGoogApiKeyAndParsesCandidate() throws Exception {
        responseBody = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hello-google\"}]}}]}";
        GoogleGenerativeAIBackend backend = new GoogleGenerativeAIBackend("gemini-x", base(), "gkey");

        String result = backend.complete(new PromptData("sys", "hi"), new HashMap<>());

        assertThat(result).isEqualTo("hello-google");
        assertThat(lastPath).isEqualTo("/models/gemini-x:generateContent");
        assertThat(lastHeaders.get("x-goog-api-key")).isEqualTo("gkey");
        assertThat(lastBody).contains("\"contents\"");
        assertThat(lastBody).contains("\"generationConfig\"");
    }

    @Test
    public void unreachableEndpointThrowsTransportFailure() throws Exception {
        server.stop(0); // force a connection failure
        String base = base();
        OpenAICompatibleBackend backend = new OpenAICompatibleBackend(
                "m", base, "k", AuthScheme.BEARER, Map.of(), Map.of());

        // Strengthened: this used to accept EITHER an "Error:" string or any exception at all, which
        // is exactly the ambiguity that let a failed call pass for a completion. A dead endpoint must
        // now raise a typed transport failure that names the endpoint it could not reach.
        assertThatThrownBy(() -> backend.complete(new PromptData("s", "u"), new HashMap<>()))
                .isInstanceOf(LLMTransportException.class)
                .hasMessageContaining("Cannot reach " + base + "/chat/completions");
    }

    @Test
    public void forbiddenStatusThrowsAuthFailureNamingTheProviderHost() throws Exception {
        status = 403;
        responseBody = "{\"type\":\"error\",\"error\":{\"message\":\"no access\"}}";
        OpenAICompatibleBackend backend = new OpenAICompatibleBackend(
                "m", base() + "/v1", "k", AuthScheme.BEARER, Map.of(), Map.of());

        // The reported defect end to end: a 403 must not come back as completion text.
        assertThatThrownBy(() -> backend.complete(new PromptData("s", "u"), new HashMap<>()))
                .isInstanceOf(LLMAuthException.class)
                .hasMessageContaining("HTTP 403")
                .hasMessageContaining("The key was accepted")
                .hasMessageContaining("no access");
    }

    @Test
    public void unparseableSuccessBodyThrowsProtocolFailure() throws Exception {
        responseBody = "not json at all";
        OpenAICompatibleBackend backend = new OpenAICompatibleBackend(
                "m", base() + "/v1", "k", AuthScheme.BEARER, Map.of(), Map.of());

        assertThatThrownBy(() -> backend.complete(new PromptData("s", "u"), new HashMap<>()))
                .isInstanceOf(LLMProtocolException.class)
                .hasMessageContaining("failed to parse response");
    }
}
