package com.eonmux.cadetcoder.ai;

import com.eonmux.cadetcoder.ai.providers.AuthScheme;
import com.eonmux.cadetcoder.ai.providers.ConnectorProtocol;
import com.eonmux.cadetcoder.ai.providers.ProviderConnector;
import com.eonmux.cadetcoder.net.LLMAuthException;
import com.eonmux.cadetcoder.net.RetryPolicy;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The connector client is the only place that knows both the configured connector id and the
 * backend instance, so it is responsible for making failures name the provider the user configured
 * rather than whatever host the endpoint happens to resolve to.
 */
public class ConnectorAIClientTest {

    private HttpServer server;
    private int        port;

    @Before
    public void setUp() throws Exception {
        System.setProperty(RetryPolicy.BASE_DELAY_MS_PROPERTY, "1");
        System.setProperty(RetryPolicy.MAX_DELAY_MS_PROPERTY, "5");
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            try (InputStream in = exchange.getRequestBody()) {
                in.readAllBytes();
            }
            byte[] out = "{\"error\":{\"message\":\"no access\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(403, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @After
    public void tearDown() {
        System.clearProperty(RetryPolicy.BASE_DELAY_MS_PROPERTY);
        System.clearProperty(RetryPolicy.MAX_DELAY_MS_PROPERTY);
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    public void providerFailureNamesTheConfiguredConnectorNotTheEndpointHost() {
        ProviderConnector connector = new ProviderConnector(
                "opencode-go", "OpenCode Go", List.of(), "http://localhost:" + port + "/v1",
                AuthScheme.BEARER, ConnectorProtocol.OPENAI_CHAT, Map.of());
        ConnectorAIClient client = new ConnectorAIClient(connector, "deepseek-v4-flash", "key", Map.of());

        assertThatThrownBy(() -> client.complete(new PromptData("sys", "hi"), new HashMap<>()))
                .isInstanceOf(LLMAuthException.class)
                .hasMessageContaining("provider 'opencode-go'")
                .hasMessageContaining("HTTP 403")
                .hasMessageContaining("no access");
    }
}
