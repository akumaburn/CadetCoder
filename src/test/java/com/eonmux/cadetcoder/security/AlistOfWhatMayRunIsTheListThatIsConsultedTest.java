package com.eonmux.cadetcoder.security;

import com.eonmux.cadetcoder.ai.parsing.ParsedAction;
import com.eonmux.cadetcoder.ai.parsing.ParsingContext;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * {@code security.allowedCommands} restricts what the shell may start, and nothing else.
 *
 * <p><b>The defect</b>: one setting had two readers that meant different things by it. Sandbox mode
 * -- the thing the setting is documented as controlling -- consulted a list written in the source
 * and ignored the user's entirely, so narrowing it restricted nothing. The list was instead matched
 * against the tool's own action names, and applied whether or not sandbox mode was on, so a user who
 * allowed {@code git} and {@code ls} stopped the agent reading and writing files.</p>
 */
class AlistOfWhatMayRunIsTheListThatIsConsultedTest {

    private static Configuration.SecurityConfig allowing(String... commands) {
        Configuration.SecurityConfig security = new Configuration.SecurityConfig();
        security.setAllowedCommands(commands);
        return security;
    }

    @Test
    void withNoListOfItsOwnTheSandboxAllowsWhatItShipsWith() {
        assertThat(SecurityValidator.sandboxAllows(allowing(), "git")).isTrue();
        assertThat(SecurityValidator.sandboxAllows(allowing(), "curl")).isFalse();
    }

    @Test
    void thelistTheUserSetIsTheOneTheSandboxConsults() {
        Configuration.SecurityConfig narrowed = allowing("git");

        assertThat(SecurityValidator.sandboxAllows(narrowed, "git")).isTrue();
        assertThat(SecurityValidator.sandboxAllows(narrowed, "ls"))
                .as("ls is on the shipped list, so allowing only git has to take it away")
                .isFalse();
    }

    @Test
    void narrowingWhatTheShellMayStartDoesNotStopTheAgentReadingAfile() {
        Configuration configuration = new Configuration();
        configuration.getSecurity().setAllowedCommands(new String[]{"git", "ls"});

        ConfigManager manager = mock(ConfigManager.class);
        when(manager.getConfig()).thenReturn(configuration);

        try (MockedStatic<ConfigManager> statics = mockStatic(ConfigManager.class)) {
            statics.when(ConfigManager::getInstance).thenReturn(manager);

            com.eonmux.cadetcoder.ai.parsing.SecurityValidator validator =
                    new com.eonmux.cadetcoder.ai.parsing.SecurityValidator();
            ParsedAction read = new ParsedAction("read", new HashMap<>(), "look at a file");

            var verdict = validator.validateAction(read, new ParsingContext("look at a file", "."));

            assertThat(verdict.getViolations())
                    .as("a list of shell programs says nothing about this tool's own actions")
                    .isEmpty();
        }
    }
}
