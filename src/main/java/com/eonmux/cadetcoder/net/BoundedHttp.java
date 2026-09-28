package com.eonmux.cadetcoder.net;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Sends an HTTP request that cannot hang.
 *
 * <h2>The hole this closes</h2>
 *
 * <p>{@link HttpRequest#timeout(java.time.Duration)} does not bound what its name suggests. Measured
 * against a server that sends headers promptly and then dribbles the body: a two-second request
 * timeout let a six-second body through untouched. It bounds the wait for response HEADERS.</p>
 *
 * <p>That leaves every {@code HttpClient.send} call in the process able to block forever. Once the
 * headers arrive, a body that stops mid-flight without the connection closing has nothing watching
 * it — no timeout fires, no retry runs, and the caller waits until the process is killed. It is the
 * worst failure shape available, because it produces no error to report or retry.</p>
 *
 * <h2>Why every caller goes through here</h2>
 *
 * <p>The deadline is uninteresting for a health probe and critical for a completion, but the failure
 * is identical in both, and a probe that hangs at startup stops the CLI just as dead as a completion
 * that hangs mid-run. Centralising it means a call added later cannot forget to be bounded, and
 * there is one place to fix if the mechanism ever needs to change.</p>
 */
public final class BoundedHttp {

    private BoundedHttp() {
    }

    /**
     * Sends a request, giving up if no complete response arrives within {@code deadlineSeconds}.
     *
     * @param client          the client to send on
     * @param request         the request; its own timeout still bounds the wait for headers
     * @param deadlineSeconds ceiling for the whole exchange, body included; must be positive
     * @return the response
     * @throws IOException          on transport failure, or when the deadline expires
     * @throws InterruptedException when the calling thread is interrupted
     */
    public static HttpResponse<String> send(HttpClient client, HttpRequest request, int deadlineSeconds)
            throws IOException, InterruptedException {
        if (client == null || request == null) {
            throw new IllegalArgumentException("client and request must not be null");
        }
        CompletableFuture<HttpResponse<String>> inFlight =
                client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        try {
            return inFlight.get(Math.max(1, deadlineSeconds), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            // Cancel, or the abandoned exchange holds its connection for the life of the process.
            // Verified that cancelling closes the socket rather than merely detaching from it.
            inFlight.cancel(true);
            throw new IOException("No complete response within " + Math.max(1, deadlineSeconds)
                                  + "s; headers arrived but the body stopped", e);
        } catch (InterruptedException e) {
            inFlight.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            throw unwrap(e);
        }
    }

    /**
     * Unwraps the real cause so it is classified on its own merits.
     *
     * <p>Without this every transport failure reaches the retry policy as an {@code
     * ExecutionException}, which is not an {@link IOException} and would therefore not be recognised
     * as retryable at all.</p>
     */
    private static IOException unwrap(ExecutionException e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        if (cause instanceof IOException io) {
            return io;
        }
        if (cause instanceof RuntimeException runtime) {
            throw runtime;
        }
        return new IOException(cause.getMessage(), cause);
    }
}
