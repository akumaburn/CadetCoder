package com.eonmux.cadetcoder.net;

import java.time.Duration;

/**
 * Typed failure raised when a call to an LLM provider does not yield a model reply.
 *
 * <p>Before this type existed the backends returned transport/API errors as ordinary completion
 * text (e.g. {@code "Error: Request failed with status 403"}). That text flowed up the pipeline as
 * if the model had answered it, so a completely failed request was indistinguishable from a
 * successful one and the process still exited 0. Failures now travel on their own channel:
 * callers either receive the model's answer or an {@code LLMException}.</p>
 *
 * <p>It is deliberately <em>unchecked</em>. {@link LLMBackend#complete}, {@code AIClient.complete}
 * and {@code AIManager.complete} keep their existing signatures, so every existing caller — including
 * commands this change does not touch — still compiles, and the ones that already wrap AI calls in
 * {@code catch (Exception e)} turn the failure into their normal error path (a non-zero exit code)
 * with no edit at all.</p>
 *
 * <p>The message is written for the user: it names the provider, the model and the HTTP status, says
 * what to do about it, and carries at most a short, redacted excerpt of the provider's response body
 * — never the whole body, and never an API key.</p>
 */
public class LLMException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Failure category. Determines whether a retry can possibly help. */
    public enum Kind {
        /** 401/403, or missing credentials: a retry can never help. */
        AUTH(false),
        /** 429: the provider asked us to slow down. */
        RATE_LIMITED(true),
        /** 400 and other terminal client errors: the request itself is wrong. */
        BAD_REQUEST(false),
        /** 5xx: the provider is broken or overloaded right now. */
        SERVER_ERROR(true),
        /** IO failure, connect/read timeout, or HTTP 408: the endpoint could not be reached. */
        TRANSPORT(true),
        /** HTTP 200 whose payload carries no usable completion (unparseable, no choices, no content). */
        PROTOCOL(false),
        /** Every attempt succeeded at the HTTP level but produced no text at all. */
        EMPTY_RESPONSE(false),
        /**
         * The caller asked the request to stop, so it never finished.
         *
         * <p>Not retryable, and deliberately not {@link #TRANSPORT}. An interruption used to be
         * reported as one, which said the endpoint could not be reached -- untrue, and it sent the
         * reader after a problem that was not there. Worse, transport failures are retryable, so a
         * deliberate stop was offered back to the user as something to try again.</p>
         */
        STOPPED(false),
        /** Anything not covered above. */
        UNKNOWN(false);

        private final boolean retryable;

        Kind(boolean retryable) {
            this.retryable = retryable;
        }

        /** @return whether failures of this kind are worth retrying. */
        public boolean isRetryable() {
            return retryable;
        }
    }

    /** Placeholder used when the provider is unknown, so messages never read "null". */
    static final String UNKNOWN_PROVIDER = "unknown";

    private final Kind     kind;
    private final String   provider;
    private final String   model;
    private final String   endpoint;
    private final int      statusCode;
    private final String   bodyExcerpt;
    private final Duration retryAfter;

    /**
     * @param kind        failure category, never null
     * @param message     user-facing, actionable description
     * @param provider    provider id or host label ({@code null} becomes {@value #UNKNOWN_PROVIDER})
     * @param model       model name, may be null
     * @param endpoint    request URL or base URL, may be null
     * @param statusCode  HTTP status, or 0 when no response was received
     * @param bodyExcerpt already truncated and redacted response excerpt, may be null
     * @param retryAfter  parsed {@code Retry-After}, may be null
     * @param cause       underlying exception, may be null
     */
    public LLMException(Kind kind,
                        String message,
                        String provider,
                        String model,
                        String endpoint,
                        int statusCode,
                        String bodyExcerpt,
                        Duration retryAfter,
                        Throwable cause) {
        super(message, cause);
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
        this.kind        = kind;
        this.provider    = (provider == null || provider.isBlank()) ? UNKNOWN_PROVIDER : provider;
        this.model       = model;
        this.endpoint    = endpoint;
        this.statusCode  = statusCode;
        this.bodyExcerpt = bodyExcerpt;
        this.retryAfter  = retryAfter;
    }

    public Kind getKind() {
        return kind;
    }

    public String getProvider() {
        return provider;
    }

    public String getModel() {
        return model;
    }

    public String getEndpoint() {
        return endpoint;
    }

    /** @return the HTTP status, or 0 when the request never produced a response. */
    public int getStatusCode() {
        return statusCode;
    }

    /** @return a short, redacted excerpt of the provider's response body, or null. */
    public String getBodyExcerpt() {
        return bodyExcerpt;
    }

    /** @return the provider's {@code Retry-After} hint, or null when it did not send one. */
    public Duration getRetryAfter() {
        return retryAfter;
    }

    /** @return whether retrying this exact request could plausibly succeed. */
    public boolean isRetryable() {
        return kind.isRetryable();
    }

    /**
     * Appends a provider-supplied excerpt to a message, when there is one worth showing.
     * Keeps the caller's message readable when the provider returned nothing useful.
     */
    static String withExcerpt(String message, String bodyExcerpt) {
        if (bodyExcerpt == null || bodyExcerpt.isBlank()) {
            return message;
        }
        return message + " Provider said: " + bodyExcerpt;
    }

    /** Renders {@code model 'x' } for messages, or an empty string when the model is unknown. */
    static String modelPhrase(String model) {
        return (model == null || model.isBlank()) ? "" : " model '" + model + "'";
    }

    /** Renders a provider label that never reads "null". */
    static String providerLabel(String provider) {
        return (provider == null || provider.isBlank()) ? UNKNOWN_PROVIDER : provider;
    }

    /**
     * A request that stopped because the caller asked it to.
     *
     * <p>Built here so every route to an interruption says the same thing. There are two: the
     * backend, where the interrupt lands on the thread waiting for the response, and
     * {@code AIManager}, where it lands on a thread waiting to retry. Reported differently they
     * described the same key press as two unrelated events, and only one of them said what had
     * actually happened.</p>
     *
     * @param provider the provider that was being asked, may be null
     * @param model    the model the request named, may be null
     * @param endpoint the URL the request was going to, may be null
     * @param cause    the interruption
     * @return the failure to throw
     */
    public static LLMException stopped(String provider, String model, String endpoint,
                                       Throwable cause) {
        return new LLMException(Kind.STOPPED,
                                "The request to provider '" + providerLabel(provider) + "'"
                                        + modelPhrase(model) + " was stopped before the model"
                                        + " answered.",
                                provider, model, endpoint, 0, null, null, cause);
    }
}
