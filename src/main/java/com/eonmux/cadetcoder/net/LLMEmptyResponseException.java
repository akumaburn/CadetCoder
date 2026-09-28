package com.eonmux.cadetcoder.net;

/**
 * Every attempt reached the provider but produced no text at all. Raised by the AI manager, which
 * previously returned an empty string here — silently, because its intended fallback message was
 * unreachable — making a total failure look like a successful, blank answer.
 */
public class LLMEmptyResponseException extends LLMException {

    private static final long serialVersionUID = 1L;

    public LLMEmptyResponseException(String provider, String model, int attempts) {
        super(Kind.EMPTY_RESPONSE,
              "AI completion failed after " + attempts + " attempt" + (attempts == 1 ? "" : "s")
                      + ": provider '" + providerLabel(provider) + "'" + modelPhrase(model)
                      + " returned an empty response.",
              provider, model, null, 0, null, null, null);
    }
}
