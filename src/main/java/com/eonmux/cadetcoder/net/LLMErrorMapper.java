package com.eonmux.cadetcoder.net;

import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import com.eonmux.cadetcoder.security.SecretRedactor;

import java.util.regex.Pattern;

/**
 * Maps an HTTP outcome from a provider onto the {@link LLMException} hierarchy.
 *
 * <p>Two rules govern what ends up in the user's face: the provider's response body is truncated to
 * a short single-line excerpt (a full body is often kilobytes of JSON), and anything that looks like
 * a credential is redacted first — providers routinely echo the offending key back in a 401 body.</p>
 */
public final class LLMErrorMapper {

    /** Upper bound on the provider body excerpt carried in a failure message. */
    static final int MAX_BODY_EXCERPT = 200;

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private LLMErrorMapper() {
        // utility
    }

    /**
     * Maps a non-200 response onto the matching typed failure.
     *
     * @param provider provider id or host label
     * @param model    model name, may be null
     * @param endpoint request URL, may be null
     * @param response the provider's response, must not be null
     */
    public static LLMException fromResponse(String provider,
                                            String model,
                                            String endpoint,
                                            HttpResponse<String> response) {
        if (response == null) {
            throw new IllegalArgumentException("response must not be null");
        }
        return fromStatus(provider, model, endpoint, response.statusCode(), response.body(),
                          response.headers());
    }

    /**
     * Whether a 422 is the provider refusing rather than retain the request.
     *
     * <p>Matched on the FULL body, not the excerpt the message quotes: the excerpt is truncated, and
     * a marker that fell off the end would silently demote this to the generic rejection. Two
     * spellings are accepted -- the documented error code, and the prose the gateway actually
     * answers with -- because either alone is a single string owned by somebody else.</p>
     *
     * @param body the response body, may be null
     * @return whether it names zero data retention as the reason
     */
    private static boolean refusedOverDataRetention(String body) {
        if (body == null) {
            return false;
        }
        String lower = body.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("cmd_zdr_no_providers") || lower.contains("zero-data-retention");
    }

    /**
     * Maps a status code (plus optional body/headers) onto the matching typed failure.
     *
     * @param statusCode the HTTP status; values other than a failure still produce a typed failure,
     *                   because callers only reach this method when the call did not succeed
     */
    public static LLMException fromStatus(String provider,
                                          String model,
                                          String endpoint,
                                          int statusCode,
                                          String body,
                                          HttpHeaders headers) {
        String excerpt = excerpt(body);
        if (statusCode == 401 || statusCode == 403) {
            return new LLMAuthException(provider, model, endpoint, statusCode, excerpt);
        }
        if (statusCode == 429) {
            return new LLMRateLimitException(provider, model, endpoint, statusCode, excerpt,
                                             retryAfter(headers));
        }
        if (statusCode == 408) {
            return new LLMTransportException(provider, model, endpoint, statusCode, excerpt);
        }
        if (statusCode >= 500 && statusCode <= 599) {
            return new LLMServerException(provider, model, endpoint, statusCode, excerpt);
        }
        if (statusCode == 422 && refusedOverDataRetention(body)) {
            return LLMBadRequestException.zeroDataRetentionUnavailable(provider, model, endpoint,
                                                                      excerpt);
        }
        if (statusCode >= 400 && statusCode <= 499) {
            return new LLMBadRequestException(provider, model, endpoint, statusCode, excerpt);
        }
        return new LLMException(LLMException.Kind.UNKNOWN,
                                LLMException.withExcerpt(
                                        "Provider '" + LLMException.providerLabel(provider)
                                                + "' returned an unexpected HTTP " + statusCode
                                                + LLMException.modelPhrase(model) + ".", excerpt),
                                provider, model, endpoint, statusCode, excerpt, null, null);
    }

    /**
     * Parses the provider's retry hint.
     *
     * <p>Prefers {@code Retry-After-Ms}, which OpenAI and Anthropic send and which carries
     * sub-second precision, then falls back to {@code Retry-After} in both forms defined by RFC
     * 7231: a delay in seconds and an HTTP-date. Taking the millisecond form first matters at the
     * short end — a provider asking for 200ms expressed as {@code Retry-After: 1} rounds up to five
     * times the wait it wanted.</p>
     *
     * @return the hint, or null when absent, malformed, or already in the past
     */
    static Duration retryAfter(HttpHeaders headers) {
        if (headers == null) {
            return null;
        }
        Optional<String> millis = headers.firstValue("Retry-After-Ms");
        if (millis.isPresent()) {
            try {
                double parsed = Double.parseDouble(millis.get().trim());
                if (parsed > 0) {
                    return Duration.ofMillis((long) Math.ceil(parsed));
                }
            } catch (NumberFormatException ignored) {
                // Fall through to the seconds/date header.
            }
        }
        Optional<String> raw = headers.firstValue("Retry-After");
        if (raw.isEmpty()) {
            return null;
        }
        String value = raw.get().trim();
        if (value.isEmpty()) {
            return null;
        }
        try {
            long seconds = Long.parseLong(value);
            return seconds > 0 ? Duration.ofSeconds(seconds) : null;
        } catch (NumberFormatException ignored) {
            // Not a delay-seconds value; fall through to the HTTP-date form.
        }
        try {
            ZonedDateTime when = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME);
            Duration delay = Duration.between(Instant.now(), when.toInstant());
            return delay.isNegative() || delay.isZero() ? null : delay;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * Produces a short, single-line, credential-free excerpt of a provider response body.
     *
     * @return the excerpt, or null when there is nothing worth showing
     */
    static String excerpt(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        String collapsed = WHITESPACE.matcher(body.trim()).replaceAll(" ");
        String redacted  = redact(collapsed);
        if (redacted.length() <= MAX_BODY_EXCERPT) {
            return redacted;
        }
        return redacted.substring(0, MAX_BODY_EXCERPT) + "...";
    }

    /** Replaces credential-shaped substrings with a marker. */
    static String redact(String text) {
        return SecretRedactor.redact(text);
    }
}
