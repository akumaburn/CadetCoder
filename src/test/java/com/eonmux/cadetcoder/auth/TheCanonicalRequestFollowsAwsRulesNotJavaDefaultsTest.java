package com.eonmux.cadetcoder.auth;

import org.junit.After;
import org.junit.Test;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three places where SigV4 canonicalization differs from what Java does by default.
 *
 * <p>Each expected signature below was computed independently with OpenSSL from the canonical
 * request AWS specifies, not from this signer.</p>
 */
public class TheCanonicalRequestFollowsAwsRulesNotJavaDefaultsTest {

    private static final String EXAMPLE_ACCESS_KEY = "AKIDEXAMPLE";
    private static final String EXAMPLE_SECRET_KEY = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY";
    private static final Instant SIGNED_AT = Instant.parse("2015-08-30T12:36:00Z");
    private static final byte[] PAYLOAD = "{\"x\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private final Locale originalLocale = Locale.getDefault();

    @After
    public void restoreLocale() {
        Locale.setDefault(originalLocale);
    }

    /**
     * A run of spaces inside a header value is one space to AWS.
     *
     * <p><b>The defect</b>: the value was only trimmed. AWS also collapses sequential whitespace
     * before signing, so a {@code Content-Type} written with two spaces after the comma was signed
     * here with two and at AWS with one. The request is rejected as unauthorized, and nothing in the
     * response says that a space was the reason.</p>
     */
    @Test
    public void arunOfSpacesInsideAheaderValueIsOneSpace() {
        Map<String, String> base = new LinkedHashMap<>();
        base.put("Content-Type", "  application/json,   charset=utf-8  ");

        Map<String, String> headers = sign(base, endpoint(""), null);

        assertThat(headers).containsEntry("content-type", "application/json, charset=utf-8");
        assertThat(headers.get("authorization"))
                .endsWith("Signature=16c21bbbebc2e44e6d79a4811cb9da710c6cfe82701c39c3ac2ad4c091a8ee88");
    }

    /**
     * Header names are lowercased by ASCII rules, not by the machine's language.
     *
     * <p><b>The defect</b>: {@code toLowerCase()} with no locale. On a machine set to Turkish,
     * {@code I} lowercases to the dotless {@code ı}, so a header named
     * {@code X-Amz-Invocation-Id} was signed -- and sent -- as {@code x-amz-ınvocatıon-ıd}. Every
     * signed request from that machine fails, and only from that machine.</p>
     */
    @Test
    public void aheaderNameIsLowercasedTheSameWayInEveryLanguage() {
        Locale.setDefault(new Locale("tr", "TR"));
        Map<String, String> base = new LinkedHashMap<>();
        base.put("X-Amz-Invocation-Id", "ID-1");

        Map<String, String> headers = sign(base, endpoint(""), null);

        assertThat(headers).containsKey("x-amz-invocation-id");
        assertThat(headers.get("authorization")).contains(";x-amz-invocation-id,");
        assertThat(headers.keySet())
                .as("the dotless Turkish lowercase of I must not appear")
                .noneMatch(name -> name.indexOf('ı') >= 0);
    }

    /**
     * A query parameter given twice is signed twice.
     *
     * <p><b>The defect</b>: the parameters were collected into a map keyed by name, so a repeated
     * name kept only the last value. The canonical query then omitted a parameter that the wire
     * request still carried, which is a signature over a different request than the one sent.</p>
     */
    @Test
    public void aqueryParameterGivenTwiceIsSignedTwice() {
        Map<String, String> base = new LinkedHashMap<>();
        base.put("Content-Type", "application/json");

        Map<String, String> headers = sign(base, endpoint("?b=2&a=1&a=0"), null);

        assertThat(headers.get("authorization"))
                .endsWith("Signature=0871344605710bb8ed6b16c1966d8702c4442bc6c2f4c3c67ca3b14c12cff8a3");
    }

    private static URI endpoint(String query) {
        return URI.create("https://bedrock-runtime.us-east-1.amazonaws.com"
                          + "/model/anthropic.claude-v2:1/converse" + query);
    }

    private static Map<String, String> sign(Map<String, String> base, URI uri, String sessionToken) {
        return AwsSigV4Signer.signedHeaders(
                "POST", uri, base, PAYLOAD, "us-east-1", "bedrock",
                new AwsSigV4Signer.Credentials(EXAMPLE_ACCESS_KEY, EXAMPLE_SECRET_KEY, sessionToken),
                SIGNED_AT);
    }
}
