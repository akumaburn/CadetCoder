package com.eonmux.cadetcoder.ai;

import com.eonmux.cadetcoder.ai.metrics.ReportedUsage;
import com.eonmux.cadetcoder.ai.metrics.RequestMetricsRecorder;
import com.eonmux.cadetcoder.ai.metrics.TokenUsage;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.session.SessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * What a provider reports must reach the figures the user is shown.
 *
 * <p>The session line is the assertion because it is the user-visible symptom: it leads every count
 * with {@code ~} to mark it approximate, and that mark was unconditional — the tool said "estimated"
 * even where the provider had sent exact numbers, because nothing read them.</p>
 */
class ProviderUsageReachesMetricsTest {

    private AIClientFactory originalFactory;

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty(AIManager.RETRY_BACKOFF_PROPERTY, "0");
        resetManager();
        RequestMetricsRecorder.getInstance().resetAll();
        ReportedUsage.clear();
    }

    @AfterEach
    void tearDown() throws Exception {
        System.clearProperty(AIManager.RETRY_BACKOFF_PROPERTY);
        resetManager();
        if (originalFactory != null) {
            AIManager.setClientFactory(originalFactory);
        }
        RequestMetricsRecorder.getInstance().resetAll();
        ReportedUsage.clear();
    }

    private void resetManager() throws Exception {
        java.lang.reflect.Field instance = AIManager.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
        java.lang.reflect.Field factoryField = AIManager.class.getDeclaredField("clientFactory");
        factoryField.setAccessible(true);
        if (originalFactory == null) {
            originalFactory = (AIClientFactory) factoryField.get(null);
        }
    }

    /**
     * Runs one completion whose client publishes {@code usage}, and returns the session line.
     *
     * @param usage what the "provider" reported, or {@code null} to report nothing
     */
    private String summaryAfterOneCompletion(TokenUsage usage) throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class)) {

            ConfigManager          configManager = mock(ConfigManager.class);
            Configuration          config        = mock(Configuration.class);
            Configuration.AiConfig aiConfig      = mock(Configuration.AiConfig.class);
            when(aiConfig.getApiEndpoint()).thenReturn("http://localhost:8012");
            when(aiConfig.getModel()).thenReturn("test-model");
            when(config.getAi()).thenReturn(aiConfig);
            when(configManager.getConfig()).thenReturn(config);
            configMock.when(ConfigManager::getInstance).thenReturn(configManager);
            sessionMock.when(SessionManager::getInstance).thenReturn(mock(SessionManager.class));

            AIClientFactory factory = mock(AIClientFactory.class);
            LocalAIClient   local   = mock(LocalAIClient.class);
            APIClient       api     = mock(APIClient.class);
            when(factory.createLocalAIClient()).thenReturn(local);
            when(factory.createAPIClient()).thenReturn(api);
            when(local.isAvailable()).thenReturn(false);
            when(api.isAvailable()).thenReturn(true);
            when(api.getModelName()).thenReturn("test-api-model");
            // The backend publishes usage as a side effect of parsing the response, exactly as the
            // real ones now do, and on the same thread.
            when(api.complete(any(PromptData.class), anyMap())).thenAnswer(invocation -> {
                ReportedUsage.report(usage);
                return "an answer";
            });

            AIManager.setClientFactory(factory);
            AIManager.getInstance().complete(new PromptData("system", "user"), new HashMap<>());

            return RequestMetricsRecorder.getInstance().sessionSummary();
        }
    }

    @Test
    @DisplayName("Provider-reported counts are used verbatim and are not marked as estimates")
    void reportedUsageIsUsedAndNotMarkedApproximate() throws Exception {
        String summary = summaryAfterOneCompletion(new TokenUsage(800, 200, 55));

        assertThat(summary).contains("1,000 in");
        assertThat(summary).contains("55 out");
        assertThat(summary).contains("80% cached");
        // The distinguishing assertion: no "~" anywhere, because nothing here was guessed.
        assertThat(summary).doesNotContain("~");
    }

    @Test
    @DisplayName("A provider that reports nothing still yields an estimate, marked as one")
    void absentUsageFallsBackToAMarkedEstimate() throws Exception {
        String summary = summaryAfterOneCompletion(null);

        assertThat(summary).contains("~");
        assertThat(summary).contains("1 request");
    }
}
