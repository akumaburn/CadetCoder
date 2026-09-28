package com.eonmux.cadetcoder.auth;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * A GitHub token that was not written, and one that was not deleted, are both failures.
 *
 * <p>Storing was a {@code void} that logged a warning and returned, so {@code login} went on to
 * exchange the token and announce "GitHub Copilot connected". Nothing had been persisted, and the
 * short-lived Copilot token is not kept anywhere either -- every later credential lookup re-reads
 * the file -- so the run that had just been declared connected was not logged in, and neither was
 * the next one. The user had already authorized the device code by then.</p>
 *
 * <p>Clearing returned {@code false} for three different outcomes: nothing was stored, the path
 * could not be determined, and the delete failed. All three printed "No stored GitHub Copilot
 * credentials to remove", so a token that could not be deleted was reported as already absent
 * while it sat on disk, still valid, still authenticating this machine.</p>
 */
public class CopilotTokenPersistenceTest {

    @Rule
    public TemporaryFolder baseDir = new TemporaryFolder();

    private MockedStatic<ConfigManager> configMock;
    private GitHubCopilotAuth           auth;

    @Before
    public void setUp() {
        Configuration config = new Configuration();
        config.setBaseDir(baseDir.getRoot().getAbsolutePath());

        ConfigManager manager = mock(ConfigManager.class);
        when(manager.getConfig()).thenReturn(config);

        configMock = mockStatic(ConfigManager.class);
        configMock.when(ConfigManager::getInstance).thenReturn(manager);

        auth = new GitHubCopilotAuth();
    }

    @After
    public void tearDown() {
        configMock.close();
    }

    /**
     * Makes the token path unusable in a way that does not depend on who is running the test.
     *
     * <p>A permission bit would be ignored by a user who can write anywhere; a non-empty directory
     * where a file is expected cannot be written to or deleted by anyone.</p>
     */
    private Path blockTokenPath() throws IOException {
        Path blocked = baseDir.getRoot().toPath().resolve("copilot-auth.json");
        Files.createDirectories(blocked);
        Files.writeString(blocked.resolve("occupied.txt"), "in the way");
        return blocked;
    }

    @Test
    public void aTokenThatCouldNotBeWrittenIsAFailure() throws IOException {
        blockTokenPath();

        assertThatThrownBy(() -> auth.storeOAuthToken("gho_exampleexampleexample"))
                .as("login reported success on top of this and the credential was never saved")
                .isInstanceOf(IOException.class);
        assertThat(auth.hasStoredOAuthToken())
                .as("nothing was stored, so nothing must be reported as stored")
                .isFalse();
    }

    @Test
    public void aTokenThatWasWrittenIsReadBack() throws Exception {
        auth.storeOAuthToken("gho_exampleexampleexample");

        assertThat(auth.hasStoredOAuthToken()).isTrue();
        assertThat(auth.loadOAuthToken()).isEqualTo("gho_exampleexampleexample");
    }

    @Test
    public void clearingATokenThatCannotBeDeletedIsAFailureNotAnAbsence() throws IOException {
        Path blocked = blockTokenPath();

        assertThatThrownBy(auth::clearStoredToken)
                .as("this machine is still authenticated; saying there was nothing to remove is a lie")
                .isInstanceOf(IOException.class);
        assertThat(Files.exists(blocked)).isTrue();
    }

    @Test
    public void clearingWhenNothingIsStoredIsNotAFailure() {
        assertThatCode(() -> assertThat(auth.clearStoredToken())
                .as("an absent credential is the outcome logout wanted, not an error")
                .isFalse())
                .doesNotThrowAnyException();
    }

    @Test
    public void clearingRemovesAStoredToken() throws Exception {
        auth.storeOAuthToken("gho_exampleexampleexample");

        assertThat(auth.clearStoredToken()).isTrue();
        assertThat(auth.hasStoredOAuthToken()).isFalse();
    }
}
