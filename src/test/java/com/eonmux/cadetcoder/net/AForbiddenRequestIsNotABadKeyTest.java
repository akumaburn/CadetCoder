package com.eonmux.cadetcoder.net;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the tool says when a provider answers 403.
 *
 * <h2>The defect</h2>
 *
 * <p>401 and 403 produced one message: <em>"Authentication failed ... Check your API key."</em> For
 * a 401 that is the advice. For a 403 the key authenticated perfectly and the account behind it is
 * not permitted the request, so the advice sends the user to re-paste a working key and meet the
 * identical failure with nothing learned. A gateway selling several vendors on one subscription
 * makes this the common case rather than a rare one -- choosing a model above the plan's tier is a
 * 403 with a good key, and the only thing that explains it is the provider's own reason.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>That a 403 says the key was accepted and does not ask for it to be checked; that it still
 * carries the provider's reason, which is the only part saying what is actually wrong; that it
 * points at the command listing what the provider does offer; that a 401 still gives the advice
 * that suits it; and that both remain the same non-retryable kind, because neither improves by
 * being sent again.</p>
 */
public class AForbiddenRequestIsNotABadKeyTest {

    private static final String REASON =
            "{\"error\":{\"message\":\"MODEL_NOT_IN_PLAN: available in Pro and above\"}}";

    private static LLMException mapped(int status) {
        return LLMErrorMapper.fromStatus("commandcode", "claude-haiku-4-5",
                                         "https://api.commandcode.ai/provider/v1", status, REASON,
                                         null);
    }

    @Test
    public void aforbiddenRequestDoesNotBlameTheKey() {
        assertThat(mapped(403).getMessage())
                .contains("The key was accepted")
                .doesNotContain("Check your API key");
    }

    @Test
    public void itSaysWhoRefusedAndWhichModel() {
        assertThat(mapped(403).getMessage())
                .contains("provider 'commandcode'")
                .contains("claude-haiku-4-5")
                .contains("HTTP 403");
    }

    @Test
    public void itCarriesTheReasonTheProviderGave() {
        // The only part of the message that says what is actually wrong.
        assertThat(mapped(403).getMessage()).contains("MODEL_NOT_IN_PLAN");
    }

    @Test
    public void itPointsAtWhatTheProviderDoesOffer() {
        assertThat(mapped(403).getMessage()).contains("cadet models commandcode");
    }

    @Test
    public void anunauthenticatedRequestStillAsksForTheKey() {
        assertThat(mapped(401).getMessage())
                .contains("Authentication failed")
                .contains("Check your API key")
                .doesNotContain("The key was accepted");
    }

    @Test
    public void neitherIsWorthSendingAgain() {
        assertThat(mapped(401)).isInstanceOf(LLMAuthException.class);
        assertThat(mapped(403)).isInstanceOf(LLMAuthException.class);
        assertThat(mapped(401).isRetryable()).isFalse();
        assertThat(mapped(403).isRetryable()).isFalse();
        assertThat(mapped(403).getKind()).isEqualTo(LLMException.Kind.AUTH);
    }
}
