package com.eonmux.cadetcoder.ai;

import com.eonmux.cadetcoder.ai.metrics.ReportedUsage;
import com.eonmux.cadetcoder.ai.metrics.RequestMetricsRecorder;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.net.LLMException;
import com.eonmux.cadetcoder.session.SessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * One request has to be able to go to a model other than the one this tool is connected to.
 *
 * <h2>The defect</h2>
 *
 * <p>{@link AIManager} held exactly one active client and every completion went to it, so nothing
 * in the tool could ask a second model anything. The harness is built to hand a stuck run over to a
 * stronger reasoner and counts the hand-offs in the record it writes, and that path could never
 * fire: there was no way to build a reasoner that asked anything but the one active model.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>Naming a client for one request changes who answers it and nothing else. The retries, the
 * measurement, the composed system prompt and the typed failures are the ones every other request
 * gets -- a second model reached through a path of its own would be a second policy, and the first
 * time the two disagreed the run would be unaccountable for what it had actually spent.</p>
 */
class ARequestCanNameTheModelItGoesToTest {

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

    /** The client the tool is connected to, which answers with its own name. */
    private APIClient active;

    /**
     * Stands an {@link AIManager} up on {@link #active}, which is reachable unless told otherwise.
     *
     * @param reachable whether the tool has a provider of its own at all
     */
    private AIManager manager(boolean reachable) throws Exception {
        AIClientFactory factory = mock(AIClientFactory.class);
        LocalAIClient   local   = mock(LocalAIClient.class);
        active = mock(APIClient.class);
        when(factory.createLocalAIClient()).thenReturn(local);
        when(factory.createAPIClient()).thenReturn(active);
        when(local.isAvailable()).thenReturn(false);
        when(active.isAvailable()).thenReturn(reachable);
        when(active.getModelName()).thenReturn("the-active-model");
        when(active.complete(any(PromptData.class), anyMap())).thenReturn("the active model answered");
        AIManager.setClientFactory(factory);
        return AIManager.getInstance();
    }

    /** A client that is nothing to do with the tool's configuration. */
    private static AIClient answering(String said) throws Exception {
        AIClient other = mock(AIClient.class);
        when(other.getModelName()).thenReturn("the-stronger-model");
        when(other.complete(any(PromptData.class), anyMap())).thenReturn(said);
        return other;
    }

    /** The configuration and session singletons every completion reaches through. */
    private MockedStatic<ConfigManager> configured() {
        MockedStatic<ConfigManager> configMock    = mockStatic(ConfigManager.class);
        ConfigManager               configManager = mock(ConfigManager.class);
        Configuration               config        = mock(Configuration.class);
        Configuration.AiConfig      aiConfig      = mock(Configuration.AiConfig.class);
        when(aiConfig.getApiEndpoint()).thenReturn("http://localhost:8012");
        when(aiConfig.getModel()).thenReturn("the-active-model");
        when(config.getAi()).thenReturn(aiConfig);
        when(configManager.getConfig()).thenReturn(config);
        configMock.when(ConfigManager::getInstance).thenReturn(configManager);
        return configMock;
    }

    @Test
    void theClientNamedForOneRequestIsTheOneThatAnswersIt() throws Exception {
        try (MockedStatic<ConfigManager> configMock = configured();
             MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class)) {
            sessionMock.when(SessionManager::getInstance).thenReturn(mock(SessionManager.class));

            Completion said = manager(true).completeMeasured(answering("the stronger model answered"),
                                                             new PromptData("system", "user"));

            assertThat(said.text()).isEqualTo("the stronger model answered");
            verify(active, never()).complete(any(PromptData.class), anyMap());
        }
    }

    @Test
    void namingNoClientStillAsksWhicheverModelThisToolIsConnectedTo() throws Exception {
        try (MockedStatic<ConfigManager> configMock = configured();
             MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class)) {
            sessionMock.when(SessionManager::getInstance).thenReturn(mock(SessionManager.class));

            assertThat(manager(true).completeMeasured((AIClient) null, new PromptData("system", "user")).text())
                    .isEqualTo("the active model answered");
        }
    }

    @Test
    void whatTheOtherModelSaidIsMeasuredTheSameWayEverythingElseIs() throws Exception {
        try (MockedStatic<ConfigManager> configMock = configured();
             MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class)) {
            sessionMock.when(SessionManager::getInstance).thenReturn(mock(SessionManager.class));

            Completion said = manager(true).completeMeasured(answering("an answer"),
                                                             new PromptData("system", "user"));

            assertThat(said.tokensIn()).isPositive();
            assertThat(said.tokensOut()).isPositive();
        }
    }

    @Test
    void aRequestThatBroughtItsOwnModelDoesNotNeedTheToolToBeConnectedToOne() throws Exception {
        try (MockedStatic<ConfigManager> configMock = configured();
             MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class)) {
            sessionMock.when(SessionManager::getInstance).thenReturn(mock(SessionManager.class));

            assertThat(manager(false).completeMeasured(answering("an answer"),
                                                       new PromptData("system", "user")).text())
                    .isEqualTo("an answer");
        }
    }

    @Test
    void aNamedModelThatFailsEndsTheRequestRatherThanFallingBackToTheActiveOne() throws Exception {
        try (MockedStatic<ConfigManager> configMock = configured();
             MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class)) {
            sessionMock.when(SessionManager::getInstance).thenReturn(mock(SessionManager.class));

            AIClient broken = mock(AIClient.class);
            when(broken.getModelName()).thenReturn("the-stronger-model");
            when(broken.complete(any(PromptData.class), anyMap()))
                    .thenThrow(new LLMException(LLMException.Kind.AUTH,
                                            "the stronger model is not reachable", "test",
                                            "the-stronger-model", null, 401, null, null, null));

            AIManager manager = manager(true);
            assertThatThrownBy(() -> manager.completeMeasured(broken, new PromptData("s", "u")))
                    .isInstanceOf(LLMException.class);
            verify(active, never()).complete(any(PromptData.class), anyMap());
        }
    }
}
