package com.eonmux.cadetcoder.ai.parsing;

import org.junit.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A recovered action is a guess, and a guess must never be dispatched as though it were read.
 *
 * <p>{@code ErrorRecoveryManager}'s last-resort strategy always "succeeds", by emitting a
 * parameterless {@code {"action":"read"}} at confidence 0.1. {@code attemptErrorRecovery} discards
 * that below the threshold, with a comment saying exactly why: accepting it turns every unparseable
 * answer into a fabricated read that the harness runs and reports as a failed step.</p>
 *
 * <p>{@code handleCircuitBreakerFailure} ran the same recovery output through the JSON parser and
 * returned on {@code isSuccessful()} alone. So the guard held on the ordinary path and not on the
 * one reached after five consecutive failures -- the moment the model is least likely to be
 * producing something worth acting on.</p>
 */
public class RecoveredActionConfidenceTest {

    private static ParsedResponse circuitBreakerResult(String aiResponse) throws Exception {
        ResponseParsingEngine engine = new ResponseParsingEngine();
        Method handler = ResponseParsingEngine.class.getDeclaredMethod(
                "handleCircuitBreakerFailure", String.class, ParsingContext.class,
                long.class, String.class);
        handler.setAccessible(true);
        return (ParsedResponse) handler.invoke(engine, aiResponse,
                new ParsingContext.Builder("do something useful").build(),
                System.currentTimeMillis(), "circuit breaker open");
    }

    @Test
    public void anUnparseableAnswerIsNotTurnedIntoADispatchableActionByTheBreakerPath()
            throws Exception {
        ParsedResponse result = circuitBreakerResult(
                "I am not sure what you want me to do here, sorry.");

        boolean dispatchable = result.isSuccessful()
                               && !ResponseParsingEngine.isFallbackOnly(result)
                               && !result.getActions().isEmpty();

        assertThat(dispatchable)
                .as("after the breaker opens, prose must be re-prompted -- not executed as a "
                    + "fabricated read the user never asked for")
                .isFalse();
    }

    @Test
    public void aLowConfidenceRecoveryIsLabelledSoTheCallerCanRePrompt() throws Exception {
        ParsedResponse result = circuitBreakerResult("no idea");

        if (!result.getActions().isEmpty()) {
            assertThat(ResponseParsingEngine.isFallbackOnly(result))
                    .as("an action that survives here must at least be marked as a guess")
                    .isTrue();
        }
    }

    /** A response that really does carry a structured action must still come back usable. */
    @Test
    public void aWellFormedActionStillSurvivesTheBreakerPath() throws Exception {
        ParsedResponse result = circuitBreakerResult(
                "{\"action\":\"read\",\"parameters\":{\"file_path\":\"src/Main.java\"},"
                + "\"reasoning\":\"inspect it\",\"confidence\":0.95}");

        assertThat(result.getActions())
                .as("degrading gracefully must not mean discarding a valid action")
                .isNotEmpty();
        assertThat(result.getActions().get(0).getCommand()).isEqualTo("read");
    }
}
