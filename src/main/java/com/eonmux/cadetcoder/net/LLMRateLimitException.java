package com.eonmux.cadetcoder.net;

import java.time.Duration;

/**
 * The provider rate-limited the request (HTTP 429). Retryable, and carries the provider's
 * {@code Retry-After} hint when it sent one so the backoff can honour it.
 */
public class LLMRateLimitException extends LLMException {

    private static final long serialVersionUID = 1L;

    public LLMRateLimitException(String provider,
                                 String model,
                                 String endpoint,
                                 int statusCode,
                                 String bodyExcerpt,
                                 Duration retryAfter) {
        super(Kind.RATE_LIMITED,
              withExcerpt(buildMessage(provider, model, statusCode, retryAfter), bodyExcerpt),
              provider, model, endpoint, statusCode, bodyExcerpt, retryAfter, null);
    }

    private static String buildMessage(String provider, String model, int statusCode, Duration retryAfter) {
        String wait = retryAfter != null
                ? " Retry after " + Math.max(1, retryAfter.toSeconds()) + "s."
                : " Please retry later.";
        return "Rate limited by provider '" + providerLabel(provider) + "'" + modelPhrase(model)
                + " (HTTP " + statusCode + ") after exhausting retries." + wait;
    }
}
