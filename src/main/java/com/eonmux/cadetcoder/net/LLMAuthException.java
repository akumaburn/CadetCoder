package com.eonmux.cadetcoder.net;

/**
 * The provider would not carry out the request on this credential (HTTP 401/403), or there was no
 * credential to try. Never retried: repeating the same request with the same key can only fail the
 * same way.
 *
 * <h2>Why 401 and 403 do not say the same thing</h2>
 *
 * <p>Both were reported as "authentication failed -- check your API key". For a 401 that is the
 * advice. For a 403 it is wrong in the way that costs the most time: the key authenticated, and the
 * account behind it is not permitted this particular request. A user sent to re-paste a working key
 * finds the same failure waiting, with nothing learned. Gateways that resell several vendors on one
 * subscription make this the ordinary case rather than a rare one -- picking a model above the
 * plan's tier is a 403 with a perfectly good key.</p>
 */
public class LLMAuthException extends LLMException {

    private static final long serialVersionUID = 1L;

    public LLMAuthException(String provider, String model, String endpoint, int statusCode, String bodyExcerpt) {
        super(Kind.AUTH,
              withExcerpt(buildMessage(provider, model, statusCode), bodyExcerpt),
              provider, model, endpoint, statusCode, bodyExcerpt, null, null);
    }

    /** Missing/unusable local credentials — no request was ever sent, hence no status. */
    public LLMAuthException(String provider, String model, String endpoint, String detail) {
        super(Kind.AUTH,
              "No usable credentials for provider '" + providerLabel(provider) + "'"
                      + (detail != null && !detail.isBlank() ? ": " + detail : "")
                      + ". Check your API key for provider '" + providerLabel(provider)
                      + "' (run `cadet login` or `cadet config`).",
              provider, model, endpoint, 0, null, null, null);
    }

    private static String buildMessage(String provider, String model, int statusCode) {
        String label = providerLabel(provider);
        if (statusCode == 403) {
            return "The request was refused by provider '" + label + "'" + modelPhrase(model)
                    + " (HTTP 403). The key was accepted, so it is the account it belongs to that "
                    + "is not permitted this request -- commonly a model above the plan's tier, or "
                    + "one the account has not been granted. The reason the provider gave follows; "
                    + "`cadet models " + label + "` lists what it offers.";
        }
        return "Authentication failed for provider '" + label + "'" + modelPhrase(model)
                + " (HTTP " + statusCode + "). Check your API key for provider '" + label
                + "' (run `cadet login` or `cadet config`).";
    }
}
