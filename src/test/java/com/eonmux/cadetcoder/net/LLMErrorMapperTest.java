package com.eonmux.cadetcoder.net;

import org.junit.Test;

import java.net.http.HttpHeaders;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Status-to-exception mapping, {@code Retry-After} parsing, and the guarantees on what reaches the
 * user: a short excerpt of the provider body, with anything credential-shaped redacted.
 */
public class LLMErrorMapperTest {

    private static final String PROVIDER = "openai";
    private static final String MODEL    = "gpt-4";
    private static final String ENDPOINT = "https://api.openai.com/v1/chat/completions";

    private static HttpHeaders headers(Map<String, List<String>> values) {
        return HttpHeaders.of(values, (k, v) -> true);
    }

    private static LLMException map(int status, String body) {
        return LLMErrorMapper.fromStatus(PROVIDER, MODEL, ENDPOINT, status, body, null);
    }

    // ---------- status -> exception ----------

    @Test
    public void unauthorizedMapsToAuthFailureWithActionableMessage() {
        LLMException failure = map(401, "{\"error\":{\"message\":\"Invalid API key\"}}");

        assertThat(failure).isInstanceOf(LLMAuthException.class);
        assertThat(failure.getKind()).isEqualTo(LLMException.Kind.AUTH);
        assertThat(failure.isRetryable()).isFalse();
        assertThat(failure.getStatusCode()).isEqualTo(401);
        assertThat(failure.getProvider()).isEqualTo(PROVIDER);
        assertThat(failure.getModel()).isEqualTo(MODEL);
        assertThat(failure.getMessage())
                .contains("Authentication failed for provider 'openai'")
                .contains("HTTP 401")
                .contains("Check your API key")
                .contains("cadet login")
                .contains("cadet config")
                .contains("Invalid API key");
    }

    @Test
    public void forbiddenMapsToAuthFailure() {
        LLMException failure = map(403, "{\"type\":\"error\"}");

        assertThat(failure).isInstanceOf(LLMAuthException.class);
        assertThat(failure.getStatusCode()).isEqualTo(403);
        assertThat(failure.isRetryable()).isFalse();
    }

    @Test
    public void tooManyRequestsMapsToRateLimitCarryingRetryAfter() {
        LLMException failure = LLMErrorMapper.fromStatus(
                PROVIDER, MODEL, ENDPOINT, 429, "slow down",
                headers(Map.of("Retry-After", List.of("30"))));

        assertThat(failure).isInstanceOf(LLMRateLimitException.class);
        assertThat(failure.getKind()).isEqualTo(LLMException.Kind.RATE_LIMITED);
        assertThat(failure.isRetryable()).isTrue();
        assertThat(failure.getRetryAfter()).isEqualTo(Duration.ofSeconds(30));
        assertThat(failure.getMessage()).contains("Rate limited by provider 'openai'")
                                        .contains("Retry after 30s");
    }

    @Test
    public void badRequestMapsToNonRetryableClientError() {
        LLMException failure = map(400, "{\"error\":\"bad model\"}");

        assertThat(failure).isInstanceOf(LLMBadRequestException.class);
        assertThat(failure.getKind()).isEqualTo(LLMException.Kind.BAD_REQUEST);
        assertThat(failure.isRetryable()).isFalse();
        assertThat(failure.getMessage()).contains("rejected the request").contains("HTTP 400");
    }

    @Test
    public void otherTerminalClientErrorsMapToBadRequest() {
        assertThat(map(404, "missing")).isInstanceOf(LLMBadRequestException.class);
        assertThat(map(422, "unprocessable")).isInstanceOf(LLMBadRequestException.class);
    }

    @Test
    public void serverErrorsMapToRetryableServerFailure() {
        for (int status : new int[]{500, 502, 503, 504, 599}) {
            LLMException failure = map(status, "boom");
            assertThat(failure).isInstanceOf(LLMServerException.class);
            assertThat(failure.getKind()).isEqualTo(LLMException.Kind.SERVER_ERROR);
            assertThat(failure.isRetryable()).isTrue();
            assertThat(failure.getStatusCode()).isEqualTo(status);
        }
    }

    @Test
    public void requestTimeoutMapsToRetryableTransportFailure() {
        LLMException failure = map(408, "timeout");

        assertThat(failure).isInstanceOf(LLMTransportException.class);
        assertThat(failure.getKind()).isEqualTo(LLMException.Kind.TRANSPORT);
        assertThat(failure.isRetryable()).isTrue();
        assertThat(failure.getMessage()).contains("timed out").contains("HTTP 408");
    }

    @Test
    public void unexpectedStatusMapsToUnknownNonRetryableFailure() {
        LLMException failure = map(302, "moved");

        assertThat(failure.getKind()).isEqualTo(LLMException.Kind.UNKNOWN);
        assertThat(failure.isRetryable()).isFalse();
        assertThat(failure.getMessage()).contains("unexpected HTTP 302");
    }

    @Test
    public void transportFailureNamesTheEndpointItCouldNotReach() {
        LLMTransportException failure = new LLMTransportException(
                PROVIDER, MODEL, "http://127.0.0.1:1/v1", new java.net.ConnectException("Connection refused"));

        assertThat(failure.getKind()).isEqualTo(LLMException.Kind.TRANSPORT);
        assertThat(failure.getMessage()).contains("Cannot reach http://127.0.0.1:1/v1")
                                        .contains("Connection refused");
    }

    @Test
    public void transportFailureExplainsAMessagelessConnectFailure() {
        // The JDK throws java.net.ConnectException with no message at all for a refused connection;
        // echoing the bare class name would tell the user nothing.
        LLMTransportException failure = new LLMTransportException(
                PROVIDER, MODEL, "http://127.0.0.1:1/v1", new java.net.ConnectException());

        assertThat(failure.getMessage()).contains("Cannot reach http://127.0.0.1:1/v1")
                                        .contains("connection refused or endpoint unreachable");
    }

    @Test
    public void transportFailurePrefersANestedMessageOverTheClassName() {
        LLMTransportException failure = new LLMTransportException(
                PROVIDER, MODEL, "http://host/v1",
                new java.io.IOException(new java.net.UnknownHostException("no-such-host")));

        assertThat(failure.getMessage()).contains("no-such-host");
    }

    // ---------- Retry-After ----------

    @Test
    public void retryAfterParsesDelaySeconds() {
        assertThat(LLMErrorMapper.retryAfter(headers(Map.of("Retry-After", List.of("12")))))
                .isEqualTo(Duration.ofSeconds(12));
    }

    @Test
    public void retryAfterParsesHttpDate() {
        String when = ZonedDateTime.now().plusSeconds(60).format(DateTimeFormatter.RFC_1123_DATE_TIME);

        Duration parsed = LLMErrorMapper.retryAfter(headers(Map.of("Retry-After", List.of(when))));

        assertThat(parsed).isNotNull();
        assertThat(parsed.toSeconds()).isBetween(50L, 60L);
    }

    @Test
    public void retryAfterIgnoresMissingMalformedAndPastValues() {
        String past = ZonedDateTime.now().minusMinutes(5).format(DateTimeFormatter.RFC_1123_DATE_TIME);

        assertThat(LLMErrorMapper.retryAfter(null)).isNull();
        assertThat(LLMErrorMapper.retryAfter(headers(Map.of()))).isNull();
        assertThat(LLMErrorMapper.retryAfter(headers(Map.of("Retry-After", List.of("soon"))))).isNull();
        assertThat(LLMErrorMapper.retryAfter(headers(Map.of("Retry-After", List.of("0"))))).isNull();
        assertThat(LLMErrorMapper.retryAfter(headers(Map.of("Retry-After", List.of(past))))).isNull();
    }

    // ---------- excerpting and redaction ----------

    @Test
    public void excerptTruncatesLongBodies() {
        String body = "x".repeat(5_000);

        String excerpt = LLMErrorMapper.excerpt(body);

        assertThat(excerpt).hasSize(LLMErrorMapper.MAX_BODY_EXCERPT + 3).endsWith("...");
    }

    @Test
    public void excerptCollapsesWhitespaceAndDropsEmptyBodies() {
        assertThat(LLMErrorMapper.excerpt("  line one\n\n\tline two  ")).isEqualTo("line one line two");
        assertThat(LLMErrorMapper.excerpt("   ")).isNull();
        assertThat(LLMErrorMapper.excerpt(null)).isNull();
    }

    @Test
    public void excerptRedactsCredentialsEchoedBackByTheProvider() {
        String body = "Incorrect API key provided: sk-lIveKey1234567890abcdef. "
                + "Authorization: Bearer ghs_ABCDEFGHIJKLMNOPQRST, "
                + "\"api_key\": \"abcd1234efgh5678\", AKIAIOSFODNN7EXAMPLE";

        String excerpt = LLMErrorMapper.excerpt(body);

        assertThat(excerpt).doesNotContain("lIveKey1234567890abcdef")
                           .doesNotContain("ABCDEFGHIJKLMNOPQRST")
                           .doesNotContain("abcd1234efgh5678")
                           .doesNotContain("AKIAIOSFODNN7EXAMPLE")
                           .contains("***");
    }

    @Test
    public void mappedMessageNeverLeaksAnApiKeyFromTheBody() {
        LLMException failure = map(401, "{\"error\":{\"message\":\"Incorrect API key provided: sk-topsecret12345\"}}");

        assertThat(failure.getMessage()).doesNotContain("topsecret12345");
        assertThat(failure.getBodyExcerpt()).doesNotContain("topsecret12345");
    }
}
