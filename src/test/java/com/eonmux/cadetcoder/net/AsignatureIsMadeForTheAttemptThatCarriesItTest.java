package com.eonmux.cadetcoder.net;

import com.eonmux.cadetcoder.ai.PromptData;
import com.eonmux.cadetcoder.auth.AwsSigV4Signer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A request signed for AWS is signed for the attempt that carries it.
 *
 * <p><b>The defect</b>: the signature was computed once, and the same immutable request was handed
 * to the retry loop. A signature carries the moment it was made; AWS refuses one more than five
 * minutes old, and the backoff between attempts is measured in exactly those minutes. So a Bedrock
 * call that hit a transient failure and waited came back as a 403 nothing could recover from --
 * turning a retryable blip into a hard authentication error.</p>
 */
class AsignatureIsMadeForTheAttemptThatCarriesItTest {

    private HttpClient          transport;
    private AmazonBedrockBackend backend;

    @BeforeEach
    void setUp() {
        System.setProperty(RetryPolicy.BASE_DELAY_MS_PROPERTY, "1");
        System.setProperty(RetryPolicy.MAX_DELAY_MS_PROPERTY, "5");

        transport = mock(HttpClient.class);
        backend   = new AmazonBedrockBackend("anthropic.claude-v2", "", "us-east-1", null,
                                             new AwsSigV4Signer.Credentials("AKIA", "secret", null));
        backend.httpClient = transport;
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(RetryPolicy.BASE_DELAY_MS_PROPERTY);
        System.clearProperty(RetryPolicy.MAX_DELAY_MS_PROPERTY);
    }

    private static HttpResponse<String> response(int status, String body) {
        @SuppressWarnings("unchecked")
        HttpResponse<String> reply = mock(HttpResponse.class);
        when(reply.statusCode()).thenReturn(status);
        when(reply.body()).thenReturn(body);
        when(reply.headers()).thenReturn(HttpHeaders.of(Map.of(), (a, b) -> true));
        when(reply.uri()).thenReturn(URI.create("https://bedrock-runtime.us-east-1.amazonaws.com"));
        return reply;
    }

    private static final String A_COMPLETION =
            "{\"output\":{\"message\":{\"content\":[{\"text\":\"hello\"}]}}}";

    @Test
    void everyAttemptCarriesAsignatureMadeForIt() throws Exception {
        // Both responses are built before any stubbing starts: creating a mock part-way through
        // stubbing another one is what Mockito reads as an unfinished stub.
        HttpResponse<String> unavailable = response(503, "unavailable");
        HttpResponse<String> answered    = response(200, A_COMPLETION);
        when(transport.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(unavailable))
                .thenReturn(CompletableFuture.completedFuture(answered));

        AtomicInteger signings = new AtomicInteger();
        List<Instant> signedAt = new ArrayList<>();

        try (MockedStatic<AwsSigV4Signer> signer = mockStatic(AwsSigV4Signer.class)) {
            signer.when(() -> AwsSigV4Signer.signedHeaders(any(), any(), any(), any(), any(), any(),
                                                           any(), any()))
                  .thenAnswer(call -> {
                      signedAt.add(call.getArgument(7));
                      return Map.of("Authorization", "signature-" + signings.incrementAndGet(),
                                    "X-Amz-Date", "stamp-" + signings.get());
                  });

            backend.complete(new PromptData("be brief", "hello"), Map.of());
        }

        ArgumentCaptor<HttpRequest> sent = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport, times(2)).sendAsync(sent.capture(), any());

        assertThat(signings.get()).as("signed once per attempt, not once per call").isEqualTo(2);
        assertThat(signedAt).as("and each signing asked what time it was").hasSize(2);

        List<String> carried = new ArrayList<>();
        for (HttpRequest request : sent.getAllValues()) {
            carried.add(request.headers().firstValue("Authorization").orElse("none"));
        }
        assertThat(carried)
                .as("the second attempt did not go out under the first attempt's signature")
                .containsExactly("signature-1", "signature-2");
    }

    @Test
    void asingleAttemptStillSignsExactlyOnce() throws Exception {
        HttpResponse<String> answered = response(200, A_COMPLETION);
        when(transport.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(answered));

        AtomicInteger signings = new AtomicInteger();
        try (MockedStatic<AwsSigV4Signer> signer = mockStatic(AwsSigV4Signer.class)) {
            signer.when(() -> AwsSigV4Signer.signedHeaders(any(), any(), any(), any(), any(), any(),
                                                           any(), any()))
                  .thenAnswer(call -> Map.of("Authorization",
                                             "signature-" + signings.incrementAndGet()));

            backend.complete(new PromptData("be brief", "hello"), Map.of());
        }

        assertThat(signings.get()).isEqualTo(1);
    }
}
