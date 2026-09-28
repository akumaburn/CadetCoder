package com.eonmux.cadetcoder.ai.providers;

import java.net.http.HttpRequest;

/**
 * Puts a connector's credential on an outbound request.
 *
 * <h2>Why this is not a method on the backend</h2>
 *
 * <p>Which header carries the key is a property of the connector, not of the request being sent, and
 * more than one kind of request has to carry it: a completion goes to {@code /chat/completions} and a
 * model listing to {@code /models}, both authenticated exactly the same way. While the switch lived
 * privately on the completion backend, the second caller had no way to reach it and would have had
 * to write the mapping out again -- and a second copy is how a provider comes to be authenticated
 * correctly for one request and not for the other.</p>
 *
 * <h2>Why a missing key is not an error here</h2>
 *
 * <p>An endpoint that needs no credential is a supported configuration ({@link AuthScheme#NONE}, for
 * a local llama-server or LM Studio), and a connector whose key has not been set yet is a normal
 * state that the caller reports in its own words. Refusing here would turn both into an exception
 * raised from inside request assembly, far from whoever can explain it.</p>
 */
public final class AuthHeaders {

    private AuthHeaders() {
    }

    /**
     * Adds the header this scheme authenticates with, if there is a credential to add.
     *
     * @param builder the request being assembled
     * @param scheme  how this connector authenticates; {@code null} is treated as {@link
     *                AuthScheme#BEARER}, the shape all but a handful of connectors use
     * @param apiKey  the resolved credential, or {@code null}/empty when there is none
     */
    public static void apply(HttpRequest.Builder builder, AuthScheme scheme, String apiKey) {
        if (builder == null || apiKey == null || apiKey.isEmpty()) {
            return;
        }
        switch (scheme == null ? AuthScheme.BEARER : scheme) {
            case BEARER         -> builder.header("Authorization", "Bearer " + apiKey);
            case API_KEY_HEADER -> builder.header("api-key", apiKey);
            case X_API_KEY      -> builder.header("x-api-key", apiKey);
            case X_GOOG_API_KEY -> builder.header("x-goog-api-key", apiKey);
            // AWS_SIGV4 signs the whole request rather than carrying a header the caller can add,
            // and NONE has nothing to carry. Neither is a failure; both simply add nothing.
            default -> { }
        }
    }
}
