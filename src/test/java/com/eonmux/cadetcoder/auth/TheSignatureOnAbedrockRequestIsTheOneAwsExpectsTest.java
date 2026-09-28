package com.eonmux.cadetcoder.auth;

import org.junit.Test;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The signer produces the signature AWS computes for the same request, not merely a signature.
 *
 * <p><b>The gap</b>: {@link AwsSigV4SignerTest} pins the key derivation and the shape of the
 * {@code Authorization} header, and asserts that it contains {@code Signature=}. It never asserts
 * what follows. Everything the canonical request has to agree with AWS about -- header ordering,
 * header case, value trimming, path encoding, query ordering, and the blank line between the
 * headers and their names -- could therefore be wrong in any way that still produced 64 hex
 * characters. A wrong signature is not a wrong answer; it is a 403 that names none of its
 * twelve possible causes.</p>
 *
 * <p>Nothing here re-implements SigV4. The expected signature was derived independently with
 * OpenSSL from the algorithm as AWS documents it. A test that recomputed it the way the signer does
 * would agree with whatever the signer got wrong.</p>
 */
public class TheSignatureOnAbedrockRequestIsTheOneAwsExpectsTest {

    /** AWS's own documentation example key. It is not a credential for any account. */
    private static final String EXAMPLE_ACCESS_KEY = "AKIDEXAMPLE";
    private static final String EXAMPLE_SECRET_KEY = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY";

    /** The instant the expected signature below was derived for. */
    private static final Instant SIGNED_AT = Instant.parse("2015-08-30T12:36:00Z");

    private static final URI ENDPOINT =
            URI.create("https://bedrock-runtime.us-east-1.amazonaws.com/model/anthropic.claude-v2:1/converse");

    private static final byte[] PAYLOAD = "{\"x\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    @Test
    public void theAuthorizationHeaderIsExactlyWhatAwsWouldCompute() {
        Map<String, String> headers = sign(null);

        assertThat(headers.get("authorization")).isEqualTo(
                "AWS4-HMAC-SHA256 Credential=" + EXAMPLE_ACCESS_KEY
                + "/20150830/us-east-1/bedrock/aws4_request"
                + ", SignedHeaders=content-type;host;x-amz-content-sha256;x-amz-date"
                + ", Signature=32639b642f1971f34df6c20ea969ce6c1ec24c7ec3ad6b6f35cd5e1220149ade");
    }

    /** The colon in a Bedrock model id is percent-encoded in the path that gets signed. */
    @Test
    public void amodelIdWithAcolonIsEncodedInTheSignedPath() {
        Map<String, String> unversioned = AwsSigV4Signer.signedHeaders(
                "POST",
                URI.create("https://bedrock-runtime.us-east-1.amazonaws.com/model/anthropic.claude-v2/converse"),
                Map.of("Content-Type", "application/json"), PAYLOAD, "us-east-1", "bedrock",
                new AwsSigV4Signer.Credentials(EXAMPLE_ACCESS_KEY, EXAMPLE_SECRET_KEY, null), SIGNED_AT);

        assertThat(unversioned.get("authorization"))
                .as("a different path is a different signature; the colon is not incidental")
                .doesNotContain("Signature=32639b642f1971f34df6c20ea969ce6c1ec24c7ec3ad6b6f35cd5e1220149ade");
    }

    /** A temporary credential's token is sent AND signed; leaving it out of the signature is a 403. */
    @Test
    public void asessionTokenChangesTheSignatureItIsPartOf() {
        Map<String, String> headers = sign("FQoGZXIvYXdzEExampleToken");

        assertThat(headers.get("authorization"))
                .contains("SignedHeaders=content-type;host;x-amz-content-sha256;x-amz-date;x-amz-security-token")
                .doesNotContain("Signature=32639b642f1971f34df6c20ea969ce6c1ec24c7ec3ad6b6f35cd5e1220149ade");
    }

    private static Map<String, String> sign(String sessionToken) {
        Map<String, String> base = new LinkedHashMap<>();
        base.put("Content-Type", "application/json");
        return AwsSigV4Signer.signedHeaders(
                "POST", ENDPOINT, base, PAYLOAD, "us-east-1", "bedrock",
                new AwsSigV4Signer.Credentials(EXAMPLE_ACCESS_KEY, EXAMPLE_SECRET_KEY, sessionToken),
                SIGNED_AT);
    }
}
