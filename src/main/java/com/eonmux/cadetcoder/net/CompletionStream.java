package com.eonmux.cadetcoder.net;

import com.eonmux.cadetcoder.ai.metrics.ReportedUsage;
import com.eonmux.cadetcoder.ai.metrics.TokenUsage;
import com.eonmux.cadetcoder.security.SecretRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One Chat Completions request sent as a server-sent-event stream, read as it arrives.
 *
 * <h2>Why this exists</h2>
 *
 * <p>A completion sent without {@code stream} is one blocking read: nothing comes back until the
 * provider has generated the whole reply, and a reasoning model spends most of its turn thinking
 * before it writes. Measured on a real run, that was a fifty-second hole after every command with
 * nothing on the screen to say the model was even alive. Streaming splits that hole into the
 * deltas it is made of: the thinking arrives while it is thought, and a stalled request is visible
 * as a stream that stops moving rather than as silence.</p>
 *
 * <h2>What a provider may answer with</h2>
 *
 * <p>Three shapes, all handled here, because the wire is not under our control:</p>
 *
 * <ul>
 *   <li>An event stream ({@code text/event-stream}): {@code data:} lines, each a Chat Completions
 *   chunk carrying a {@code delta}, ending at {@code data: [DONE]}. This is the asked-for shape.</li>
 *   <li>Plain JSON: a proxy or a smaller server that ignores {@code stream} and answers with the
 *   ordinary single-body completion. Reported back to the caller as {@link Result#plainBody}, and
 *   the caller parses it exactly as it always has.</li>
 *   <li>An error: a non-200 status, or a 200 whose stream carries the provider's error object.
 *   Both are raised on the typed failure channel, like the blocking send's failures are.</li>
 * </ul>
 *
 * <h2>The deadline</h2>
 *
 * <p>A blocking {@code read()} on the body cannot be timed out directly, so a watch runs beside
 * the reader and closes the stream when the whole-exchange deadline passes or the reading thread
 * is interrupted. Closing the socket unblocks the read, which surfaces as the ordinary transport
 * failure the retry loop already knows how to classify -- the same failure a blocked completion
 * produces today, so streaming changes the liveness and not the failure semantics.</p>
 */
final class CompletionStream {

    /** Byte ceiling for a plain (non-streamed) body, which is read whole when it is not a stream. */
    private static final long PLAIN_BODY_LIMIT_BYTES = 64L * 1024 * 1024;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * One daemon thread for every stream's watch. Scheduled once a second per stream, it does
     * nothing but compare two clocks and close a socket when one of them runs out.
     */
    private static final ScheduledExecutorService WATCH = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "cadet-stream-watch");
        t.setDaemon(true);
        return t;
    });

    private CompletionStream() {
    }

    /** What one streamed request produced. */
    static final class Result {

        final boolean eventStream;
        final String  plainBody;
        final String  content;
        final String  reasoning;

        private Result(boolean eventStream, String plainBody, String content, String reasoning) {
            this.eventStream = eventStream;
            this.plainBody   = plainBody;
            this.content     = content;
            this.reasoning   = reasoning;
        }

        static Result streamed(String content, String reasoning) {
            return new Result(true, null, content, reasoning);
        }

        static Result plain(String body) {
            return new Result(false, body, null, null);
        }
    }

    /** Told about each reasoning delta as it comes off the wire, for the live display. */
    interface Listener {
        void reasoningDelta(String delta);
    }

    /**
     * Sends {@code request} and reads what comes back.
     *
     * @param client               the shared client
     * @param request              the request, whose body must ask for a stream
     * @param provider             provider label for the typed failures
     * @param model                model label for the typed failures
     * @param endpoint             endpoint for the typed failures
     * @param headerTimeoutSeconds how long to wait for the response headers
     * @param deadlineSeconds      ceiling for the whole exchange, body included
     * @param listener             told about reasoning deltas; may be {@code null}
     * @return what the provider produced
     */
    static Result send(HttpClient client, HttpRequest request, String provider, String model,
                       String endpoint, int headerTimeoutSeconds, int deadlineSeconds,
                       Listener listener) throws Exception {
        CompletableFuture<HttpResponse<InputStream>> inFlight =
                client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        HttpResponse<InputStream> response;
        try {
            response = inFlight.get(Math.max(1, headerTimeoutSeconds), TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            inFlight.cancel(true);
            throw new LLMTransportException(provider, model, endpoint,
                    new java.io.IOException("No response headers within "
                                            + Math.max(1, headerTimeoutSeconds) + "s", e));
        } catch (InterruptedException e) {
            inFlight.cancel(true);
            throw e;
        } catch (java.util.concurrent.ExecutionException e) {
            inFlight.cancel(true);
            // Wrapped, not rethrown raw: the retry loop in the backend classifies failures by
            // type, and a connection refused is exactly as retryable coming from a streamed
            // request as from a plain one.
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof java.io.IOException io) {
                throw new LLMTransportException(provider, model, endpoint, io);
            }
            throw new LLMTransportException(provider, model, endpoint, cause);
        }

        if (response.statusCode() != 200) {
            String body = drain(response.body());
            throw LLMErrorMapper.fromStatus(provider, model, endpoint, response.statusCode(),
                                            body, response.headers());
        }

        String contentType = response.headers().firstValue("Content-Type").orElse("");
        if (!contentType.toLowerCase().contains("text/event-stream")) {
            // Not a stream, though one was asked for: a proxy that ignores the parameter. The
            // body is an ordinary completion, and the caller's own parser reads it unchanged.
            // The watch runs over this read too: a body that stalls here is stalled all the same.
            StringBuilder body = new StringBuilder();
            long deadlineNanos = System.nanoTime()
                    + TimeUnit.SECONDS.toNanos(Math.max(1, deadlineSeconds));
            try {
                BoundedBody.readInto(response.body(), BoundedBody.charsetOf(contentType),
                                     PLAIN_BODY_LIMIT_BYTES, body);
            } catch (java.io.IOException e) {
                if (System.nanoTime() - deadlineNanos >= 0) {
                    throw new LLMTransportException(provider, model, endpoint,
                    new java.io.IOException("Body did not finish within "
                                            + Math.max(1, deadlineSeconds) + "s; headers arrived "
                                            + "but the body stopped", e));
                }
                throw e;
            } finally {
                closeQuietly(response.body());
            }
            return Result.plain(body.toString());
        }

        return readStream(response.body(), provider, model, endpoint, deadlineSeconds, listener);
    }

    /** Reads one event stream to its end, telling the listener about reasoning as it arrives. */
    private static Result readStream(InputStream raw, String provider, String model,
                                     String endpoint, int deadlineSeconds, Listener listener)
            throws Exception {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(1, deadlineSeconds));
        AtomicLong watchdogRuns = new AtomicLong();
        Thread reader = Thread.currentThread();

        // Closing the body is what unblocks a read() parked on a silent socket, so the watch does
        // exactly that -- once the deadline has passed, or once the user interrupted the reader and
        // nothing else would ever notice.
        java.util.concurrent.ScheduledFuture<?> watch = WATCH.scheduleAtFixedRate(() -> {
            watchdogRuns.incrementAndGet();
            if (System.nanoTime() - deadlineNanos >= 0 || reader.isInterrupted()) {
                closeQuietly(raw);
            }
        }, 1, 1, TimeUnit.SECONDS);

        StringBuilder content   = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        boolean done = false;
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(raw, StandardCharsets.UTF_8))) {
            String line;
            while (!done && (line = in.readLine()) != null) {
                if (Thread.currentThread().isInterrupted()) {
                    throw LLMException.stopped(provider, model, endpoint,
                                               new java.io.InterruptedIOException("stream interrupted"));
                }
                done = acceptLine(line, provider, model, endpoint, content, reasoning, listener);
            }
        } catch (java.io.IOException e) {
            // The watch closed the stream under us: deadline, or the user's stop. Tell the two
            // apart the way the blocking send does, by whose interruption it was.
            if (Thread.currentThread().isInterrupted() || e instanceof java.io.InterruptedIOException) {
                throw LLMException.stopped(provider, model, endpoint,
                                           new java.io.InterruptedIOException("stream interrupted"));
            }
            if (System.nanoTime() - deadlineNanos >= 0) {
                throw new LLMTransportException(provider, model, endpoint,
                new java.io.IOException("Stream did not finish within "
                                        + Math.max(1, deadlineSeconds) + "s; headers arrived but the "
                                        + "stream stopped", e));
            }
            throw e;
        } finally {
            watch.cancel(false);
            closeQuietly(raw);
        }
        return Result.streamed(content.toString(), reasoning.toString());
    }

    /**
     * Handles one line of an event stream.
     *
     * @return whether the stream said it was done
     */
    private static boolean acceptLine(String line, String provider, String model, String endpoint,
                                      StringBuilder content, StringBuilder reasoning,
                                      Listener listener) throws Exception {
        if (line == null || line.isEmpty() || line.startsWith(":")) {
            return false; // blank separator or an SSE comment
        }
        String payload;
        if (line.startsWith("data:")) {
            payload = line.substring("data:".length()).trim();
        } else if (line.startsWith("event:") || line.startsWith("id:") || line.startsWith("retry:")) {
            return false; // SSE framing this protocol does not use
        } else {
            // Some smaller servers separate their JSON from any framing and send the chunk bare.
            // A line that opens with the brace of a chunk is read as one; anything else is a
            // protocol slip worth naming rather than dropping.
            if (line.stripLeading().startsWith("{")) {
                payload = line.stripLeading();
            } else {
                throw new LLMProtocolException(provider, model, endpoint,
                "unrecognised line in stream: " + com.eonmux.cadetcoder.security.SecretRedactor
                        .redact(line.length() > 120 ? line.substring(0, 120) : line));
            }
        }
        if (payload.isEmpty()) {
            return false;
        }
        if ("[DONE]".equals(payload)) {
            return true;
        }

        Map<String, Object> chunk = MAPPER.readValue(payload,
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
        if (chunk.containsKey("error")) {
            throw new LLMProtocolException(provider, model, endpoint,
            TransientPayload.describe(payload));
        }
        // The provider's own token counts arrive on their own final chunk, with no choices on it.
        if (chunk.get("usage") != null) {
            ReportedUsage.report(TokenUsage.from(chunk).orElse(null));
        }
        if (!(chunk.get("choices") instanceof List<?> choices) || choices.isEmpty()) {
            return false;
        }
        if (!(choices.get(0) instanceof Map<?, ?> choice)
                || !(choice.get("delta") instanceof Map<?, ?> delta)) {
            return false;
        }        append(delta, "reasoning_content", reasoning, listener);
        append(delta, "reasoning", reasoning, listener);
        append(delta, "content", content, null);
        return false;
    }

    /** Appends one delta field to its builder, telling the listener when it is the reasoning. */
    private static void append(Map<?, ?> delta, String field, StringBuilder into, Listener listener) {
        if (delta.get(field) instanceof String piece && !piece.isEmpty()) {
            into.append(piece);
            if (listener != null) {
                listener.reasoningDelta(piece);
            }
        }
    }

    /** Reads a body for an error report; whatever arrives is more than enough of it. */
    private static String drain(InputStream raw) {
        try {
            StringBuilder out = new StringBuilder();
            BoundedBody.readInto(raw, StandardCharsets.UTF_8, 1024 * 1024, out);
            return out.toString();
        } catch (java.io.IOException e) {
            return "";
        } finally {
            closeQuietly(raw);
        }
    }

    private static void closeQuietly(InputStream raw) {
        try {
            raw.close();
        } catch (java.io.IOException ignored) {
            // the socket is going away either way
        }
    }
}
