package com.eonmux.cadetcoder.net;

import com.eonmux.cadetcoder.ai.PromptData;
import com.eonmux.cadetcoder.ai.providers.AuthScheme;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Characterises how a provider failure that arrives MID-RUN is handled.
 *
 * <p>These drive a real {@link HttpServer} rather than a mocked client, because the behaviour under
 * test is the interaction between the JDK HTTP stack, the retry loop, and the response parser --
 * which a mock of any one of them would assume rather than exercise.</p>
 */
public class MidRunConnectionFailureTest {

    private HttpServer          server;
    private final AtomicInteger requests = new AtomicInteger();

    private String baseUrl;

    @Before
    public void setUp() {
        // Keep the run short: these tests are about which failures are retried, not about waiting.
        System.setProperty(RetryPolicy.MAX_ATTEMPTS_PROPERTY, "3");
        System.setProperty(RetryPolicy.BASE_DELAY_MS_PROPERTY, "1");
        System.setProperty(RetryPolicy.MAX_DELAY_MS_PROPERTY, "5");
    }

    /**
     * Releases any handler that is deliberately stalling.
     *
     * <p>A wall-clock sleep costs the test what it sleeps however fast the assertion finishes;
     * waiting on this makes the stall last exactly as long as the client takes to give up.</p>
     */
    private final java.util.concurrent.CountDownLatch release =
            new java.util.concurrent.CountDownLatch(1);

    @After
    public void tearDown() {
        release.countDown();
        if (server != null) {
            server.stop(0);
        }
        System.clearProperty(RetryPolicy.MAX_ATTEMPTS_PROPERTY);
        System.clearProperty(RetryPolicy.BASE_DELAY_MS_PROPERTY);
        System.clearProperty(RetryPolicy.MAX_DELAY_MS_PROPERTY);
    }

    /** Starts a server whose /chat/completions handler is supplied by the test. */
    private void serve(Consumer<com.sun.net.httpserver.HttpExchange> handler) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.incrementAndGet();
            handler.accept(exchange);
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) {
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String complete() throws Exception {
        OpenAICompatibleBackend backend = new OpenAICompatibleBackend(
                "test-model", baseUrl, "test-key", AuthScheme.BEARER, Map.of(), Map.of());
        backend.setProviderId("testprovider");
        return backend.complete(new PromptData("system", "user"), Map.of("completionTimeout", 5));
    }

    @Test
    public void a200CarryingAnOverloadedErrorInsteadOfChoicesIsRetried() throws Exception {
        // Gateways in front of a model (Cloudflare, nginx, provider edge) routinely answer 200 with
        // an error payload. The status says success, so status-code classification alone reads this
        // as "the model replied with nothing" and gives up -- ending a long run on a blip.
        serve(x -> respond(x, 200, "{\"error\":{\"message\":\"Overloaded\",\"type\":\"overloaded_error\"}}"));

        Throwable thrown = catchThrowable(this::complete);

        assertThat(thrown).isInstanceOf(LLMException.class);
        assertThat(requests.get())
                .as("a transient overload reported under a 200 must be retried, not fatal")
                .isGreaterThan(1);
        // The endpoint answered, so pointing the user at their endpoint configuration would send
        // them after a problem they do not have.
        assertThat(thrown.getMessage())
                .contains("provider-side")
                .doesNotContain("Check the endpoint is running");
    }

    @Test
    public void a200WhoseBodyWasTruncatedMidFlightIsRetried() throws Exception {
        // A connection dropped while the body is in flight can surface as a short read rather than
        // an IOException, leaving unparseable JSON. That is a transport failure wearing a 200.
        serve(x -> respond(x, 200, "{\"choices\":[{\"message\":{\"cont"));

        Throwable thrown = catchThrowable(this::complete);

        assertThat(thrown).isInstanceOf(LLMException.class);
        assertThat(requests.get())
                .as("a truncated body is a transport failure and must be retried")
                .isGreaterThan(1);
    }

    @Test
    public void aServerErrorIsRetried() throws Exception {
        serve(x -> respond(x, 503, "{\"error\":\"service unavailable\"}"));

        catchThrowable(this::complete);

        assertThat(requests.get()).isEqualTo(3);
    }

    @Test
    public void aConnectionClosedWithoutAnyResponseIsRetried() throws Exception {
        // The classic stale-pooled-connection failure: the socket dies with nothing written back.
        serve(x -> x.close());

        catchThrowable(this::complete);

        assertThat(requests.get())
                .as("a dropped connection must be retried")
                .isGreaterThan(1);
    }

    @Test
    public void aBadRequestIsNotRetried() throws Exception {
        serve(x -> respond(x, 400, "{\"error\":{\"message\":\"bad model\"}}"));

        catchThrowable(this::complete);

        assertThat(requests.get()).as("repeating a malformed request cannot help").isEqualTo(1);
    }

    @Test
    public void aRealAnswerAboutRateLimitsIsNotMistakenForOne() throws Exception {
        // The safety property that governs how the 200 check is written. A completion's text is
        // arbitrary: ask this tool why a provider is rate-limiting and the correct answer contains
        // every word a naive body scan looks for. Retrying it would discard a good response, bill
        // for it twice, and do so precisely when the user asked about failures.
        String answer = "You are seeing 429 rate limit errors because the provider is overloaded "
                        + "and temporarily unavailable; try again later after a connection reset.";
        serve(x -> respond(x, 200, "{\"choices\":[{\"message\":{\"content\":\"" + answer + "\"}}]}"));

        assertThat(complete()).isEqualTo(answer);
        assertThat(requests.get()).as("a usable completion is never retried").isEqualTo(1);
    }

    @Test
    public void a200WithAnErrorNodeThatIsNotTransientStaysFatal() throws Exception {
        // A 200 carrying a terminal error is still terminal; retrying it just wastes the budget.
        serve(x -> respond(x, 200, "{\"error\":{\"message\":\"model does not exist\"}}"));

        catchThrowable(this::complete);

        assertThat(requests.get()).isEqualTo(1);
    }

    // Bounded for the same reason the assertion exists: a regression here hangs rather than fails,
    // and a hanging test reports nothing while stopping everything.
    @Test(timeout = 20_000)
    public void aBodyThatStallsAfterTheHeadersDoesNotHangForever() throws Exception {
        // HttpRequest.timeout() bounds the wait for HEADERS only -- verified against a server that
        // sent headers promptly and then dribbled the body well past the timeout. Without a second
        // budget covering the body, a wedged upstream freezes the run with no timeout and no retry.
        System.setProperty(AbstractLLMBackend.RESPONSE_DEADLINE_PROPERTY, "1");
        // One attempt: this is about the deadline firing at all, not about retrying it, and every
        // extra attempt costs the test another full deadline.
        System.setProperty(RetryPolicy.MAX_ATTEMPTS_PROPERTY, "1");
        serve(x -> {
            try {
                x.sendResponseHeaders(200, 0);
                OutputStream out = x.getResponseBody();
                out.write("{\"choices\":[".getBytes(StandardCharsets.UTF_8));
                out.flush();
                release.await(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // the client gives up first, which is the point
            }
        });

        try {
            long start = System.currentTimeMillis();
            Throwable thrown = catchThrowable(this::complete);
            long elapsed = System.currentTimeMillis() - start;

            assertThat(thrown).isInstanceOf(LLMException.class);
            assertThat(elapsed).as("must give up rather than block on a stalled body").isLessThan(20_000L);
        } finally {
            System.clearProperty(AbstractLLMBackend.RESPONSE_DEADLINE_PROPERTY);
        }
    }

    @Test
    public void aRecoveredFailureReturnsTheAnswerRatherThanPropagating() throws Exception {
        serve(x -> {
            if (requests.get() == 1) {
                respond(x, 503, "{\"error\":\"try again\"}");
            } else {
                respond(x, 200, "{\"choices\":[{\"message\":{\"content\":\"recovered\"}}]}");
            }
        });

        assertThat(complete()).isEqualTo("recovered");
        assertThat(requests.get()).isEqualTo(2);
    }
}
