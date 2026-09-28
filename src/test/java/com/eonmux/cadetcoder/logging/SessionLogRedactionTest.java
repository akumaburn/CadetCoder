package com.eonmux.cadetcoder.logging;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nothing a provider sends back is trusted to be free of credentials.
 *
 * <p>Session logging is on by default and writes to a file that, unlike {@code config.json}, is not
 * restricted to the owner. The response most likely to contain a key is a 401, because that is the
 * one where a provider quotes the offending credential back. The body was redacted; the response
 * headers were copied in verbatim one loop below it, which is where {@code authorization} echoes
 * and {@code set-cookie} live.</p>
 */
public class SessionLogRedactionTest {

    private static Map<String, String> headers(String... namesAndValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            map.put(namesAndValues[i], namesAndValues[i + 1]);
        }
        return map;
    }

    @Test
    public void aCredentialHeaderIsRemovedWhateverItsValueLooksLike() {
        Map<String, String> metadata = SessionLogger.responseMetadata(
                "gpt-4", "{}", 12L, 401,
                headers("Authorization", "Bearer nothing-shaped-like-a-key",
                        "Set-Cookie", "session=abcdefghijklmnop; Path=/",
                        "X-Api-Key", "0123456789"));

        assertThat(metadata.get("header_authorization")).doesNotContain("nothing-shaped-like-a-key");
        assertThat(metadata.get("header_set-cookie")).doesNotContain("abcdefghijklmnop");
        assertThat(metadata.get("header_x-api-key")).doesNotContain("0123456789");
    }

    @Test
    public void anOrdinaryHeaderIsKeptSoTheLogStaysUseful() {
        Map<String, String> metadata = SessionLogger.responseMetadata(
                "gpt-4", "{}", 12L, 200,
                headers("Content-Type", "application/json", "X-Request-Id", "req_12345"));

        assertThat(metadata.get("header_content-type")).isEqualTo("application/json");
        assertThat(metadata.get("header_x-request-id")).isEqualTo("req_12345");
    }

    @Test
    public void aKeyQuotedBackInAnErrorBodyIsRemoved() {
        Map<String, String> metadata = SessionLogger.responseMetadata(
                "gpt-4",
                "{\"error\":{\"message\":\"Incorrect API key provided: sk-abcdef0123456789\"}}",
                12L, 401, null);

        assertThat(metadata.get("fullResponse")).doesNotContain("abcdef0123456789");
    }

    /** A redacted body must not make the log lie about how much actually arrived. */
    @Test
    public void theRecordedSizeDescribesWhatArrivedNotWhatWasKept() {
        String body = "{\"error\":\"key sk-abcdef0123456789 is invalid\"}";

        Map<String, String> metadata = SessionLogger.responseMetadata("gpt-4", body, 1L, 401, null);

        assertThat(metadata.get("responseSize")).isEqualTo(String.valueOf(body.length()));
    }

    @Test
    public void aResponseWithNoBodyOrHeadersIsStillRecorded() {
        Map<String, String> metadata = SessionLogger.responseMetadata("gpt-4", null, 1L, 500, null);

        assertThat(metadata.get("statusCode")).isEqualTo("500");
        assertThat(metadata.get("responseSize")).isEqualTo("0");
    }
}
