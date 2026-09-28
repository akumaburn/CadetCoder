package com.eonmux.cadetcoder.net;

import com.eonmux.cadetcoder.ai.PromptData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An error a provider sent under HTTP 200 is reported in the provider's own words.
 *
 * <h2>The defect</h2>
 *
 * <p>Gateways routinely answer 200 with an error object where the completion should be. Those
 * whose wording says "overloaded", "rate limited" or "try again" were recognised and retried. The
 * rest -- a model not enabled for this key, a billing problem, an unknown parameter -- matched no
 * retry pattern, so the response was handed back to the backend, which found no {@code choices} in
 * it and said "no completion choices returned". The provider had written a sentence explaining
 * exactly what was wrong and it was thrown away, leaving the user with a message that named nothing
 * to act on. {@code TransientPayload.describe} already knew how to pull that sentence out; nobody
 * asked it on the path where retrying is not the answer.</p>
 */
class AproviderThatExplainedItselfIsQuotedTest {

    @BeforeEach
    void doNotSpendTheTestBudgetOnBackoff() {
        System.setProperty(RetryPolicy.MAX_ATTEMPTS_PROPERTY, "2");
        System.setProperty(RetryPolicy.BASE_DELAY_MS_PROPERTY, "1");
        System.setProperty(RetryPolicy.MAX_DELAY_MS_PROPERTY, "1");
    }

    @AfterEach
    void restoreTheRetryPolicy() {
        System.clearProperty(RetryPolicy.MAX_ATTEMPTS_PROPERTY);
        System.clearProperty(RetryPolicy.BASE_DELAY_MS_PROPERTY);
        System.clearProperty(RetryPolicy.MAX_DELAY_MS_PROPERTY);
    }

    /** A client that answers every request with one canned 200. */
    private static HttpClient answering(String body) {
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(body);
        HttpClient client = mock(HttpClient.class);
        when(client.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(response));
        return client;
    }

    private static LlamaServerBackend backendAnswering(String body) {
        return new LlamaServerBackend("local-x", "http://127.0.0.1:8080") {
            { this.httpClient = answering(body); }
        };
    }

    @Test
    void whatTheProviderSaidIsWhatTheUserIsTold() {
        String body = "{\"error\":{\"message\":\"model gpt-5 is not enabled for this key\","
                      + "\"type\":\"invalid_request_error\"}}";

        assertThat(catchThrowable(
                () -> backendAnswering(body).complete(new PromptData("sys", "hi"), new HashMap<>())))
                .isInstanceOf(LLMException.class)
                .hasMessageContaining("model gpt-5 is not enabled for this key");
    }

    @Test
    void anErrorNobodyCanRetryIsNotRetried() {
        assertThat(TransientPayload.isTransient(
                "{\"error\":{\"message\":\"model gpt-5 is not enabled for this key\"}}"))
                .as("the wording names nothing that a second attempt would change")
                .isFalse();
        assertThat(TransientPayload.carriesProviderError(
                "{\"error\":{\"message\":\"model gpt-5 is not enabled for this key\"}}")).isTrue();
    }

    @Test
    void acompletionIsNeverMistakenForAnError() {
        // The model's own prose is arbitrary and routinely discusses errors. Only a top-level
        // error node counts, which a real completion does not have.
        String reply = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\","
                       + "\"content\":\"The error field is where providers report problems.\"}}]}";

        assertThat(TransientPayload.carriesProviderError(reply)).isFalse();
    }

    @Test
    void anErrorReportedAsNullIsNotAnError() {
        // Several providers include the key and leave it null on success.
        assertThat(TransientPayload.carriesProviderError(
                "{\"error\":null,\"choices\":[]}")).isFalse();
        assertThat(TransientPayload.carriesProviderError(null)).isFalse();
        assertThat(TransientPayload.carriesProviderError("not json at all")).isFalse();
    }
}
