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
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.util.concurrent.CompletableFuture;

@RunWith (MockitoJUnitRunner.class)
public class OpenAIBackendTest {

    private static final String        API_KEY      = "test-api-key";
    private static final String        API_ENDPOINT = "https://api.openai.com";
    private static final String        MODEL_NAME   = "gpt-4";
    @Mock
    private HttpClient mockHttpClient;
    @Mock
    private HttpResponse<String> mockResponse;
    @Mock
    private PromptData mockPromptData;
    private              OpenAIBackend openAIBackend;

    @Before
    public void setUp() {
        // Keep the retry backoff imperceptible; the delays themselves are covered by RetryPolicyTest.
        System.setProperty(RetryPolicy.BASE_DELAY_MS_PROPERTY, "1");
        System.setProperty(RetryPolicy.MAX_DELAY_MS_PROPERTY, "5");

        // Create backend with mocked HttpClient
        openAIBackend = new OpenAIBackend(MODEL_NAME, API_ENDPOINT, API_KEY) {
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
    public void constructor_WithValidParameters_InitializesCorrectly() {
        // Given
        String modelName   = "gpt-3.5-turbo";
        String apiEndpoint = "https://custom.openai.com";
        String apiKey      = "custom-key";

        // When
        OpenAIBackend backend = new OpenAIBackend(modelName, apiEndpoint, apiKey);

        // Then
        assertThat(backend.getModelName()).isEqualTo(modelName);
        assertThat(backend.isAvailable()).isTrue();
    }

    @Test
    public void constructor_WithNullApiKey_NotAvailable() {
        // When
        OpenAIBackend backend = new OpenAIBackend(MODEL_NAME, API_ENDPOINT, null);

        // Then
        assertThat(backend.isAvailable()).isFalse();
    }

    @Test
    public void constructor_WithEmptyApiEndpoint_NotAvailable() {
        // When
        OpenAIBackend backend = new OpenAIBackend(MODEL_NAME, "", API_KEY);

        // Then
        assertThat(backend.isAvailable()).isFalse();
    }

    @Test
    public void complete_WithStructuredMessages_SuccessfulResponse() throws Exception {
        // Given
        when(mockPromptData.getSystemPrompt()).thenReturn("You are a helpful assistant");
        when(mockPromptData.getUserPrompt()).thenReturn("Hello, how are you?");

        String responseJson = createSuccessfulResponse("I'm doing well, thank you!");
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(responseJson);
        when(mockHttpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(mockResponse));

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("temperature", 0.8f);
        parameters.put("maxTokens", 2048);
        parameters.put("completionTimeout", 60);

        // When
        String result = openAIBackend.complete(mockPromptData, parameters);

        // Then
        assertThat(result).isEqualTo("I'm doing well, thank you!");

        // Verify request was sent correctly
        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockHttpClient).sendAsync(requestCaptor.capture(), any());

        HttpRequest sentRequest = requestCaptor.getValue();
        assertThat(sentRequest.uri().toString()).isEqualTo(API_ENDPOINT + "/v1/chat/completions");
        assertThat(sentRequest.headers().firstValue("Authorization")).contains("Bearer " + API_KEY);
        assertThat(sentRequest.headers().firstValue("Content-Type")).contains("application/json");
        assertThat(sentRequest.timeout().get()).isEqualTo(Duration.ofSeconds(60));
    }

    // Helper method to create a successful response JSON
    /**
     * A 200 whose choice carries {@code "content": null}.
     *
     * <p>This is not a malformed response; it is what OpenAI returns when {@code finish_reason} is
     * {@code tool_calls}, when a content filter fires, and when a reasoning model spends its budget
     * without producing an answer -- and the shipped default model is a reasoning model. The key is
     * PRESENT, so a {@code containsKey} guard passes and the null reaches {@code content.length()}.
     */
    @Test
    public void complete_WhenTheProviderReturnsNullContent_ReportsNoContentRatherThanNpe() {
        when(mockPromptData.getSystemPrompt()).thenReturn("System");
        when(mockPromptData.getUserPrompt()).thenReturn("User");
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(
                "{\"choices\":[{\"index\":0,\"finish_reason\":\"tool_calls\","
                + "\"message\":{\"role\":\"assistant\",\"content\":null}}]}");
        when(mockHttpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(mockResponse));

        assertThat(catchThrowable(() -> openAIBackend.complete(mockPromptData, new HashMap<>())))
                .as("the caller needs to be told the provider sent no completion, not handed a "
                    + "NullPointerException that reads as the model having failed")
                .isInstanceOf(LLMProtocolException.class)
                .isNotInstanceOf(NullPointerException.class);
    }

    /**
     * Some providers return content as an array of blocks, which is a reply like any other.
     *
     * <p>This client sends that same shape itself whenever it marks a cache breakpoint, so a
     * provider that echoes the request shape answers in it. Reading it as a string is unsafe, and
     * {@code toString()} on the list produces {@code [{type=text, text=hi}]} -- text that is not an
     * error, and so reaches the model as the reply.</p>
     */
    @Test
    public void complete_WhenContentIsAnArrayOfBlocks_ReadsTheTextOutOfThem() throws Exception {
        when(mockPromptData.getSystemPrompt()).thenReturn("System");
        when(mockPromptData.getUserPrompt()).thenReturn("User");
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(
                "{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}}]}");
        when(mockHttpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(mockResponse));

        assertThat(openAIBackend.complete(mockPromptData, new HashMap<>())).isEqualTo("hi");
    }

    @Test
    public void complete_WhenTheChoiceHasNoMessage_ReportsNoContentRatherThanNpe() {
        when(mockPromptData.getSystemPrompt()).thenReturn("System");
        when(mockPromptData.getUserPrompt()).thenReturn("User");
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn("{\"choices\":[{\"index\":0,\"message\":null}]}");
        when(mockHttpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(mockResponse));

        assertThat(catchThrowable(() -> openAIBackend.complete(mockPromptData, new HashMap<>())))
                .isInstanceOf(LLMProtocolException.class)
                .isNotInstanceOf(NullPointerException.class);
    }

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

        String responseJson = createSuccessfulResponse("Response to formatted prompt");
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(responseJson);
        when(mockHttpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(mockResponse));

        Map<String, Object> parameters = new HashMap<>();

        // When
        String result = openAIBackend.complete(mockPromptData, parameters);

        // Then
        assertThat(result).isEqualTo("Response to formatted prompt");
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
        openAIBackend.complete(mockPromptData, parameters);

        // Then
        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockHttpClient).sendAsync(requestCaptor.capture(), any());

        // Verify default timeout
        assertThat(requestCaptor.getValue().timeout().get()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    public void complete_WithNetworkError_ThrowsException() throws Exception {
        // Given
        when(mockPromptData.getUserPrompt()).thenReturn("Test");

        IOException networkError = new IOException("Network connection failed");
        when(mockHttpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.failedFuture(networkError));

        Map<String, Object> parameters = new HashMap<>();

        // When/Then
        // Inverted: an unreachable endpoint used to surface as a bare IOException, which callers had
        // no way to tell apart from any other failure. It is now a typed transport failure naming the
        // endpoint, and the original IOException is preserved as the cause.
        assertThatThrownBy(() -> openAIBackend.complete(mockPromptData, parameters))
                .isInstanceOf(LLMTransportException.class)
                .hasMessageContaining("Cannot reach " + API_ENDPOINT + "/v1/chat/completions")
                .hasMessageContaining("Network connection failed")
                .hasCauseInstanceOf(IOException.class);
    }

    @Test
    public void complete_WithNon200StatusCode_ThrowsTypedAuthFailure() throws Exception {
        // Given
        when(mockPromptData.getUserPrompt()).thenReturn("Test");

        String errorBody = "{\"error\": {\"message\": \"Invalid API key\"}}";
        when(mockResponse.statusCode()).thenReturn(401);
        when(mockResponse.body()).thenReturn(errorBody);
        when(mockHttpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(mockResponse));

        Map<String, Object> parameters = new HashMap<>();

        // When/Then
        // Inverted: a 401 used to be RETURNED as completion text ("Error: Request failed with status
        // 401"), so the caller parsed a failed call as if the model had answered and still exited 0.
        // It now raises a typed, actionable failure and is never retried.
        Throwable thrown = catchThrowable(() -> openAIBackend.complete(mockPromptData, parameters));

        assertThat(thrown).isInstanceOf(LLMAuthException.class)
                          .hasMessageContaining("Authentication failed for provider 'openai'")
                          .hasMessageContaining("HTTP 401")
                          .hasMessageContaining("Check your API key")
                          .hasMessageContaining("Invalid API key");
        assertThat(((LLMException) thrown).getStatusCode()).isEqualTo(401);
        assertThat(((LLMException) thrown).isRetryable()).isFalse();
        // Credentials that were rejected once stay rejected: exactly one request is sent.
        verify(mockHttpClient, times(1)).sendAsync(any(HttpRequest.class), any());
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
        // Inverted: a 200 carrying no choices is a failed call, not a completion, so it no longer
        // comes back as the string "Error: No completion choices returned.".
        assertThatThrownBy(() -> openAIBackend.complete(mockPromptData, parameters))
                .isInstanceOf(LLMProtocolException.class)
                .hasMessageContaining("no completion choices returned");
    }

    @Test
    public void complete_WithMissingContent_ThrowsProtocolFailure() throws Exception {
        // Given
        when(mockPromptData.getUserPrompt()).thenReturn("Test");

        String responseJson = "{\"choices\": [{\"message\": {}}]}";
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(responseJson);
        when(mockHttpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(mockResponse));

        Map<String, Object> parameters = new HashMap<>();

        // When/Then
        // Inverted for the same reason as the empty-choices case above.
        assertThatThrownBy(() -> openAIBackend.complete(mockPromptData, parameters))
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
        openAIBackend.complete(mockPromptData, parameters);

        // Then
        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockHttpClient).sendAsync(requestCaptor.capture(), any());

        // The request body should have properly escaped JSON
        // We can't easily check the exact body content due to HttpRequest API limitations
        // but the test ensures no exceptions are thrown during JSON construction
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
        String result = openAIBackend.complete(mockPromptData, parameters);

        // Then
        assertThat(result).isEqualTo("Response");
    }

    @Test
    public void complete_WithRateLimitError_ThrowsRateLimitFailureAfterRetrying() throws Exception {
        // Given
        when(mockPromptData.getUserPrompt()).thenReturn("Test");

        String errorBody = "{\"error\": {\"message\": \"Rate limit exceeded\", \"type\": \"rate_limit_error\"}}";
        when(mockResponse.statusCode()).thenReturn(429);
        when(mockResponse.body()).thenReturn(errorBody);
        when(mockHttpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(mockResponse));

        Map<String, Object> parameters = new HashMap<>();

        // When/Then
        // Inverted: rate limiting used to be returned as completion text. It is now retried (a 429 is
        // transient) and, once the attempts are exhausted, raised as a typed failure.
        assertThatThrownBy(() -> openAIBackend.complete(mockPromptData, parameters))
                .isInstanceOf(LLMRateLimitException.class)
                .hasMessageContaining("Rate limited by provider 'openai'")
                .hasMessageContaining("Rate limit exceeded");
        verify(mockHttpClient, times(RetryPolicy.DEFAULT_MAX_ATTEMPTS)).sendAsync(any(HttpRequest.class), any());
    }
}