package com.eonmux.cadetcoder.context;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Test class for ContextEngine
 */
public class ContextEngineTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private TestOutputCapture outputCapture;
    private File              testDir;

    /**
     * These tests point the engine at a temporary project by moving {@code user.dir}, and that
     * directory is deleted when the test ends. Left pointing at a directory that is no longer
     * there, it breaks whatever runs next in the same JVM -- the auto-commit scheduler, for one,
     * walks {@code user.dir} to register a file watch and fails when it cannot.
     */
    private String originalWorkingDir;

    @Before
    public void setUp() throws Exception {
        outputCapture      = new TestOutputCapture();
        originalWorkingDir = System.getProperty("user.dir");
        testDir            = tempFolder.newFolder("test-project");

        // Create test files
        File srcDir = new File(testDir, "src");
        srcDir.mkdirs();
        File testFile1 = new File(srcDir, "Main.java");
        Files.writeString(testFile1.toPath(), "public class Main { public static void main(String[] args) {} }");

        File testFile2 = new File(testDir, "README.md");
        Files.writeString(testFile2.toPath(), "# Test Project\nThis is a test project for ContextEngine.");
    }

    @After
    public void tearDown() {
        outputCapture.restore();
        if (originalWorkingDir != null) {
            System.setProperty("user.dir", originalWorkingDir);
        }
        // Reset the singleton to avoid cross-test contamination
        ContextEngine.resetInstance();
    }

    @Test
    public void testContextEngine_Construction() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            // Mock config
            ConfigManager                mockConfigManager = mock(ConfigManager.class);
            Configuration                mockConfig        = new Configuration();
            Configuration.IndexingConfig indexingConfig    = new Configuration.IndexingConfig();
            indexingConfig.setEnabled(false); // Disable indexing to avoid complexity
            indexingConfig.setIndexLocation(tempFolder.newFolder("index-" + System.nanoTime()).getAbsolutePath());
            mockConfig.setIndexing(indexingConfig);

            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);

            ContextEngine engine = ContextEngine.getInstance();

            assertThat(engine).isNotNull();
        }
    }

    @Test
    public void testContextEngine_Singleton() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            // Mock config
            ConfigManager                mockConfigManager = mock(ConfigManager.class);
            Configuration                mockConfig        = new Configuration();
            Configuration.IndexingConfig indexingConfig    = new Configuration.IndexingConfig();
            indexingConfig.setEnabled(false);
            indexingConfig.setIndexLocation(tempFolder.newFolder("index-" + System.nanoTime()).getAbsolutePath());
            mockConfig.setIndexing(indexingConfig);

            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);

            ContextEngine engine1 = ContextEngine.getInstance();
            ContextEngine engine2 = ContextEngine.getInstance();

            assertThat(engine1).isSameAs(engine2);
        }
    }

    @Test
    public void testIndexFile() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            // Mock config
            ConfigManager                mockConfigManager = mock(ConfigManager.class);
            Configuration                mockConfig        = new Configuration();
            Configuration.IndexingConfig indexingConfig    = new Configuration.IndexingConfig();
            indexingConfig.setEnabled(false);
            indexingConfig.setIndexLocation(tempFolder.newFolder("index-" + System.nanoTime()).getAbsolutePath());
            mockConfig.setIndexing(indexingConfig);

            Configuration.ContextConfig contextConfig = new Configuration.ContextConfig();
            contextConfig.setMaxFiles(10);
            mockConfig.setContext(contextConfig);

            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);

            ContextEngine engine   = ContextEngine.getInstance();
            File          testFile = new File(testDir, "test.txt");
            Files.writeString(testFile.toPath(), "Test content for indexing");

            engine.indexFile(testFile);

            // Asserted by what the index can now answer, not by a console line. The engine used to
            // announce every file it touched, which is one line per file in the middle of whatever
            // the user had actually asked for.
            assertThat(engine.search("indexing")).isNotEmpty();
            assertThat(outputCapture.getAllOutput())
                    .as("indexing a file is not news")
                    .doesNotContain("Indexed file");
        }
    }

    @Test
    public void testSearch() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            // Mock config
            ConfigManager                mockConfigManager = mock(ConfigManager.class);
            Configuration                mockConfig        = new Configuration();
            Configuration.IndexingConfig indexingConfig    = new Configuration.IndexingConfig();
            indexingConfig.setEnabled(false);
            indexingConfig.setIndexLocation(tempFolder.newFolder("index-" + System.nanoTime()).getAbsolutePath());
            mockConfig.setIndexing(indexingConfig);

            Configuration.ContextConfig contextConfig = new Configuration.ContextConfig();
            contextConfig.setMaxFiles(10);
            mockConfig.setContext(contextConfig);

            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);

            ContextEngine engine = ContextEngine.getInstance();

            // Index a file with searchable content
            File testFile = new File(testDir, "searchable.java");
            Files.writeString(testFile.toPath(), "public class SearchableClass { private String searchTerm; }");
            engine.indexFile(testFile);

            // Search for content
            List<String> results = engine.search("searchTerm");

            assertThat(results).isNotEmpty();
            // Since ContextEngine is a singleton, verify search functionality works
            boolean foundSearchTerm = results.stream()
                                             .anyMatch(result -> result.toLowerCase().contains("searchterm"));
            assertThat(foundSearchTerm).isTrue();
        }
    }

    @Test
    public void testSearchRelevantSnippets() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            // Mock config
            ConfigManager                mockConfigManager = mock(ConfigManager.class);
            Configuration                mockConfig        = new Configuration();
            Configuration.IndexingConfig indexingConfig    = new Configuration.IndexingConfig();
            indexingConfig.setEnabled(false);
            indexingConfig.setIndexLocation(tempFolder.newFolder("index-" + System.nanoTime()).getAbsolutePath());
            mockConfig.setIndexing(indexingConfig);

            Configuration.ContextConfig contextConfig = new Configuration.ContextConfig();
            contextConfig.setMaxFiles(5);
            mockConfig.setContext(contextConfig);

            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);

            ContextEngine engine = ContextEngine.getInstance();

            // Index a file
            File testFile = new File(testDir, "relevant.txt");
            Files.writeString(testFile.toPath(), "This file contains relevant information about the project");
            engine.indexFile(testFile);

            // Search using natural language
            List<String> snippets = engine.searchRelevantSnippets("relevant information");

            assertThat(snippets).isNotEmpty();
            // Since ContextEngine is a singleton, it may index other project files too
            // Just verify that search functionality works
            boolean foundRelevantContent = snippets.stream()
                                                   .anyMatch(snippet -> snippet.toLowerCase().contains("relevant"));
            assertThat(foundRelevantContent).isTrue();
        }
    }

    @Test
    public void testReindex() throws Exception {
        String oldWorkingDir = System.getProperty("user.dir");

        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            // Set working directory to our test directory
            System.setProperty("user.dir", testDir.getAbsolutePath());

            // Mock config
            ConfigManager                mockConfigManager = mock(ConfigManager.class);
            Configuration                mockConfig        = new Configuration();
            Configuration.IndexingConfig indexingConfig    = new Configuration.IndexingConfig();
            indexingConfig.setEnabled(true); // Enable indexing for this test
            indexingConfig.setIndexLocation(tempFolder.newFolder("index-" + System.nanoTime()).getAbsolutePath());
            indexingConfig.setExcludePatterns(new String[] {".git", "node_modules"});
            mockConfig.setIndexing(indexingConfig);

            Configuration.ContextConfig contextConfig = new Configuration.ContextConfig();
            contextConfig.setMaxFiles(10);
            mockConfig.setContext(contextConfig);

            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);

            ContextEngine engine = ContextEngine.getInstance();

            // Call reindex explicitly since we disabled auto-indexing
            ContextEngine.ReindexSummary summary = engine.reindex();

            assertThat(summary.getTotal()).isGreaterThan(0);
            assertThat(outputCapture.getAllOutput())
                    .as("reindexing reports to its caller, not to the console")
                    .doesNotContain("Reindexing completed")
                    .doesNotContain("Indexed file");
        } finally {
            // Restore original working directory
            System.setProperty("user.dir", oldWorkingDir);
        }
    }

    @Test
    public void testReindex_WithExcludedDirectories() throws Exception {
        String oldWorkingDir = System.getProperty("user.dir");

        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            // Create excluded directories
            new File(testDir, ".git").mkdirs();
            new File(testDir, "node_modules").mkdirs();
            new File(testDir, ".cadet").mkdirs();

            // Set working directory to our test directory
            System.setProperty("user.dir", testDir.getAbsolutePath());

            // Mock config
            ConfigManager                mockConfigManager = mock(ConfigManager.class);
            Configuration                mockConfig        = new Configuration();
            Configuration.IndexingConfig indexingConfig    = new Configuration.IndexingConfig();
            indexingConfig.setEnabled(false); // We'll call reindex manually
            indexingConfig.setIndexLocation(tempFolder.newFolder("index-" + System.nanoTime()).getAbsolutePath());
            indexingConfig.setExcludePatterns(new String[] {".git", "node_modules"});
            mockConfig.setIndexing(indexingConfig);

            Configuration.ContextConfig contextConfig = new Configuration.ContextConfig();
            contextConfig.setMaxFiles(10);
            mockConfig.setContext(contextConfig);

            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);

            // Content the exclusions must keep out, asserted against the index itself rather than
            // against a per-file console line that no longer exists (and that only ever proved
            // what had been printed, not what had been indexed).
            Files.writeString(new File(testDir, ".git/config").toPath(), "excludedgitmarker");
            Files.writeString(new File(testDir, "node_modules/pkg.js").toPath(), "excludednodemarker");
            Files.writeString(new File(testDir, ".cadet/notes.txt").toPath(), "excludedcadetmarker");

            ContextEngine engine  = ContextEngine.getInstance();
            ContextEngine.ReindexSummary summary = engine.reindex();

            assertThat(summary.getTotal()).isGreaterThan(0);
            assertThat(engine.search("excludedgitmarker")).isEmpty();
            assertThat(engine.search("excludednodemarker")).isEmpty();
            assertThat(engine.search("excludedcadetmarker")).isEmpty();
        } finally {
            // Restore original working directory
            System.setProperty("user.dir", oldWorkingDir);
        }
    }

    @Test
    public void testIndexFile_LargeFile() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            // Mock config
            ConfigManager                mockConfigManager = mock(ConfigManager.class);
            Configuration                mockConfig        = new Configuration();
            Configuration.IndexingConfig indexingConfig    = new Configuration.IndexingConfig();
            indexingConfig.setEnabled(false);
            indexingConfig.setIndexLocation(tempFolder.newFolder("index-" + System.nanoTime()).getAbsolutePath());
            mockConfig.setIndexing(indexingConfig);

            Configuration.ContextConfig contextConfig = new Configuration.ContextConfig();
            contextConfig.setMaxFiles(10);
            mockConfig.setContext(contextConfig);

            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);

            ContextEngine engine = ContextEngine.getInstance();

            // Create a large file (will be skipped in discovery but can be manually indexed)
            File          largeFile = new File(testDir, "large.txt");
            StringBuilder content   = new StringBuilder();
            for (int i = 0; i < 10000; i++) {
                content.append("This is line ").append(i).append(" of a large file.\n");
            }
            Files.writeString(largeFile.toPath(), content.toString());

            // Index it manually (should work even if large)
            engine.indexFile(largeFile);

            assertThat(engine.search("large")).isNotEmpty();
            assertThat(outputCapture.getAllOutput()).doesNotContain("Indexed file");
        }
    }

    /**
     * The scan the constructor used to run is now the caller's to ask for, so {@code index} pays
     * for exactly one and a command that only wants to search pays for it once, lazily.
     */
    @Test
    public void constructionDoesNotIndexAnything() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            System.setProperty("user.dir", testDir.getAbsolutePath());
            ConfigManager mockConfigManager = mock(ConfigManager.class);
            Configuration mockConfig        = new Configuration();
            Configuration.IndexingConfig indexingConfig = new Configuration.IndexingConfig();
            indexingConfig.setEnabled(true);
            indexingConfig.setIndexLocation(tempFolder.newFolder("index-" + System.nanoTime()).getAbsolutePath());
            indexingConfig.setExcludePatterns(new String[] {".git", "node_modules"});
            mockConfig.setIndexing(indexingConfig);
            mockConfig.setContext(new Configuration.ContextConfig());
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);

            ContextEngine engine = ContextEngine.getInstance();

            // If construction had indexed the project, this first explicit pass would find every
            // file already current and report it as unchanged -- which is exactly what `index` used
            // to do, scanning the whole project twice for one command.
            ContextEngine.ReindexSummary first = engine.reindex();
            assertThat(first.getIndexed()).as("the caller's reindex is the first one").isGreaterThan(0);
            assertThat(first.getUnchanged()).isZero();
        }
    }

    /** A command that only wants to search still gets an up-to-date index, without asking. */
    @Test
    public void aSearchIndexesOnceByItself() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            System.setProperty("user.dir", testDir.getAbsolutePath());
            ConfigManager mockConfigManager = mock(ConfigManager.class);
            Configuration mockConfig        = new Configuration();
            Configuration.IndexingConfig indexingConfig = new Configuration.IndexingConfig();
            indexingConfig.setEnabled(true);
            indexingConfig.setIndexLocation(tempFolder.newFolder("index-" + System.nanoTime()).getAbsolutePath());
            indexingConfig.setExcludePatterns(new String[] {".git", "node_modules"});
            mockConfig.setIndexing(indexingConfig);
            mockConfig.setContext(new Configuration.ContextConfig());
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);

            ContextEngine engine = ContextEngine.getInstance();

            assertThat(engine.search("Main")).as("indexed on demand").isNotEmpty();
            // Once, though: a second search does not scan the project again.
            assertThat(engine.reindex().getIndexed()).isZero();
        }
    }

    /** A second pass finds nothing to do, so a caller can tell a real reindex from a no-op. */
    @Test
    public void reindexReportsWhatItDid() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            System.setProperty("user.dir", testDir.getAbsolutePath());
            ConfigManager mockConfigManager = mock(ConfigManager.class);
            Configuration mockConfig        = new Configuration();
            Configuration.IndexingConfig indexingConfig = new Configuration.IndexingConfig();
            indexingConfig.setEnabled(true);
            indexingConfig.setIndexLocation(tempFolder.newFolder("index-" + System.nanoTime()).getAbsolutePath());
            indexingConfig.setExcludePatterns(new String[] {".git", "node_modules"});
            mockConfig.setIndexing(indexingConfig);
            mockConfig.setContext(new Configuration.ContextConfig());
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);

            ContextEngine engine = ContextEngine.getInstance();

            ContextEngine.ReindexSummary first = engine.reindex();
            assertThat(first.getIndexed()).isGreaterThan(0);
            assertThat(first.getUnchanged()).isZero();
            assertThat(first.describe()).contains("indexed");

            ContextEngine.ReindexSummary second = engine.reindex();
            assertThat(second.getIndexed()).isZero();
            assertThat(second.getUnchanged()).isEqualTo(first.getIndexed());
            assertThat(second.describe()).contains("all unchanged");
        }
    }

    /**
     * An index written by an incompatible Lucene used to fail every command that opened it, with a
     * sentence about a missing JAR. It is a derived cache of files still on disk, so it is thrown
     * away and rebuilt.
     */
    @Test
    public void anUnreadableIndexIsRebuiltInsteadOfFailing() throws Exception {
        File indexDir = tempFolder.newFolder("index-broken-" + System.nanoTime());
        Files.writeString(new File(indexDir, "segments_1").toPath(), "not a lucene segments file");
        Files.writeString(new File(indexDir, "_0.cfs").toPath(), "rubbish");

        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            System.setProperty("user.dir", testDir.getAbsolutePath());
            ConfigManager mockConfigManager = mock(ConfigManager.class);
            Configuration mockConfig        = new Configuration();
            Configuration.IndexingConfig indexingConfig = new Configuration.IndexingConfig();
            indexingConfig.setEnabled(true);
            indexingConfig.setIndexLocation(indexDir.getAbsolutePath());
            indexingConfig.setExcludePatterns(new String[] {".git", "node_modules"});
            mockConfig.setIndexing(indexingConfig);
            mockConfig.setContext(new Configuration.ContextConfig());
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);

            ContextEngine engine = ContextEngine.getInstance();

            assertThat(engine.wasIndexRebuilt()).isTrue();
            assertThat(engine.reindex().getIndexed()).isGreaterThan(0);
            assertThat(engine.search("Main")).isNotEmpty();
        }
    }
}