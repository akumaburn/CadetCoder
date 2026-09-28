package com.eonmux.cadetcoder.ai.parsing;

import com.eonmux.cadetcoder.ai.parsing.SecurityValidator.SecurityLevel;
import com.eonmux.cadetcoder.ai.parsing.SecurityValidator.ValidationResult;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;

import org.junit.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * What {@code security.requireConfirmation} is allowed to decide.
 *
 * <h2>The defect</h2>
 *
 * <p>Whether a screened action was valid read:</p>
 *
 * <pre>
 * boolean isValid = violations.isEmpty()
 *     &amp;&amp; (riskLevel != HIGH || (securityConfig != null &amp;&amp; !securityConfig.isRequireConfirmation()));
 * </pre>
 *
 * <p>Read plainly: a HIGH-risk action with no violation is refused while confirmation is required,
 * and permitted once the user turns confirmation off. A setting whose job is to decide whether the
 * user is asked was deciding whether the screen applies, and in the direction nobody would predict.
 * Asking for fewer prompts bought weaker screening.</p>
 *
 * <p>No action reached the clause, because every branch that raises the level to HIGH also records a
 * violation, and a violation refuses on its own. That is what makes it worth removing rather than
 * leaving alone. It is a rule written down in the code, and the next branch to set HIGH without a
 * violation would inherit the inversion with nobody watching.</p>
 *
 * <h2>The fix</h2>
 *
 * <p>{@link ValidationResult} now derives validity from the violations it is given, so no caller
 * supplies a verdict and no caller can supply one that contradicts its own evidence. Violations are
 * the refusal. The risk level says how loudly to report the result, and the caller that has a user
 * to ask decides whether to ask.</p>
 */
public class TurningOffAPromptDoesNotTurnOffAGuardTest {

    private static final List<String> NOTHING = new ArrayList<>();

    private static List<String> one(String entry) {
        List<String> entries = new ArrayList<>();
        entries.add(entry);
        return entries;
    }

    @Test
    public void ahighRiskFindingWithNoViolationIsNotRefused() {
        // The state the removed clause governed. It is permitted because nothing objected, and that
        // answer does not move when a setting about prompts moves.
        ValidationResult verdict = new ValidationResult(SecurityLevel.HIGH, NOTHING, NOTHING);

        assertThat(verdict.isValid()).isTrue();
        assertThat(verdict.getRiskLevel()).isEqualTo(SecurityLevel.HIGH);
    }

    @Test
    public void aviolationRefusesAtEveryLevel() {
        for (SecurityLevel level : SecurityLevel.values()) {
            assertThat(new ValidationResult(level, one("dangerous command"), NOTHING).isValid())
                    .as("a violation at " + level)
                    .isFalse();
        }
    }

    @Test
    public void awarningDoesNotRefuse() {
        ValidationResult verdict =
                new ValidationResult(SecurityLevel.MEDIUM, NOTHING, one("path traversal"));

        assertThat(verdict.isValid()).isTrue();
        assertThat(verdict.hasWarnings()).isTrue();
    }

    @Test
    public void theVerdictIsTheSameInBothPositionsOfTheSetting() {
        // Stated as a property over the actions that actually reach the screen, rather than as two
        // examples: whatever the screen decides, the prompt setting is not part of it.
        for (String line : new String[]{"ls -la", "rm -rf /", "cat pom.xml", "curl http://x"}) {
            assertThat(verdictOn(line, true).isValid())
                    .as("the verdict on '" + line + "' must not depend on the prompt setting")
                    .isEqualTo(verdictOn(line, false).isValid());
        }
    }

    /** The verdict on one shell line, with confirmation configured either way. */
    private ValidationResult verdictOn(String shellCommand, boolean requireConfirmation) {
        Configuration config = new Configuration();
        config.getSecurity().setRequireConfirmation(requireConfirmation);
        ConfigManager manager = mock(ConfigManager.class);
        when(manager.getConfig()).thenReturn(config);

        try (MockedStatic<ConfigManager> configured = mockStatic(ConfigManager.class)) {
            configured.when(ConfigManager::getInstance).thenReturn(manager);
            ParsedAction action = new ParsedAction.Builder("bash")
                    .addParameter("command", shellCommand)
                    .setReasoning("Run a command")
                    .build();
            return new SecurityValidator().validateAction(
                    action,
                    new ParsingContext.Builder("do some work")
                            .workingDirectory(System.getProperty("user.dir"))
                            .build());
        }
    }
}
