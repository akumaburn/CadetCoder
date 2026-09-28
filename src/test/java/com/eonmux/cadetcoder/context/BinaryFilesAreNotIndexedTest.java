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
 * Compiled output is not project context.
 *
 * <p>{@code ContextEngine} decided what to index on three questions -- is it hidden, is it under
 * half a megabyte, is it a credential file -- and none of them asks whether the bytes are text. So
 * {@code target/classes/Main.class}, a bundled {@code .so}, a {@code .png} and every small
 * {@code .jar} were read with {@code new String(bytes)}, stored whole in the Lucene index, and
 * handed back as snippets by {@code search} -- and spliced into the prompt by {@code edit} and
 * {@code agent}. The user pays for those tokens, and a constant pool is not an answer to anything.
 *
 * <p>{@code grep} has known the difference all along: {@code GrepCommand} carried a list of 34
 * binary extensions and a content sniff privately, so the same project directory was searched by
 * one command and swallowed whole by another. The rule now lives in {@code TextFiles}, once.</p>
 */
public class BinaryFilesAreNotIndexedTest {

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

    private void writeText(String name, String contents) throws IOException {
        File file = new File(projectDir, name);
        Files.createDirectories(file.toPath().getParent());
        Files.writeString(file.toPath(), contents);
    }

    /** Writes a file whose bytes are binary but which contains {@code marker} as readable ASCII. */
    private void writeBinary(String name, byte[] magic, String marker) throws IOException {
        File file = new File(projectDir, name);
        Files.createDirectories(file.toPath().getParent());
        byte[] text = (" " + marker + " ").getBytes(StandardCharsets.US_ASCII);
        byte[] tail = new byte[] {0, 0, 0, 0, 0, 0, 0, 0};
        byte[] all  = new byte[magic.length + text.length + tail.length];
        System.arraycopy(magic, 0, all, 0, magic.length);
        System.arraycopy(text, 0, all, magic.length, text.length);
        System.arraycopy(tail, 0, all, magic.length + text.length, tail.length);
        Files.write(file.toPath(), all);
    }

    /**
     * Builds a configuration whose index lives in its own temporary directory.
     *
     * @return the mock control, to be closed by the caller
     */
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
    public void compiledAndPackagedOutputIsNotIndexed() throws Exception {
        try (MockedStatic<ConfigManager> ignored = configuredEngine()) {
            writeBinary("target/classes/Main.class",
                        new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE},
                        "classbinarymarker");
            writeBinary("lib/native.so", new byte[] {0x7F, 'E', 'L', 'F'}, "sharedobjectmarker");
            writeBinary("target/app.jar", new byte[] {'P', 'K', 3, 4}, "jarbinarymarker");
            writeText("src/Main.java", "class Main { /* javasourcemarker */ }\n");

            ContextEngine engine = ContextEngine.getInstance();
            engine.reindex();

            assertThat(engine.search("classbinarymarker"))
                    .as("a constant pool is not context; edit and agent splice indexed snippets "
                        + "straight into the prompt")
                    .isEmpty();
            assertThat(engine.search("sharedobjectmarker")).isEmpty();
            assertThat(engine.search("jarbinarymarker")).isEmpty();
            assertThat(engine.search("javasourcemarker"))
                    .as("source must still be indexed")
                    .isNotEmpty();
        }
    }

    @Test
    public void anImageIsNotIndexed() throws Exception {
        try (MockedStatic<ConfigManager> ignored = configuredEngine()) {
            writeBinary("docs/assets/logo.png",
                        new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'},
                        "pngbinarymarker");
            writeText("docs/README.md", "the logo is at assets/logo.png -- documentmarker\n");

            ContextEngine engine = ContextEngine.getInstance();
            engine.reindex();

            assertThat(engine.search("pngbinarymarker")).isEmpty();
            assertThat(engine.search("documentmarker"))
                    .as("documentation must still be indexed")
                    .isNotEmpty();
        }
    }

    /**
     * Whatever the shared rule refuses, the index refuses.
     *
     * <p>Driven off {@link com.eonmux.cadetcoder.util.TextFiles#binaryExtensions()} rather than a
     * written-out list, because a written-out list is what drifted: {@code grep} had 34 of these
     * and the index had none, and nothing said so.</p>
     */
    @Test
    public void everyExtensionTheSharedRuleRefusesIsKeptOutOfTheIndex() throws Exception {
        try (MockedStatic<ConfigManager> ignored = configuredEngine()) {
            for (String extension : com.eonmux.cadetcoder.util.TextFiles.binaryExtensions()) {
                writeBinary("build/artifact." + extension, new byte[] {0x7F, 'E', 'L', 'F'},
                            "familymarker" + extension);
            }
            writeText("src/Kept.java", "class Kept { /* keptsourcemarker */ }\n");

            ContextEngine engine = ContextEngine.getInstance();
            engine.reindex();

            for (String extension : com.eonmux.cadetcoder.util.TextFiles.binaryExtensions()) {
                assertThat(engine.search("familymarker" + extension))
                        .as(".%s is on the shared binary list and must not reach the index", extension)
                        .isEmpty();
            }
            assertThat(engine.search("keptsourcemarker"))
                    .as("the guard must not be vacuously satisfied by an empty index")
                    .isNotEmpty();
        }
    }

    /**
     * The extension list is a fast path, not the whole rule.
     *
     * <p>A build produces plenty of binaries whose names say nothing -- {@code a.out}, a stripped
     * executable, a serialized cache. The bytes have to be looked at.</p>
     */
    @Test
    public void binaryContentIsNotIndexedEvenWhenTheNameSaysNothing() throws Exception {
        try (MockedStatic<ConfigManager> ignored = configuredEngine()) {
            writeBinary("build/a.out", new byte[] {0x7F, 'E', 'L', 'F', 2, 1, 1, 0},
                        "unnamedbinarymarker");
            writeText("build/Makefile", "all:\n\t@echo makefilemarker\n");

            ContextEngine engine = ContextEngine.getInstance();
            engine.reindex();

            assertThat(engine.search("unnamedbinarymarker")).isEmpty();
            assertThat(engine.search("makefilemarker"))
                    .as("a text file with no extension at all is still text")
                    .isNotEmpty();
        }
    }
}
