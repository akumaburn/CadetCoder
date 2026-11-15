package com.eonmux.cadetcoder.net;

import com.eonmux.cadetcoder.ai.PromptData;
import com.eonmux.cadetcoder.ai.providers.AuthScheme;
import com.eonmux.cadetcoder.ui.ThinkingView;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a streamed completion does, on the wire.
 *
 * <p><b>The defect</b>: a completion sent without {@code stream} is one blocking read, and a
 * reasoning model spends most of its turn thinking before it writes -- measured on a real run,
 * fifty seconds after every command with nothing on the screen saying the model was alive. The
 * streamed request is what turns that silence into the deltas it is made of.</p>
 *
 * <p><b>What is locked here</b>: the deltas arrive in order (reasoning first, then content), the
 * assembled answer is exactly what the chunks carried, a provider that answers the request with
 * plain JSON anyway is parsed as always, a refusal to stream is remembered so it is not asked for
 * twice, an error object inside a 200 stream is a typed failure, and a stream that stops moving
 * dies at the deadline instead of hanging forever.</p>
 */
public class CompletionStreamTest {

    private HttpServer          server;
    private final AtomicInteger requests = new AtomicInteger();
    /** The body of the most recent request, read by tests that assert what was asked for. */
    private final ConcurrentLinkedQueue<String> bodies = new ConcurrentLinkedQueue<>();

    /** @return what the last request to arrive carried */
    private String lastBody() {
        return bodies.isEmpty() ? "" : bodies.stream().reduce((a, b) -> b).orElse("");
    }

    private String baseUrl;

    @Before
    public void setUp() {
        // The run stays short: these tests are about shapes, not about waiting.
        System.setProperty(RetryPolicy.MAX_ATTEMPTS_PROPERTY, "3");
        System.setProperty(RetryPolicy.BASE_DELAY_MS_PROPERTY, "1");
        System.setProperty(RetryPolicy.MAX_DELAY_MS_PROPERTY, "5");
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        System.clearProperty(RetryPolicy.MAX_ATTEMPTS_PROPERTY);
        System.clearProperty(RetryPolicy.BASE_DELAY_MS_PROPERTY);
        System.clearProperty(RetryPolicy.MAX_DELAY_MS_PROPERTY);
        System.clearProperty(AbstractLLMBackend.RESPONSE_DEADLINE_PROPERTY);
    }

    /** Starts a server whose /chat/completions handler is supplied by the test. */
    private void serve(Consumer<com.sun.net.httpserver.HttpExchange> handler) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // Daemon threads: a handler that blocks forever (the stalled-stream test's whole point)
        // must not hold the test JVM open after the server has stopped.
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/v1/chat/completions", exchange -> {
            requests.incrementAndGet();
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            handler.accept(exchange);
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    /** Responds with one whole body of the given content type. */
    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status,
                                String contentType, String body) {
        try {
            exchange.getResponseHeaders().set("Content-Type", contentType);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private OpenAICompatibleBackend backend(String model) {
        OpenAICompatibleBackend backend = new OpenAICompatibleBackend(
                model, baseUrl, "test-key", AuthScheme.BEARER, Map.of(), Map.of());
        backend.setProviderId("streamtest-" + model);
        return backend;
    }

    private String complete(OpenAICompatibleBackend backend) throws Exception {
        return backend.complete(new PromptData("system", "user"), Map.of("completionTimeout", 5));
    }

    /** The event stream of one ordinary reasoning turn: two thoughts, then the answer. */
    private static final String REASONING_THEN_ANSWER = """
            data: {"choices":[{"delta":{"reasoning_content":"looking at "}}]}

            data: {"choices":[{"delta":{"reasoning_content":"the files\\n"}}]}

            data: {"choices":[{"delta":{"content":"the answer"}}]}

            data: {"choices":[{"delta":{"content":", assembled"}}]}

            data: {"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":5}}

            data: [DONE]

            """;

    @Test
    public void deltasArriveInOrderAndTheAnswerIsAssembled() throws Exception {
        List<String> seen = new ArrayList<>();
        serve(ex -> respond(ex, 200, "text/event-stream", REASONING_THEN_ANSWER));

        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(baseUrl + "/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers
                              .ofString("{\"model\":\"m\"}"))
                .build();

        CompletionStream.Result result = CompletionStream.send(
                client, request, "p", "m", baseUrl + "/chat/completions",
                5, 60, seen::add);

        assertThat(seen).containsExactly("looking at ", "the files\n");
        assertThat(result.eventStream).isTrue();
        assertThat(result.content).isEqualTo("the answer, assembled");
        assertThat(result.reasoning).isEqualTo("looking at the files\n");
    }

    @Test
    public void theStreamedRequestCarriesTheStreamFlags() throws Exception {
        serve(ex -> respond(ex, 200, "text/event-stream", REASONING_THEN_ANSWER));

        complete(backend("flag-check"));

        String body = lastBody();
        assertThat(body).contains("\"stream\":true")
                        .contains("\"include_usage\":true");
    }

    @Test
    public void aStreamedTurnAnswersThroughTheBackendAndReportsItsThinking() throws Exception {
        serve(ex -> respond(ex, 200, "text/event-stream", REASONING_THEN_ANSWER));

        String answer = complete(backend("integration"));

        assertThat(answer).isEqualTo("the answer, assembled");
        // The reasoning went to the side channel, where the view finds it -- and where the live
        // span, having already shown it, is what stops it being shown twice.
        assertThat(ThinkingView.drain()).isEqualTo("looking at the files\n");
    }

    @Test
    public void aPlainJsonAnswerToAStreamRequestIsParsedAsAlways() throws Exception {
        serve(ex -> respond(ex, 200, "application/json",
                "{\"choices\":[{\"message\":{\"content\":\"done\"}}]}"));

        String answer = complete(backend("plain-answerer"));

        assertThat(answer).isEqualTo("done");
        assertThat(lastBody()).contains("\"stream\":true");
    }

    @Test
    public void aProviderThatRefusesTheStreamIsNotAskedForOneAgain() throws Exception {
        serve(ex -> {
            if (requests.get() == 1) {
                respond(ex, 400, "application/json",
                        "{\"error\":{\"message\":\"stream_options is not supported by this model\"}}");
            } else {
                respond(ex, 200, "application/json",
                        "{\"choices\":[{\"message\":{\"content\":\"done\"}}]}");
            }
        });
        // A unique model name: the refusal memory is per provider and model, and shared by the
        // whole process, so this test must not turn streaming off for the others.
        OpenAICompatibleBackend refusing = backend("refuser-" + System.nanoTime());

        assertThat(complete(refusing)).isEqualTo("done"); // streamed once, refused, plain fallback
        assertThat(complete(refusing)).isEqualTo("done"); // and never asked again

        assertThat(requests.get()).isEqualTo(3); // stream, plain fallback, then a plain request
        assertThat(lastBody()).doesNotContain("\"stream\"");
    }

    @Test
    public void anErrorObjectInsideAStreamIsATypedFailure() throws Exception {
        serve(ex -> respond(ex, 200, "text/event-stream",
                "data: {\"error\":{\"message\":\"model overloaded\"}}\n\ndata: [DONE]\n"));

        assertThatThrownBy(() -> complete(backend("error-carrier")))
                .isInstanceOf(LLMProtocolException.class)
                .hasMessageContaining("model overloaded");
    }

    @Test
    public void aStreamThatStopsMovingDiesAtTheDeadline() throws Exception {
        // Two seconds, instead of the default ten minutes: the point is that it dies at all.
        System.setProperty(AbstractLLMBackend.RESPONSE_DEADLINE_PROPERTY, "2");
        serve(ex -> {
            try {
                ex.getResponseHeaders().set("Content-Type", "text/event-stream");
                ex.sendResponseHeaders(200, 0); // chunked, and then nothing, ever
                java.util.concurrent.CountDownLatch forever = new java.util.concurrent.CountDownLatch(1);
                forever.await(30, java.util.concurrent.TimeUnit.SECONDS); // released by tearDown's stop
            } catch (Exception ignored) {
                // the client is gone either way
            }
        });

        long started = System.currentTimeMillis();
        assertThatThrownBy(() -> complete(backend("stallers")))
                .isInstanceOf(LLMTransportException.class);
        long taken = System.currentTimeMillis() - started;

        assertThat(taken).as("the deadline, not the test server, ends it").isLessThan(15_000);
    }
}
