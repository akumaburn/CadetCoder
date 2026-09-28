package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link LoginCommand}. Interactive prompts resolve to an empty response in the
 * test environment ({@code System.console()} is null), so the command takes its
 * cancel/no-key paths without blocking.
 */
public class LoginCommandTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private LoginCommand      loginCommand;
    private TestOutputCapture outputCapture;
    private String            originalUserHome;
    private String            originalBaseDir;

    @Before
    public void setUp() throws Exception {
        loginCommand  = new LoginCommand();
        outputCapture = new TestOutputCapture();
        outputCapture.startCapture();

        // Both are process-wide and both outlive this class unless they are put back: surefire
        // runs every test in one reused fork, so whatever is left here is what the next 90-odd
        // classes see.
        originalUserHome = System.getProperty("user.home");
        originalBaseDir  = Configuration.defaultBaseDir;
        System.setProperty("user.home", tempFolder.getRoot().getAbsolutePath());
        Configuration.defaultBaseDir = tempFolder.getRoot().getAbsolutePath() + "/.cadet";

        java.lang.reflect.Field configInstance = ConfigManager.class.getDeclaredField("instance");
        configInstance.setAccessible(true);
        configInstance.set(null, null);
    }

    @After
    public void tearDown() {
        outputCapture.stopCapture();
        if (originalUserHome != null) {
            System.setProperty("user.home", originalUserHome);
        }
        Configuration.defaultBaseDir = originalBaseDir;
    }

    @Test
    public void getDescriptionAndUsage() {
        assertThat(loginCommand.getDescription()).contains("Connect an AI provider");
        assertThat(loginCommand.getUsage()).isEqualTo("login [<provider> | status | logout <provider>]");
    }

    @Test
    public void status_listsConnectorsAndSucceeds() {
        int exitCode = loginCommand.execute(new String[] {"status"});
        assertThat(exitCode).isEqualTo(0);
        String out = outputCapture.getAllOutput();
        assertThat(out).contains("Provider login status");
        assertThat(out).contains("anthropic");
        assertThat(out).contains("github-copilot");
    }

    @Test
    public void unknownProvider_returnsError() {
        int exitCode = loginCommand.execute(new String[] {"not-a-real-provider"});
        assertThat(exitCode).isEqualTo(1);
        assertThat(outputCapture.getAllOutput()).contains("Unknown provider");
    }

    @Test
    public void noArgs_promptCancelsCleanly() {
        int exitCode = loginCommand.execute(new String[] {});
        assertThat(exitCode).isEqualTo(0);
        String out = outputCapture.getAllOutput();
        assertThat(out).contains("choose a provider");
        assertThat(out).contains("Login cancelled");
    }

    @Test
    public void keyProvider_withoutConsole_refusesEchoingReadAndPersistsNothing() {
        // System.console() is null in the test harness, so there is no non-echoing read
        // available. The command must refuse rather than read the key on an echoing prompt.
        int exitCode = loginCommand.execute(new String[] {"anthropic"});
        assertThat(exitCode).isEqualTo(1);
        String out = outputCapture.getAllOutput();
        assertThat(out).contains("Authenticate Anthropic");
        assertThat(out).contains("without showing it on screen");
        // No key should have been persisted.
        assertThat(ConfigManager.getInstance().getConfig().getAi().getProviderApiKeys())
                .doesNotContainKey("anthropic");
    }

    @Test
    public void keyProvider_withoutConsole_neverEchoesAKeyValue() {
        // The no-echo path must not request, print, or otherwise surface any key value.
        // We exercise the path and assert no key-like string from a (hypothetical) entry leaks.
        loginCommand.execute(new String[] {"anthropic"});
        String out = outputCapture.getAllOutput();
        // The command never reads input here, so the canonical secret marker must be absent.
        assertThat(out).doesNotContain("sk-");
        // The prompt that would echo the key must not be shown on this path.
        assertThat(out).doesNotContain("Paste the API key");
    }

    @Test
    public void logout_withoutProvider_returnsUsageError() {
        int exitCode = loginCommand.execute(new String[] {"logout"});
        assertThat(exitCode).isEqualTo(1);
        // Spelled for the surface the reader is on; no shell is running in a test.
        assertThat(outputCapture.getAllOutput())
                .contains("Which provider should I log out of?")
                .contains("Usage: cadet login logout <provider>");
    }

    @Test
    public void logout_unknownProvider_reportsNothingToRemove() {
        int exitCode = loginCommand.execute(new String[] {"logout", "anthropic"});
        assertThat(exitCode).isEqualTo(0);
        assertThat(outputCapture.getAllOutput()).contains("No saved API key for anthropic");
    }

    @Test
    public void logout_removesSavedKey() {
        Configuration.AiConfig ai = ConfigManager.getInstance().getConfig().getAi();
        ai.getProviderApiKeys().put("openai", "sk-test-value");

        int exitCode = loginCommand.execute(new String[] {"logout", "openai"});

        assertThat(exitCode).isEqualTo(0);
        assertThat(outputCapture.getAllOutput()).contains("Removed saved API key for openai");
        assertThat(ConfigManager.getInstance().getConfig().getAi().getProviderApiKeys())
                .doesNotContainKey("openai");
    }

    @Test
    public void logout_copilot_whenNotLoggedIn() {
        int exitCode = loginCommand.execute(new String[] {"logout", "github-copilot"});
        assertThat(exitCode).isEqualTo(0);
        assertThat(outputCapture.getAllOutput())
                .contains("No stored GitHub Copilot credentials to remove");
    }
}
