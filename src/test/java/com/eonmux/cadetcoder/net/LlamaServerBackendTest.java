package com.eonmux.cadetcoder.net;

import com.eonmux.cadetcoder.ai.PromptData;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.io.IOException;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.util.concurrent.CompletableFuture;

@RunWith (MockitoJUnitRunner.class)
public class LlamaServerBackendTest {

    private static final String             API_ENDPOINT = "http://localhost:8080";
    private static final String             MODEL_NAME   = "llama-2-7b";
    @Mock
    private HttpClient mockHttpClient;
    @Mock
    private HttpResponse<String> mockResponse;
    @Mock
    private PromptData mockPromptData;
    private              LlamaServerBackend llamaServerBackend;

    @Before
    public void setUp() {
        // Keep the retry backoff imperceptible; the delays themselves are covered by RetryPolicyTest.
        System.setProperty(RetryPolicy.BASE_DELAY_MS_PROPERTY, "1");
        System.setProperty(RetryPolicy.MAX_DELAY_MS_PROPERTY, "5");

        // Create backend with mocked HttpClient
        llamaServerBackend = new LlamaServerBackend(MODEL_NAME, API_ENDPOINT) {
            {
                this.httpClient = mockHttpClient;
            }
        };
    }

    @After
    public void tearDown() {
        System.clearProperty(RetryPolicy.BASE_DELAY_MS_PROPERTY);
        System.clearProperty(RetryPolicy.MAX_DELAY_MS_PROPERTY);
    }

    @Test
    public void constructor_WithValidEndpoint_InitializesCorrectly() {
        // Given
        String modelName   = "llama-2-13b";
        String apiEndpoint = "http://192.168.1.100:8080";

        // When
        LlamaServerBackend backend = new LlamaServerBackend(modelName, apiEndpoint);

        // Then
        assertThat(backend.getModelName()).isEqualTo(modelName);
        assertThat(backend.isAvailable()).isTrue();
    }

    @Test
    public void constructor_WithNullEndpoint_NotAvailable() {
        // When
        LlamaServerBackend backend = new LlamaServerBackend(MODEL_NAME, null);

        // Then
        assertThat(backend.isAvailable()).isFalse();
    }

    @Test
    public void constructor_WithEmptyEndpoint_NotAvailable() {
        // When
        LlamaServerBackend backend = new LlamaServerBackend(MODEL_NAME, "");

        // Then
        assertThat(backend.isAvailable()).isFalse();
    }

    @Test
    public void complete_WithStructuredMessages_SuccessfulResponse() throws Exception {
        // Given
        when(mockPromptData.getSystemPrompt()).thenReturn("You are a coding assistant");
        when(mockPromptData.getUserPrompt()).thenReturn("Write a hello world program");

        String responseJson = createSuccessfulResponse(
                "Here's a hello world program in Python:\n```python\nprint(\"Hello, World!\")\n```");
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(responseJson);
        when(mockHttpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(mockResponse));

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("temperature", 0.5f);
        parameters.put("maxTokens", 1024);
        parameters.put("completionTimeout", 120);

        // When
        String result = llamaServerBackend.complete(mockPromptData, parameters);

        // Then
        assertThat(result).contains("Hello, World!");

        // Verify request was sent correctly
        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockHttpClient).sendAsync(requestCaptor.capture(), any());

        HttpRequest sentRequest = requestCaptor.getValue();
        assertThat(sentRequest.uri().toString()).isEqualTo(API_ENDPOINT + "/v1/chat/completions");
        assertThat(sentRequest.headers().firstValue("Content-Type")).contains("application/json");
        assertThat(sentRequest.timeout().get()).isEqualTo(Duration.ofSeconds(120));
    }

    // Helper method to create a successful response JSON
    private String createSuccessfulResponse(String content) {
        Map<String, Object> response = new HashMap<>();
        Map<String, Object> choice   = new HashMap<>();
        Map<String, Object> message  = new HashMap<>();

        message.put("role", "assistant");
        message.put("content", content);
        choice.put("message", message);
        choice.put("index", 0);
        response.put("choices", List.of(choice));

        try {
            return new ObjectMapper().writeValueAsString(response);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void complete_WithFormattedTemplate_SuccessfulResponse() throws Exception {
        // Given

        String responseJson = createSuccessfulResponse("Task completed successfully");
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(responseJson);
        when(mockHttpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(mockResponse));

        Map<String, Object> parameters = new HashMap<>();

        // When
        String result = llamaServerBackend.complete(mockPromptData, parameters);

        // Then
        assertThat(result).isEqualTo("Task completed successfully");
    }

    @Test
    public void complete_WithDefaultParameters_UsesCorrectDefaults() throws Exception {
        // Given
        when(mockPromptData.getSystemPrompt()).thenReturn("System");
        when(mockPromptData.getUserPrompt()).thenReturn("User");

        String responseJson = createSuccessfulResponse("Response");
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(responseJson);
        when(mockHttpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(mockResponse));

        Map<String, Object> parameters = new HashMap<>(); // Empty parameters

        // When
        llamaServerBackend.complete(mockPromptData, parameters);

        // Then
        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockHttpClient).sendAsync(requestCaptor.capture(), any());

        // Verify default timeout (300 seconds for llama server)
        assertThat(requestCaptor.getValue().timeout().get()).isEqualTo(Duration.ofSeconds(300));
    }

    @Test
    public void complete_WithNetworkError_ThrowsException() throws Exception {
        // Given
        when(mockPromptData.getUserPrompt()).thenReturn("Test");

        IOException networkError = new IOException("Connection refused");
        when(mockHttpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.failedFuture(networkError));

        Map<String, Object> parameters = new HashMap<>();

        // When/Then
        // Inverted: a local llama-server that is not running used to surface as a bare IOException.
        // It is now a typed transport failure naming the endpoint the user has to bring up, with the
        // original IOException preserved as the cause.
        assertThatThrownBy(() -> llamaServerBackend.complete(mockPromptData, parameters))
                .isInstanceOf(LLMTransportException.class)
                .hasMessageContaining("Cannot reach " + API_ENDPOINT + "/v1/chat/completions")
                .hasMessageContaining("Connection refused")
                .hasCauseInstanceOf(IOException.class);
    }

    @Test
    public void complete_WithServerError_ThrowsServerFailureAfterRetrying() throws Exception {
        // Given
        when(mockPromptData.getUserPrompt()).thenReturn("Test");

        String errorBody = "{\"error\": \"Server overloaded\"}";
        when(mockResponse.statusCode()).thenReturn(503);
        when(mockResponse.body()).thenReturn(errorBody);
        when(mockHttpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(mockResponse));

        Map<String, Object> parameters = new HashMap<>();

        // When/Then
        // Inverted: a 503 used to be RETURNED as completion text, so a failed call was parsed as if
        // the model had answered and the command still exited 0. A 5xx is transient, so it is now
        // retried and then raised as a typed failure.
        assertThatThrownBy(() -> llamaServerBackend.complete(mockPromptData, parameters))
                .isInstanceOf(LLMServerException.class)
                .hasMessageContaining("Provider 'llama-server' returned a server error (HTTP 503)")
                .hasMessageContaining("try again later");
        verify(mockHttpClient, times(RetryPolicy.DEFAULT_MAX_ATTEMPTS)).sendAsync(any(HttpRequest.class), any());
    }

    @Test
    public void complete_WithEmptyChoices_ThrowsProtocolFailure() throws Exception {
        // Given
        when(mockPromptData.getUserPrompt()).thenReturn("Test");

        String responseJson = "{\"choices\": []}";
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(responseJson);
        when(mockHttpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(mockResponse));

        Map<String, Object> parameters = new HashMap<>();

        // When/Then
        // Inverted: a 200 that carries no usable completion is a failed call, not an answer, so it
        // no longer comes back as the string "Error: No completion choices returned.".
        assertThatThrownBy(() -> llamaServerBackend.complete(mockPromptData, parameters))
                .isInstanceOf(LLMProtocolException.class)
                .hasMessageContaining("no completion choices returned");
    }

    @Test
    public void complete_WithNullChoices_ThrowsProtocolFailure() throws Exception {
        // Given
        when(mockPromptData.getUserPrompt()).thenReturn("Test");

        String responseJson = "{\"choices\": null}";
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(responseJson);
        when(mockHttpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(mockResponse));

        Map<String, Object> parameters = new HashMap<>();

        // When/Then
        // Inverted: a 200 that carries no usable completion is a failed call, not an answer, so it
        // no longer comes back as the string "Error: No completion choices returned.".
        assertThatThrownBy(() -> llamaServerBackend.complete(mockPromptData, parameters))
                .isInstanceOf(LLMProtocolException.class)
                .hasMessageContaining("no completion choices returned");
    }

    /**
     * <b>Why this test changed</b>: it asserted that a reply carrying a choice was reported as
     * carrying none. The wording mattered, because the two have different causes and the message is
     * all the user gets: "no choices" is a provider that answered nothing, while this is a provider
     * that answered with a choice whose message is missing.
     */
    @Test
    public void complete_WithNullMessage_ThrowsProtocolFailure() throws Exception {
        // Given
        when(mockPromptData.getUserPrompt()).thenReturn("Test");

        String responseJson = "{\"choices\": [{\"message\": null}]}";
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(responseJson);
        when(mockHttpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(mockResponse));

        Map<String, Object> parameters = new HashMap<>();

        // When/Then
        // A choice IS present, so the failure names what is actually missing from it.
        assertThatThrownBy(() -> llamaServerBackend.complete(mockPromptData, parameters))
                .isInstanceOf(LLMProtocolException.class)
                .hasMessageContaining("no content found in message");
    }

    /**
     * <b>Why this test changed</b>: it asserted that a null completion was handed back to the
     * caller as the model's answer. Nothing downstream can tell that from a model that said
     * nothing, and the shape it comes in is a reasoning model's -- llama-server leaves
     * {@code content} null and puts the text under {@code reasoning_content} -- so the reply that
     * was hardest to read was the one reported as no reply at all. It is a failed call now, and the
     * reply that really is there is read by {@link ChatCompletionContent} like every other
     * provider's.
     */
    @Test
    public void complete_WithNullContent_ThrowsProtocolFailure() throws Exception {
        // Given
        when(mockPromptData.getUserPrompt()).thenReturn("Test");

        Map<String, Object> response = new HashMap<>();
        Map<String, Object> choice   = new HashMap<>();
        Map<String, Object> message  = new HashMap<>();

        message.put("role", "assistant");
        message.put("content", null);
        choice.put("message", message);
        response.put("choices", List.of(choice));

        String responseJson = new ObjectMapper().writeValueAsString(response);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(responseJson);
        when(mockHttpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(mockResponse));

        Map<String, Object> parameters = new HashMap<>();

        // When/Then
        assertThatThrownBy(() -> llamaServerBackend.complete(mockPromptData, parameters))
                .isInstanceOf(LLMProtocolException.class)
                .hasMessageContaining("no content found in message");
    }

    @Test
    public void complete_WithSpecialCharactersInPrompt_EscapesJsonCorrectly() throws Exception {
        // Given
        when(mockPromptData.getSystemPrompt()).thenReturn("System with \"quotes\" and \nnewlines");
        when(mockPromptData.getUserPrompt()).thenReturn("User with \\backslash and \ttab");

        String responseJson = createSuccessfulResponse("Response");
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(responseJson);
        when(mockHttpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(mockResponse));

        Map<String, Object> parameters = new HashMap<>();

        // When
        llamaServerBackend.complete(mockPromptData, parameters);

        // Then
        // Test ensures no exceptions are thrown during JSON construction
        verify(mockHttpClient).sendAsync(any(HttpRequest.class), any());
    }

    @Test
    public void complete_WithNullSystemPrompt_HandlesGracefully() throws Exception {
        // Given
        when(mockPromptData.getSystemPrompt()).thenReturn(null);
        when(mockPromptData.getUserPrompt()).thenReturn("User prompt only");

        String responseJson = createSuccessfulResponse("Response");
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(responseJson);
        when(mockHttpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(mockResponse));

        Map<String, Object> parameters = new HashMap<>();

        // When
        String result = llamaServerBackend.complete(mockPromptData, parameters);

        // Then
        assertThat(result).isEqualTo("Response");
    }

    @Test
    public void complete_WithEmptySystemPrompt_HandlesGracefully() throws Exception {
        // Given
        when(mockPromptData.getSystemPrompt()).thenReturn("");
        when(mockPromptData.getUserPrompt()).thenReturn("User prompt");

        String responseJson = createSuccessfulResponse("Response");
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(responseJson);
        when(mockHttpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(mockResponse));

        Map<String, Object> parameters = new HashMap<>();

        // When
        String result = llamaServerBackend.complete(mockPromptData, parameters);

        // Then
        assertThat(result).isEqualTo("Response");
    }

    @Test
    public void complete_WithLongRunningRequest_UsesCorrectTimeout() throws Exception {
        // Given
        when(mockPromptData.getUserPrompt()).thenReturn("Generate a very long response");

        String responseJson = createSuccessfulResponse("Long response...");
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(responseJson);
        when(mockHttpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(mockResponse));

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("completionTimeout", 600); // 10 minutes

        // When
        llamaServerBackend.complete(mockPromptData, parameters);

        // Then
        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockHttpClient).sendAsync(requestCaptor.capture(), any());

        assertThat(requestCaptor.getValue().timeout().get()).isEqualTo(Duration.ofSeconds(600));
    }
}