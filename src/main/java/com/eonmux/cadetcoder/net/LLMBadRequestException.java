package com.eonmux.cadetcoder.net;

/**
 * The provider rejected the request itself (HTTP 400, and other terminal 4xx such as 404/422).
 * Never retried: the same request will be rejected again.
 */
public class LLMBadRequestException extends LLMException {

    private static final long serialVersionUID = 1L;

    public LLMBadRequestException(String provider,
                                  String model,
                                  String endpoint,
                                  int statusCode,
                                  String bodyExcerpt) {
        super(Kind.BAD_REQUEST,
              withExcerpt(buildMessage(provider, model, statusCode), bodyExcerpt),
              provider, model, endpoint, statusCode, bodyExcerpt, null, null);
    }

    private LLMBadRequestException(String message,
                                   String provider,
                                   String model,
                                   String endpoint,
                                   int statusCode,
                                   String bodyExcerpt) {
        super(Kind.BAD_REQUEST, message, provider, model, endpoint, statusCode, bodyExcerpt,
              null, null);
    }

    /**
     * The provider will not serve this model without retaining the request.
     *
     * <p>Worth its own message because the generic one sends the reader to check the model name and
     * the request settings, and both are fine: the request was refused over a promise the tool asked
     * for on the user's behalf, and the user may not know it asks. Refusing is the correct behaviour
     * on the provider's part -- the alternative is retaining a request that was supposed not to
     * be -- so what the message owes the reader is the two ways out, and which one keeps the
     * promise.</p>
     *
     * @param provider    the connector that refused
     * @param model       the model with no such upstream
     * @param endpoint    the endpoint it was asked of
     * @param bodyExcerpt what the provider said
     * @return the failure to report
     */
    static LLMBadRequestException zeroDataRetentionUnavailable(String provider, String model,
                                                              String endpoint, String bodyExcerpt) {
        String message = "Provider '" + providerLabel(provider) + "' has no zero-retention upstream"
                + " for" + (modelPhrase(model).isEmpty() ? " the configured model" : modelPhrase(model))
                + " (HTTP 422), so it refused the request rather than retain it. Either choose a"
                + " model whose upstream does promise it, or allow retention deliberately with"
                + " `cadet config ai.zeroDataRetention false`.";
        return new LLMBadRequestException(withExcerpt(message, bodyExcerpt), provider, model,
                                          endpoint, 422, bodyExcerpt);
    }

    private static String buildMessage(String provider, String model, int statusCode) {
        return "Provider '" + providerLabel(provider) + "' rejected the request for"
                + (modelPhrase(model).isEmpty() ? " the configured model" : modelPhrase(model))
                + " (HTTP " + statusCode + "). The request was not accepted; check the model name"
                + " and request settings (`cadet config`).";
    }
}
