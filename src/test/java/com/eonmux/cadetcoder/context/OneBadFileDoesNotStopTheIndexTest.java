package com.eonmux.cadetcoder.context;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.test.TestOutputCapture;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assume.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * One file the index cannot read, and one index another process is already using.
 *
 * <p><b>The first defect</b>: a file that could not be read was caught and then handed straight to
 * the indexer "with an empty checksum so the next run reconsiders it" -- and indexing it reads it,
 * so it threw again, this time out of {@code reindex} entirely. A single unreadable file (mode 000,
 * a broken symlink, a file deleted between discovery and indexing) therefore abandoned the whole
 * reindex at the point it reached, leaving every file after it in the walk unindexed and
 * {@code search} quietly answering from a partial index.</p>
 *
 * <p><b>The second defect</b>: Lucene's {@code IndexWriter} takes an exclusive lock, so a second
 * CadetCoder opening the same index fails to acquire it. That failure was treated as "the index
 * cannot be read", whose recovery is to DELETE every file in the index directory and start again --
 * so a second process destroyed the first one's index, and the lock file with it, while the first
 * was still writing into it.</p>
 */
public class OneBadFileDoesNotStopTheIndexTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private TestOutputCapture output;
    private String            wasWorkingDir;
    private File              project;
    private File              indexDirectory;

    @Before
    public void setUp() throws Exception {
        output         = new TestOutputCapture();
        wasWorkingDir  = System.getProperty("user.dir");
        project        = folder.newFolder("test-project");
        indexDirectory = folder.newFolder("index-" + System.nanoTime());
        System.setProperty("user.dir", project.getAbsolutePath());
    }

    @After
    public void tearDown() {
        output.restore();
        if (wasWorkingDir != null) {
            System.setProperty("user.dir", wasWorkingDir);
        }
        ContextEngine.resetInstance();
    }

    private ConfigManager configuredManager() {
        ConfigManager                manager  = mock(ConfigManager.class);
        Configuration                config   = new Configuration();
        Configuration.IndexingConfig indexing = new Configuration.IndexingConfig();
        indexing.setEnabled(false);
        indexing.setIndexLocation(indexDirectory.getAbsolutePath());
        config.setIndexing(indexing);
        Configuration.ContextConfig context = new Configuration.ContextConfig();
        context.setMaxFiles(50);
        config.setContext(context);
        when(manager.getConfig()).thenReturn(config);
        return manager;
    }

    private static Path unreadableFileIn(File directory, String name) throws IOException {
        Path file = Files.writeString(directory.toPath().resolve(name), "secret");
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------"));
        } catch (UnsupportedOperationException e) {
            return null;
        }
        return Files.isReadable(file) ? null : file;
    }

    @Test
    public void afileThatCannotBeReadIsCountedAndTheRestAreIndexed() throws Exception {
        Files.writeString(project.toPath().resolve("aaa.txt"), "the first file");
        Path blocked = unreadableFileIn(project, "mmm.txt");
        assumeTrue("needs a filesystem where a file can be made unreadable", blocked != null);
        Files.writeString(project.toPath().resolve("zzz.txt"), "the last file");

        ConfigManager manager = configuredManager();
        try (MockedStatic<ConfigManager> configured = mockStatic(ConfigManager.class)) {
            configured.when(ConfigManager::getInstance).thenReturn(manager);

            ContextEngine engine = ContextEngine.getInstance();
            ContextEngine.ReindexSummary summary = engine.reindex();

            assertThat(summary.getUnreadable())
                    .as("the file that could not be read is reported, not thrown")
                    .isEqualTo(1);
            assertThat(summary.getIndexed())
                    .as("every readable file is indexed, including the ones after it in the walk")
                    .isEqualTo(2);
            assertThat(engine.searchRelevantSnippets("last"))
                    .as("a file later in the walk than the bad one is searchable")
                    .isNotEmpty();
        } finally {
            Files.setPosixFilePermissions(blocked, PosixFilePermissions.fromString("rw-------"));
        }
    }

    @Test
    public void anIndexAnotherProcessIsUsingIsNotDeleted() throws Exception {
        try (FSDirectory held = FSDirectory.open(indexDirectory.toPath());
             IndexWriter otherProcess = new IndexWriter(held, new IndexWriterConfig(new StandardAnalyzer()))) {
            otherProcess.commit();
            Set<String> before = Set.of(indexDirectory.list());
            assertThat(before).isNotEmpty();

            ConfigManager manager = configuredManager();
            try (MockedStatic<ConfigManager> configured = mockStatic(ConfigManager.class)) {
                configured.when(ConfigManager::getInstance).thenReturn(manager);

                assertThatThrownBy(ContextEngine::getInstance)
                        .as("the index belongs to whoever holds the lock; it is not ours to discard")
                        .isInstanceOf(IOException.class)
                        .hasMessageContaining("another");
            }

            assertThat(Set.of(indexDirectory.list()))
                    .as("nothing in the other process's index directory was removed")
                    .containsAll(before);
            otherProcess.commit();
        }
    }
}
