package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

public class GlobCommandTest {

    /**
     * A fixed past moment the time-sorted fixtures are spaced out from.
     *
     * <p>In the past, so nothing depends on how long the test itself takes, and far enough back
     * that "just now" formatting is not being relied on either way.</p>
     */
    private static final long FIXED_START_MILLIS = 1_600_000_000_000L;

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private GlobCommand       globCommand;
    private TestOutputCapture outputCapture;

    @Before
    public void setUp() {
        globCommand   = new GlobCommand();
        outputCapture = new TestOutputCapture();
        outputCapture.startCapture();
    }

    @After
    public void tearDown() {
        outputCapture.stopCapture();
    }

    @Test
    public void testGlob_NoPattern() {
        int exitCode = globCommand.execute(new String[] {});

        assertThat(exitCode).isEqualTo(1);
        assertThat(outputCapture.getStderr()).contains("Missing required parameter");
    }

    @Test
    public void testGlob_InvalidPath() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager mockConfigManager = mock(ConfigManager.class);
            Configuration config            = new Configuration();
            when(mockConfigManager.getConfig()).thenReturn(config);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            int exitCode = globCommand.execute(new String[] {"*.txt", "-p", "/nonexistent/path"});

            assertThat(exitCode).isEqualTo(1);
            assertThat(outputCapture.getAllOutput()).contains("Path does not exist");
        }
    }

    @Test
    public void testGlob_NoMatches() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager mockConfigManager = mock(ConfigManager.class);
            Configuration config            = new Configuration();
            when(mockConfigManager.getConfig()).thenReturn(config);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            int exitCode =
                    globCommand.execute(new String[] {"*.nonexistent", "-p", tempFolder.getRoot().getAbsolutePath()});

            assertThat(exitCode).isEqualTo(0);
            assertThat(outputCapture.getAllOutput()).contains("No files found matching pattern");
        }
    }

    @Test
    public void testGlob_SimplePattern() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager mockConfigManager = mock(ConfigManager.class);
            Configuration config            = new Configuration();
            when(mockConfigManager.getConfig()).thenReturn(config);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            // Create test files
            tempFolder.newFile("test1.txt");
            tempFolder.newFile("test2.txt");
            tempFolder.newFile("test.java");

            int exitCode = globCommand.execute(new String[] {"*.txt", "-p", tempFolder.getRoot().getAbsolutePath()});

            assertThat(exitCode).isEqualTo(0);
            String output = outputCapture.getAllOutput();
            assertThat(output).contains("test1.txt");
            assertThat(output).contains("test2.txt");
            assertThat(output).doesNotContain("test.java");
            assertThat(output).contains("Found 2 matches");
        }
    }

    @Test
    public void testGlob_RecursivePattern() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager mockConfigManager = mock(ConfigManager.class);
            Configuration config            = new Configuration();
            when(mockConfigManager.getConfig()).thenReturn(config);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            // Create nested directories with files
            tempFolder.newFile("root.java");
            tempFolder.newFolder("src");
            tempFolder.newFile("src/Main.java");
            tempFolder.newFolder("src", "test");
            tempFolder.newFile("src/test/Test.java");

            int exitCode =
                    globCommand.execute(new String[] {"**/*.java", "-p", tempFolder.getRoot().getAbsolutePath()});

            assertThat(exitCode).isEqualTo(0);
            String output = outputCapture.getAllOutput();
            assertThat(output).contains("root.java");
            assertThat(output).contains("Main.java");
            assertThat(output).contains("Test.java");
            assertThat(output).contains("Found 3 matches");
        }
    }

    @Test
    public void testGlob_WithLimit() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager mockConfigManager = mock(ConfigManager.class);
            Configuration config            = new Configuration();
            when(mockConfigManager.getConfig()).thenReturn(config);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            // Created a second apart, in the order the assertions below depend on. glob's default
            // sort is by modification time, and five files written in a loop can share one -- on a
            // filesystem whose mtime granularity is coarser than the loop is fast, they always do.
            // The order of a tie is then arbitrary, and this test failed roughly one run in twenty
            // with file1 among the three shown. The subject here is what --limit withholds, not how
            // ties are broken, so the times are made distinct rather than the assertion vague.
            for (int i = 1; i <= 5; i++) {
                java.io.File created = tempFolder.newFile("file" + i + ".txt");
                assertThat(created.setLastModified(FIXED_START_MILLIS + i * 1000L)).isTrue();
            }

            int exitCode = globCommand.execute(new String[] {"*.txt",
                                                             "-p",
                                                             tempFolder.getRoot().getAbsolutePath(),
                                                             "-l",
                                                             "3"});

            assertThat(exitCode).isEqualTo(0);
            String output = outputCapture.getAllOutput();
            // The count says what the SEARCH found, not how much of it was printed. It used to
            // report the post-truncation size, so `-l 3` over five matching files ended with
            // "Found 3 matches" -- and over five thousand, with "Found 3 matches" as well.
            assertThat(output).contains("Found 5 matches")
                              .doesNotContain("Found 3 matches");
            // What was withheld is said, rather than being left for the user to notice.
            assertThat(output).contains("Showing the first 3; 2 more omitted by --limit");
            // Only the first 3 are shown, and "first" means oldest: the default sort is by time,
            // ascending, and -r is what asks for newest first. This used to expect file5/file4/
            // file3 -- the opposite direction -- and passed only because the five files shared a
            // modification time, which left the order arbitrary rather than newest-first.
            assertThat(output).contains("file1.txt").contains("file2.txt").contains("file3.txt")
                              .doesNotContain("file4.txt").doesNotContain("file5.txt");
        }
    }

    /** With nothing withheld there is nothing to say about it. */
    @Test
    public void alimitNothingReachedIsNotMentioned() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager mockConfigManager = mock(ConfigManager.class);
            Configuration config            = new Configuration();
            when(mockConfigManager.getConfig()).thenReturn(config);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            for (int i = 1; i <= 2; i++) {
                tempFolder.newFile("file" + i + ".txt");
            }

            globCommand.execute(new String[] {"*.txt", "-p",
                                              tempFolder.getRoot().getAbsolutePath(), "-l", "9"});

            String output = outputCapture.getAllOutput();
            assertThat(output).contains("Found 2 matches").doesNotContain("omitted by --limit");
        }
    }

    @Test
    public void testGlob_IncludeDirectories() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager mockConfigManager = mock(ConfigManager.class);
            Configuration config            = new Configuration();
            when(mockConfigManager.getConfig()).thenReturn(config);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            // Create directories and files
            tempFolder.newFolder("src");
            tempFolder.newFolder("test");
            tempFolder.newFile("file.txt");

            int exitCode =
                    globCommand.execute(new String[] {"*", "-p", tempFolder.getRoot().getAbsolutePath(), "-d"});

            assertThat(exitCode).isEqualTo(0);
            String output = outputCapture.getAllOutput();
            assertThat(output).contains("src");
            assertThat(output).contains("test");
            assertThat(output).contains("file.txt");
            assertThat(output).contains("[DIR]");
        }
    }

    @Test
    public void testGlob_SortByName() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager mockConfigManager = mock(ConfigManager.class);
            Configuration config            = new Configuration();
            when(mockConfigManager.getConfig()).thenReturn(config);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            // Create files with different names
            tempFolder.newFile("zebra.txt");
            tempFolder.newFile("alpha.txt");
            tempFolder.newFile("beta.txt");

            int exitCode = globCommand.execute(new String[] {"*.txt",
                                                             "-p",
                                                             tempFolder.getRoot().getAbsolutePath(),
                                                             "-s",
                                                             "name"});

            assertThat(exitCode).isEqualTo(0);
            String output = outputCapture.getAllOutput();
            // Check that files appear in alphabetical order
            int alphaIndex = output.indexOf("alpha.txt");
            int betaIndex  = output.indexOf("beta.txt");
            int zebraIndex = output.indexOf("zebra.txt");
            assertThat(alphaIndex).isLessThan(betaIndex);
            assertThat(betaIndex).isLessThan(zebraIndex);
        }
    }

    @Test
    public void testGlob_MaxDepth() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager mockConfigManager = mock(ConfigManager.class);
            Configuration config            = new Configuration();
            when(mockConfigManager.getConfig()).thenReturn(config);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            // Create nested structure
            tempFolder.newFile("level0.txt");
            tempFolder.newFolder("dir1");
            tempFolder.newFile("dir1/level1.txt");
            tempFolder.newFolder("dir1", "dir2");
            tempFolder.newFile("dir1/dir2/level2.txt");

            int exitCode = globCommand.execute(new String[] {"**/*.txt",
                                                             "-p",
                                                             tempFolder.getRoot().getAbsolutePath(),
                                                             "--max-depth",
                                                             "2"});

            assertThat(exitCode).isEqualTo(0);
            String output = outputCapture.getAllOutput();
            assertThat(output).contains("level0.txt");
            assertThat(output).contains("level1.txt");
            assertThat(output).doesNotContain("level2.txt");
        }
    }

    @Test
    public void testGlob_CallableDispatchDoesNotThrow() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager mockConfigManager = mock(ConfigManager.class);
            Configuration config            = new Configuration();
            when(mockConfigManager.getConfig()).thenReturn(config);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            tempFolder.newFile("dispatch.txt");

            // Exercise the picocli Callable path (parses args, then invokes call()).
            // Previously call() did execute(null) which threw NPE in parseArgs(null) -> exit 1.
            int exitCode = new picocli.CommandLine(globCommand)
                    .execute("*.txt", "-p", tempFolder.getRoot().getAbsolutePath());

            assertThat(exitCode).isEqualTo(0);
            assertThat(outputCapture.getAllOutput()).contains("dispatch.txt");
        }
    }

    @Test
    public void testGlob_MatchesInsideExplicitlyProvidedHiddenStartDirectory() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager mockConfigManager = mock(ConfigManager.class);
            Configuration config            = new Configuration();
            when(mockConfigManager.getConfig()).thenReturn(config);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            // A hidden directory passed explicitly as the search root must be descended into.
            java.nio.file.Path hiddenDir = tempFolder.newFolder(".github").toPath();
            java.nio.file.Files.writeString(hiddenDir.resolve("ci.yml"), "name: ci");

            int exitCode = globCommand.execute(new String[] {"*.yml", "-p", hiddenDir.toString()});

            assertThat(exitCode).isEqualTo(0);
            String output = outputCapture.getAllOutput();
            assertThat(output).contains("ci.yml");
            assertThat(output).doesNotContain("No files found matching pattern");
        }
    }

    @Test
    public void testGlob_NegativeMaxDepthRejected() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager mockConfigManager = mock(ConfigManager.class);
            Configuration config            = new Configuration();
            when(mockConfigManager.getConfig()).thenReturn(config);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            tempFolder.newFile("a.txt");

            int exitCode = globCommand.execute(new String[] {"*.txt",
                                                             "-p",
                                                             tempFolder.getRoot().getAbsolutePath(),
                                                             "--max-depth",
                                                             "-1"});

            assertThat(exitCode).isEqualTo(1);
            String output = outputCapture.getAllOutput();
            assertThat(output).contains("Invalid --max-depth");
            assertThat(output).contains("-1");
        }
    }

    @Test
    public void testGlob_MaxDepthZeroSearchesStartDirectoryOnly() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager mockConfigManager = mock(ConfigManager.class);
            Configuration config            = new Configuration();
            when(mockConfigManager.getConfig()).thenReturn(config);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            // With max-depth 0 (start directory only) no files below the root are visited,
            // so a file living directly in the start directory is not matched.
            tempFolder.newFile("top.txt");

            int exitCode = globCommand.execute(new String[] {"*.txt",
                                                             "-p",
                                                             tempFolder.getRoot().getAbsolutePath(),
                                                             "--max-depth",
                                                             "0"});

            assertThat(exitCode).isEqualTo(0);
            String output = outputCapture.getAllOutput();
            assertThat(output).contains("No files found matching pattern");
            assertThat(output).doesNotContain("top.txt");
        }
    }

    @Test
    public void testGlob_SingleStarIsNotRecursive() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager mockConfigManager = mock(ConfigManager.class);
            Configuration config            = new Configuration();
            when(mockConfigManager.getConfig()).thenReturn(config);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            // A single-star pattern matches within one path component only: it must match the
            // top-level file but NOT the same-extension file nested in a subdirectory.
            tempFolder.newFile("top.java");
            tempFolder.newFolder("nested");
            tempFolder.newFile("nested/Deep.java");

            int exitCode = globCommand.execute(new String[] {"*.java",
                                                             "-p",
                                                             tempFolder.getRoot().getAbsolutePath()});

            assertThat(exitCode).isEqualTo(0);
            String output = outputCapture.getAllOutput();
            assertThat(output).contains("top.java");
            assertThat(output).doesNotContain("Deep.java");
            assertThat(output).contains("Found 1 match");
        }
    }

    @Test
    public void testGlob_DoubleStarMatchesStartDirectoryAndNested() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager mockConfigManager = mock(ConfigManager.class);
            Configuration config            = new Configuration();
            when(mockConfigManager.getConfig()).thenReturn(config);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            // The documented catalog example: **/*.java must match both a top-level file and a
            // nested file, demonstrating ** crosses directory boundaries (including the root).
            tempFolder.newFile("top.java");
            tempFolder.newFolder("pkg");
            tempFolder.newFile("pkg/Deep.java");

            int exitCode = globCommand.execute(new String[] {"**/*.java",
                                                             "-p",
                                                             tempFolder.getRoot().getAbsolutePath()});

            assertThat(exitCode).isEqualTo(0);
            String output = outputCapture.getAllOutput();
            assertThat(output).contains("top.java");
            assertThat(output).contains("Deep.java");
            assertThat(output).contains("Found 2 matches");
        }
    }

    @Test
    public void testGlob_SortByNameDoesNotThrowAndOrders() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager mockConfigManager = mock(ConfigManager.class);
            Configuration config            = new Configuration();
            when(mockConfigManager.getConfig()).thenReturn(config);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            // Regression: the name comparator must be null-safe (a path with no file name yields
            // a null getFileName()). Exercising name sort over normal files must not throw and
            // must order alphabetically.
            tempFolder.newFile("c.txt");
            tempFolder.newFile("a.txt");
            tempFolder.newFile("b.txt");

            int exitCode = globCommand.execute(new String[] {"*.txt",
                                                             "-p",
                                                             tempFolder.getRoot().getAbsolutePath(),
                                                             "-s",
                                                             "name"});

            assertThat(exitCode).isEqualTo(0);
            String output = outputCapture.getAllOutput();
            assertThat(output.indexOf("a.txt")).isLessThan(output.indexOf("b.txt"));
            assertThat(output.indexOf("b.txt")).isLessThan(output.indexOf("c.txt"));
        }
    }

    @Test
    public void testGlob_GenericFailureReportedToUser() {
        // Force a generic (non-IO, non-pattern) failure by leaving ConfigManager unmocked so
        // getConfig()/getInstance() throws inside the try block. The generic catch must surface
        // the failure through the standard error channel rather than silently swallowing it.
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            configMock.when(ConfigManager::getInstance)
                      .thenThrow(new IllegalStateException("config unavailable"));

            int exitCode = globCommand.execute(new String[] {"*.txt",
                                                             "-p",
                                                             tempFolder.getRoot().getAbsolutePath()});

            assertThat(exitCode).isEqualTo(1);
            assertThat(outputCapture.getAllOutput()).contains("Glob command failed");
        }
    }

    @Test
    public void testGetDescription() {
        assertThat(globCommand.getDescription()).isEqualTo("Find files matching glob patterns");
    }

    @Test
    public void testGetUsage() {
        assertThat(globCommand.getUsage()).contains("glob");
    }
}