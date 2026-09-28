package com.eonmux.cadetcoder.net;

/**
 * The provider answered HTTP 200 with a payload that carries no usable completion: unparseable
 * JSON, no choices/candidates, or a message without content. The call failed even though the
 * transport succeeded, so it must not be handed to callers as if it were the model's answer.
 */
public class LLMProtocolException extends LLMException {

    private static final long serialVersionUID = 1L;

    public LLMProtocolException(String provider, String model, String endpoint, String detail, Throwable cause) {
        super(Kind.PROTOCOL,
              "Provider '" + providerLabel(provider) + "' returned an unusable response"
                      + modelPhrase(model) + ": " + (detail != null && !detail.isBlank()
                                                     ? detail : "no completion content")
                      + ".",
              provider, model, endpoint, 200, null, null, cause);
    }

    public LLMProtocolException(String provider, String model, String endpoint, String detail) {
        this(provider, model, endpoint, detail, null);
    }
}
