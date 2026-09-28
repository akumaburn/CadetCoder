package com.eonmux.cadetcoder.ai;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.net.LLMAuthException;
import com.eonmux.cadetcoder.net.LLMEmptyResponseException;
import com.eonmux.cadetcoder.net.LLMException;
import com.eonmux.cadetcoder.session.SessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

public class AIManagerTest {

    private AIManager       aiManager;
    private AIClientFactory originalFactory;

    @BeforeEach
    void setUp() {
        // The retries under test are about WHICH failures repeat, not about how long the pause is;
        // paying a real second per attempt buys the assertions nothing.
        System.setProperty(AIManager.RETRY_BACKOFF_PROPERTY, "0");

        // Reset singleton
        try {
            java.lang.reflect.Field instance = AIManager.class.getDeclaredField("instance");
            instance.setAccessible(true);
            instance.set(null, null);

            // Save original factory
            java.lang.reflect.Field factoryField = AIManager.class.getDeclaredField("clientFactory");
            factoryField.setAccessible(true);
            originalFactory = (AIClientFactory) factoryField.get(null);
        } catch (Exception e) {
            // Ignore
        }
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(AIManager.RETRY_BACKOFF_PROPERTY);

        // Reset singleton after test
        try {
            java.lang.reflect.Field instance = AIManager.class.getDeclaredField("instance");
            instance.setAccessible(true);
            instance.set(null, null);

            // Restore original factory
            if (originalFactory != null) {
                AIManager.setClientFactory(originalFactory);
            }
        } catch (Exception e) {
            // Ignore
        }
    }

    @Test
    void testComplete_Success() throws Exception {
        // Mock dependencies
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class)) {
            ConfigManager          mockConfigManager  = mock(ConfigManager.class);
            Configuration          mockConfig         = mock(Configuration.class);
            Configuration.AiConfig mockAiConfig       = mock(Configuration.AiConfig.class);
            SessionManager         mockSessionManager = mock(SessionManager.class);

            when(mockAiConfig.getApiEndpoint()).thenReturn("http://localhost:8012");
            when(mockAiConfig.getModel()).thenReturn("test-model");
            when(mockConfig.getAi()).thenReturn(mockAiConfig);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            sessionMock.when(SessionManager::getInstance).thenReturn(mockSessionManager);

            // Setup mock AI client factory
            AIClientFactory mockFactory     = mock(AIClientFactory.class);
            LocalAIClient   mockLocalClient = mock(LocalAIClient.class);
            APIClient       mockAPIClient   = mock(APIClient.class);

            when(mockFactory.createLocalAIClient()).thenReturn(mockLocalClient);
            when(mockFactory.createAPIClient()).thenReturn(mockAPIClient);
            when(mockLocalClient.isAvailable()).thenReturn(false);
            when(mockAPIClient.isAvailable()).thenReturn(true);
            when(mockAPIClient.getModelName()).thenReturn("test-api-model");
            when(mockAPIClient.complete(any(PromptData.class), anyMap())).thenReturn("AI response");

            // Set the factory and get instance
            AIManager.setClientFactory(mockFactory);
            aiManager = AIManager.getInstance();

            // Test
            PromptData          promptData = new PromptData("system", "user", PromptBuilder.Template.ALPACA_SYSTEM);
            Map<String, Object> options    = new HashMap<>();

            String result = aiManager.complete(promptData, options);

            // Verify
            assertThat(result).isEqualTo("AI response");

            // The client receives a COMPOSED prompt, not the caller's instance: AIManager leads the
            // system prompt with the operating principles. Asserting the composition rather than
            // object identity states what actually has to hold -- the caller's own prompts survive
            // intact and the principles are in front of them.
            ArgumentCaptor<PromptData> sent = ArgumentCaptor.forClass(PromptData.class);
            // The map is no longer the caller's instance: the configured temperature, token ceiling
            // and timeout are filled into a copy of it on the way past (see RequestSettings), which
            // is what makes those three settings mean anything on the path a provider takes.
            verify(mockAPIClient).complete(sent.capture(), anyMap());
            assertThat(sent.getValue().getUserPrompt()).isEqualTo("user");
            assertThat(sent.getValue().getSystemPrompt()).endsWith("system");
            assertThat(sent.getValue().getSystemPrompt())
                    .contains(SystemPromptProvider.principles());
            // Marked as the model's turn: a restored conversation has to read as one, and the
            // user's half is recorded separately by the command that knows the real request.
            verify(mockSessionManager).addToConversationHistory("AI: AI response");
        }
    }

    @Test
    void anAnswerThatDiscussesTimeoutsIsNotMistakenForOne() throws Exception {
        // A completion's text is arbitrary. Scanning it for failure wording throws away a correct
        // answer and re-requests it -- and it does so exactly when the user is asking about
        // failures, which is when they can least afford a silently doubled bill and a lost reply.
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class)) {
            ConfigManager          mockConfigManager  = mock(ConfigManager.class);
            Configuration          mockConfig         = mock(Configuration.class);
            Configuration.AiConfig mockAiConfig       = mock(Configuration.AiConfig.class);
            SessionManager         mockSessionManager = mock(SessionManager.class);

            when(mockAiConfig.getApiEndpoint()).thenReturn("http://localhost:8012");
            when(mockAiConfig.getModel()).thenReturn("test-model");
            when(mockConfig.getAi()).thenReturn(mockAiConfig);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            sessionMock.when(SessionManager::getInstance).thenReturn(mockSessionManager);

            AIClientFactory mockFactory     = mock(AIClientFactory.class);
            LocalAIClient   mockLocalClient = mock(LocalAIClient.class);
            APIClient       mockAPIClient   = mock(APIClient.class);

            when(mockFactory.createLocalAIClient()).thenReturn(mockLocalClient);
            when(mockFactory.createAPIClient()).thenReturn(mockAPIClient);
            when(mockLocalClient.isAvailable()).thenReturn(false);
            when(mockAPIClient.isAvailable()).thenReturn(true);
            when(mockAPIClient.getModelName()).thenReturn("test-api-model");

            String answer = "Your request timed out because the provider was slow to respond.";
            when(mockAPIClient.complete(any(PromptData.class), anyMap())).thenReturn(answer);

            AIManager.setClientFactory(mockFactory);
            aiManager = AIManager.getInstance();

            String result = aiManager.complete(new PromptData("system", "user"), new HashMap<>());

            assertThat(result).isEqualTo(answer);
            verify(mockAPIClient, times(1)).complete(any(PromptData.class), anyMap());
        }
    }

    @Test
    void aBackendTimeoutSentinelIsStillRetried() throws Exception {
        // The check exists for the legacy backends that RETURNED "Error: ..." instead of throwing.
        // Anchoring it to that sentinel keeps the behaviour without reading the model's prose.
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class)) {
            ConfigManager          mockConfigManager  = mock(ConfigManager.class);
            Configuration          mockConfig         = mock(Configuration.class);
            Configuration.AiConfig mockAiConfig       = mock(Configuration.AiConfig.class);
            SessionManager         mockSessionManager = mock(SessionManager.class);

            when(mockAiConfig.getApiEndpoint()).thenReturn("http://localhost:8012");
            when(mockAiConfig.getModel()).thenReturn("test-model");
            when(mockConfig.getAi()).thenReturn(mockAiConfig);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            sessionMock.when(SessionManager::getInstance).thenReturn(mockSessionManager);

            AIClientFactory mockFactory     = mock(AIClientFactory.class);
            LocalAIClient   mockLocalClient = mock(LocalAIClient.class);
            APIClient       mockAPIClient   = mock(APIClient.class);

            when(mockFactory.createLocalAIClient()).thenReturn(mockLocalClient);
            when(mockFactory.createAPIClient()).thenReturn(mockAPIClient);
            when(mockLocalClient.isAvailable()).thenReturn(false);
            when(mockAPIClient.isAvailable()).thenReturn(true);
            when(mockAPIClient.getModelName()).thenReturn("test-api-model");
            when(mockAPIClient.complete(any(PromptData.class), anyMap()))
                    .thenReturn("Error: the request timed out")
                    .thenReturn("recovered");

            AIManager.setClientFactory(mockFactory);
            aiManager = AIManager.getInstance();

            String result = aiManager.complete(new PromptData("system", "user"), new HashMap<>());

            assertThat(result).isEqualTo("recovered");
            verify(mockAPIClient, times(2)).complete(any(PromptData.class), anyMap());
        }
    }

    @Test
    void testComplete_WithOptions() throws Exception {
        // Mock dependencies
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class)) {
            ConfigManager          mockConfigManager  = mock(ConfigManager.class);
            Configuration          mockConfig         = mock(Configuration.class);
            Configuration.AiConfig mockAiConfig       = mock(Configuration.AiConfig.class);
            SessionManager         mockSessionManager = mock(SessionManager.class);

            when(mockAiConfig.getApiEndpoint()).thenReturn("http://localhost:8012");
            when(mockAiConfig.getModel()).thenReturn("test-model");
            when(mockConfig.getAi()).thenReturn(mockAiConfig);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            sessionMock.when(SessionManager::getInstance).thenReturn(mockSessionManager);

            // Setup mock AI client factory
            AIClientFactory mockFactory     = mock(AIClientFactory.class);
            LocalAIClient   mockLocalClient = mock(LocalAIClient.class);
            APIClient       mockAPIClient   = mock(APIClient.class);

            when(mockFactory.createLocalAIClient()).thenReturn(mockLocalClient);
            when(mockFactory.createAPIClient()).thenReturn(mockAPIClient);
            when(mockLocalClient.isAvailable()).thenReturn(false);
            when(mockAPIClient.isAvailable()).thenReturn(true);
            when(mockAPIClient.getModelName()).thenReturn("test-api-model");
            when(mockAPIClient.complete(any(PromptData.class), anyMap())).thenReturn("AI response with options");

            // Set the factory and get instance
            AIManager.setClientFactory(mockFactory);
            aiManager = AIManager.getInstance();

            // Test with options
            PromptData          promptData = new PromptData("system", "user", PromptBuilder.Template.ALPACA_SYSTEM);
            Map<String, Object> options    = new HashMap<>();
            options.put("temperature", 0.5);
            options.put("max_tokens", 1000);

            String result = aiManager.complete(promptData, options);

            // Verify
            assertThat(result).isEqualTo("AI response with options");
            ArgumentCaptor<PromptData> sent = ArgumentCaptor.forClass(PromptData.class);
            verify(mockAPIClient).complete(sent.capture(), argThat(map ->
                            map.get("temperature").equals(0.5) &&
                            map.get("max_tokens").equals(1000)
                                                                  ));
            assertThat(sent.getValue().getUserPrompt()).isEqualTo("user");
            assertThat(sent.getValue().getSystemPrompt()).endsWith("system");
            verify(mockSessionManager).addToConversationHistory("AI: AI response with options");
        }
    }

    @Test
    void testSingleton() {
        // Mock ConfigManager
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager          mockConfigManager = mock(ConfigManager.class);
            Configuration          mockConfig        = mock(Configuration.class);
            Configuration.AiConfig mockAiConfig      = mock(Configuration.AiConfig.class);

            when(mockAiConfig.getApiEndpoint()).thenReturn("http://localhost:8012");
            when(mockConfig.getAi()).thenReturn(mockAiConfig);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            // Mock factory to prevent real connections
            AIClientFactory mockFactory     = mock(AIClientFactory.class);
            LocalAIClient   mockLocalClient = mock(LocalAIClient.class);
            APIClient       mockAPIClient   = mock(APIClient.class);

            when(mockFactory.createLocalAIClient()).thenReturn(mockLocalClient);
            when(mockFactory.createAPIClient()).thenReturn(mockAPIClient);
            when(mockLocalClient.isAvailable()).thenReturn(false);
            when(mockAPIClient.isAvailable()).thenReturn(false);

            AIManager.setClientFactory(mockFactory);

            // Test singleton
            AIManager instance1 = AIManager.getInstance();
            AIManager instance2 = AIManager.getInstance();

            assertThat(instance1).isSameAs(instance2);
        }
    }

    @Test
    void testComplete_ProviderFailurePropagatesAsTypedException() throws Exception {
        // Regression: a failed provider call used to come back as ordinary completion text
        // ("Error: Request failed with status 403"), so callers could not tell it apart from a real
        // answer and reported success. It must now leave on the typed failure channel.
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class)) {
            SessionManager mockSessionManager = mockConfigAndSession(configMock, sessionMock);

            AIClientFactory mockFactory     = mock(AIClientFactory.class);
            LocalAIClient   mockLocalClient = mock(LocalAIClient.class);
            APIClient       mockAPIClient   = mock(APIClient.class);

            when(mockFactory.createLocalAIClient()).thenReturn(mockLocalClient);
            when(mockFactory.createAPIClient()).thenReturn(mockAPIClient);
            when(mockLocalClient.isAvailable()).thenReturn(false);
            when(mockAPIClient.isAvailable()).thenReturn(true);
            when(mockAPIClient.getModelName()).thenReturn("test-api-model");
            when(mockAPIClient.complete(any(PromptData.class), anyMap()))
                    .thenThrow(new LLMAuthException("openai", "gpt-4",
                                                    "https://api.openai.com/v1", 403, "no access"));

            AIManager.setClientFactory(mockFactory);
            aiManager = AIManager.getInstance();

            PromptData promptData = new PromptData("system", "user", PromptBuilder.Template.ALPACA_SYSTEM);

            assertThatThrownBy(() -> aiManager.complete(promptData, new HashMap<>()))
                    .isInstanceOf(LLMAuthException.class)
                    .hasMessageContaining("HTTP 403")
                    .hasMessageContaining("The key was accepted");

            // A failed call is not part of the conversation.
            verify(mockSessionManager, never()).addToConversationHistory(anyString());
            // The backend owns the retry policy, so the manager must not multiply the attempts.
            verify(mockAPIClient, times(1)).complete(any(PromptData.class), anyMap());
        }
    }

    @Test
    void testComplete_EmptyResponsesFailInsteadOfReturningBlankText() throws Exception {
        // Regression: `response` was seeded with "" and the final `response != null ? response : ...`
        // therefore always chose the empty string, making the intended "AI completion failed"
        // fallback unreachable and a total failure indistinguishable from a blank answer.
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class)) {
            SessionManager mockSessionManager = mockConfigAndSession(configMock, sessionMock);

            AIClientFactory mockFactory     = mock(AIClientFactory.class);
            LocalAIClient   mockLocalClient = mock(LocalAIClient.class);
            APIClient       mockAPIClient   = mock(APIClient.class);

            when(mockFactory.createLocalAIClient()).thenReturn(mockLocalClient);
            when(mockFactory.createAPIClient()).thenReturn(mockAPIClient);
            when(mockLocalClient.isAvailable()).thenReturn(false);
            when(mockAPIClient.isAvailable()).thenReturn(true);
            when(mockAPIClient.getModelName()).thenReturn("test-api-model");
            when(mockAPIClient.complete(any(PromptData.class), anyMap())).thenReturn("   ");

            AIManager.setClientFactory(mockFactory);
            aiManager = AIManager.getInstance();

            PromptData promptData = new PromptData("system", "user", PromptBuilder.Template.ALPACA_SYSTEM);

            assertThatThrownBy(() -> aiManager.complete(promptData, new HashMap<>()))
                    .isInstanceOf(LLMEmptyResponseException.class)
                    .hasMessageContaining("AI completion failed after 2 attempts")
                    .hasMessageContaining("empty response");

            verify(mockSessionManager, never()).addToConversationHistory(anyString());
        }
    }

    @Test
    void testComplete_UnexpectedFailureIsWrappedInATypedException() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class)) {
            mockConfigAndSession(configMock, sessionMock);

            AIClientFactory mockFactory     = mock(AIClientFactory.class);
            LocalAIClient   mockLocalClient = mock(LocalAIClient.class);
            APIClient       mockAPIClient   = mock(APIClient.class);

            when(mockFactory.createLocalAIClient()).thenReturn(mockLocalClient);
            when(mockFactory.createAPIClient()).thenReturn(mockAPIClient);
            when(mockLocalClient.isAvailable()).thenReturn(false);
            when(mockAPIClient.isAvailable()).thenReturn(true);
            when(mockAPIClient.getModelName()).thenReturn("test-api-model");
            when(mockAPIClient.complete(any(PromptData.class), anyMap()))
                    .thenThrow(new IllegalArgumentException("boom"));

            AIManager.setClientFactory(mockFactory);
            aiManager = AIManager.getInstance();

            PromptData promptData = new PromptData("system", "user", PromptBuilder.Template.ALPACA_SYSTEM);

            assertThatThrownBy(() -> aiManager.complete(promptData, new HashMap<>()))
                    .isInstanceOf(LLMException.class)
                    .hasMessageContaining("AI completion failed after 2 attempts")
                    .hasMessageContaining("boom")
                    .hasCauseInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void testComplete_BackendErrorSentinelIsAFailureNotACompletion() throws Exception {
        // The remaining "Error: ..." sentinels must never pollute the conversation context with text
        // the model never produced -- nor be handed back to the caller as the model's answer, which
        // is what used to happen on the final attempt. The caller then parsed
        // "Error: Request failed with status 403" as a completion. Both halves of that are the same
        // judgement: this text is not model output.
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class)) {
            SessionManager mockSessionManager = mockConfigAndSession(configMock, sessionMock);

            AIClientFactory mockFactory     = mock(AIClientFactory.class);
            LocalAIClient   mockLocalClient = mock(LocalAIClient.class);
            APIClient       mockAPIClient   = mock(APIClient.class);

            when(mockFactory.createLocalAIClient()).thenReturn(mockLocalClient);
            when(mockFactory.createAPIClient()).thenReturn(mockAPIClient);
            when(mockLocalClient.isAvailable()).thenReturn(false);
            when(mockAPIClient.isAvailable()).thenReturn(true);
            when(mockAPIClient.getModelName()).thenReturn("test-api-model");
            when(mockAPIClient.complete(any(PromptData.class), anyMap()))
                    .thenReturn("Error: something the backend could not do");

            AIManager.setClientFactory(mockFactory);
            aiManager = AIManager.getInstance();

            PromptData promptData = new PromptData("system", "user", PromptBuilder.Template.ALPACA_SYSTEM);

            assertThatThrownBy(() -> aiManager.complete(promptData, new HashMap<>()))
                    .as("a backend failure must leave on the typed failure channel, not be "
                        + "returned as the model's completion")
                    .isInstanceOf(LLMException.class)
                    .hasMessageContaining("something the backend could not do");
            verify(mockSessionManager, never()).addToConversationHistory(anyString());
        }
    }

    /** Wires the ConfigManager/SessionManager statics used by every completion test. */
    private static SessionManager mockConfigAndSession(MockedStatic<ConfigManager> configMock,
                                                       MockedStatic<SessionManager> sessionMock) {
        ConfigManager          mockConfigManager  = mock(ConfigManager.class);
        Configuration          mockConfig         = mock(Configuration.class);
        Configuration.AiConfig mockAiConfig       = mock(Configuration.AiConfig.class);
        SessionManager         mockSessionManager = mock(SessionManager.class);

        when(mockConfig.getAi()).thenReturn(mockAiConfig);
        when(mockConfigManager.getConfig()).thenReturn(mockConfig);
        configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
        sessionMock.when(SessionManager::getInstance).thenReturn(mockSessionManager);
        return mockSessionManager;
    }

    @Test
    void testComplete_NoClientsAvailable() {
        // Mock ConfigManager
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager          mockConfigManager = mock(ConfigManager.class);
            Configuration          mockConfig        = mock(Configuration.class);
            Configuration.AiConfig mockAiConfig      = mock(Configuration.AiConfig.class);

            when(mockAiConfig.getApiEndpoint()).thenReturn("http://localhost:8012");
            when(mockConfig.getAi()).thenReturn(mockAiConfig);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            // Mock factory with unavailable clients
            AIClientFactory mockFactory     = mock(AIClientFactory.class);
            LocalAIClient   mockLocalClient = mock(LocalAIClient.class);
            APIClient       mockAPIClient   = mock(APIClient.class);

            when(mockFactory.createLocalAIClient()).thenReturn(mockLocalClient);
            when(mockFactory.createAPIClient()).thenReturn(mockAPIClient);
            when(mockLocalClient.isAvailable()).thenReturn(false);
            when(mockAPIClient.isAvailable()).thenReturn(false);

            AIManager.setClientFactory(mockFactory);
            aiManager = AIManager.getInstance();

            // Test
            PromptData promptData = new PromptData("system", "user", PromptBuilder.Template.ALPACA_SYSTEM);

            // Should throw exception
            // Asserted on what the message has to ACHIEVE rather than its exact wording: a caller
            // prefixes this ("Error analyzing request: ..."), so it must read as a sentence and it
            // must say what to do. Pinning the literal string is what let it stay as "No AI clients
            // available" -- correct about the internals, useless to the person reading it.
            assertThatThrownBy(() -> aiManager.complete(promptData, new HashMap<>()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no AI provider is reachable")
                    // Spelled for the surface the reader is on; no shell is running in a test, so
                    // the remedy is the command-line form.
                    .hasMessageContaining("cadet login");
        }
    }
}