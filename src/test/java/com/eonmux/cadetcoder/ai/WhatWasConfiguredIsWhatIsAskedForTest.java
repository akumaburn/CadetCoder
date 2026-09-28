package com.eonmux.cadetcoder.ai;

import com.eonmux.cadetcoder.ai.metrics.ReportedUsage;
import com.eonmux.cadetcoder.ai.metrics.RequestMetricsRecorder;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.session.SessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The settings that say how to ask a model reach the model.
 *
 * <p><b>The defect</b>: {@code ai.temperature}, {@code ai.maxTokens} and
 * {@code ai.completionTimeoutSeconds} were offered by {@code /config}, saved, listed and documented.
 * Almost every caller hands the model an empty parameter map, and every backend answers an empty map
 * with a literal of its own, so on the path every configured provider takes all three settings were
 * read by nothing. Setting one reported success and changed what was sent not at all.</p>
 */
class WhatWasConfiguredIsWhatIsAskedForTest {

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
        RequestMetricsRecorder.getInstance().resetAll();
        ReportedUsage.clear();
    }

    private void resetManager() throws Exception {
        java.lang.reflect.Field instance = AIManager.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    /** A configuration that asks for values nothing would arrive at by accident. */
    private static MockedStatic<ConfigManager> configuredWith(float temperature, int maxTokens,
                                                              int timeoutSeconds) {
        Configuration configuration = new Configuration();
        configuration.getAi().setTemperature(temperature);
        configuration.getAi().setMaxTokens(maxTokens);
        configuration.getAi().setCompletionTimeoutSeconds(timeoutSeconds);

        ConfigManager manager = mock(ConfigManager.class);
        when(manager.getConfig()).thenReturn(configuration);
        MockedStatic<ConfigManager> statics = mockStatic(ConfigManager.class);
        statics.when(ConfigManager::getInstance).thenReturn(manager);
        return statics;
    }

    private static AIClient answering() throws Exception {
        AIClient client = mock(AIClient.class);
        when(client.getModelName()).thenReturn("a-model");
        when(client.complete(any(PromptData.class), anyMap())).thenReturn("it answered");
        return client;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> whatWasAskedOf(AIClient client) throws Exception {
        ArgumentCaptor<Map<String, Object>> asked = ArgumentCaptor.forClass(Map.class);
        verify(client).complete(any(PromptData.class), asked.capture());
        return asked.getValue();
    }

    @Test
    void theConfiguredValuesAreTheOnesSent() throws Exception {
        try (MockedStatic<ConfigManager> ignored = configuredWith(0.25f, 1234, 42);
             MockedStatic<SessionManager> sessions = mockStatic(SessionManager.class)) {
            sessions.when(SessionManager::getInstance).thenReturn(mock(SessionManager.class));
            AIClient client = answering();

            AIManager.getInstance().completeMeasured(client, new PromptData("system", "user"),
                                                     new HashMap<>());

            Map<String, Object> asked = whatWasAskedOf(client);
            assertThat(((Number) asked.get(RequestSettings.TEMPERATURE)).floatValue()).isEqualTo(0.25f);
            assertThat(((Number) asked.get(RequestSettings.MAX_TOKENS)).intValue()).isEqualTo(1234);
            assertThat(((Number) asked.get(RequestSettings.COMPLETION_TIMEOUT)).intValue()).isEqualTo(42);
        }
    }

    @Test
    void whatTheCallerAsksForStandsOverWhatWasConfigured() throws Exception {
        try (MockedStatic<ConfigManager> ignored = configuredWith(0.25f, 1234, 42);
             MockedStatic<SessionManager> sessions = mockStatic(SessionManager.class)) {
            sessions.when(SessionManager::getInstance).thenReturn(mock(SessionManager.class));
            AIClient client = answering();

            Map<String, Object> mine = new HashMap<>();
            mine.put(RequestSettings.TEMPERATURE, 0.1);

            AIManager.getInstance().completeMeasured(client, new PromptData("system", "user"), mine);

            Map<String, Object> asked = whatWasAskedOf(client);
            assertThat(((Number) asked.get(RequestSettings.TEMPERATURE)).doubleValue()).isEqualTo(0.1);
            assertThat(((Number) asked.get(RequestSettings.MAX_TOKENS)).intValue())
                    .as("the one thing the caller cared about is not the whole request")
                    .isEqualTo(1234);
            assertThat(mine)
                    .as("the caller's map is the caller's; one request's settings must not become the next's")
                    .containsOnlyKeys(RequestSettings.TEMPERATURE);
        }
    }
}
