package com.eonmux.cadetcoder.net;

/**
 * The provider failed on its own side (HTTP 5xx). Retryable with backoff; when the retries are
 * exhausted the failure surfaces to the user as "try again later".
 */
public class LLMServerException extends LLMException {

    private static final long serialVersionUID = 1L;

    public LLMServerException(String provider, String model, String endpoint, int statusCode, String bodyExcerpt) {
        super(Kind.SERVER_ERROR,
              withExcerpt(buildMessage(provider, model, statusCode), bodyExcerpt),
              provider, model, endpoint, statusCode, bodyExcerpt, null, null);
    }

    private static String buildMessage(String provider, String model, int statusCode) {
        return "Provider '" + providerLabel(provider) + "' returned a server error (HTTP " + statusCode + ")"
                + modelPhrase(model) + " after exhausting retries. The provider is unavailable;"
                + " please try again later.";
    }
}
