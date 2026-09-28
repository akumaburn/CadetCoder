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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * What the index holds is what the file holds.
 *
 * <p>Both of {@code ContextEngine}'s indexing methods decoded a file with {@code new String(bytes)},
 * which is the platform default charset -- US-ASCII on a JVM started under {@code LANG=C}, which is
 * what a container, a cron entry and most CI runners give a process. Every accented letter and every
 * CJK character in the project became U+FFFD on the way into Lucene, so {@code search} could not
 * find them and the snippets it stored were handed to the provider, by {@code edit} and
 * {@code agent}, as the user's own code.</p>
 *
 * <p>These assertions hold on a JVM whose default charset is already UTF-8 either way, so they are a
 * lock on the behaviour rather than the instrument that failed before the fix; that instrument is
 * {@code OneTextDecodingRuleTest.nothingBuildsTextFromFileBytesWithoutNamingTheCharset}, which reads
 * the sources and does not depend on the charset this JVM happened to start with.</p>
 */
public class IndexedTextKeepsItsCharactersTest {

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

    private void write(String name, byte[] contents) throws IOException {
        File file = new File(projectDir, name);
        Files.createDirectories(file.toPath().getParent());
        Files.write(file.toPath(), contents);
    }

    /** A configuration whose index lives in its own temporary directory. */
    private MockedStatic<ConfigManager> configuredEngine() throws IOException {
        MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
        ConfigManager               manager    = mock(ConfigManager.class);
        Configuration               config     = new Configuration();

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
    public void aNonAsciiIdentifierIsStillFindable() throws Exception {
        try (MockedStatic<ConfigManager> ignored = configuredEngine()) {
            write("src/Grüße.java",
                  "class Gruesse { String begrüßung = \"guten Tag\"; }\n".getBytes(StandardCharsets.UTF_8));
            write("docs/ja.md", "検索できること\n".getBytes(StandardCharsets.UTF_8));

            ContextEngine engine = ContextEngine.getInstance();
            engine.reindex();

            assertThat(engine.search("begrüßung"))
                    .as("the file says begrüßung; the index must say it too")
                    .isNotEmpty();
            assertThat(engine.search("検索できること"))
                    .as("a project is not required to be written in ASCII")
                    .isNotEmpty();
        }
    }

    /**
     * A byte that is not valid UTF-8 costs that byte, and nothing else.
     *
     * <p>A single Latin-1 character in one comment is not a reason for the rest of the file to
     * disappear from the index, which is what a strict decoder would have made of it.</p>
     */
    @Test
    public void oneUndecodableByteDoesNotCostTheWholeFile() throws Exception {
        try (MockedStatic<ConfigManager> ignored = configuredEngine()) {
            byte[] latin1Comment = new byte[] {'/', '/', ' ', 'c', 'a', 'f', (byte) 0xE9, '\n'};
            byte[] rest          = "class Kept { /* survivingmarker */ }\n".getBytes(StandardCharsets.UTF_8);
            byte[] both          = new byte[latin1Comment.length + rest.length];
            System.arraycopy(latin1Comment, 0, both, 0, latin1Comment.length);
            System.arraycopy(rest, 0, both, latin1Comment.length, rest.length);
            write("src/Legacy.java", both);

            ContextEngine engine = ContextEngine.getInstance();
            engine.reindex();

            assertThat(engine.search("survivingmarker"))
                    .as("the bad byte is replaced; everything around it is still indexed")
                    .isNotEmpty();
        }
    }
}
