package com.eonmux.cadetcoder.net;

import com.eonmux.cadetcoder.ai.PromptData;
import com.eonmux.cadetcoder.ai.providers.AuthScheme;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A request to a server on a plain {@code http://} address is sent as HTTP/1.1.
 *
 * <h2>The defect</h2>
 *
 * <p>Java's {@code HttpClient} prefers HTTP/2, and on an address without TLS it asks for it with
 * {@code Upgrade: h2c} on the first request. A model server run by uvicorn -- vLLM, SGLang and most
 * Python servers -- takes such a request as a protocol switch and never reads its body, so every
 * completion was refused with "Field required ... 'loc': 'body'" while the model list, a GET with
 * no body, worked. The request never reached the model. Over TLS the version is agreed in the
 * handshake and no upgrade is asked for, so HTTP/2 is kept there.</p>
 */
public class AplainHttpServerGetsTheRequestBodyTest {

    private HttpServer server;
    private volatile Map<String, String> headers = Map.of();
    private volatile String body = "";

    @Before
    public void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            try (InputStream in = exchange.getRequestBody()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            Map<String, String> seen = new HashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> seen.put(k.toLowerCase(), String.join(",", v)));
            headers = seen;
            byte[] out = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
    }

    @After
    public void tearDown() {
        server.stop(0);
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    public void acompletionToAplainAddressAsksForNoUpgrade() throws Exception {
        OpenAICompatibleBackend backend = new OpenAICompatibleBackend(
                "m", base() + "/v1", "k", AuthScheme.BEARER, Map.of(), Map.of());

        assertThat(backend.complete(new PromptData("sys", "hi"), new HashMap<>())).isEqualTo("ok");

        assertThat(headers).doesNotContainKey("upgrade").doesNotContainKey("http2-settings");
        assertThat(body).contains("\"messages\"");
    }

    @Test
    public void theVersionFollowsTheScheme() {
        assertThat(HttpRequests.to(URI.create("http://192.168.0.28:8000/v1")).build().version())
                .contains(HttpClient.Version.HTTP_1_1);
        assertThat(HttpRequests.to(URI.create("https://api.example.com/v1")).build().version())
                .as("TLS agrees the version in the handshake, so HTTP/2 is left to it")
                .isEmpty();
    }

    @Test
    public void everyRequestIsBuiltWhereTheVersionIsChosen() throws IOException {
        List<String> elsewhere = new ArrayList<>();
        try (Stream<Path> sources = Files.walk(Path.of("src/main/java"))) {
            for (Path source : (Iterable<Path>) sources.filter(p -> p.toString().endsWith(".java"))::iterator) {
                if (source.endsWith(Path.of("net", "HttpRequests.java"))) {
                    continue;
                }
                if (Files.readString(source).contains("HttpRequest.newBuilder(")) {
                    elsewhere.add(source.toString());
                }
            }
        }
        assertThat(elsewhere).as("requests built without HttpRequests.to").isEmpty();
    }
}
