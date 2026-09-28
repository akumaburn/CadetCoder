package com.eonmux.cadetcoder.net;

import com.eonmux.cadetcoder.ai.PromptData;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Retry behaviour of the shared send loop: transient failures are repeated with backoff, terminal
 * ones are handed straight back to the backend, and an unreachable endpoint surfaces as a typed
 * transport failure instead of an opaque IOException.
 */
public class AbstractLLMBackendRetryTest {

    private static final String ENDPOINT = "http://localhost:9/v1";

    private HttpClient  mockHttpClient;
    private TestBackend backend;
    private HttpRequest request;

    @Before
    public void setUp() {
        // Keep the backoff imperceptible; the delay values themselves are covered by RetryPolicyTest.
        System.setProperty(RetryPolicy.BASE_DELAY_MS_PROPERTY, "1");
        System.setProperty(RetryPolicy.MAX_DELAY_MS_PROPERTY, "5");

        mockHttpClient = mock(HttpClient.class);
        backend        = new TestBackend("test-model", ENDPOINT, mockHttpClient);
        request        = HttpRequest.newBuilder().uri(URI.create(ENDPOINT + "/chat/completions"))
                                    .GET().build();
    }

    @After
    public void tearDown() {
        System.clearProperty(RetryPolicy.BASE_DELAY_MS_PROPERTY);
        System.clearProperty(RetryPolicy.MAX_DELAY_MS_PROPERTY);
        System.clearProperty(RetryPolicy.MAX_ATTEMPTS_PROPERTY);
    }

    @Test
    public void successfulResponseIsReturnedWithoutRetrying() throws Exception {
        HttpResponse<String> ok = response(200, "{}", null);
        when(mockHttpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(ok));

        HttpResponse<String> result = backend.send(request);

        assertThat(result.statusCode()).isEqualTo(200);
        verify(mockHttpClient, times(1)).sendAsync(any(HttpRequest.class), any());
    }

    @Test
    public void rateLimitIsRetriedAndThenSucceeds() throws Exception {
        HttpResponse<String> rateLimited = response(429, "slow down", null);
        HttpResponse<String> ok          = response(200, "{}", null);
        when(mockHttpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(rateLimited))
                .thenReturn(CompletableFuture.completedFuture(ok));

        HttpResponse<String> result = backend.send(request);

        assertThat(result.statusCode()).isEqualTo(200);
        verify(mockHttpClient, times(2)).sendAsync(any(HttpRequest.class), any());
    }

    @Test
    public void serverErrorsAreRetriedUpToTheAttemptCap() throws Exception {
        HttpResponse<String> unavailable = response(503, "unavailable", null);
        when(mockHttpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(unavailable));

        HttpResponse<String> result = backend.send(request);

        // The final failing response comes back so the backend can log and map it.
        assertThat(result.statusCode()).isEqualTo(503);
        verify(mockHttpClient, times(RetryPolicy.DEFAULT_MAX_ATTEMPTS)).sendAsync(any(HttpRequest.class), any());
    }

    @Test
    public void authFailuresAreNeverRetried() throws Exception {
        HttpResponse<String> forbidden = response(403, "forbidden", null);
        when(mockHttpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(forbidden));

        HttpResponse<String> result = backend.send(request);

        assertThat(result.statusCode()).isEqualTo(403);
        verify(mockHttpClient, times(1)).sendAsync(any(HttpRequest.class), any());
    }

    @Test
    public void badRequestsAreNeverRetried() throws Exception {
        HttpResponse<String> badRequest = response(400, "bad request", null);
        when(mockHttpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(badRequest));

        HttpResponse<String> result = backend.send(request);

        assertThat(result.statusCode()).isEqualTo(400);
        verify(mockHttpClient, times(1)).sendAsync(any(HttpRequest.class), any());
    }

    @Test
    public void aRetryAfterLongerThanTheCapStopsTheRetryLoop() throws Exception {
        HttpResponse<String> rateLimited = response(429, "slow down", "3600");
        when(mockHttpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(rateLimited));

        HttpResponse<String> result = backend.send(request);

        assertThat(result.statusCode()).isEqualTo(429);
        verify(mockHttpClient, times(1)).sendAsync(any(HttpRequest.class), any());
    }

    @Test
    public void ioFailuresAreRetriedThenReportedAsTransportFailures() throws Exception {
        when(mockHttpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.failedFuture(new IOException("Connection refused")));

        assertThatThrownBy(() -> backend.send(request))
                .isInstanceOf(LLMTransportException.class)
                .hasMessageContaining("Cannot reach " + ENDPOINT + "/chat/completions")
                .hasMessageContaining("Connection refused")
                .hasCauseInstanceOf(IOException.class);

        verify(mockHttpClient, times(RetryPolicy.DEFAULT_MAX_ATTEMPTS)).sendAsync(any(HttpRequest.class), any());
    }

    @Test
    public void aTransientIoFailureRecoversOnRetry() throws Exception {
        HttpResponse<String> ok = response(200, "{}", null);
        when(mockHttpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.failedFuture(new IOException("connection reset")))
                .thenReturn(CompletableFuture.completedFuture(ok));

        HttpResponse<String> result = backend.send(request);

        assertThat(result.statusCode()).isEqualTo(200);
        verify(mockHttpClient, times(2)).sendAsync(any(HttpRequest.class), any());
    }

    @Test
    public void theAttemptCapIsConfigurable() throws Exception {
        System.setProperty(RetryPolicy.MAX_ATTEMPTS_PROPERTY, "2");
        HttpResponse<String> serverError = response(500, "boom", null);
        when(mockHttpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(serverError));

        backend.send(request);

        verify(mockHttpClient, times(2)).sendAsync(any(HttpRequest.class), any());
    }

    @Test
    public void aNullRequestIsRejectedAtTheBoundary() {
        assertThatThrownBy(() -> backend.send(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("request");
    }

    @SuppressWarnings ("unchecked")
    private static HttpResponse<String> response(int status, String body, String retryAfter) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        // The body is stubbed for every status, 200 included: the send loop now inspects a
        // successful payload too, because a provider error can arrive under a 200.
        when(response.body()).thenReturn(body);
        when(response.headers()).thenReturn(retryAfter != null
                                            ? HttpHeaders.of(Map.of("Retry-After", List.of(retryAfter)),
                                                             (k, v) -> true)
                                            : HttpHeaders.of(Map.of(), (k, v) -> true));
        return response;
    }

    /** Minimal backend that only exercises the inherited send loop. */
    private static final class TestBackend extends AbstractLLMBackend {

        TestBackend(String modelName, String apiEndpoint, HttpClient client) {
            super(modelName, apiEndpoint);
            this.httpClient = client;
        }

        HttpResponse<String> send(HttpRequest request) {
            return sendWithRetry(request);
        }

        @Override
        public String complete(PromptData promptData, Map<String, Object> parameters) {
            throw new UnsupportedOperationException("not used");
        }
    }
}
