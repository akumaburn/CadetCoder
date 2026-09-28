package com.eonmux.cadetcoder.net;

import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.logging.CadetLogger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Abstract base class for LLM backends providing common functionality: a shared, connect-timed-out
 * {@link HttpClient} and the retry loop every backend sends its requests through.
 */
public abstract class AbstractLLMBackend implements LLMBackend {

    /**
     * Initialized lazily and defensively: the concrete backends already do this because building a
     * {@link CadetLogger} touches configuration and the filesystem, which can fail under test.
     */
    private static volatile CadetLogger logger;

    /** Connect timeout for every provider call; without one a black-holed endpoint hangs forever. */
    public static final  String CONNECT_TIMEOUT_PROPERTY        = "cadet.llm.connectTimeoutSeconds";
    public static final  int    DEFAULT_CONNECT_TIMEOUT_SECONDS = 10;
    private static final int    MAX_CONNECT_TIMEOUT_SECONDS     = 300;

    /**
     * Deadline for the whole exchange, body included.
     *
     * <p>This exists because {@link HttpRequest#timeout(Duration)} does not cover what its name
     * suggests. Measured against a server that sends headers promptly and then dribbles the body:
     * a two-second request timeout let a six-second body through untouched. The request timeout
     * bounds the wait for response HEADERS only.</p>
     *
     * <p>That split is right for a completion — time-to-first-byte is predictable, while generation
     * time scales with the answer and should not be capped at some arbitrary figure — but it leaves
     * a hole. Once headers arrive, a body that stops mid-flight without the connection closing
     * blocks forever: no timeout fires, no retry runs, and the run simply freezes. This deadline is
     * the backstop for that, so it is set far above any real generation rather than as a latency
     * budget.</p>
     */
    public static final  String RESPONSE_DEADLINE_PROPERTY        = "cadet.llm.responseDeadlineSeconds";
    public static final  int    DEFAULT_RESPONSE_DEADLINE_SECONDS = 600;
    private static final int    MAX_RESPONSE_DEADLINE_SECONDS     = 3600;

    /**
     * One client for the whole process. {@link HttpClient} is thread-safe and owns a connection pool
     * and selector thread, so building one per request (as this class used to) threw away keep-alive
     * connections and leaked threads.
     */
    private static final HttpClient SHARED_CLIENT = newSharedClient();

    protected String     modelName;
    protected String     apiEndpoint;
    protected HttpClient httpClient;
    protected boolean    available;

    /** Label identifying the provider in failure messages; see {@link #setProviderId(String)}. */
    private String providerId;

    public AbstractLLMBackend(String modelName, String apiEndpoint) {
        this.modelName   = modelName;
        this.apiEndpoint = apiEndpoint;
        this.httpClient  = SHARED_CLIENT;
        this.available   = false;
        this.providerId  = hostOf(apiEndpoint);
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    @Override
    public final String getApiEndpoint() {
        return apiEndpoint;
    }

    /**
     * Label identifying the provider in failure messages and logs. Defaults to the endpoint host,
     * which is all a generic OpenAI-compatible backend knows about itself.
     */
    protected final String providerId() {
        return providerId;
    }

    /**
     * Names the provider this backend speaks to. Backends bound to a single connector set it in
     * their constructor; {@code ConnectorAIClient} sets it again from the configured connector id,
     * so a failure says "provider 'openrouter'" rather than "provider 'openrouter.ai'". Blank values
     * are ignored so the host-derived default always survives.
     */
    public void setProviderId(String providerId) {
        if (providerId != null && !providerId.isBlank()) {
            this.providerId = providerId;
        }
    }

    /**
     * Sends a request, retrying transient failures with jittered exponential backoff.
     *
     * <p>Returns the response for any status the policy considers terminal — including a non-200 one
     * — so the calling backend keeps its own response logging and maps the failure itself through
     * {@link LLMErrorMapper}.</p>
     *
     * <p>Two kinds of failure are thrown from here rather than returned, because there is no response
     * worth handing back: one that never arrived (IO, timeout, interruption), and one that arrived as
     * an HTTP 200 carrying no usable completion. The second is retried like any other transport
     * failure — see {@link TransientPayload} for why a 200 can be one, and for why the decision is
     * structural rather than a scan of the body text.</p>
     *
     * @param request the request to send; it is immutable and safe to resend
     * @return the provider's response, never null
     * @throws LLMTransportException when the endpoint could not be reached, or answered without a
     *                               usable completion, on every attempt
     */
    protected HttpResponse<String> sendWithRetry(HttpRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        return sendWithRetry(() -> request);
    }

    /**
     * The same, for a request that cannot simply be sent again.
     *
     * <p>A signature is computed over the moment it was made as well as over the payload. AWS
     * refuses one more than a few minutes old, and the backoff between attempts is measured in
     * exactly those minutes -- so a request signed once and resent was rejected for being stale,
     * and the rejection is not retryable, which turned every transient Bedrock failure into a
     * hard authentication error. Asking for the request again per attempt is what lets the
     * backend re-sign it.</p>
     *
     * @param nextAttempt builds the request to send; called once per attempt, never null
     * @return the provider's response, never null
     * @throws LLMTransportException when the endpoint could not be reached, or answered without a
     *                               usable completion, on every attempt
     */
    protected HttpResponse<String> sendWithRetry(java.util.function.Supplier<HttpRequest> nextAttempt) {
        if (nextAttempt == null) {
            throw new IllegalArgumentException("something has to build the request");
        }
        RetryPolicy policy   = RetryPolicy.fromSystemProperties();
        String      provider = providerId();

        for (int attempt = 1; ; attempt++) {
            HttpRequest request = nextAttempt.get();
            if (request == null) {
                throw new IllegalArgumentException("request must not be null");
            }
            String endpoint = request.uri() != null ? request.uri().toString() : apiEndpoint;

            HttpResponse<String> response = null;
            LLMException         failure;
            try {
                response = sendOnce(request);
                failure  = null;
            } catch (IOException e) {
                failure = new LLMTransportException(provider, modelName, endpoint, e);
            } catch (InterruptedException e) {
                // Never retry an interruption: the caller asked us to stop. Reported as STOPPED
                // rather than as a transport failure, which claimed the endpoint was unreachable
                // and, being retryable, offered the user's own stop back to them to try again.
                Thread.currentThread().interrupt();
                throw LLMException.stopped(provider, modelName, endpoint, e);
            }

            if (response != null) {
                if (!RetryPolicy.isRetryableStatus(response.statusCode())) {
                    if (!isUnusableSuccess(response)) {
                        // A 200 carrying the provider's own error object rather than a completion,
                        // in wording no retry would help. Reported here, with what the provider
                        // actually said, because the backend that parses this body next finds no
                        // choices in it and can only say so -- which is how a gateway's "this model
                        // is not enabled for your key" reached the user as "returned an unusable
                        // response". See TransientPayload.carriesProviderError.
                        if (response.statusCode() == 200
                            && TransientPayload.carriesProviderError(response.body())) {
                            throw new LLMProtocolException(provider, modelName, endpoint,
                                                           TransientPayload.describe(response.body()));
                        }
                        return response;
                    }
                    // A 200 that carries no completion: see TransientPayload for why this is a
                    // transport failure rather than the model answering with nothing.
                    failure = LLMTransportException.incompleteResponse(
                            provider, modelName, endpoint, TransientPayload.describe(response.body()));
                    response = null;
                } else {
                    failure = LLMErrorMapper.fromResponse(provider, modelName, endpoint, response);
                }
            }

            if (!policy.shouldRetry(failure, attempt)) {
                if (response != null) {
                    return response;
                }
                throw failure;
            }

            long delay = policy.delayMillis(attempt, failure.getRetryAfter());
            announceRetry(failure, attempt, policy.getMaxAttempts(), delay);
            sleep(delay, provider, endpoint);
        }
    }

    /**
     * Whether a response the policy considers terminal is in fact a transient failure in disguise.
     *
     * <p>Only 200s are examined, and only structurally: a body carrying a real completion is never
     * treated as a failure, whatever the model happened to write in it.</p>
     */
    private static boolean isUnusableSuccess(HttpResponse<String> response) {
        return response.statusCode() == 200 && TransientPayload.isTransient(response.body());
    }

    /**
     * Sends one attempt, bounded by {@link #RESPONSE_DEADLINE_PROPERTY}.
     *
     * <p>Delegates to {@link BoundedHttp}, which is where the reason for not using the synchronous
     * {@code send} is recorded: it offers no way to stop waiting on a stalled body.</p>
     *
     * @throws IOException          on transport failure, or when the deadline expires
     * @throws InterruptedException when the caller is interrupted
     */
    private HttpResponse<String> sendOnce(HttpRequest request) throws IOException, InterruptedException {
        return BoundedHttp.send(httpClient, request, responseDeadlineSeconds());
    }

    /** @return the whole-exchange deadline in seconds, from the system property or the default */
    private static int responseDeadlineSeconds() {
        String configured = System.getProperty(RESPONSE_DEADLINE_PROPERTY);
        if (configured != null) {
            try {
                int value = Integer.parseInt(configured.trim());
                if (value >= 1 && value <= MAX_RESPONSE_DEADLINE_SECONDS) {
                    return value;
                }
            } catch (NumberFormatException ignored) {
                // fall through to the default
            }
        }
        return DEFAULT_RESPONSE_DEADLINE_SECONDS;
    }

    /** Tells the user (and the log) that a transient failure is being retried, and for how long. */
    private void announceRetry(LLMException failure, int attempt, int maxAttempts, long delayMillis) {
        String message = describeRetry(failure) + "; retrying (attempt " + (attempt + 1) + "/"
                + maxAttempts + ") in " + delayMillis + "ms";
        // warnToFile, not warn: CadetLogger.warn now renders its own console line, so pairing it
        // with printWarning put the same retry notice on screen twice per attempt.
        CadetLogger log = logger();
        if (log != null) {
            log.warnToFile(message);
        }
        OutputFormatter.printWarning(message);
    }

    private String describeRetry(LLMException failure) {
        String provider = LLMException.providerLabel(failure.getProvider());
        switch (failure.getKind()) {
            case RATE_LIMITED:
                return "Rate limited by provider '" + provider + "' (HTTP 429)";
            case SERVER_ERROR:
                return "Provider '" + provider + "' returned HTTP " + failure.getStatusCode();
            case TRANSPORT:
                if (failure.getStatusCode() == 200) {
                    // Reached, answered, and the answer was unusable -- not the same thing as
                    // unreachable, and saying so would send the user after the wrong problem.
                    return "Provider '" + provider + "' returned an incomplete response";
                }
                return failure.getStatusCode() > 0
                       ? "Request to provider '" + provider + "' timed out (HTTP "
                               + failure.getStatusCode() + ")"
                       : "Cannot reach provider '" + provider + "'";
            default:
                return "Provider '" + provider + "' call failed";
        }
    }

    private void sleep(long delayMillis, String provider, String endpoint) {
        if (delayMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException e) {
            // Interrupted while waiting to try again, which is the same event as being interrupted
            // mid-request and is reported the same way.
            Thread.currentThread().interrupt();
            throw LLMException.stopped(provider, modelName, endpoint, e);
        }
    }

    /** Extracts a readable host label from a URL, falling back to the raw value. */
    protected static String hostOf(String url) {
        if (url == null || url.isBlank()) {
            return LLMException.UNKNOWN_PROVIDER;
        }
        try {
            URI uri = URI.create(url);
            return uri.getHost() != null ? uri.getHost() : url;
        } catch (IllegalArgumentException e) {
            return url;
        }
    }

    private static CadetLogger logger() {
        CadetLogger local = logger;
        if (local == null) {
            try {
                local = CadetLogger.getLogger(AbstractLLMBackend.class);
            } catch (RuntimeException e) {
                return null;
            }
            logger = local;
        }
        return local;
    }

    private static HttpClient newSharedClient() {
        int timeoutSeconds = DEFAULT_CONNECT_TIMEOUT_SECONDS;
        String configured = System.getProperty(CONNECT_TIMEOUT_PROPERTY);
        if (configured != null) {
            try {
                int value = Integer.parseInt(configured.trim());
                if (value >= 1 && value <= MAX_CONNECT_TIMEOUT_SECONDS) {
                    timeoutSeconds = value;
                }
            } catch (NumberFormatException ignored) {
                // fall through to the default
            }
        }
        return HttpClient.newBuilder()
                         .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                         .build();
    }

    // Concrete classes must implement complete(...)
}
