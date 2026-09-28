package com.eonmux.cadetcoder.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shapes a credential takes on its way into a log line or an error message.
 */
class SecretRedactorTest {

    @Test
    void aProviderKeyIsReplacedButStillIdentifiable() {
        assertThat(SecretRedactor.redact("Invalid key sk-abc123def456ghi"))
                .isEqualTo("Invalid key sk-***");
    }

    @Test
    void aGitHubTokenIsReplaced() {
        assertThat(SecretRedactor.redact("token ghp_0123456789abcdefghij"))
                .doesNotContain("0123456789abcdefghij")
                .contains("ghp_***");
    }

    @Test
    void anAwsAccessKeyIdIsReplaced() {
        assertThat(SecretRedactor.redact("id AKIAIOSFODNN7EXAMPLE")).doesNotContain("IOSFODNN7EXAMPLE");
    }

    @Test
    void aBearerHeaderIsReplaced() {
        assertThat(SecretRedactor.redact("Authorization: Bearer abcdefghijklmnop"))
                .doesNotContain("abcdefghijklmnop");
    }

    @Test
    void aKeyQuotedInsideJsonIsReplaced() {
        assertThat(SecretRedactor.redact("{\"api_key\": \"verysecretvalue\"}"))
                .doesNotContain("verysecretvalue");
    }

    @Test
    void ordinaryTextIsLeftAlone() {
        String prose = "The request failed because the model was overloaded.";

        assertThat(SecretRedactor.redact(prose)).isEqualTo(prose);
    }

    @Test
    void nothingIsNotACrash() {
        assertThat(SecretRedactor.redact(null)).isNull();
        assertThat(SecretRedactor.redact("")).isEmpty();
    }
}
