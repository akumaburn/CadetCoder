package com.eonmux.cadetcoder.ai;

import com.eonmux.cadetcoder.agents.WorkerPool;
import com.eonmux.cadetcoder.net.LLMException;
import com.eonmux.cadetcoder.net.LLMRateLimitException;
import com.eonmux.cadetcoder.ui.OutputCapture;
import com.eonmux.cadetcoder.ui.OutputRouter;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A blank line only counts as "press Enter to retry" when somebody was there to press it.
 *
 * <h2>The defect</h2>
 *
 * <p>{@code AIManager.completeMeasured} loops for as long as {@link ManualRetry#offer} keeps saying
 * yes, and the only gate on the offer was "is the shell running?" -- which is true on every worker
 * thread, because the shell is what routes their output. Asked from a worker, the shell declines to
 * hand over its input line (nobody is reading that worker's transcript as it is written) and returns
 * an empty string immediately. An empty string is what Enter sends, Enter means "try again", so a
 * persistent 429 inside a worker retried at full speed for as long as the process lived, printing
 * the same failure and never reaching anyone who could stop it.</p>
 *
 * <p>The same blank line arrives when the prompt is cancelled or the shell stops mid-question, and
 * neither of those is consent either.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>That a worker is never offered the retry at all; that a question put during an agent step --
 * where the user IS watching -- reaches the screen rather than the step's transcript; and that a
 * blank answer from a shell that has since stopped ends the run instead of restarting it.</p>
 */
class AretryNobodyCanAnswerIsNotConsentTest {

    private static LLMException rateLimited() {
        return new LLMRateLimitException("groq", "some-model",
                                         "https://api.groq.com/openai/v1/chat/completions",
                                         429, "rate limit exceeded", null);
    }

    @Test
    void aWorkerIsNeverOfferedAretryItCannotAnswer() {
        try (MockedStatic<WorkerPool> workers = mockStatic(WorkerPool.class);
             MockedStatic<OutputRouter> routers = mockStatic(OutputRouter.class)) {
            workers.when(WorkerPool::insideWorker).thenReturn(true);
            OutputRouter router = mock(OutputRouter.class);
            // True on every thread while the shell is running, workers included: this is exactly
            // the question that could not tell them apart.
            when(router.canPrompt()).thenReturn(true);
            when(router.getUserInput(anyString())).thenReturn("");
            routers.when(OutputRouter::getInstance).thenReturn(router);

            assertThat(ManualRetry.offer(rateLimited()))
                    .as("the worker would have retried the same 429 for the life of the process")
                    .isFalse();
            verify(router, never()).getUserInput(anyString());
        }
    }

    @Test
    void aquestionAskedDuringAnAgentStepReachesTheScreenRatherThanTheTranscript() {
        List<String> transcript = new ArrayList<>();

        try (MockedStatic<OutputRouter> routers = mockStatic(OutputRouter.class)) {
            OutputRouter router = mock(OutputRouter.class);
            when(router.canPrompt()).thenReturn(true);
            when(router.getUserInput(anyString())).thenReturn("");
            routers.when(OutputRouter::getInstance).thenReturn(router);

            boolean[] retried = new boolean[1];
            OutputCapture.collectInto(transcript::add,
                                      () -> retried[0] = ManualRetry.offer(rateLimited()));

            assertThat(retried[0])
                    .as("the user is watching an agent step, so Enter still means retry")
                    .isTrue();
            verify(router).getUserInput(anyString());
        }

        assertThat(String.join("\n", transcript))
                .as("a question filed into the step's transcript is a question nobody sees")
                .doesNotContain("AI request failed");
    }

    @Test
    void ablankAnswerFromAshellThatHasStoppedEndsTheRun() {
        try (MockedStatic<OutputRouter> routers = mockStatic(OutputRouter.class)) {
            OutputRouter router = mock(OutputRouter.class);
            // Able to be asked when the question goes up, gone by the time the answer comes back:
            // the shell cancels every waiting prompt with an empty line as it stops.
            when(router.canPrompt()).thenReturn(true, false);
            when(router.getUserInput(anyString())).thenReturn("");
            routers.when(OutputRouter::getInstance).thenReturn(router);

            assertThat(ManualRetry.offer(rateLimited()))
                    .as("nobody pressed anything; the prompt was released")
                    .isFalse();
        }
    }
}
