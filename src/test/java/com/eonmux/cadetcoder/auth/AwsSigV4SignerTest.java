package com.eonmux.cadetcoder.auth;

import org.junit.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

public class AwsSigV4SignerTest {

    /** AWS-documented signing-key derivation example. */
    @Test
    public void signingKeyMatchesAwsDocumentedVector() {
        byte[] key = AwsSigV4Signer.getSignatureKey(
                "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY", "20120215", "us-east-1", "iam");
        assertThat(AwsSigV4Signer.hex(key))
                .isEqualTo("f4780e2d9f65fa895f9c67b32ce1baf0b0d8a43505a000a1a9e090d414db404d");
    }

    @Test
    public void sha256OfEmptyMatchesKnownDigest() {
        assertThat(AwsSigV4Signer.sha256Hex(""))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    public void signedHeadersProduceAuthorizationAndAmzHeaders() {
        AwsSigV4Signer.Credentials creds =
                new AwsSigV4Signer.Credentials("AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY", null);
        URI uri = URI.create("https://bedrock-runtime.us-east-1.amazonaws.com/model/anthropic.claude/converse");
        Map<String, String> base = new TreeMap<>();
        base.put("content-type", "application/json");

        Map<String, String> signed = AwsSigV4Signer.signedHeaders(
                "POST", uri, base, "{}".getBytes(StandardCharsets.UTF_8),
                "us-east-1", "bedrock", creds, Instant.parse("2024-01-01T00:00:00Z"));

        assertThat(signed).containsKey("authorization");
        assertThat(signed.get("authorization"))
                .startsWith("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20240101/us-east-1/bedrock/aws4_request")
                .contains("SignedHeaders=content-type;host;x-amz-content-sha256;x-amz-date")
                .contains("Signature=");
        assertThat(signed).containsKey("x-amz-date");
        assertThat(signed).containsKey("x-amz-content-sha256");
        assertThat(signed.get("host")).isEqualTo("bedrock-runtime.us-east-1.amazonaws.com");
    }

    @Test
    public void sessionTokenAddsSecurityTokenHeader() {
        AwsSigV4Signer.Credentials creds =
                new AwsSigV4Signer.Credentials("AKID", "secret", "session-token-value");
        URI uri = URI.create("https://bedrock-runtime.us-west-2.amazonaws.com/model/m/converse");
        Map<String, String> signed = AwsSigV4Signer.signedHeaders(
                "POST", uri, Map.of("content-type", "application/json"),
                new byte[0], "us-west-2", "bedrock", creds, Instant.parse("2024-06-01T12:00:00Z"));
        assertThat(signed).containsEntry("x-amz-security-token", "session-token-value");
        assertThat(signed.get("authorization")).contains("x-amz-security-token");
    }
}
