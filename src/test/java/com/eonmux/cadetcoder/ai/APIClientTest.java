package com.eonmux.cadetcoder.ai;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.net.OpenAIBackend;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.http.*;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

public class APIClientTest {

    private APIClient         client;
    private TestOutputCapture outputCapture;

    @BeforeEach
    void setUp() {
        outputCapture = new TestOutputCapture();
        System.setProperty("cadet.test.mode", "true");
    }

    @AfterEach
    void tearDown() {
        outputCapture.restore();
        System.clearProperty("cadet.test.mode");
    }

    @Test
    void testConstructor_OpenAI() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedConstruction<OpenAIBackend> backendMock = mockConstruction(OpenAIBackend.class)) {

            // Mock configuration for OpenAI
            ConfigManager          mockConfigManager = mock(ConfigManager.class);
            Configuration          mockConfig        = mock(Configuration.class);
            Configuration.AiConfig mockAiConfig      = mock(Configuration.AiConfig.class);

            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);
            when(mockConfig.getAi()).thenReturn(mockAiConfig);
            when(mockAiConfig.getLocalEndpoint()).thenReturn("http://localhost:8080");
            when(mockAiConfig.getLocalModel()).thenReturn("local-model");
            when(mockAiConfig.getApiKey()).thenReturn("api-key");

            client = new APIClient();

            assertThat(client.isAvailable()).isTrue(); // test mode
            assertThat(backendMock.constructed()).hasSize(1);
        }
    }

    @Test
    void testComplete_Success() throws Exception {
        // Enable test mode
        System.setProperty("cadet.test.mode", "true");

        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedConstruction<OpenAIBackend> backendMock = mockConstruction(OpenAIBackend.class)) {

            // Mock configuration
            ConfigManager          mockConfigManager = mock(ConfigManager.class);
            Configuration          mockConfig        = mock(Configuration.class);
            Configuration.AiConfig mockAiConfig      = mock(Configuration.AiConfig.class);
            Configuration.UiConfig mockUiConfig      = mock(Configuration.UiConfig.class);

            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);
            when(mockConfig.getAi()).thenReturn(mockAiConfig);
            when(mockConfig.getUi()).thenReturn(mockUiConfig);
            when(mockAiConfig.getLocalEndpoint()).thenReturn("http://localhost:8080");
            when(mockAiConfig.getLocalModel()).thenReturn("test-model");
            when(mockAiConfig.getApiKey()).thenReturn("test-key");
            when(mockAiConfig.getTemperature()).thenReturn(0.7f);
            when(mockAiConfig.getMaxTokens()).thenReturn(2048);
            when(mockAiConfig.getCompletionTimeoutSeconds()).thenReturn(30);
            when(mockUiConfig.getVerbosityLevel()).thenReturn(0);

            client = new APIClient();

            // Get the mocked backend
            OpenAIBackend mockBackend = backendMock.constructed().get(0);
            when(mockBackend.complete(any(PromptData.class), anyMap())).thenReturn("Generated response");

            PromptData          promptData = new PromptData("System prompt", "User prompt");
            Map<String, Object> context    = new HashMap<>();

            String response = client.complete(promptData, context);

            assertThat(response).isEqualTo("Generated response");
            verify(mockBackend).complete(any(PromptData.class), argThat(map ->
                            map.get("temperature").equals(0.7f) &&
                            map.get("maxTokens").equals(2048)
                                                                       ));
        }
    }

    /**
     * A loopback URL that nothing answers on.
     *
     * <p>The availability check is a real request. The test used to name {@code localhost:8080},
     * and on a machine where anything listens there the client was available and the test failed.
     * A port just released by the system has no listener.</p>
     */
    private static String anEndpointNothingListensOn() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return "http://127.0.0.1:" + socket.getLocalPort();
        }
    }

    @Test
    void testComplete_NotAvailable() throws Exception {
        // Clear test mode to simulate unavailable API
        System.clearProperty("cadet.test.mode");
        String unanswered = anEndpointNothingListensOn();

        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedConstruction<OpenAIBackend> backendMock = mockConstruction(OpenAIBackend.class)) {

            // Mock configuration
            ConfigManager          mockConfigManager = mock(ConfigManager.class);
            Configuration          mockConfig        = mock(Configuration.class);
            Configuration.AiConfig mockAiConfig      = mock(Configuration.AiConfig.class);

            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);
            when(mockConfig.getAi()).thenReturn(mockAiConfig);
            when(mockAiConfig.getLocalEndpoint()).thenReturn(unanswered);
            when(mockAiConfig.getLocalModel()).thenReturn("test-model");
            when(mockAiConfig.getApiKey()).thenReturn("test-key");

            client = new APIClient();

            PromptData          promptData = new PromptData("System prompt", "User prompt");
            Map<String, Object> context    = new HashMap<>();

            assertThatThrownBy(() -> client.complete(promptData, context))
                    .isInstanceOf(Exception.class)
                    .hasMessage("API client is not available");
        }
    }

    @Test
    void testComplete_ExceptionFromBackend() throws Exception {
        // Enable test mode
        System.setProperty("cadet.test.mode", "true");

        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedConstruction<OpenAIBackend> backendMock = mockConstruction(OpenAIBackend.class)) {

            // Mock configuration
            ConfigManager          mockConfigManager = mock(ConfigManager.class);
            Configuration          mockConfig        = mock(Configuration.class);
            Configuration.AiConfig mockAiConfig      = mock(Configuration.AiConfig.class);
            Configuration.UiConfig mockUiConfig      = mock(Configuration.UiConfig.class);

            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);
            when(mockConfig.getAi()).thenReturn(mockAiConfig);
            when(mockConfig.getUi()).thenReturn(mockUiConfig);
            when(mockAiConfig.getLocalEndpoint()).thenReturn("http://localhost:8080");
            when(mockAiConfig.getLocalModel()).thenReturn("test-model");
            when(mockAiConfig.getApiKey()).thenReturn("test-key");
            when(mockAiConfig.getTemperature()).thenReturn(0.7f);
            when(mockAiConfig.getMaxTokens()).thenReturn(2048);
            when(mockAiConfig.getCompletionTimeoutSeconds()).thenReturn(30);
            when(mockUiConfig.getVerbosityLevel()).thenReturn(0);

            client = new APIClient();

            // Get the mocked backend
            OpenAIBackend mockBackend = backendMock.constructed().get(0);
            when(mockBackend.complete(any(PromptData.class), anyMap())).thenThrow(new RuntimeException("API error"));

            PromptData          promptData = new PromptData("System prompt", "User prompt");
            Map<String, Object> context    = new HashMap<>();

            assertThatThrownBy(() -> client.complete(promptData, context))
                    .isInstanceOf(Exception.class)
                    .hasMessageContaining("API error");
        }
    }

    @Test
    void testIsAvailable() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedConstruction<OpenAIBackend> backendMock = mockConstruction(OpenAIBackend.class)) {

            // Mock configuration
            ConfigManager          mockConfigManager = mock(ConfigManager.class);
            Configuration          mockConfig        = mock(Configuration.class);
            Configuration.AiConfig mockAiConfig      = mock(Configuration.AiConfig.class);

            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);
            when(mockConfig.getAi()).thenReturn(mockAiConfig);
            when(mockAiConfig.getLocalEndpoint()).thenReturn("http://localhost:8080");
            when(mockAiConfig.getLocalModel()).thenReturn("test-model");
            when(mockAiConfig.getApiKey()).thenReturn("test-key");

            client = new APIClient();

            assertThat(client.isAvailable()).isTrue(); // test mode
        }
    }

    @Test
    void testGetModelName() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedConstruction<OpenAIBackend> backendMock = mockConstruction(OpenAIBackend.class)) {

            // Mock configuration
            ConfigManager          mockConfigManager = mock(ConfigManager.class);
            Configuration          mockConfig        = mock(Configuration.class);
            Configuration.AiConfig mockAiConfig      = mock(Configuration.AiConfig.class);

            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);
            when(mockConfig.getAi()).thenReturn(mockAiConfig);
            when(mockAiConfig.getLocalEndpoint()).thenReturn("http://localhost:8080");
            when(mockAiConfig.getLocalModel()).thenReturn("test-model-name");
            when(mockAiConfig.getApiKey()).thenReturn("test-key");

            client = new APIClient();

            assertThat(client.getModelName()).isEqualTo("test-model-name");
        }
    }

    @Test
    void testCheckAvailability_NonTestMode() throws Exception {
        // Clear test mode
        System.clearProperty("cadet.test.mode");

        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedConstruction<OpenAIBackend> backendMock = mockConstruction(OpenAIBackend.class);
             MockedStatic<HttpClient> httpClientMock = mockStatic(HttpClient.class)) {

            // Mock configuration
            ConfigManager          mockConfigManager = mock(ConfigManager.class);
            Configuration          mockConfig        = mock(Configuration.class);
            Configuration.AiConfig mockAiConfig      = mock(Configuration.AiConfig.class);

            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);
            when(mockConfig.getAi()).thenReturn(mockAiConfig);
            when(mockAiConfig.getLocalEndpoint()).thenReturn("http://localhost:8080");
            when(mockAiConfig.getLocalModel()).thenReturn("test-model");
            when(mockAiConfig.getApiKey()).thenReturn("test-key");
            when(mockAiConfig.getApiEndpoint()).thenReturn("https://api.openai.com");
            when(mockAiConfig.getModel()).thenReturn("gpt-3.5");

            // Mock HTTP client
            HttpClient           mockHttpClient = mock(HttpClient.class);
            HttpResponse<String> mockResponse   = mock(HttpResponse.class);
            when(mockResponse.statusCode()).thenReturn(404); // Local endpoint fails
            when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(
                    mockResponse);

            HttpClient.Builder mockBuilder = mock(HttpClient.Builder.class);
            when(mockBuilder.connectTimeout(any())).thenReturn(mockBuilder);
            when(mockBuilder.build()).thenReturn(mockHttpClient);
            httpClientMock.when(() -> HttpClient.newBuilder()).thenReturn(mockBuilder);

            client = new APIClient();

            // The fallback is asserted by WHERE it ended up, not by the narration it used to print.
            // Which endpoint this client settled on is internal bookkeeping; the user asked a
            // question, not for a tour of the selection algorithm.
            // The local model is "test-model" and the API model is "gpt-3.5", so the model this
            // client ended up on is what says which endpoint it settled for.
            assertThat(client.getModelName()).isEqualTo("gpt-3.5");
            assertThat(outputCapture.getOutput()).doesNotContain("falling back");
        }
    }
}