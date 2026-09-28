package com.eonmux.cadetcoder.logging;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Observing a request must never be the reason the request does not happen.
 *
 * <p><b>The defect</b>: {@code aiRequestStart} read the temperature out of the caller's parameter
 * map with {@code getOrDefault("temperature", "0.7").toString()} and then
 * {@code Double.parseDouble}. The map is whatever the caller assembled, so a temperature present
 * with a {@code null} value threw a {@link NullPointerException} -- {@code getOrDefault} returns
 * the null rather than the default when the key is there -- and one written as anything but a
 * number threw a {@link NumberFormatException}. Either escaped {@code aiRequestStart}, which is
 * called on the way into a completion, so a logging detail killed the AI request it existed to
 * describe. The parsed number is immediately reformatted into a log line, which is the whole of
 * what it is for.</p>
 */
public class AloggerNeverEndsTheWorkItIsDescribingTest {

    private static final ObservabilityLogger LOGGER =
            ObservabilityLogger.forComponent("AiRequestTemperature");

    @Test
    public void aTemperatureStatedAsNullIsRecordedRatherThanThrown() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("temperature", null);

        assertThatCode(() -> LOGGER.aiRequestStart("a-model", "a prompt", parameters))
                .doesNotThrowAnyException();
    }

    @Test
    public void aTemperatureThatIsNotANumberIsRecordedRatherThanThrown() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("temperature", "warm");

        assertThatCode(() -> LOGGER.aiRequestStart("a-model", "a prompt", parameters))
                .doesNotThrowAnyException();
    }

    @Test
    public void aTemperatureGivenAsANumberIsTakenAsOne() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("temperature", 0.25d);

        assertThatCode(() -> LOGGER.aiRequestStart("a-model", "a prompt", parameters))
                .doesNotThrowAnyException();
    }

    @Test
    public void noParametersAtAllIsNotAFailureEither() {
        assertThatCode(() -> LOGGER.aiRequestStart("a-model", "a prompt", null))
                .doesNotThrowAnyException();
    }
}
