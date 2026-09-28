package com.eonmux.cadetcoder.net;

import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** The guard that stops a stalled response body from blocking a caller forever. */
public class BoundedHttpTest {

    private HttpServer server;

    /**
     * Releases any handler that is deliberately stalling.
     *
     * <p>A wall-clock sleep in the handler makes the test cost what it sleeps, however quickly the
     * assertion finishes. Waiting on this instead means the stall lasts exactly as long as the
     * client takes to give up.</p>
     */
    private final CountDownLatch release = new CountDownLatch(1);

    @After
    public void tearDown() {
        release.countDown();
        if (server != null) {
            server.stop(0);
        }
    }

    /** Blocks until the test is done with this handler, or the safety bound elapses. */
    private void stallUntilReleased() throws InterruptedException {
        release.await(30, TimeUnit.SECONDS);
    }

    private String start(com.sun.net.httpserver.HttpHandler handler) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/probe", handler);
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/probe";
    }

    private static HttpRequest get(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build();
    }

    @Test
    public void aCompleteResponseComesBackNormally() throws Exception {
        String url = start(x -> {
            byte[] body = "pong".getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(200, body.length);
            try (OutputStream o = x.getResponseBody()) {
                o.write(body);
            }
        });

        assertThat(BoundedHttp.send(HttpClient.newHttpClient(), get(url), 10).body()).isEqualTo("pong");
    }

    // A hard bound, because this is the one test whose subject is "does not block forever". Without
    // it a regression that removes the deadline does not fail here -- it hangs, and takes the whole
    // suite with it, which is a worse outcome than the bug it was meant to report.
    @Test(timeout = 20_000)
    public void aBodyThatStopsMidFlightGivesUpInsteadOfBlocking() throws Exception {
        // The failure this exists for. HttpRequest.timeout bounds the wait for HEADERS, so a body
        // that stalls after they arrive has nothing watching it: no timeout, no error, no retry.
        String url = start(x -> {
            try {
                x.sendResponseHeaders(200, 0);
                OutputStream o = x.getResponseBody();
                o.write("{\"partial\":".getBytes(StandardCharsets.UTF_8));
                o.flush();
                stallUntilReleased();
            } catch (Exception ignored) {
                // the client gives up first, which is the point
            }
        });

        long start = System.currentTimeMillis();
        Throwable thrown = catchThrowable(() -> BoundedHttp.send(HttpClient.newHttpClient(), get(url), 1));
        long elapsed = System.currentTimeMillis() - start;

        assertThat(thrown).isInstanceOf(IOException.class);
        assertThat(thrown).hasMessageContaining("body stopped");
        assertThat(elapsed).isLessThan(10_000L);
    }

    @Test
    public void aTransportFailureArrivesAsAnIOExceptionNotAnExecutionException() throws Exception {
        // Unwrapping matters: an ExecutionException is not an IOException, so the retry policy
        // would not recognise a wrapped transport failure as retryable at all.
        HttpRequest request = get("http://127.0.0.1:1/probe");

        Throwable thrown = catchThrowable(() -> BoundedHttp.send(HttpClient.newHttpClient(), request, 5));

        assertThat(thrown).isInstanceOf(IOException.class);
    }

    @Test
    public void nullArgumentsAreRejectedAtTheBoundary() {
        assertThat(catchThrowable(() -> BoundedHttp.send(null, get("http://x/"), 5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(catchThrowable(() -> BoundedHttp.send(HttpClient.newHttpClient(), null, 5)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
