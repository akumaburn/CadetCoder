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
 * The actions {@code copilot} answers to.
 *
 * <h2>What was missing</h2>
 *
 * <p>{@code copilot} could connect an account and report on one, and had no way to disconnect it.
 * The capability existed -- {@code login logout github-copilot} has always removed the token -- but
 * nobody who had just typed {@code copilot login} would look for it under another command, and what
 * they got instead was "Unknown action: logout" beside a usage line that did not mention where
 * logout lived. That is a poor answer to a request to revoke a credential on a machine somebody is
 * handing back.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>Disconnecting is reachable from the command that connected, it removes the token that is
 * actually there, and it reports a delete it could not do rather than claiming a clean logout --
 * the same guarantee {@link CopilotLogoutTellsTheTruthTest} holds for the other spelling, which
 * matters because both now run one implementation. {@code login} itself is not exercised: it opens
 * a device-authorization flow against GitHub.</p>
 */
public class DisconnectingCopilotTest {

    @Rule
    public TemporaryFolder baseDir = new TemporaryFolder();

    private MockedStatic<ConfigManager> configMock;
    private TestOutputCapture           output;
    private final CopilotCommand        copilot = new CopilotCommand();

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

    /** A stored token, as {@code copilot login} would have left it. */
    private Path storedToken() throws IOException {
        Path token = baseDir.getRoot().toPath().resolve("copilot-auth.json");
        Files.writeString(token, "{\"oauth_token\":\"gho_pretend\"}");
        return token;
    }

    @Test
    public void loggingOutRemovesTheStoredToken() throws IOException {
        Path token = storedToken();
        output.startCapture();

        int exitCode = copilot.execute(new String[] {"logout"});

        output.restore();
        assertThat(exitCode).isZero();
        assertThat(token).doesNotExist();
        assertThat(output.getAllOutput()).contains("Removed stored GitHub Copilot credentials.");
    }

    @Test
    public void signOutIsTheSameAct() throws IOException {
        Path token = storedToken();
        output.startCapture();

        int exitCode = copilot.execute(new String[] {"signout"});

        output.restore();
        assertThat(exitCode).isZero();
        assertThat(token).doesNotExist();
    }

    @Test
    public void loggingOutOfNothingIsNotAFailure() {
        output.startCapture();

        int exitCode = copilot.execute(new String[] {"logout"});

        output.restore();
        assertThat(exitCode).isZero();
        assertThat(output.getAllOutput()).contains("No stored GitHub Copilot credentials to remove.");
    }

    @Test
    public void aTokenThatCouldNotBeRemovedIsNotReportedAsGone() throws IOException {
        // A directory where the token file goes: the delete fails, and the credential is still here.
        Path blocked = baseDir.getRoot().toPath().resolve("copilot-auth.json");
        Files.createDirectories(blocked);
        Files.writeString(blocked.resolve("occupied.txt"), "in the way");
        output.startCapture();

        int exitCode = copilot.execute(new String[] {"logout"});

        output.restore();
        assertThat(exitCode).isEqualTo(1);
        assertThat(output.getAllOutput()).contains("still logged in");
        assertThat(blocked).exists();
    }

    @Test
    public void statusSaysThisMachineIsNotConnectedWhenNothingIsStored() {
        output.startCapture();

        int exitCode = copilot.execute(new String[] {"status"});

        output.restore();
        assertThat(exitCode).isEqualTo(1);
        assertThat(output.getAllOutput()).contains("not authenticated");
    }

    @Test
    public void anActionItDoesNotHaveIsReportedRatherThanGuessedAt() {
        output.startCapture();

        int exitCode = copilot.execute(new String[] {"disconnect"});

        output.restore();
        assertThat(exitCode).isEqualTo(1);
        assertThat(output.getAllOutput()).contains("Unknown action: disconnect");
        assertThat(output.getAllOutput()).contains("logout");
    }

    @Test
    public void theUsageSaysWhereTheOtherSpellingIs() {
        assertThat(copilot.getUsage())
                .contains("logout")
                .contains("login logout github-copilot");
    }
}
