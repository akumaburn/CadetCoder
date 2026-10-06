package com.eonmux.cadetcoder.net;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the tool does when Z.ai answers HTTP 429.
 *
 * <h2>The defect</h2>
 *
 * <p>Z.ai sends 429 both for too many requests and for an account that cannot pay, and tells the
 * two apart only by the business code in the body. Every 429 was read as a rate limit, so an empty
 * balance (code 1113) or an expired GLM Coding Plan (code 1309) was retried for about ninety
 * seconds, and the run then reported "rate limited". Neither part was true of the account.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>That an exhausted allowance is a non-retryable failure of its own kind that names the code;
 * that a real rate limit from the same platform is still retryable; and that the codes are read only
 * for the platform that owns them.</p>
 */
public class AnEmptyBalanceIsNotARateLimitTest {

    private static LLMException mapped(String provider, String body) {
        return LLMErrorMapper.fromStatus(provider, "glm-5.1",
                                         "https://api.z.ai/api/paas/v4/chat/completions", 429,
                                         body, null);
    }

    @Test
    public void anEmptyBalanceIsNotRetried() {
        LLMException failure = mapped("zai",
                "{\"error\":{\"code\":\"1113\",\"message\":\"Insufficient balance or no resource"
                + " package. Please recharge.\"}}");

        assertThat(failure).isInstanceOf(LLMAccountLimitException.class);
        assertThat(failure.getKind()).isEqualTo(LLMException.Kind.ACCOUNT_LIMIT);
        assertThat(failure.isRetryable()).isFalse();
        assertThat(failure.getMessage())
                .contains("Provider 'zai'")
                .contains("code 1113")
                .contains("Insufficient balance")
                .contains("'zai-coding-plan'");
    }

    @Test
    public void anExpiredCodingPlanIsNotRetried() {
        // Sent as a number rather than a string: the code is read either way.
        LLMException failure = mapped("zai-coding-plan",
                "{\"error\":{\"code\":1309,\"message\":\"Your GLM Coding Plan has expired\"}}");

        assertThat(failure.getKind()).isEqualTo(LLMException.Kind.ACCOUNT_LIMIT);
        assertThat(failure.getMessage()).contains("code 1309").doesNotContain("connector:");
    }

    @Test
    public void aRealRateLimitIsStillRetried() {
        LLMException failure = mapped("zai",
                "{\"error\":{\"code\":\"1302\",\"message\":\"Rate limit reached for requests\"}}");

        assertThat(failure).isInstanceOf(LLMRateLimitException.class);
        assertThat(failure.isRetryable()).isTrue();
    }

    @Test
    public void anOverloadedPlatformIsStillRetried() {
        assertThat(mapped("zhipuai", "{\"error\":{\"code\":\"1305\",\"message\":\"overloaded\"}}")
                           .isRetryable()).isTrue();
    }

    @Test
    public void anotherProvidersCodeMeansWhatThatProviderMeans() {
        // 1113 is Z.ai's number. Another provider that sent the same digits owes nothing to it.
        assertThat(mapped("openrouter", "{\"error\":{\"code\":\"1113\",\"message\":\"slow down\"}}"))
                .isInstanceOf(LLMRateLimitException.class);
    }

    @Test
    public void aBodyThatIsNotJsonIsARateLimit() {
        assertThat(mapped("zai", "<html>429 Too Many Requests</html>"))
                .isInstanceOf(LLMRateLimitException.class);
    }
}
