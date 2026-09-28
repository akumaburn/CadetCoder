package com.eonmux.cadetcoder.ai.providers;

import org.junit.Test;

import java.net.URI;
import java.net.http.HttpRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which header a connector's credential travels in.
 *
 * <h2>The defect</h2>
 *
 * <p>This mapping lived privately inside the completion backend, where only a completion could
 * reach it. The moment a second kind of request had to be authenticated the same way -- asking a
 * provider for its model list -- the choice was between reaching into the backend for something
 * unrelated to completing anything, or writing the switch out a second time. A second copy is how a
 * provider ends up authenticated correctly for one request and not for the other, and the symptom
 * would have been the worst kind: completions working while the model list came back empty, which
 * reads as "this provider has no models" rather than as an authentication failure.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>One case per scheme, because each names a different header and a wrong one is rejected by the
 * provider rather than by anything here; that the two schemes with no header to add -- request
 * signing, and an endpoint needing no credential -- add none rather than failing; and that a
 * missing key adds nothing, since an unauthenticated request returns an error the caller can
 * explain while a header reading {@code Bearer null} returns one nobody can.</p>
 */
public class TheCredentialGoesInTheHeaderTheSchemeNamesTest {

    private static HttpRequest signed(AuthScheme scheme, String key) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                                                 .uri(URI.create("https://example.invalid/v1/models"))
                                                 .GET();
        AuthHeaders.apply(builder, scheme, key);
        return builder.build();
    }

    private static String header(HttpRequest request, String name) {
        return request.headers().firstValue(name).orElse(null);
    }

    @Test
    public void aBearerKeyTravelsInAuthorization() {
        assertThat(header(signed(AuthScheme.BEARER, "k3y"), "Authorization")).isEqualTo("Bearer k3y");
    }

    @Test
    public void anAzureKeyTravelsInApiKey() {
        assertThat(header(signed(AuthScheme.API_KEY_HEADER, "k3y"), "api-key")).isEqualTo("k3y");
    }

    @Test
    public void anAnthropicKeyTravelsInXApiKey() {
        assertThat(header(signed(AuthScheme.X_API_KEY, "k3y"), "x-api-key")).isEqualTo("k3y");
    }

    @Test
    public void aGoogleKeyTravelsInXGoogApiKey() {
        assertThat(header(signed(AuthScheme.X_GOOG_API_KEY, "k3y"), "x-goog-api-key")).isEqualTo("k3y");
    }

    @Test
    public void anUnspecifiedSchemeIsTreatedAsBearer() {
        // What all but a handful of connectors use, and the shape a hand-written config omits.
        assertThat(header(signed(null, "k3y"), "Authorization")).isEqualTo("Bearer k3y");
    }

    @Test
    public void aSchemeWithNoHeaderToAddAddsNone() {
        // Signature V4 signs the whole request elsewhere; NONE has nothing to carry.
        assertThat(signed(AuthScheme.AWS_SIGV4, "k3y").headers().map()).isEmpty();
        assertThat(signed(AuthScheme.NONE, "k3y").headers().map()).isEmpty();
    }

    @Test
    public void aMissingKeyIsNotSentAsTheWordNull() {
        assertThat(signed(AuthScheme.BEARER, null).headers().map()).isEmpty();
        assertThat(signed(AuthScheme.BEARER, "").headers().map()).isEmpty();
    }

    @Test
    public void thereIsNothingToDoWithoutARequest() {
        // Reached when a caller assembles a request conditionally; it must not end the caller.
        AuthHeaders.apply(null, AuthScheme.BEARER, "k3y");
    }
}
