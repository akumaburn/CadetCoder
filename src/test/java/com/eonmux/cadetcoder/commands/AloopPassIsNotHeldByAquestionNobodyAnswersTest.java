package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ai.AIClient;
import com.eonmux.cadetcoder.ai.AIManager;
import com.eonmux.cadetcoder.ai.OutageWait;
import com.eonmux.cadetcoder.ai.PromptData;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.net.LLMException;
import com.eonmux.cadetcoder.session.SessionManager;
import com.eonmux.cadetcoder.ui.OutputRouter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A request made inside a loop pass waits out an outage by itself and never asks the terminal.
 *
 * <h2>The defect</h2>
 *
 * <p>After its automatic retries, a failed request asks "Enter to retry, or 'stop'" of whoever can
 * be asked, and inside the shell a loop can always be asked. A loop is started so that it runs
 * without anyone; one met a 524 and waited on that question for a day. Outside a loop the question
 * stays: somebody started that run and is there to answer it.</p>
 */
class AloopPassIsNotHeldByAquestionNobodyAnswersTest {

    private MockedStatic<ConfigManager>  configs;
    private MockedStatic<SessionManager> sessions;
    private MockedStatic<OutputRouter>   routers;
    private OutputRouter                 router;

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty(AIManager.RETRY_BACKOFF_PROPERTY, "0");
        System.setProperty(OutageWait.FIRST_WAIT_MS_PROPERTY, "1");
        System.setProperty(OutageWait.LONGEST_WAIT_MS_PROPERTY, "1");
        System.setProperty(OutageWait.BUDGET_MS_PROPERTY, "3");
        resetManager();

        ConfigManager manager = mock(ConfigManager.class);
        when(manager.getConfig()).thenReturn(new Configuration());
        configs = mockStatic(ConfigManager.class);
        configs.when(ConfigManager::getInstance).thenReturn(manager);
        sessions = mockStatic(SessionManager.class);
        sessions.when(SessionManager::getInstance).thenReturn(mock(SessionManager.class));

        // Somebody can be asked, and says "retry" to everything: whatever asks, retries forever.
        router = mock(OutputRouter.class);
        when(router.canPrompt()).thenReturn(true);
        when(router.getUserInput(anyString())).thenReturn("retry");
        when(router.commandPrefix()).thenReturn("/");
        routers = mockStatic(OutputRouter.class);
        routers.when(OutputRouter::getInstance).thenReturn(router);
    }

    @AfterEach
    void tearDown() throws Exception {
        routers.close();
        sessions.close();
        configs.close();
        System.clearProperty(AIManager.RETRY_BACKOFF_PROPERTY);
        System.clearProperty(OutageWait.FIRST_WAIT_MS_PROPERTY);
        System.clearProperty(OutageWait.LONGEST_WAIT_MS_PROPERTY);
        System.clearProperty(OutageWait.BUDGET_MS_PROPERTY);
        resetManager();
    }

    private static void resetManager() throws Exception {
        java.lang.reflect.Field instance = AIManager.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    private static LLMException failure(LLMException.Kind kind, int status) {
        return new LLMException(kind, "the provider answered " + status, "a-provider", "a-model",
                                "https://example.invalid/v1", status, null, null, null);
    }

    /** A client that fails every time; Mockito repeats the last answer it was given. */
    private static AIClient failingWith(LLMException failure) throws Exception {
        AIClient client = mock(AIClient.class);
        when(client.getModelName()).thenReturn("a-model");
        when(client.isAvailable()).thenReturn(true);
        when(client.complete(any(PromptData.class), anyMap())).thenThrow(failure);
        return client;
    }

    private static String ask(AIClient client) {
        return AIManager.getInstance()
                        .completeMeasured(client, new PromptData("system", "user"), new HashMap<>())
                        .text();
    }

    private static String askInAPass(AIClient client) {
        String[] answer = new String[1];
        LoopPass.inPass(1, 3, 0, () -> answer[0] = ask(client));
        return answer[0];
    }

    @Test
    void anoutageInsideApassIsWaitedOutAndTheRequestMadeAgain() throws Exception {
        AIClient client = mock(AIClient.class);
        when(client.getModelName()).thenReturn("a-model");
        when(client.isAvailable()).thenReturn(true);
        when(client.complete(any(PromptData.class), anyMap()))
                .thenThrow(failure(LLMException.Kind.SERVER_ERROR, 524))
                .thenReturn("it answered");

        assertThat(askInAPass(client)).isEqualTo("it answered");
        verify(router, never()).getUserInput(anyString());
    }

    @Test
    void anoutageLongerThanTheBudgetEndsThePassWithoutAquestion() throws Exception {
        LLMException outage = failure(LLMException.Kind.SERVER_ERROR, 524);
        AIClient client = failingWith(outage);

        LLMException ended = catchThrowableOfType(() -> askInAPass(client), LLMException.class);

        assertThat(ended).isNotNull();
        assertThat(ended.getKind()).isEqualTo(LLMException.Kind.SERVER_ERROR);
        verify(router, never()).getUserInput(anyString());
    }

    @Test
    void arateLimitInsideApassEndsItWithoutAquestion() throws Exception {
        AIClient client = failingWith(failure(LLMException.Kind.RATE_LIMITED, 429));

        LLMException ended = catchThrowableOfType(() -> askInAPass(client), LLMException.class);

        assertThat(ended).isNotNull();
        assertThat(ended.getKind()).isEqualTo(LLMException.Kind.RATE_LIMITED);
        verify(router, never()).getUserInput(anyString());
    }

    @Test
    void outsideAloopThePersonIsStillAsked() throws Exception {
        when(router.getUserInput(anyString())).thenReturn("stop");
        AIClient client = failingWith(failure(LLMException.Kind.SERVER_ERROR, 524));

        LLMException ended = catchThrowableOfType(() -> ask(client), LLMException.class);

        assertThat(ended).isNotNull();
        verify(router, times(1)).getUserInput(anyString());
    }
}
