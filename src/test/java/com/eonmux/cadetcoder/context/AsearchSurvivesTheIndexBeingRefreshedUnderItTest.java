package com.eonmux.cadetcoder.context;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.test.TestOutputCapture;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * A worker searching the index must not be broken by another worker refreshing it.
 *
 * <p><b>The defect</b>: {@code ContextEngine} is a singleton and up to eight workers reach it at
 * once through {@code AgentPrompts.searchRelevantSnippets}. A search read the {@code searcher}
 * field and then used it across several calls -- one to score the query and one per hit to fetch
 * the stored document -- while {@code refreshSearcher} closed that very reader and put another in
 * its place from whichever thread had just indexed a file or reached the refresh interval. Lucene
 * answers a read through a closed reader with an {@code AlreadyClosedException}, the caller turns
 * that into "Could not retrieve context", and the worker's entire turn then went to the model with
 * no project context in it at all. Nothing said so: the run looks exactly like a project with
 * nothing relevant in it.</p>
 *
 * <p>What is locked here is that a search and a refresh can overlap without either one failing --
 * the search finishes on the segments it started with, and the refresh is not made to wait for
 * it.</p>
 */
public class AsearchSurvivesTheIndexBeingRefreshedUnderItTest {

    /**
     * Searches run against the refreshing thread.
     *
     * <p>Kept small on purpose: a refresh is a Lucene commit, which is an fsync, and the searches
     * queue behind it. The unguarded version failed on one of the first few, so a handful is enough
     * to show it and two hundred only makes the suite slower.</p>
     */
    private static final int SEARCHES = 10;

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
        // An engine another test left behind holds the real project's index rather than this one.
        ContextEngine.resetInstance();
    }

    @After
    public void tearDown() {
        output.restore();
        if (wasWorkingDir != null) {
            System.setProperty("user.dir", wasWorkingDir);
        }
        ContextEngine.resetInstance();
    }

    /**
     * A manager whose index is this test's, indexed once and then left alone.
     *
     * <p>{@code refreshIntervalMinutes} of zero means a search never triggers a reindex of its own,
     * so the only thing refreshing the searcher is the thread this test starts for the purpose.</p>
     */
    private ConfigManager configuredManager() {
        ConfigManager manager = mock(ConfigManager.class);
        Configuration config  = new Configuration();
        Configuration.IndexingConfig indexing = new Configuration.IndexingConfig();
        indexing.setEnabled(true);
        indexing.setIndexLocation(indexDirectory.getAbsolutePath());
        indexing.setRefreshIntervalMinutes(0);
        config.setIndexing(indexing);
        when(manager.getConfig()).thenReturn(config);
        return manager;
    }

    @Test
    public void asearchRunningWhileTheIndexIsRefreshedStillAnswers() throws Exception {
        File source = new File(project, "Main.java");
        Files.writeString(source.toPath(),
                          "public class Main { void search() { relevant(); } }");

        // Mockito's static mock belongs to the thread that installs it, so everything that consults
        // the configuration stays on this one. The refreshing thread below touches only the writer.
        // Built before the static stub is written: Mockito reads a mock set up inside the
        // argument of thenReturn() as an unfinished stubbing of the outer call.
        ConfigManager manager = configuredManager();
        try (MockedStatic<ConfigManager> configured = mockStatic(ConfigManager.class)) {
            configured.when(ConfigManager::getInstance).thenReturn(manager);

            ContextEngine engine = ContextEngine.getInstance();
            engine.reindex();

            AtomicBoolean            keepRefreshing = new AtomicBoolean(true);
            AtomicReference<Throwable> refreshFailure = new AtomicReference<>();
            Thread refresher = new Thread(() -> {
                while (keepRefreshing.get()) {
                    try {
                        // Every one of these closes the reader the searches are reading through and
                        // opens another: this is what an indexing worker does to a searching one.
                        engine.indexFile(source);
                    } catch (Throwable failed) {
                        refreshFailure.compareAndSet(null, failed);
                        return;
                    }
                }
            }, "index-refresher");
            refresher.setDaemon(true);
            refresher.start();

            try {
                for (int attempt = 0; attempt < SEARCHES; attempt++) {
                    List<String> snippets = engine.searchRelevantSnippets("relevant search");

                    assertThat(snippets)
                            .as("a search interrupted by a refresh answers from the index it "
                                + "started on rather than failing the worker's whole turn")
                            .isNotEmpty();
                }
            } finally {
                keepRefreshing.set(false);
                refresher.join(10_000);
            }

            assertThat(refreshFailure.get())
                    .as("refreshing the index must not be broken by the searches either")
                    .isNull();
        }
    }
}
