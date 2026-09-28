package com.eonmux.cadetcoder.net;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Telling a transient provider failure wearing an HTTP 200 from a genuinely empty answer. */
public class TransientPayloadTest {

    @Test
    public void aTruncatedBodyIsTransient() {
        // A connection that died mid-body leaves valid-looking JSON with the end missing.
        assertThat(TransientPayload.isTransient("{\"choices\":[{\"message\":{\"cont")).isTrue();
    }

    @Test
    public void anEmptyBodyIsTransient() {
        assertThat(TransientPayload.isTransient("")).isTrue();
        assertThat(TransientPayload.isTransient("   ")).isTrue();
        assertThat(TransientPayload.isTransient(null)).isTrue();
    }

    @Test
    public void aBodyThatWasNeverJsonIsNotTransient() {
        // HTML, or plain text, under a 200 means this endpoint does not speak the protocol -- the
        // wrong URL, or something intercepting it. Retrying cannot fix a misconfiguration, and
        // spending the whole budget first only delays saying so.
        assertThat(TransientPayload.isTransient("<html><body>502 Bad Gateway</body></html>")).isFalse();
        assertThat(TransientPayload.isTransient("not json at all")).isFalse();
    }

    @Test
    public void anOverloadedErrorNodeIsTransient() {
        assertThat(TransientPayload.isTransient(
                "{\"error\":{\"message\":\"Overloaded\",\"type\":\"overloaded_error\"}}")).isTrue();
        assertThat(TransientPayload.isTransient(
                "{\"error\":{\"message\":\"The service is temporarily unavailable\"}}")).isTrue();
        assertThat(TransientPayload.isTransient(
                "{\"error\":{\"message\":\"Rate limit reached, please try again\"}}")).isTrue();
    }

    @Test
    public void aTerminalErrorNodeIsNotTransient() {
        // Retrying these produces the same answer more slowly.
        assertThat(TransientPayload.isTransient(
                "{\"error\":{\"message\":\"model does not exist\"}}")).isFalse();
        assertThat(TransientPayload.isTransient(
                "{\"error\":{\"message\":\"invalid_api_key\"}}")).isFalse();
    }

    @Test
    public void aWellFormedAnswerWithNoContentIsNotTransient() {
        // The model genuinely returned nothing. Another attempt returns nothing again.
        assertThat(TransientPayload.isTransient("{\"choices\":[]}")).isFalse();
        assertThat(TransientPayload.isTransient(
                "{\"choices\":[{\"message\":{\"content\":\"\"}}]}")).isFalse();
    }

    @Test
    public void aCompletionThatTalksAboutOutagesIsNotTransient() {
        // THE property that decides how this class is written. The model's prose is arbitrary and
        // must never be searched for failure wording: a correct answer about rate limiting would be
        // thrown away and re-billed, exactly when the user is asking about failures.
        String[] answers = {
                "{\"choices\":[{\"message\":{\"content\":\"You are overloaded and rate limited.\"}}]}",
                "{\"choices\":[{\"message\":{\"content\":\"The service is temporarily unavailable; try again.\"}}]}",
                "{\"content\":[{\"type\":\"text\",\"text\":\"ECONNRESET means the connection was reset.\"}]}",
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Internal server error, please retry.\"}]}}]}",
        };
        for (String answer : answers) {
            assertThat(TransientPayload.isTransient(answer))
                    .as("a usable completion must never be retried: %s", answer)
                    .isFalse();
        }
    }

    @Test
    public void aWellFormedDocumentOfTheWrongShapeIsNotTransient() {
        // It parsed cleanly, so nothing was lost in transit; the shape is simply wrong.
        assertThat(TransientPayload.isTransient("[]")).isFalse();
        assertThat(TransientPayload.isTransient("\"just a string\"")).isFalse();
    }

    @Test
    public void truncationIsToldApartFromAWrongEndpointByHowTheBodyStarts() {
        // Both fail to parse; only one of them began arriving as a completion.
        assertThat(TransientPayload.isTransient("{\"choices\":[{\"mess")).isTrue();
        assertThat(TransientPayload.isTransient("Service Unavailable")).isFalse();
    }

    @Test
    public void theReasonSaysWhatWentWrong() {
        assertThat(TransientPayload.describe("")).contains("empty");
        assertThat(TransientPayload.describe("{\"choices\":[{\"mess")).contains("truncated");
        assertThat(TransientPayload.describe("{\"error\":{\"message\":\"Overloaded\"}}"))
                .contains("Overloaded");
    }
}
