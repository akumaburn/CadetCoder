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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * A session that outlives the project's last edit still searches what the project now says.
 *
 * <p><b>The defect</b>: {@code ensureIndexed} guarded on a boolean set the first time anything
 * indexed, so an interactive shell indexed the project when it opened and never again. Every file
 * written afterwards -- by the user's editor, or by this tool's own {@code write} and {@code edit}
 * -- was missing from {@code search} and from the context handed to the model, for as long as the
 * session lasted. {@code indexing.refreshIntervalMinutes} named the cure and nothing read it, so
 * there was also no way to ask for one.</p>
 */
public class AlongSessionDoesNotSearchAstaleIndexTest {

    private static final long MINUTE = 60_000L;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private TestOutputCapture output;
    private String            wasWorkingDir;
    private File              project;
    private File              indexDirectory;
    private Configuration.IndexingConfig indexing;

    @Before
    public void setUp() throws Exception {
        output         = new TestOutputCapture();
        wasWorkingDir  = System.getProperty("user.dir");
        project        = folder.newFolder("test-project");
        indexDirectory = folder.newFolder("index-" + System.nanoTime());
        System.setProperty("user.dir", project.getAbsolutePath());
        // An engine another test left behind is one already holding the real project's index, and
        // getInstance() would hand it back rather than open one on this temporary checkout.
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

    private ConfigManager configuredManager(int refreshIntervalMinutes) {
        ConfigManager manager = mock(ConfigManager.class);
        Configuration config  = new Configuration();
        indexing = new Configuration.IndexingConfig();
        indexing.setEnabled(true);
        indexing.setIndexLocation(indexDirectory.getAbsolutePath());
        indexing.setRefreshIntervalMinutes(refreshIntervalMinutes);
        config.setIndexing(indexing);
        Configuration.ContextConfig context = new Configuration.ContextConfig();
        context.setMaxFiles(50);
        config.setContext(context);
        when(manager.getConfig()).thenReturn(config);
        return manager;
    }

    @Test
    public void afileWrittenAfterTheSessionStartedIsFoundOnceTheIntervalHasPassed() throws Exception {
        Files.writeString(project.toPath().resolve("first.txt"), "the opening file");
        ConfigManager manager = configuredManager(60);

        try (MockedStatic<ConfigManager> configured = mockStatic(ConfigManager.class)) {
            configured.when(ConfigManager::getInstance).thenReturn(manager);

            ContextEngine engine = ContextEngine.getInstance();
            engine.ensureIndexed(0L);

            Files.writeString(project.toPath().resolve("second.txt"), "written while the shell was open");

            assertThat(engine.searchRelevantSnippetsAt("shell", 59 * MINUTE))
                    .as("nothing is re-read before the interval the user configured")
                    .isEmpty();

            assertThat(engine.searchRelevantSnippetsAt("shell", 60 * MINUTE))
                    .as("once it has passed, a search sees what the project now holds")
                    .isNotEmpty();
        }
    }

    @Test
    public void anIntervalOfZeroIndexesOnceAndThenLeavesTheProjectAlone() throws Exception {
        Files.writeString(project.toPath().resolve("first.txt"), "the opening file");
        ConfigManager manager = configuredManager(0);

        try (MockedStatic<ConfigManager> configured = mockStatic(ConfigManager.class)) {
            configured.when(ConfigManager::getInstance).thenReturn(manager);

            ContextEngine engine = ContextEngine.getInstance();
            engine.ensureIndexed(0L);
            Files.writeString(project.toPath().resolve("second.txt"), "written while the shell was open");

            assertThat(engine.searchRelevantSnippetsAt("shell", Long.MAX_VALUE / 2))
                    .as("zero means 'index once', which is what the flag used to do to everyone")
                    .isEmpty();
        }
    }

    @Test
    public void indexingSwitchedOffStaysOffHoweverLongTheSessionRuns() throws Exception {
        Files.writeString(project.toPath().resolve("first.txt"), "the opening file");
        ConfigManager manager = configuredManager(1);

        try (MockedStatic<ConfigManager> configured = mockStatic(ConfigManager.class)) {
            configured.when(ConfigManager::getInstance).thenReturn(manager);
            ContextEngine engine = ContextEngine.getInstance();
            indexing.setEnabled(false);

            assertThat(engine.searchRelevantSnippetsAt("opening", 10 * MINUTE))
                    .as("the refresh interval does not switch indexing back on")
                    .isEmpty();
        }
    }
}
