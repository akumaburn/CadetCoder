package com.eonmux.cadetcoder.net;

/**
 * The provider could not be reached at all: connection refused, DNS failure, connect/read timeout,
 * an interrupted request, or the server answering HTTP 408. Retryable with backoff.
 */
public class LLMTransportException extends LLMException {

    private static final long serialVersionUID = 1L;

    /** No HTTP response was received; {@code cause} is the underlying IO/interruption failure. */
    public LLMTransportException(String provider, String model, String endpoint, Throwable cause) {
        super(Kind.TRANSPORT,
              "Cannot reach " + endpointLabel(endpoint) + " for provider '" + providerLabel(provider) + "'"
                      + modelPhrase(model) + causePhrase(cause)
                      + ". Check the endpoint is running and reachable (`cadet config`).",
              provider, model, endpoint, 0, null, null, cause);
    }

    /** The server answered, but with a timeout status (HTTP 408). */
    public LLMTransportException(String provider, String model, String endpoint, int statusCode, String bodyExcerpt) {
        super(Kind.TRANSPORT,
              withExcerpt("Request to " + endpointLabel(endpoint) + " for provider '" + providerLabel(provider)
                                  + "'" + modelPhrase(model) + " timed out (HTTP " + statusCode
                                  + ") after exhausting retries.", bodyExcerpt),
              provider, model, endpoint, statusCode, bodyExcerpt, null, null);
    }

    /**
     * The exchange completed with HTTP 200, but the payload was not a usable completion.
     *
     * <p>Kept apart from the no-response constructor because the advice differs: nothing is wrong
     * with the endpoint or the configuration here, so telling the user to check them sends them
     * after a problem they do not have.</p>
     *
     * @param reason why the payload was unusable, from {@link TransientPayload#describe(String)}
     */
    public static LLMTransportException incompleteResponse(String provider, String model,
                                                           String endpoint, String reason) {
        return new LLMTransportException(
                "Provider '" + providerLabel(provider) + "'" + modelPhrase(model)
                        + " answered HTTP 200 without a usable completion: " + reason
                        + ". This is a provider-side failure, not a configuration problem.",
                provider, model, endpoint);
    }

    /** Backing constructor for {@link #incompleteResponse}; the status was 200, not an error code. */
    private LLMTransportException(String message, String provider, String model, String endpoint) {
        super(Kind.TRANSPORT, message, provider, model, endpoint, 200, null, null, null);
    }

    private static String endpointLabel(String endpoint) {
        return (endpoint == null || endpoint.isBlank()) ? "the configured AI endpoint" : endpoint;
    }

    /**
     * Describes the underlying failure. The JDK HTTP client often throws a {@code ConnectException}
     * with no message of its own and the real reason ("Connection refused") on a nested cause, so
     * the chain is walked for the first message that actually says something.
     */
    private static String causePhrase(Throwable cause) {
        if (cause == null) {
            return "";
        }
        for (Throwable current = cause; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && !message.isBlank()) {
                return ": " + message;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return ": " + describeSilentCause(cause);
    }

    /**
     * The JDK's connect failures frequently carry no message at all, and a bare class name tells the
     * user nothing. Translate the common ones into plain language.
     */
    private static String describeSilentCause(Throwable cause) {
        if (cause instanceof java.net.http.HttpConnectTimeoutException) {
            return "connect timed out";
        }
        if (cause instanceof java.net.http.HttpTimeoutException) {
            return "request timed out";
        }
        if (cause instanceof java.net.UnknownHostException) {
            return "unknown host";
        }
        if (cause instanceof java.net.ConnectException) {
            return "connection refused or endpoint unreachable";
        }
        if (cause instanceof java.nio.channels.ClosedChannelException) {
            return "connection closed";
        }
        return cause.getClass().getSimpleName();
    }
}
