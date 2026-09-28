package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import com.eonmux.cadetcoder.test.ProjectFolder;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * {@code grep} prints file contents, so it answers to the credential denylist like {@code read}.
 *
 * <p>{@code SecurityValidator}'s denylist is consulted by {@code read}, {@code write}, {@code ls},
 * {@code explain}, {@code analyze}, {@code suggest}, {@code refactor}, {@code prompt} and
 * {@code chat}. {@code grep} was not among them, and it opens every readable file under the search
 * root and prints the matching lines. So {@code read .env} was refused while {@code grep API_KEY}
 * printed the same line out of the same file -- a rule that depends on which command you go
 * through is one rule pretending to be two, which is the same shape of gap that put {@code .ssh}
 * behind {@code read} alone.</p>
 */
public class CredentialFilesAreNotSearchedTest {

    @Rule
    public TemporaryFolder projectFolder = new ProjectFolder();

    private GrepCommand       grep;
    private TestOutputCapture output;

    @Before
    public void setUp() {
        grep   = new GrepCommand();
        output = new TestOutputCapture();
        output.startCapture();
    }

    @After
    public void tearDown() {
        output.stopCapture();
    }

    private void write(String name, String contents) throws IOException {
        Path file = projectFolder.getRoot().toPath().resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, contents);
    }

    /**
     * Runs a search over the temporary project and returns everything the user would see.
     *
     * @param pattern the search pattern
     * @return the command's combined output
     */
    private String search(String pattern) {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager manager = mock(ConfigManager.class);
            Configuration config  = new Configuration();
            config.getUi().setColorEnabled(false);
            when(manager.getConfig()).thenReturn(config);
            configMock.when(ConfigManager::getInstance).thenReturn(manager);

            grep.execute(new String[] {pattern, "-p", projectFolder.getRoot().getAbsolutePath()});
        }
        return output.getAllOutput();
    }

    @Test
    public void aDotenvFileIsNotSearched() throws Exception {
        write(".env", "DATABASE_PASSWORD=hunter2\n");
        write("deploy/prod.env", "DATABASE_PASSWORD=correcthorse\n");
        write("README.md", "set DATABASE_PASSWORD before running\n");

        String printed = search("DATABASE_PASSWORD");

        assertThat(printed)
                .as("read refuses .env; grep must not print the same line out of the same file")
                .doesNotContain("hunter2")
                .doesNotContain("correcthorse");
        assertThat(printed)
                .as("ordinary files must still be searched")
                .contains("set DATABASE_PASSWORD before running");
    }

    /**
     * The secret sits on the matched line itself.
     *
     * <p>{@code grep} prints matching lines, so key material on a line the pattern does not match
     * is not printed whatever the rule says -- an assertion written that way would pass before the
     * gate existed and prove nothing.</p>
     */
    @Test
    public void keyMaterialIsNotSearched() throws Exception {
        write("certs/server.key", "PRIVATE-KEY-MATERIAL MIIBOgIBAAJBAK5tOPY\n");
        write("certs/client.p12", "PRIVATE-KEY-MATERIAL MIIBOgIBAAJBAK5tOPZ\n");
        write("docs/tls.md", "PRIVATE-KEY-MATERIAL is what those files hold\n");

        String printed = search("PRIVATE-KEY-MATERIAL");

        assertThat(printed)
                .as("a private key is a private key whichever container it is encoded in")
                .doesNotContain("MIIBOgIBAAJBAK5tOPY")
                .doesNotContain("MIIBOgIBAAJBAK5tOPZ");
        assertThat(printed)
                .as("the names are what the user needs to understand the gap; the contents are the "
                    + "secret, and ls already names a file it refuses")
                .contains("server.key")
                .contains("client.p12");
        assertThat(printed).contains("is what those files hold");
    }

    /** The committed placeholder holds names, not values, and must stay searchable. */
    @Test
    public void anEnvironmentTemplateIsStillSearched() throws Exception {
        write(".env.example", "DATABASE_PASSWORD=changeme\n");

        assertThat(search("DATABASE_PASSWORD"))
                .as(".env.example is checked in on purpose; refusing it protects nothing")
                .contains("changeme");
    }

    /** Skipping a file is a decision, and the user is told it was made. */
    @Test
    public void theSkipIsReportedRatherThanSilent() throws Exception {
        write(".env", "DATABASE_PASSWORD=hunter2\n");
        write("README.md", "set DATABASE_PASSWORD before running\n");

        assertThat(search("DATABASE_PASSWORD"))
                .as("a search that quietly omits a file gives an answer the user cannot trust")
                .contains("credential");
    }
}
