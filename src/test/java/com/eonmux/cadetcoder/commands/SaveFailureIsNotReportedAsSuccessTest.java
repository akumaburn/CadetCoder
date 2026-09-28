package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * A setting that never reached disk must not be announced as saved.
 *
 * <p>{@code ConfigManager.saveConfig()} returns whether the write happened, and says so in its own
 * javadoc: "Callers that surface a success message to the user should gate it on this result."
 * Three callers discarded it and printed success unconditionally, so a full disk, a read-only home
 * or a permission change produced "Failed to save configuration: ..." from inside {@code saveConfig}
 * followed immediately by the command's own confirmation, and exit code 0.</p>
 *
 * <p>{@code login logout} is the one that matters most. It removes the key from the in-memory map
 * first, so on a failed write the running process believes it is logged out while the key is still
 * on disk -- the user is told the credential is gone, and the next run reads it straight back. The
 * matching save path in the same file was already fixed once, with a comment explaining exactly
 * this; the removal path was left as it was.</p>
 */
public class SaveFailureIsNotReportedAsSuccessTest {

    private MockedStatic<ConfigManager> configMock;
    private Configuration               config;
    private TestOutputCapture           output;

    @Before
    public void setUp() {
        config = new Configuration();

        ConfigManager manager = mock(ConfigManager.class);
        when(manager.getConfig()).thenReturn(config);
        when(manager.saveConfig()).thenReturn(false);

        configMock = mockStatic(ConfigManager.class);
        configMock.when(ConfigManager::getInstance).thenReturn(manager);

        output = new TestOutputCapture();
    }

    @After
    public void tearDown() {
        output.restore();
        configMock.close();
    }

    @Test
    public void aLogoutThatCannotBeWrittenDoesNotClaimTheKeyIsGone() {
        config.getAi().getProviderApiKeys().put("openai", "sk-not-really-a-key");

        int exitCode = new LoginCommand().execute(new String[] {"logout", "openai"});

        assertThat(output.getAllOutput())
                .as("the key is still in config.json, so saying it was removed is a lie")
                .doesNotContain("Removed saved API key");
        assertThat(exitCode)
                .as("nothing was removed, so this did not succeed")
                .isEqualTo(1);
        assertThat(config.getAi().getProviderApiKeys())
                .as("memory must agree with the file: the key was not removed from either")
                .containsKey("openai");
    }

    @Test
    public void aThemeThatCannotBeWrittenIsReportedAsAppliedButNotSaved() {
        new ThemeCommand().execute(new String[] {"set", "dracula", "--save"});

        String printed = output.getAllOutput();
        assertThat(printed)
                .as("the theme did apply for this session")
                .contains("Applied theme");
        assertThat(printed)
                .as("but it is not in the configuration, and the next run will not have it")
                .contains("not saved to configuration");
        assertThat(printed).doesNotContain("Theme saved to configuration");
    }
}
