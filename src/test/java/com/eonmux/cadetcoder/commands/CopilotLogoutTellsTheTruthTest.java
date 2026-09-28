package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * {@code login logout github-copilot} must not report a credential gone while it is still there.
 *
 * <p>The command rendered one message for every unsuccessful outcome -- "No stored GitHub Copilot
 * credentials to remove" -- and returned 0. A delete that failed was therefore indistinguishable
 * from having nothing to delete, and the user walked away believing the machine was logged out
 * while a long-lived GitHub OAuth token was still on disk.</p>
 *
 * <p>The saved-API-key half of the same command was corrected earlier for exactly this reason.
 * This is the other half.</p>
 */
public class CopilotLogoutTellsTheTruthTest {

    @Rule
    public TemporaryFolder baseDir = new TemporaryFolder();

    private MockedStatic<ConfigManager> configMock;
    private TestOutputCapture           output;

    @Before
    public void setUp() {
        Configuration config = new Configuration();
        config.setBaseDir(baseDir.getRoot().getAbsolutePath());
        config.getUi().setColorEnabled(false);

        ConfigManager manager = mock(ConfigManager.class);
        when(manager.getConfig()).thenReturn(config);
        when(manager.saveConfig()).thenReturn(true);

        configMock = mockStatic(ConfigManager.class);
        configMock.when(ConfigManager::getInstance).thenReturn(manager);

        output = new TestOutputCapture();
    }

    @After
    public void tearDown() {
        output.restore();
        configMock.close();
    }

    private Path blockTokenPath() throws IOException {
        Path blocked = baseDir.getRoot().toPath().resolve("copilot-auth.json");
        Files.createDirectories(blocked);
        Files.writeString(blocked.resolve("occupied.txt"), "in the way");
        return blocked;
    }

    @Test
    public void aLogoutThatCouldNotDeleteTheTokenSaysSo() throws IOException {
        Path blocked = blockTokenPath();

        int exitCode = new LoginCommand().execute(new String[] {"logout", "github-copilot"});

        assertThat(output.getAllOutput())
                .as("the token is still on disk, so 'nothing to remove' is the wrong answer")
                .doesNotContain("No stored GitHub Copilot credentials to remove");
        assertThat(exitCode)
                .as("the credential was not removed, so this did not succeed")
                .isEqualTo(1);
        assertThat(Files.exists(blocked)).isTrue();
    }

    @Test
    public void aLogoutWithNothingStoredIsReportedAsSuchAndSucceeds() {
        int exitCode = new LoginCommand().execute(new String[] {"logout", "github-copilot"});

        assertThat(output.getAllOutput()).contains("No stored GitHub Copilot credentials to remove");
        assertThat(exitCode).isEqualTo(0);
    }
}
