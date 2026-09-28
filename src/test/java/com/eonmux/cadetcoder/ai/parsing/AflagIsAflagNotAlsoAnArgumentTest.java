package com.eonmux.cadetcoder.ai.parsing;

import com.eonmux.cadetcoder.commands.ChatCommand;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A parameter one arm turns into a flag is not also swept up as a positional.
 *
 * <p><b>The defect</b>: {@code context} with {@code {"verbose": true}} and no action rendered as
 * {@code context true -v}. The sweep that collects untranslated parameters did not know the arm
 * below it was about to emit {@code -v}, so it put the VALUE on the command line as an argument in
 * its own right -- and the command read {@code true} as the action it had been asked for and failed
 * with "Unknown action: true".</p>
 */
class AflagIsAflagNotAlsoAnArgumentTest {

    private static List<String> argv(Map<String, Object> parameters) throws Exception {
        ChatCommand.AIAction dispatched =
                ActionArguments.legacyActionOf(new ParsedAction("context", parameters, "because"));
        Field field = ChatCommand.AIAction.class.getDeclaredField("arguments");
        field.setAccessible(true);
        return Arrays.asList((String[]) field.get(dispatched));
    }

    @Test
    void averboseRequestWithNoActionAsksForVerbosityAndNothingElse() throws Exception {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("verbose", true);

        assertThat(argv(parameters))
                .as("nothing was named as the action, so nothing stands where one would")
                .containsExactly("-v");
    }

    @Test
    void anActionThatWasNamedIsStillPassedOn() throws Exception {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("action", "stats");
        parameters.put("verbose", true);

        assertThat(argv(parameters)).containsExactly("stats", "-v");
    }

    @Test
    void aparameterNoArmTranslatesIsStillSweptUp() throws Exception {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("scope", "project");

        assertThat(argv(parameters)).containsExactly("project");
    }
}
