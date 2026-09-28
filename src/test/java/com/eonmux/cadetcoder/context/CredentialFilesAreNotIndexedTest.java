package com.eonmux.cadetcoder.context;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * What the content index holds is what the model is shown, so the denylist reaches it too.
 *
 * <p>{@code ContextEngine} stores whole file contents in a Lucene index that outlives the run, and
 * {@code search}, {@code edit} and {@code agent} all read snippets back out of it -- the last two
 * straight into the prompt they send to the provider. It consulted no denylist at all: it skipped
 * hidden files, which caught {@code .env} by accident, and indexed {@code deploy/prod.env} and
 * {@code certs/server.key} in full. So {@code read} refused a file that {@code agent} had already
 * uploaded.</p>
 *
 * <p>Not indexing them from now on is only half of it. The index is durable, so a credential file
 * indexed before the rule existed would still be served afterwards, and {@code reindex} deleted
 * nothing -- it only added and updated. A file deleted from the project stayed in the index and was
 * still returned as though it were current, which is the same bug with the secrecy taken out.</p>
 */
public class CredentialFilesAreNotIndexedTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private File   projectDir;
    private String originalWorkingDir;

    @Before
    public void setUp() throws Exception {
        originalWorkingDir = System.getProperty("user.dir");
        projectDir         = tempFolder.newFolder("indexed-project");
        System.setProperty("user.dir", projectDir.getAbsolutePath());
    }

    @After
    public void tearDown() {
        if (originalWorkingDir != null) {
            System.setProperty("user.dir", originalWorkingDir);
        }
        ContextEngine.resetInstance();
    }

    private File write(String name, String contents) throws IOException {
        File file = new File(projectDir, name);
        Files.createDirectories(file.toPath().getParent());
        Files.writeString(file.toPath(), contents);
        return file;
    }

    /**
     * Builds a configuration whose index lives in its own temporary directory.
     *
     * @return the mock control, to be closed by the caller
     */
    private MockedStatic<ConfigManager> configuredEngine() throws IOException {
        MockedStatic<ConfigManager> configMock  = mockStatic(ConfigManager.class);
        ConfigManager               manager     = mock(ConfigManager.class);
        Configuration               config      = new Configuration();

        Configuration.IndexingConfig indexing = new Configuration.IndexingConfig();
        indexing.setEnabled(false);
        indexing.setIndexLocation(tempFolder.newFolder("index-" + System.nanoTime()).getAbsolutePath());
        indexing.setExcludePatterns(new String[] {".git", "node_modules"});
        config.setIndexing(indexing);

        Configuration.ContextConfig context = new Configuration.ContextConfig();
        context.setMaxFiles(50);
        config.setContext(context);

        when(manager.getConfig()).thenReturn(config);
        configMock.when(ConfigManager::getInstance).thenReturn(manager);
        return configMock;
    }

    @Test
    public void aCredentialFileIsNotIndexed() throws Exception {
        try (MockedStatic<ConfigManager> ignored = configuredEngine()) {
            write("deploy/prod.env", "DATABASE_PASSWORD=credentialmarkerone\n");
            write("certs/server.key", "PRIVATE KEY credentialmarkertwo\n");
            write("README.md", "ordinarymarker documents the deployment\n");

            ContextEngine engine = ContextEngine.getInstance();
            engine.reindex();

            assertThat(engine.search("credentialmarkerone"))
                    .as("agent and edit put indexed snippets into the prompt they send to the "
                        + "provider; read refuses this file")
                    .isEmpty();
            assertThat(engine.search("credentialmarkertwo")).isEmpty();
            assertThat(engine.search("ordinarymarker"))
                    .as("ordinary project files must still be indexed")
                    .isNotEmpty();
        }
    }

    @Test
    public void aCredentialFileIndexedBeforeTheRuleIsRemovedFromTheIndex() throws Exception {
        try (MockedStatic<ConfigManager> ignored = configuredEngine()) {
            File secretFile = write("deploy/prod.env", "DATABASE_PASSWORD=staleindexedmarker\n");
            write("README.md", "ordinarymarker documents the deployment\n");

            ContextEngine engine = ContextEngine.getInstance();
            engine.indexFile(secretFile);
            assertThat(engine.search("staleindexedmarker"))
                    .as("precondition: the index really does hold it")
                    .isNotEmpty();

            engine.reindex();

            assertThat(engine.search("staleindexedmarker"))
                    .as("the index is durable, so a rule that only stops new writes leaves the "
                        + "old ones being served")
                    .isEmpty();
        }
    }

    @Test
    public void aFileDeletedFromTheProjectIsNoLongerServed() throws Exception {
        try (MockedStatic<ConfigManager> ignored = configuredEngine()) {
            File removed = write("src/Removed.java", "class Removed { String deletedmarker; }");
            write("README.md", "ordinarymarker documents the deployment\n");

            ContextEngine engine = ContextEngine.getInstance();
            engine.reindex();
            assertThat(engine.search("deletedmarker")).isNotEmpty();

            assertThat(removed.delete()).isTrue();
            engine.reindex();

            assertThat(engine.search("deletedmarker"))
                    .as("a snippet from a file that no longer exists is stale context presented "
                        + "as current")
                    .isEmpty();
            assertThat(engine.search("ordinarymarker"))
                    .as("forgetting what is gone must not forget what is still there")
                    .isNotEmpty();
        }
    }
}
