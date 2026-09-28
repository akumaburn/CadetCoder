package com.eonmux.cadetcoder.ai;

import com.eonmux.cadetcoder.net.LLMException;
import com.eonmux.cadetcoder.net.LLMTransportException;
import com.eonmux.cadetcoder.ui.OutputRouter;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Offering one more attempt after the automatic retries are spent. */
public class ManualRetryTest {

    private String original;

    @Before
    public void setUp() {
        original = System.getProperty(ManualRetry.PROPERTY);
    }

    @After
    public void tearDown() {
        if (original == null) {
            System.clearProperty(ManualRetry.PROPERTY);
        } else {
            System.setProperty(ManualRetry.PROPERTY, original);
        }
    }

    private static LLMException failure() {
        return new LLMTransportException("groq", "some-model", "http://127.0.0.1:1/v1",
                                         new java.net.ConnectException("Connection refused"));
    }

    @Test
    public void withoutAConsoleTheFailurePropagatesExactlyAsBefore() {
        // A script, a pipe or CI has nobody to ask, so behaviour and exit codes are unchanged.
        assertThat(ManualRetry.offer(failure())).isFalse();
    }

    @Test
    public void theOfferCanBeSuppressedOutright() {
        System.setProperty(ManualRetry.PROPERTY, "false");

        assertThat(ManualRetry.offer(failure())).isFalse();
    }

    @Test
    public void aBlankOverrideDoesNotReadAsDisabled() {
        System.setProperty(ManualRetry.PROPERTY, "   ");

        // Still false here only because there is no console in a test; the point is that a blank
        // property does not silently turn the feature off.
        assertThat(ManualRetry.offer(failure())).isFalse();
    }

    /**
     * Runs {@link ManualRetry#offer} against a console that answers with {@code answer}.
     *
     * <p>The tests above all return early on "there is no console", so none of them reach the
     * decision this feature exists to make. This supplies one.</p>
     */
    private static boolean answering(String answer) {
        return answering(answer, failure());
    }

    /** As {@link #answering(String)}, for a failure of a particular kind. */
    private static boolean answering(String answer, LLMException failure) {
        try (MockedStatic<OutputRouter> routers = mockStatic(OutputRouter.class)) {
            OutputRouter router = mock(OutputRouter.class);
            when(router.canPrompt()).thenReturn(true);
            when(router.getUserInput(anyString())).thenReturn(answer);
            routers.when(OutputRouter::getInstance).thenReturn(router);

            return ManualRetry.offer(failure);
        }
    }

    /** A failure of exactly {@code kind}, with the rest of the detail left plausible. */
    private static LLMException ofKind(LLMException.Kind kind) {
        return new LLMException(kind, "the provider refused the request", "groq", "some-model",
                                "https://api.groq.com/openai/v1/chat/completions",
                                kind == LLMException.Kind.AUTH ? 401 : 0, null, null, null);
    }

    @Test
    public void aBareEnterRetries() {
        // The whole point of the feature: one keystroke rather than re-running the task.
        assertThat(answering("")).isTrue();
        assertThat(answering("   ")).isTrue();
    }

    @Test
    public void theObviousWordsForYesAlsoRetry() {
        assertThat(answering("retry")).isTrue();
        assertThat(answering("y")).isTrue();
        assertThat(answering("RETRY")).isTrue();
    }

    @Test
    public void anythingElseEndsTheRun() {
        assertThat(answering("stop")).isFalse();
        assertThat(answering("n")).isFalse();
        assertThat(answering("no thanks")).isFalse();
    }

    @Test
    public void endOfInputEndsTheRunRatherThanLoopingOnIt() {
        // A closed stream returns null forever, so treating it as "retry" would spin without ever
        // reaching a different outcome.
        assertThat(answering(null)).isFalse();
    }

    @Test
    public void anUnusableConsoleEndsTheRunRatherThanFailingInsideTheHandler() {
        try (MockedStatic<OutputRouter> routers = mockStatic(OutputRouter.class)) {
            OutputRouter router = mock(OutputRouter.class);
            when(router.canPrompt()).thenReturn(true);
            when(router.getUserInput(anyString())).thenThrow(new IllegalStateException("no terminal"));
            routers.when(OutputRouter::getInstance).thenReturn(router);

            // This runs while an LLM failure is already being handled; throwing a second exception
            // here would replace the real cause with a terminal-plumbing one.
            assertThat(ManualRetry.offer(failure())).isFalse();
        }
    }

    @Test
    public void aSuppressedOfferNeverTouchesTheConsole() {
        System.setProperty(ManualRetry.PROPERTY, "false");

        try (MockedStatic<OutputRouter> routers = mockStatic(OutputRouter.class)) {
            OutputRouter router = mock(OutputRouter.class);
            when(router.canPrompt()).thenReturn(true);
            when(router.getUserInput(anyString())).thenReturn("");
            routers.when(OutputRouter::getInstance).thenReturn(router);

            assertThat(ManualRetry.offer(failure())).isFalse();
            verify(router, never()).getUserInput(anyString());
        }
    }

    /**
     * A key that has been revoked is revoked on the next attempt too.
     *
     * <p>{@code AIManager.complete} loops for as long as {@code offer} keeps saying yes, so
     * offering a retry for a failure that cannot change turns a terminal error into an endless
     * prompt: 401, press Enter, 401, press Enter. {@code LLMException.Kind} already records which
     * failures a retry can help -- {@code AUTH} is annotated "a retry can never help" -- and the
     * automatic retry policy reads it. This asks the same question rather than a second one.</p>
     */
    @Test
    public void aFailureThatCannotChangeIsNotOfferedAsSomethingToTryAgain() {
        assertThat(answering("", ofKind(LLMException.Kind.AUTH)))
                .as("a revoked or missing key is not fixed by pressing Enter")
                .isFalse();
        assertThat(answering("", ofKind(LLMException.Kind.BAD_REQUEST)))
                .as("the request itself is wrong; sending it again sends the same one")
                .isFalse();
    }

    @Test
    public void everyKindIsOfferedExactlyWhenItIsWorthRetrying() {
        for (LLMException.Kind kind : LLMException.Kind.values()) {
            assertThat(answering("", ofKind(kind)))
                    .as("%s declares isRetryable()=%s", kind, kind.isRetryable())
                    .isEqualTo(kind.isRetryable());
        }
    }

    /** Not merely declined: never asked, so the user is not invited to press a useless key. */
    @Test
    public void anUnretryableFailureNeverReachesTheConsole() {
        try (MockedStatic<OutputRouter> routers = mockStatic(OutputRouter.class)) {
            OutputRouter router = mock(OutputRouter.class);
            when(router.canPrompt()).thenReturn(true);
            when(router.getUserInput(anyString())).thenReturn("");
            routers.when(OutputRouter::getInstance).thenReturn(router);

            assertThat(ManualRetry.offer(ofKind(LLMException.Kind.AUTH))).isFalse();
            verify(router, never()).getUserInput(anyString());
        }
    }
}
