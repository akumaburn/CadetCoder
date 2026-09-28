package com.eonmux.cadetcoder.ai;

import com.eonmux.cadetcoder.ai.metrics.ReportedUsage;
import com.eonmux.cadetcoder.ai.metrics.RequestMetricsRecorder;
import com.eonmux.cadetcoder.ai.metrics.TokenUsage;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.net.LLMException;
import com.eonmux.cadetcoder.session.SessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * What one request cost has to leave {@link AIManager} with the words it paid for.
 *
 * <h2>The defect these lock out</h2>
 *
 * <p>{@code complete} answered with a bare {@link String}. The figures every request already
 * produces -- the provider's own usage block, or the estimate standing in for it -- were built,
 * printed once, and dropped on the floor. Anything downstream that has to know what a run has spent
 * was left to guess by re-counting the characters it sent, which is a different number from the one
 * the provider billed; a driver that stops on a token allowance would then stop at the wrong
 * place, in whichever direction the guess happened to be wrong.</p>
 *
 * <p>The interrupt case is the same defect wearing different clothes: the sentence
 * {@code "AI completion interrupted."} was handed back as though the model had said it, so a caller
 * parsed a control-flow event as an answer and charged nothing for it.</p>
 */
class WhatACompletionCostIsHandedBackWithItTest {

    /** What the "provider" reports: 800 cached in, 200 new in, 55 out. */
    private static final TokenUsage REPORTED = new TokenUsage(800, 200, 55);

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
        // An interrupt left standing here would make the next test's first blocking call throw.
        Thread.interrupted();
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
     * Stands an {@link AIManager} up on a client that answers however the caller says.
     *
     * @param answering what the backend does when it is asked
     */
    private AIManager managerAnswering(Answer answering) throws Exception {
        AIClientFactory factory = mock(AIClientFactory.class);
        LocalAIClient   local   = mock(LocalAIClient.class);
        APIClient       api     = mock(APIClient.class);
        when(factory.createLocalAIClient()).thenReturn(local);
        when(factory.createAPIClient()).thenReturn(api);
        when(local.isAvailable()).thenReturn(false);
        when(api.isAvailable()).thenReturn(true);
        when(api.getModelName()).thenReturn("test-api-model");
        when(api.complete(any(PromptData.class), anyMap())).thenAnswer(call -> answering.answer());

        AIManager.setClientFactory(factory);
        return AIManager.getInstance();
    }

    /** What the backend does when it is asked. */
    private interface Answer {
        String answer() throws Exception;
    }

    /** The configuration and session singletons every completion reaches through. */
    private MockedStatic<ConfigManager> configured() {
        MockedStatic<ConfigManager> configMock    = mockStatic(ConfigManager.class);
        ConfigManager               configManager = mock(ConfigManager.class);
        Configuration               config        = mock(Configuration.class);
        Configuration.AiConfig      aiConfig      = mock(Configuration.AiConfig.class);
        when(aiConfig.getApiEndpoint()).thenReturn("http://localhost:8012");
        when(aiConfig.getModel()).thenReturn("test-model");
        when(config.getAi()).thenReturn(aiConfig);
        when(configManager.getConfig()).thenReturn(config);
        configMock.when(ConfigManager::getInstance).thenReturn(configManager);
        return configMock;
    }

    @Test
    void theCountsAProviderReportedComeBackWithTheAnswerItReportedThemFor() throws Exception {
        try (MockedStatic<ConfigManager> configMock = configured();
             MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class)) {
            sessionMock.when(SessionManager::getInstance).thenReturn(mock(SessionManager.class));

            AIManager manager = managerAnswering(() -> {
                ReportedUsage.report(REPORTED);
                return "an answer";
            });

            Completion completion =
                    manager.completeMeasured(new PromptData("system", "user"), new HashMap<>());

            assertThat(completion.text()).isEqualTo("an answer");
            assertThat(completion.tokensIn()).isEqualTo(1_000L);
            assertThat(completion.tokensOut()).isEqualTo(55L);
            assertThat(completion.estimated()).isFalse();
        }
    }

    @Test
    void aProviderThatReportsNothingStillSaysWhatItCostAndSaysItIsAGuess() throws Exception {
        try (MockedStatic<ConfigManager> configMock = configured();
             MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class)) {
            sessionMock.when(SessionManager::getInstance).thenReturn(mock(SessionManager.class));

            AIManager manager = managerAnswering(() -> "an answer");

            Completion completion =
                    manager.completeMeasured(new PromptData("system", "user"), new HashMap<>());

            assertThat(completion.text()).isEqualTo("an answer");
            assertThat(completion.tokensIn()).isPositive();
            assertThat(completion.tokensOut()).isPositive();
            assertThat(completion.estimated()).isTrue();
        }
    }

    @Test
    void theCallThatOnlyWantedTheWordsStillGetsExactlyTheWords() throws Exception {
        try (MockedStatic<ConfigManager> configMock = configured();
             MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class)) {
            sessionMock.when(SessionManager::getInstance).thenReturn(mock(SessionManager.class));

            AIManager manager = managerAnswering(() -> {
                ReportedUsage.report(REPORTED);
                return "an answer";
            });

            assertThat(manager.complete(new PromptData("system", "user"), new HashMap<>()))
                    .isEqualTo("an answer");
        }
    }

    @Test
    void aRequestThatWasInterruptedIsNotHandedBackAsSomethingTheModelSaid() throws Exception {
        try (MockedStatic<ConfigManager> configMock = configured();
             MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class)) {
            SessionManager session = mock(SessionManager.class);
            sessionMock.when(SessionManager::getInstance).thenReturn(session);

            AIManager manager = managerAnswering(() -> {
                throw new InterruptedException("stopped");
            });

            // The kind rather than the wording. What the caller acts on is that this one is not
            // retryable: reported as a transport failure, as it once was, a deliberate stop was
            // offered back to the user as something to try again.
            assertThatThrownBy(() -> manager.complete(new PromptData("system", "user"),
                                                      new HashMap<>()))
                    .isInstanceOf(LLMException.class)
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(LLMException.class))
                    .satisfies(failure -> {
                        assertThat(failure.getKind()).isEqualTo(LLMException.Kind.STOPPED);
                        assertThat(failure.getKind().isRetryable()).isFalse();
                        assertThat(failure.getMessage()).contains("stopped");
                    });

            // The interrupt has to survive the call: swallowing it leaves a thread that has been
            // asked to stop carrying on as though it had not.
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        }
    }

    @Test
    void aCompletionCannotHaveCostLessThanNothing() {
        assertThatThrownBy(() -> new Completion("said", -1L, 0L, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Completion("said", 0L, -1L, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aCompletionWithNothingInItIsEmptyRatherThanNull() {
        assertThat(new Completion(null, 1L, 1L, true).text()).isEmpty();
    }

    /**
     * A request that failed every attempt has to stop being in flight.
     *
     * <p><b>The defect</b>: the handle taken out before the first attempt was given back on each of
     * the three ways out of the loop -- the interrupt, the typed backend failure, the interrupt
     * while backing off -- and on none of the three ways out after it. Every exhausted request left
     * one behind, and the elapsed-time counter the shell reads off them ticks for as long as one is
     * outstanding. A user whose provider was down watched a request that ended minutes ago keep
     * counting for the rest of the session, and every later request's figures were summed with
     * it.</p>
     */
    @Test
    void arequestThatFailedEveryAttemptIsNoLongerInFlight() throws Exception {
        try (MockedStatic<ConfigManager> configMock = configured();
             MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class)) {
            sessionMock.when(SessionManager::getInstance).thenReturn(mock(SessionManager.class));

            AIManager manager = managerAnswering(() -> "Error: Request failed with status 503");

            assertThatThrownBy(() -> manager.complete(new PromptData("system", "user"),
                                                      new HashMap<>()))
                    .isInstanceOf(LLMException.class)
                    .hasMessageContaining("AI completion failed after");

            assertThat(RequestMetricsRecorder.getInstance().inFlightStatus()).isEmpty();
            assertThat(RequestMetricsRecorder.getInstance().inFlightElapsedMillis()).isEmpty();
        }
    }

    @Test
    void arequestEveryAttemptOfWhichThrewIsNoLongerInFlight() throws Exception {
        try (MockedStatic<ConfigManager> configMock = configured();
             MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class)) {
            sessionMock.when(SessionManager::getInstance).thenReturn(mock(SessionManager.class));

            AIManager manager = managerAnswering(() -> {
                throw new IllegalStateException("the socket went away");
            });

            assertThatThrownBy(() -> manager.complete(new PromptData("system", "user"),
                                                      new HashMap<>()))
                    .isInstanceOf(LLMException.class);

            assertThat(RequestMetricsRecorder.getInstance().inFlightStatus()).isEmpty();
            assertThat(RequestMetricsRecorder.getInstance().inFlightElapsedMillis()).isEmpty();
        }
    }

    @Test
    void arequestThatWasAnsweredWithNothingEveryTimeIsNoLongerInFlight() throws Exception {
        try (MockedStatic<ConfigManager> configMock = configured();
             MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class)) {
            sessionMock.when(SessionManager::getInstance).thenReturn(mock(SessionManager.class));

            AIManager manager = managerAnswering(() -> "   ");

            assertThatThrownBy(() -> manager.complete(new PromptData("system", "user"),
                                                      new HashMap<>()))
                    .isInstanceOf(LLMException.class);

            assertThat(RequestMetricsRecorder.getInstance().inFlightStatus()).isEmpty();
            assertThat(RequestMetricsRecorder.getInstance().inFlightElapsedMillis()).isEmpty();
        }
    }
}
