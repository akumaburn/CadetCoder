package com.eonmux.cadetcoder.ai;

import com.eonmux.cadetcoder.ai.metrics.ReportedUsage;
import com.eonmux.cadetcoder.ai.metrics.RequestMetricsRecorder;
import com.eonmux.cadetcoder.ai.metrics.TokenUsage;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.session.SessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * A request that took several attempts is reported for what it really cost and how long it really
 * took.
 *
 * <h2>Two defects, both in the same loop</h2>
 *
 * <p><b>What it cost.</b> The usage slot was emptied at the top of every attempt, so only the
 * attempt that finally answered contributed to the session's figures. An attempt that reached the
 * provider, was generated, was billed, and was then rejected here -- for arriving empty, or
 * truncated, or wearing a legacy {@code "Error: ..."} sentinel -- counted as nothing. The requests
 * reported most wrongly were the expensive ones, because those are the ones that get retried.</p>
 *
 * <p><b>How long it took.</b> The measurement was started once, outside the attempt loop, so the
 * duration reported for the answer covered every attempt that did not answer and every backoff
 * pause between them. A 1.2s call that succeeded on the fourth try was reported as roughly eleven
 * seconds -- and that number is what a user reads to decide whether a model is slow. It is now the
 * duration of the call that answered, with the waiting said separately.</p>
 */
class WhatArequestCostIsWhatEveryAttemptCostTest {

    private AIClientFactory originalFactory;

    @BeforeEach
    void setUp() throws Exception {
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
     * Runs one completion whose client answers with {@code replies} in order, reporting the matching
     * entry of {@code usages} on each attempt.
     */
    private void completeAnsweringInTurn(List<String> replies, List<TokenUsage> usages)
            throws Exception {
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

            AtomicInteger attempt = new AtomicInteger();
            when(api.complete(any(PromptData.class), anyMap())).thenAnswer(invocation -> {
                int index = attempt.getAndIncrement();
                // The backend publishes usage as a side effect of parsing the response, exactly as
                // the real ones do, and on the same thread.
                ReportedUsage.report(usages.get(index));
                return replies.get(index);
            });

            AIManager.setClientFactory(factory);
            AIManager.getInstance().complete(new PromptData("system", "user"), new HashMap<>());
        }
    }

    @Test
    void anAttemptThatWasBilledAndRejectedStillCountsTowardsTheBill() throws Exception {
        System.setProperty(AIManager.RETRY_BACKOFF_PROPERTY, "0");

        // The first attempt reached the provider and came back empty. It was generated and paid
        // for; only what it produced was unusable.
        completeAnsweringInTurn(List.of("", "an answer"),
                                List.of(new TokenUsage(800, 200, 55),
                                        new TokenUsage(800, 200, 60)));

        String summary = RequestMetricsRecorder.getInstance().sessionSummary();
        assertThat(summary).contains("2,000 in").contains("115 out");
        assertThat(summary)
                .as("both attempts were reported by the provider, so nothing here is a guess")
                .doesNotContain("~");
    }

    @Test
    void theTimeReportedIsTheTimeTheAnswerTook() throws Exception {
        long backoffMillis = 400;
        System.setProperty(AIManager.RETRY_BACKOFF_PROPERTY, Long.toString(backoffMillis));

        long started = System.currentTimeMillis();
        completeAnsweringInTurn(List.of("", "an answer"),
                                List.of(new TokenUsage(1, 1, 1), new TokenUsage(1, 1, 1)));
        long wallClock = System.currentTimeMillis() - started;

        assertThat(wallClock)
                .as("the retry really did pause, so there is something to have wrongly counted")
                .isGreaterThanOrEqualTo(backoffMillis);
        assertThat(RequestMetricsRecorder.getInstance().totalDurationMillis())
                .as("the attempt that answered returned at once; the waiting was not it")
                .isLessThan(backoffMillis);
    }

    /**
     * Choosing a model and reading the chosen one have to be one indivisible act.
     *
     * <p>{@code models select} and {@code login} reset the client from the shell thread while a
     * worker is in the middle of a request. The read was unsynchronized while the write that feeds
     * it was not, so the reset need never become visible to that worker at all: it went on asking
     * the provider the user had just switched away from, for the rest of its run, with nothing to
     * say it had happened.</p>
     *
     * <p>Asserted as mutual exclusion rather than by racing the two, because a race that happens to
     * work proves nothing about a memory-visibility defect.</p>
     */
    @Test
    void aModelSelectionCannotSlipPastAthreadThatIsChoosingOne() throws Exception {
        AIManager      manager  = AIManager.getInstance();
        CountDownLatch started  = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);

        Thread selecting = new Thread(() -> {
            started.countDown();
            manager.resetActiveClient();
            finished.countDown();
        });

        synchronized (manager) {
            selecting.start();
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(finished.await(250, TimeUnit.MILLISECONDS))
                    .as("the selection must wait for whoever is reading the choice")
                    .isFalse();
        }

        assertThat(finished.await(5, TimeUnit.SECONDS))
                .as("and it must go through the moment they are done")
                .isTrue();
        selecting.join(5_000);
    }
}
