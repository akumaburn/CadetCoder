package com.eonmux.cadetcoder.net;

/**
 * The provider refused the request because the account cannot pay for it: the balance is empty,
 * the subscription expired, or the plan's usage allowance for the period is used up.
 *
 * <p>Sent by some providers as HTTP 429, the status of a rate limit, and told apart only by a code
 * in the body. It is not retried. A rate limit clears in seconds, and these clear only when somebody
 * pays or a usage window of hours resets. Retries do not end, so a retried refusal would hold the
 * run until then and never report the reason.</p>
 */
public class LLMAccountLimitException extends LLMException {

    private static final long serialVersionUID = 1L;

    /**
     * @param provider     the connector that refused
     * @param model        the model asked for, may be null
     * @param endpoint     the request URL, may be null
     * @param statusCode   the HTTP status the refusal arrived with
     * @param businessCode the provider's own code for the refusal
     * @param advice       what the provider's own setup makes worth checking, may be null
     * @param bodyExcerpt  what the provider said, already truncated and redacted
     */
    public LLMAccountLimitException(String provider,
                                    String model,
                                    String endpoint,
                                    int statusCode,
                                    String businessCode,
                                    String advice,
                                    String bodyExcerpt) {
        super(Kind.ACCOUNT_LIMIT,
              withExcerpt(buildMessage(provider, model, statusCode, businessCode, advice),
                          bodyExcerpt),
              provider, model, endpoint, statusCode, bodyExcerpt, null, null);
    }

    private static String buildMessage(String provider, String model, int statusCode,
                                       String businessCode, String advice) {
        return "Provider '" + providerLabel(provider) + "' refused the request for"
                + (modelPhrase(model).isEmpty() ? " the configured model" : modelPhrase(model))
                + " because the account has no allowance left for it (HTTP " + statusCode
                + ", code " + businessCode + "). A retry cannot succeed until the balance is topped"
                + " up, the plan's usage period resets, or the plan includes this model."
                + (advice == null || advice.isBlank() ? "" : " " + advice);
    }
}
