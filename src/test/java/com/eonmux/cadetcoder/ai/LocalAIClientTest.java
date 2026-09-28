package com.eonmux.cadetcoder.ai;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.net.LlamaServerBackend;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.net.http.*;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import java.util.concurrent.CompletableFuture;

public class LocalAIClientTest {

    private LocalAIClient     client;
    private TestOutputCapture outputCapture;

    @BeforeEach
    void setUp() {
        outputCapture = new TestOutputCapture();
    }

    @AfterEach
    void tearDown() {
        outputCapture.restore();
    }

    @Test
    void testConstructor_ServerAvailable() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedConstruction<LlamaServerBackend> backendMock = mockConstruction(LlamaServerBackend.class);
             MockedStatic<HttpClient> httpClientMock = mockStatic(HttpClient.class)) {

            // Mock configuration
            ConfigManager          mockConfigManager = mock(ConfigManager.class);
            Configuration          mockConfig        = mock(Configuration.class);
            Configuration.AiConfig mockAiConfig      = mock(Configuration.AiConfig.class);
            Configuration.UiConfig mockUiConfig      = mock(Configuration.UiConfig.class);

            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);
            when(mockConfig.getAi()).thenReturn(mockAiConfig);
            when(mockConfig.getUi()).thenReturn(mockUiConfig);
            when(mockUiConfig.getVerbosityLevel()).thenReturn(0);
            when(mockAiConfig.getModel()).thenReturn("llama-model");
            when(mockAiConfig.getLocalEndpoint()).thenReturn("http://localhost:8012");
            when(mockAiConfig.getLocalModel()).thenReturn("llama-model");

            // Mock HTTP client for server check
            HttpClient           mockHttpClient = mock(HttpClient.class);
            HttpResponse<String> mockResponse   = mock(HttpResponse.class);
            when(mockResponse.statusCode()).thenReturn(200);
            when(mockHttpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(CompletableFuture.completedFuture(
                    mockResponse));

            httpClientMock.when(() -> HttpClient.newBuilder()).thenReturn(mock(HttpClient.Builder.class));
            HttpClient.Builder mockBuilder = mock(HttpClient.Builder.class);
            when(mockBuilder.connectTimeout(any())).thenReturn(mockBuilder);
            when(mockBuilder.build()).thenReturn(mockHttpClient);
            httpClientMock.when(() -> HttpClient.newBuilder()).thenReturn(mockBuilder);

            client = new LocalAIClient();

            assertThat(client.isAvailable()).isTrue();
            assertThat(outputCapture.getOutput()).contains("Connected to local llama-server endpoint");
        }
    }

    @Test
    void testConstructor_ServerNotAvailable() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedConstruction<LlamaServerBackend> backendMock = mockConstruction(LlamaServerBackend.class);
             MockedStatic<HttpClient> httpClientMock = mockStatic(HttpClient.class)) {

            // Mock configuration
            ConfigManager          mockConfigManager = mock(ConfigManager.class);
            Configuration          mockConfig        = mock(Configuration.class);
            Configuration.AiConfig mockAiConfig      = mock(Configuration.AiConfig.class);
            Configuration.UiConfig mockUiConfig      = mock(Configuration.UiConfig.class);

            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);
            when(mockConfig.getAi()).thenReturn(mockAiConfig);
            when(mockConfig.getUi()).thenReturn(mockUiConfig);
            when(mockUiConfig.getVerbosityLevel()).thenReturn(0);
            when(mockAiConfig.getModel()).thenReturn("llama-model");
            when(mockAiConfig.getLocalEndpoint()).thenReturn("");
            when(mockAiConfig.getLocalModel()).thenReturn("llama-model");

            // Mock HTTP client to simulate server not available
            HttpClient.Builder mockBuilder = mock(HttpClient.Builder.class);
            when(mockBuilder.connectTimeout(any())).thenReturn(mockBuilder);
            when(mockBuilder.build()).thenThrow(new RuntimeException("Connection failed"));
            httpClientMock.when(() -> HttpClient.newBuilder()).thenReturn(mockBuilder);

            client = new LocalAIClient();

            // What matters is that the client reports itself unusable, so selection moves on. It
            // used to also print a warning, and this asserted that: a local endpoint that is not
            // running is the ordinary case for anyone on a hosted provider, so every start warned
            // about a fallback path they never chose. The diagnosis lives in the log now.
            assertThat(client.isAvailable()).isFalse();
            assertThat(outputCapture.getOutput())
                    .as("an unreachable local endpoint is not news for the terminal")
                    .doesNotContain("Local llama-server not available");
        }
    }

    @Test
    void testComplete_Success() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedConstruction<LlamaServerBackend> backendMock = mockConstruction(LlamaServerBackend.class);
             MockedStatic<HttpClient> httpClientMock = mockStatic(HttpClient.class)) {

            // Mock configuration
            ConfigManager          mockConfigManager = mock(ConfigManager.class);
            Configuration          mockConfig        = mock(Configuration.class);
            Configuration.AiConfig mockAiConfig      = mock(Configuration.AiConfig.class);
            Configuration.UiConfig mockUiConfig      = mock(Configuration.UiConfig.class);

            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);
            when(mockConfig.getAi()).thenReturn(mockAiConfig);
            when(mockConfig.getUi()).thenReturn(mockUiConfig);
            when(mockUiConfig.getVerbosityLevel()).thenReturn(0);
            when(mockAiConfig.getModel()).thenReturn("llama-model");
            when(mockAiConfig.getLocalEndpoint()).thenReturn("http://localhost:8012");
            when(mockAiConfig.getLocalModel()).thenReturn("llama-model");
            when(mockAiConfig.getTemperature()).thenReturn(0.7f);
            when(mockAiConfig.getMaxTokens()).thenReturn(2048);

            // Mock HTTP client for server check
            HttpClient           mockHttpClient = mock(HttpClient.class);
            HttpResponse<String> mockResponse   = mock(HttpResponse.class);
            when(mockResponse.statusCode()).thenReturn(200);
            when(mockHttpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(CompletableFuture.completedFuture(
                    mockResponse));

            httpClientMock.when(() -> HttpClient.newBuilder()).thenReturn(mock(HttpClient.Builder.class));
            HttpClient.Builder mockBuilder = mock(HttpClient.Builder.class);
            when(mockBuilder.connectTimeout(any())).thenReturn(mockBuilder);
            when(mockBuilder.build()).thenReturn(mockHttpClient);
            httpClientMock.when(() -> HttpClient.newBuilder()).thenReturn(mockBuilder);

            client = new LocalAIClient();

            // Get the mocked backend
            LlamaServerBackend mockBackend = backendMock.constructed().get(0);
            when(mockBackend.complete(any(PromptData.class), anyMap())).thenReturn("Generated response");

            PromptData          promptData = new PromptData("System prompt", "User prompt");
            Map<String, Object> context    = new HashMap<>();

            String response = client.complete(promptData, context);

            assertThat(response).isEqualTo("Generated response");
            verify(mockBackend).complete(any(PromptData.class), anyMap());
        }
    }

    @Test
    void testComplete_Exception() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedConstruction<LlamaServerBackend> backendMock = mockConstruction(LlamaServerBackend.class);
             MockedStatic<HttpClient> httpClientMock = mockStatic(HttpClient.class)) {

            // Mock configuration
            ConfigManager          mockConfigManager = mock(ConfigManager.class);
            Configuration          mockConfig        = mock(Configuration.class);
            Configuration.AiConfig mockAiConfig      = mock(Configuration.AiConfig.class);
            Configuration.UiConfig mockUiConfig      = mock(Configuration.UiConfig.class);

            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);
            when(mockConfig.getAi()).thenReturn(mockAiConfig);
            when(mockConfig.getUi()).thenReturn(mockUiConfig);
            when(mockUiConfig.getVerbosityLevel()).thenReturn(0);
            when(mockAiConfig.getModel()).thenReturn("llama-model");
            when(mockAiConfig.getLocalEndpoint()).thenReturn("http://localhost:8012");
            when(mockAiConfig.getLocalModel()).thenReturn("llama-model");
            when(mockAiConfig.getTemperature()).thenReturn(0.7f);
            when(mockAiConfig.getMaxTokens()).thenReturn(2048);

            // Mock HTTP client for server check
            HttpClient           mockHttpClient = mock(HttpClient.class);
            HttpResponse<String> mockResponse   = mock(HttpResponse.class);
            when(mockResponse.statusCode()).thenReturn(200);
            when(mockHttpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(CompletableFuture.completedFuture(
                    mockResponse));

            httpClientMock.when(() -> HttpClient.newBuilder()).thenReturn(mock(HttpClient.Builder.class));
            HttpClient.Builder mockBuilder = mock(HttpClient.Builder.class);
            when(mockBuilder.connectTimeout(any())).thenReturn(mockBuilder);
            when(mockBuilder.build()).thenReturn(mockHttpClient);
            httpClientMock.when(() -> HttpClient.newBuilder()).thenReturn(mockBuilder);

            client = new LocalAIClient();

            // Get the mocked backend
            LlamaServerBackend mockBackend = backendMock.constructed().get(0);
            when(mockBackend.complete(any(PromptData.class), anyMap())).thenThrow(new RuntimeException("API error"));

            PromptData          promptData = new PromptData("System prompt", "User prompt");
            Map<String, Object> context    = new HashMap<>();

            assertThatThrownBy(() -> client.complete(promptData, context))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("API error");
        }
    }

    @Test
    void testIsAvailable() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedConstruction<LlamaServerBackend> backendMock = mockConstruction(LlamaServerBackend.class)) {

            // Mock configuration
            ConfigManager          mockConfigManager = mock(ConfigManager.class);
            Configuration          mockConfig        = mock(Configuration.class);
            Configuration.AiConfig mockAiConfig      = mock(Configuration.AiConfig.class);
            Configuration.UiConfig mockUiConfig      = mock(Configuration.UiConfig.class);

            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);
            when(mockConfig.getAi()).thenReturn(mockAiConfig);
            when(mockConfig.getUi()).thenReturn(mockUiConfig);
            when(mockUiConfig.getVerbosityLevel()).thenReturn(0);
            when(mockAiConfig.getModel()).thenReturn("llama-model");
            when(mockAiConfig.getLocalEndpoint()).thenReturn("http://localhost:18765"); // Use a port that's unlikely to have a service
            when(mockAiConfig.getLocalModel()).thenReturn("llama-model");

            client = new LocalAIClient();

            // Initially false because server check fails by default
            assertThat(client.isAvailable()).isFalse();
        }
    }

    @Test
    void testGetModelName() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedConstruction<LlamaServerBackend> backendMock = mockConstruction(LlamaServerBackend.class);
             MockedStatic<HttpClient> httpClientMock = mockStatic(HttpClient.class)) {

            // Mock configuration
            ConfigManager          mockConfigManager = mock(ConfigManager.class);
            Configuration          mockConfig        = mock(Configuration.class);
            Configuration.AiConfig mockAiConfig      = mock(Configuration.AiConfig.class);
            Configuration.UiConfig mockUiConfig      = mock(Configuration.UiConfig.class);

            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);
            when(mockConfig.getAi()).thenReturn(mockAiConfig);
            when(mockConfig.getUi()).thenReturn(mockUiConfig);
            when(mockUiConfig.getVerbosityLevel()).thenReturn(0);
            when(mockAiConfig.getModel()).thenReturn("test-model");
            when(mockAiConfig.getLocalModel()).thenReturn("test-model");
            when(mockAiConfig.getLocalEndpoint()).thenReturn("http://localhost:8012");

            // Mock HTTP client for server check - this might be failing
            HttpClient           mockHttpClient = mock(HttpClient.class);
            HttpResponse<String> mockResponse   = mock(HttpResponse.class);
            when(mockResponse.statusCode()).thenReturn(200);
            when(mockHttpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(CompletableFuture.completedFuture(
                    mockResponse));

            httpClientMock.when(() -> HttpClient.newBuilder()).thenReturn(mock(HttpClient.Builder.class));
            HttpClient.Builder mockBuilder = mock(HttpClient.Builder.class);
            when(mockBuilder.connectTimeout(any())).thenReturn(mockBuilder);
            when(mockBuilder.build()).thenReturn(mockHttpClient);
            httpClientMock.when(() -> HttpClient.newBuilder()).thenReturn(mockBuilder);

            client = new LocalAIClient();

            assertThat(client.getModelName()).isEqualTo("test-model");
        }
    }
}