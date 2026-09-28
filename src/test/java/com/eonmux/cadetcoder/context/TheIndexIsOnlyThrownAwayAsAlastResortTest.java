package com.eonmux.cadetcoder.context;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.test.TestOutputCapture;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * What it takes before the search index is emptied.
 *
 * <p><b>The defect</b>: refreshing the searcher closed the old reader and opened a new one inside
 * one {@code try}, and the recovery for that {@code try} was {@code deleteAll}. So a failure to
 * CLOSE the old reader -- which says nothing whatever about whether a new one can be opened -- went
 * straight to emptying the index, before opening had been attempted even once. The index is only
 * derived from the project, so nothing is lost for good; what is lost is the time to reindex the
 * whole of it, over a leaked file handle.</p>
 */
public class TheIndexIsOnlyThrownAwayAsAlastResortTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private TestOutputCapture output;
    private String            wasWorkingDir;
    private File              project;

    @Before
    public void setUp() throws Exception {
        output        = new TestOutputCapture();
        wasWorkingDir = System.getProperty("user.dir");
        project       = folder.newFolder("test-project");
    }

    @After
    public void tearDown() {
        output.restore();
        if (wasWorkingDir != null) {
            System.setProperty("user.dir", wasWorkingDir);
        }
        ContextEngine.resetInstance();
    }

    private ContextEngine engineOverAnIndexOf(String content) throws Exception {
        ConfigManager                manager  = mock(ConfigManager.class);
        Configuration                config   = new Configuration();
        Configuration.IndexingConfig indexing = new Configuration.IndexingConfig();
        indexing.setEnabled(false);
        indexing.setIndexLocation(folder.newFolder("index-" + System.nanoTime()).getAbsolutePath());
        config.setIndexing(indexing);
        Configuration.ContextConfig context = new Configuration.ContextConfig();
        context.setMaxFiles(10);
        config.setContext(context);

        when(manager.getConfig()).thenReturn(config);
        try (MockedStatic<ConfigManager> configured = mockStatic(ConfigManager.class)) {
            configured.when(ConfigManager::getInstance).thenReturn(manager);

            ContextEngine engine = ContextEngine.getInstance();
            File          file   = new File(project, "indexed.txt");
            Files.writeString(file.toPath(), content);
            engine.indexFile(file);
            return engine;
        }
    }

    private static void refresh(ContextEngine engine) throws Exception {
        Method refreshSearcher = ContextEngine.class.getDeclaredMethod("refreshSearcher");
        refreshSearcher.setAccessible(true);
        refreshSearcher.invoke(engine);
    }

    private static Object field(ContextEngine engine, String named) throws Exception {
        Field field = ContextEngine.class.getDeclaredField(named);
        field.setAccessible(true);
        return field.get(engine);
    }

    private static void setField(ContextEngine engine, String named, Object value) throws Exception {
        Field field = ContextEngine.class.getDeclaredField(named);
        field.setAccessible(true);
        field.set(engine, value);
    }

    @Test
    public void areaderThatWillNotCloseDoesNotCostTheWholeIndex() throws Exception {
        ContextEngine engine = engineOverAnIndexOf("a sentence only this document contains");
        IndexWriter   writer = (IndexWriter) field(engine, "writer");
        int           before = writer.getDocStats().numDocs;
        assertThat(before).isPositive();

        DirectoryReader wontClose = mock(DirectoryReader.class);
        doThrow(new IOException("this handle is stuck")).when(wontClose).close();
        setField(engine, "reader", wontClose);

        refresh(engine);

        assertThat(writer.getDocStats().numDocs)
                .as("the index is still there; only the old reader was in trouble")
                .isEqualTo(before);
        assertThat(field(engine, "searcher"))
                .as("and the searcher was opened all the same")
                .isNotNull();
    }
}
