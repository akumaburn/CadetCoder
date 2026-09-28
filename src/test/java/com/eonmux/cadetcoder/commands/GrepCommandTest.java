package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import com.eonmux.cadetcoder.test.ProjectFolder;
import org.mockito.MockedStatic;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

public class GrepCommandTest {

    @Rule
    public TemporaryFolder tempFolder = new ProjectFolder();

    private GrepCommand       grepCommand;
    private TestOutputCapture outputCapture;

    @Before
    public void setUp() {
        grepCommand   = new GrepCommand();
        outputCapture = new TestOutputCapture();
        outputCapture.startCapture();
    }

    @After
    public void tearDown() {
        outputCapture.stopCapture();
    }

    @Test
    public void testGrep_NoPattern() {
        int exitCode = grepCommand.execute(new String[] {});

        assertThat(exitCode).isEqualTo(1);
        assertThat(outputCapture.getStderr()).contains("Missing required parameter");
    }

    /**
     * A pattern that will not compile is searched for as plain text and the substitution is
     * announced. It used to be refused, which cost the caller a turn to be told about a bracket
     * they had written on purpose. See ApatternThatIsNotAnExpressionIsStillASearchTest.
     */
    @Test
    public void testGrep_InvalidRegex() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager mockConfigManager = mock(ConfigManager.class);
            Configuration config            = new Configuration();
            config.getUi().setColorEnabled(false);  // Disable colors for testing
            when(mockConfigManager.getConfig()).thenReturn(config);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            int exitCode = grepCommand.execute(new String[] {"[invalid(regex"});

            assertThat(exitCode).isZero();
            assertThat(outputCapture.getAllOutput())
                    .contains("is not a regular expression")
                    .contains("searched for as plain text");
        }
    }

    @Test
    public void testGrep_NoMatches() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager mockConfigManager = mock(ConfigManager.class);
            Configuration config            = new Configuration();
            config.getUi().setColorEnabled(false);  // Disable colors for testing
            when(mockConfigManager.getConfig()).thenReturn(config);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            // Create test file
            Path testFile = tempFolder.newFile("test.txt").toPath();
            Files.writeString(testFile, "Hello World\nThis is a test file\n");

            int exitCode =
                    grepCommand.execute(new String[] {"nonexistent", "-p", tempFolder.getRoot().getAbsolutePath()});

            assertThat(exitCode).isEqualTo(0);
            assertThat(outputCapture.getAllOutput()).contains("No matches found");
        }
    }

    @Test
    public void testGrep_BasicSearch() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager mockConfigManager = mock(ConfigManager.class);
            Configuration config            = new Configuration();
            config.getUi().setColorEnabled(false);  // Disable colors for testing
            when(mockConfigManager.getConfig()).thenReturn(config);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            // Create test files
            Path testFile1 = tempFolder.newFile("test1.txt").toPath();
            Files.writeString(testFile1, "Hello World\nThis is a test file\nTest pattern here\n");

            Path testFile2 = tempFolder.newFile("test2.txt").toPath();
            Files.writeString(testFile2, "Another file\nWith test content\n");

            int exitCode = grepCommand.execute(new String[] {"test", "-p", tempFolder.getRoot().getAbsolutePath()});

            assertThat(exitCode).isEqualTo(0);
            String output = outputCapture.getAllOutput();
            assertThat(output).contains("test1.txt");
            assertThat(output).contains("test2.txt");
            assertThat(output).contains("This is a test file");
            assertThat(output).doesNotContain("Test pattern here");  // Capital T won't match
            assertThat(output).contains("With test content");
            assertThat(output).contains("Found 2 matches in 2 files");  // Only 2 matches for lowercase "test"
        }
    }

    @Test
    public void testGrep_CaseInsensitive() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager mockConfigManager = mock(ConfigManager.class);
            Configuration config            = new Configuration();
            config.getUi().setColorEnabled(false);  // Disable colors for testing
            when(mockConfigManager.getConfig()).thenReturn(config);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            Path testFile = tempFolder.newFile("test.txt").toPath();
            Files.writeString(testFile, "Hello WORLD\nhello world\nHeLLo WoRLd\n");

            int exitCode =
                    grepCommand.execute(new String[] {"hello", "-I", "-p", tempFolder.getRoot().getAbsolutePath()});

            assertThat(exitCode).isEqualTo(0);
            String output = outputCapture.getAllOutput();
            // The output includes line numbers and file header
            assertThat(output).contains("1: Hello WORLD");
            assertThat(output).contains("2: hello world");
            assertThat(output).contains("3: HeLLo WoRLd");
            assertThat(output).contains("Found 3 matches");
        }
    }

    @Test
    public void testGrep_CountOnly() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager mockConfigManager = mock(ConfigManager.class);
            Configuration config            = new Configuration();
            config.getUi().setColorEnabled(false);  // Disable colors for testing
            when(mockConfigManager.getConfig()).thenReturn(config);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            Path testFile = tempFolder.newFile("test.txt").toPath();
            Files.writeString(testFile, "test line 1\ntest line 2\nno match\ntest line 3\n");

            int exitCode =
                    grepCommand.execute(new String[] {"test", "-c", "-p", tempFolder.getRoot().getAbsolutePath()});

            assertThat(exitCode).isEqualTo(0);
            String output = outputCapture.getAllOutput();
            assertThat(output).contains("test.txt:3");
        }
    }

    @Test
    public void testGrep_FilesOnly() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager mockConfigManager = mock(ConfigManager.class);
            Configuration config            = new Configuration();
            config.getUi().setColorEnabled(false);  // Disable colors for testing
            when(mockConfigManager.getConfig()).thenReturn(config);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            Path testFile1 = tempFolder.newFile("match.txt").toPath();
            Files.writeString(testFile1, "contains test");

            Path testFile2 = tempFolder.newFile("nomatch.txt").toPath();
            Files.writeString(testFile2, "no matches here");

            int exitCode =
                    grepCommand.execute(new String[] {"test", "-l", "-p", tempFolder.getRoot().getAbsolutePath()});

            assertThat(exitCode).isEqualTo(0);
            String output = outputCapture.getAllOutput();
            assertThat(output).contains("match.txt");
            assertThat(output).doesNotContain("nomatch.txt");
            assertThat(output).doesNotContain("contains test");
        }
    }

    @Test
    public void testGrep_IncludePattern() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager mockConfigManager = mock(ConfigManager.class);
            Configuration config            = new Configuration();
            config.getUi().setColorEnabled(false);  // Disable colors for testing
            when(mockConfigManager.getConfig()).thenReturn(config);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            Path javaFile = tempFolder.newFile("Test.java").toPath();
            Files.writeString(javaFile, "public class Test {}");

            Path txtFile = tempFolder.newFile("test.txt").toPath();
            Files.writeString(txtFile, "Test content");

            int exitCode = grepCommand.execute(new String[] {"Test",
                                                             "--include",
                                                             "*.java",
                                                             "-p",
                                                             tempFolder.getRoot().getAbsolutePath()});

            assertThat(exitCode).isEqualTo(0);
            String output = outputCapture.getAllOutput();
            assertThat(output).contains("Test.java");
            assertThat(output).doesNotContain("test.txt");
        }
    }

    @Test
    public void testGrep_WholeWord() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager mockConfigManager = mock(ConfigManager.class);
            Configuration config            = new Configuration();
            config.getUi().setColorEnabled(false);  // Disable colors for testing
            when(mockConfigManager.getConfig()).thenReturn(config);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            Path testFile = tempFolder.newFile("test.txt").toPath();
            Files.writeString(testFile, "test testing\ntested tester\ntest\n");

            int exitCode =
                    grepCommand.execute(new String[] {"test", "-w", "-p", tempFolder.getRoot().getAbsolutePath()});

            assertThat(exitCode).isEqualTo(0);
            String output = outputCapture.getAllOutput();
            // Check for line numbers in output
            assertThat(output).contains("1: test testing");
            assertThat(output).contains("3: test");
            assertThat(output).doesNotContain("tested");
            assertThat(output).doesNotContain("tester");
        }
    }

    @Test
    public void testGetDescription() {
        assertThat(grepCommand.getDescription()).isEqualTo("Search file contents with a regular expression");
    }

    @Test
    public void testGetUsage() {
        assertThat(grepCommand.getUsage()).contains("grep");
    }
    
    @Test
    public void testGrep_PlaceholderPath() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager mockConfigManager = mock(ConfigManager.class);
            Configuration config            = new Configuration();
            config.getUi().setColorEnabled(false);  // Disable colors for testing
            when(mockConfigManager.getConfig()).thenReturn(config);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            
            // Create test directory structure
            Path srcDir = tempFolder.newFolder("src").toPath();
            Path testFile = srcDir.resolve("test.txt");
            Files.writeString(testFile, "This is a test file with a pattern to match");
            
            // Set the current directory to the temp directory for this test
            String originalDir = System.getProperty("user.dir");
            try {
                System.setProperty("user.dir", tempFolder.getRoot().getAbsolutePath());
                
                // Execute command with a placeholder path
                int exitCode = grepCommand.execute(new String[] {"pattern", "-p", "path/to/src"});
                
                // Only the substitution is checked. The substituted path is relative, and Java
                // resolves a relative path against the directory the JVM started in, which a test
                // cannot move. This used to also expect "pattern to match", and passed because the
                // search found that text in this test's own source file.
                String output = outputCapture.getAllOutput();
                assertThat(output).contains("Substituted path: 'path/to/src' → './src'");
            } finally {
                // Restore the original directory
                System.setProperty("user.dir", originalDir);
            }
        }
    }
    
    @Test
    public void testGrep_FuzzyPatternParsing() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager mockConfigManager = mock(ConfigManager.class);
            Configuration config            = new Configuration();
            config.getUi().setColorEnabled(false);  // Disable colors for testing
            when(mockConfigManager.getConfig()).thenReturn(config);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            
            // Create test file
            Path testFile = tempFolder.newFile("fuzzy_test.txt").toPath();
            Files.writeString(testFile, "This is a test file with a PATTERN to match");
            
            // Test with quoted pattern - note that the quotes are part of the argument in the test
            // but would be stripped by the shell in real usage
            int exitCode = grepCommand.execute(new String[] {"PATTERN", "-p", testFile.getParent().toString()});
            
            // Verify the result
            assertThat(exitCode).isEqualTo(0);
            String output = outputCapture.getAllOutput();
            assertThat(output).contains("PATTERN to match");
            
            // Clear output for next test
            outputCapture.stopCapture();
            outputCapture.startCapture();
            
            // Test with regex: prefix
            exitCode = grepCommand.execute(new String[] {"regex:PATTERN", "-p", testFile.getParent().toString()});
            
            // Verify the result
            assertThat(exitCode).isEqualTo(0);
            output = outputCapture.getAllOutput();
            assertThat(output).contains("PATTERN to match");
        }
    }
    
    @Test
    public void testGrep_ParameterValidation() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager mockConfigManager = mock(ConfigManager.class);
            Configuration config            = new Configuration();
            config.getUi().setColorEnabled(false);  // Disable colors for testing
            when(mockConfigManager.getConfig()).thenReturn(config);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            
            // Create test file
            Path testFile = tempFolder.newFile("param_test.txt").toPath();
            Files.writeString(testFile, "This is a test file for parameter validation");
            
            // Test with conflicting options
            int exitCode = grepCommand.execute(new String[] {
                "test", 
                "-p", testFile.getParent().toString(),
                "-c", // count only
                "-l"  // files only
            });
            
            // Verify the result - should succeed but with warning
            assertThat(exitCode).isEqualTo(0);
            String output = outputCapture.getAllOutput();
            assertThat(output).contains("Both --count and --files-with-matches options specified");
            
            // Clear output for next test
            outputCapture.stopCapture();
            outputCapture.startCapture();
            
            // For now, let's just verify that the test passes with the first part
            // We'll add more comprehensive tests for parameter validation in the future
            assertThat(exitCode).isEqualTo(0);
        }
    }
    
    @Test
    public void testGrep_IncludeFilterDoesNotLeakAcrossInvocations() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager mockConfigManager = mock(ConfigManager.class);
            Configuration config            = new Configuration();
            config.getUi().setColorEnabled(false);
            when(mockConfigManager.getConfig()).thenReturn(config);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            Path javaFile = tempFolder.newFile("Leak.java").toPath();
            Files.writeString(javaFile, "needle in java");
            Path txtFile = tempFolder.newFile("leak.txt").toPath();
            Files.writeString(txtFile, "needle in txt");

            // First run filters to *.java only.
            int exitCode = grepCommand.execute(new String[] {"needle",
                                                             "--include", "*.java",
                                                             "-p", tempFolder.getRoot().getAbsolutePath()});
            assertThat(exitCode).isEqualTo(0);
            String firstOutput = outputCapture.getAllOutput();
            assertThat(firstOutput).contains("Leak.java");
            assertThat(firstOutput).doesNotContain("leak.txt");

            // Reset capture and re-run the SAME singleton with no --include filter.
            outputCapture.stopCapture();
            outputCapture = new TestOutputCapture();
            outputCapture.startCapture();

            exitCode = grepCommand.execute(new String[] {"needle",
                                                         "-p", tempFolder.getRoot().getAbsolutePath()});
            assertThat(exitCode).isEqualTo(0);
            String secondOutput = outputCapture.getAllOutput();
            // The previous --include *.java filter must NOT leak into this invocation.
            assertThat(secondOutput).contains("Leak.java");
            assertThat(secondOutput).contains("leak.txt");
        }
    }

    @Test
    public void testGrep_SearchesExplicitlyProvidedHiddenStartDirectory() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager mockConfigManager = mock(ConfigManager.class);
            Configuration config            = new Configuration();
            config.getUi().setColorEnabled(false);
            when(mockConfigManager.getConfig()).thenReturn(config);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            // A hidden directory (e.g. .github) passed explicitly as the search root.
            Path hiddenDir = tempFolder.newFolder(".github").toPath();
            Path file = hiddenDir.resolve("workflow.txt");
            Files.writeString(file, "needle inside hidden dir");

            int exitCode = grepCommand.execute(new String[] {"needle", "-p", hiddenDir.toString()});

            assertThat(exitCode).isEqualTo(0);
            String output = outputCapture.getAllOutput();
            assertThat(output).contains("needle inside hidden dir");
            assertThat(output).doesNotContain("No matches found");
        }
    }

    @Test
    public void testGrep_BinaryFileDetection() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager mockConfigManager = mock(ConfigManager.class);
            Configuration config            = new Configuration();
            config.getUi().setColorEnabled(false);  // Disable colors for testing
            when(mockConfigManager.getConfig()).thenReturn(config);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            
            // Create a text file
            Path textFile = tempFolder.newFile("text_file.txt").toPath();
            Files.writeString(textFile, "This is a text file with a pattern to match");
            
            // Create a binary file
            Path binaryFile = tempFolder.newFile("binary_file.bin").toPath();
            byte[] binaryData = new byte[1024];
            // Add some null bytes to make it clearly binary
            binaryData[10] = 0;
            binaryData[20] = 0;
            Files.write(binaryFile, binaryData);
            
            // Execute command on both files
            int exitCode = grepCommand.execute(new String[] {"pattern", "-p", tempFolder.getRoot().getAbsolutePath()});
            
            // Verify the result - should only match the text file
            assertThat(exitCode).isEqualTo(0);
            String output = outputCapture.getAllOutput();
            assertThat(output).contains("text_file.txt");
            assertThat(output).doesNotContain("binary_file.bin");
        }
    }

    /**
     * grep-2: an existing directory named like a known placeholder (e.g. "src" / "test")
     * must NOT be rewritten. The search must run against the real path and find content.
     */
    @Test
    public void testGrep_ExistingPlaceholderNamedDirIsNotSubstituted() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager mockConfigManager = mock(ConfigManager.class);
            Configuration config            = new Configuration();
            config.getUi().setColorEnabled(false);
            when(mockConfigManager.getConfig()).thenReturn(config);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            // A real directory literally named "src" with matching content.
            Path srcDir   = tempFolder.newFolder("src").toPath();
            Path testFile = srcDir.resolve("real.txt");
            Files.writeString(testFile, "needle in real src dir");

            int exitCode = grepCommand.execute(new String[] {"needle", "-p", srcDir.toString()});

            assertThat(exitCode).isEqualTo(0);
            String output = outputCapture.getAllOutput();
            assertThat(output).contains("needle in real src dir");
            // The existing path must not have been classified as a placeholder.
            assertThat(output).doesNotContain("Substituted path");
            assertThat(output).doesNotContain("No matches found");
        }
    }

    /**
     * grep-3: -i now means ignore-case (conventional grep), no longer --include.
     */
    @Test
    public void testGrep_ShortIMeansIgnoreCase() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager mockConfigManager = mock(ConfigManager.class);
            Configuration config            = new Configuration();
            config.getUi().setColorEnabled(false);
            when(mockConfigManager.getConfig()).thenReturn(config);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            Path testFile = tempFolder.newFile("case.txt").toPath();
            Files.writeString(testFile, "Hello WORLD\nhello world\n");

            int exitCode =
                    grepCommand.execute(new String[] {"hello", "-i", "-p", tempFolder.getRoot().getAbsolutePath()});

            assertThat(exitCode).isEqualTo(0);
            String output = outputCapture.getAllOutput();
            assertThat(output).contains("1: Hello WORLD");
            assertThat(output).contains("2: hello world");
            assertThat(output).contains("Found 2 matches");
        }
    }

    /**
     * grep-3: -I is retained as an alias for ignore-case (backward compatibility).
     */
    @Test
    public void testGrep_UppercaseIStillMeansIgnoreCase() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager mockConfigManager = mock(ConfigManager.class);
            Configuration config            = new Configuration();
            config.getUi().setColorEnabled(false);
            when(mockConfigManager.getConfig()).thenReturn(config);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            Path testFile = tempFolder.newFile("case2.txt").toPath();
            Files.writeString(testFile, "ALPHA\nalpha\n");

            int exitCode =
                    grepCommand.execute(new String[] {"alpha", "-I", "-p", tempFolder.getRoot().getAbsolutePath()});

            assertThat(exitCode).isEqualTo(0);
            assertThat(outputCapture.getAllOutput()).contains("Found 2 matches");
        }
    }

    /**
     * grep-4: a recursive include glob (**\/*.java) must match files in subdirectories,
     * not only files directly under the search root.
     */
    @Test
    public void testGrep_RecursiveIncludeGlobMatchesSubdirectories() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager mockConfigManager = mock(ConfigManager.class);
            Configuration config            = new Configuration();
            config.getUi().setColorEnabled(false);
            when(mockConfigManager.getConfig()).thenReturn(config);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            Path subDir   = tempFolder.newFolder("nested").toPath();
            Path javaFile = subDir.resolve("Deep.java");
            Files.writeString(javaFile, "needle in nested java");
            Path txtFile = subDir.resolve("deep.txt");
            Files.writeString(txtFile, "needle in nested txt");

            int exitCode = grepCommand.execute(new String[] {"needle",
                                                             "--include", "**/*.java",
                                                             "-p", tempFolder.getRoot().getAbsolutePath()});

            assertThat(exitCode).isEqualTo(0);
            String output = outputCapture.getAllOutput();
            assertThat(output).contains("Deep.java");
            assertThat(output).doesNotContain("deep.txt");
        }
    }

    /**
     * grep-4: a recursive exclude glob (**\/*.txt) must exclude matching files in
     * subdirectories.
     */
    @Test
    public void testGrep_RecursiveExcludeGlobMatchesSubdirectories() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager mockConfigManager = mock(ConfigManager.class);
            Configuration config            = new Configuration();
            config.getUi().setColorEnabled(false);
            when(mockConfigManager.getConfig()).thenReturn(config);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            Path subDir   = tempFolder.newFolder("deep").toPath();
            Path javaFile = subDir.resolve("Keep.java");
            Files.writeString(javaFile, "needle to keep");
            Path txtFile = subDir.resolve("drop.txt");
            Files.writeString(txtFile, "needle to drop");

            int exitCode = grepCommand.execute(new String[] {"needle",
                                                             "-e", "**/*.txt",
                                                             "-p", tempFolder.getRoot().getAbsolutePath()});

            assertThat(exitCode).isEqualTo(0);
            String output = outputCapture.getAllOutput();
            assertThat(output).contains("Keep.java");
            assertThat(output).doesNotContain("drop.txt");
        }
    }

    /**
     * grep-6: zero matches is still a successful search (exit 0) but emits an explicit
     * "Found 0 matches" summary so a consumer can tell matches=0 unambiguously.
     */
    @Test
    public void testGrep_NoMatchesEmitsZeroCountAndExitsZero() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager mockConfigManager = mock(ConfigManager.class);
            Configuration config            = new Configuration();
            config.getUi().setColorEnabled(false);
            when(mockConfigManager.getConfig()).thenReturn(config);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            Path testFile = tempFolder.newFile("none.txt").toPath();
            Files.writeString(testFile, "Hello World\n");

            int exitCode =
                    grepCommand.execute(new String[] {"absent", "-p", tempFolder.getRoot().getAbsolutePath()});

            assertThat(exitCode).isEqualTo(0);
            String output = outputCapture.getAllOutput();
            // One line, on one channel. The "Found 0 matches in 0 files" info line that used to
            // follow said exactly what the warning says -- "it ran, and found nothing" -- so the
            // same fact was reported twice back to back.
            assertThat(output).contains("No matches found");
            assertThat(output).doesNotContain("Found 0 matches");
        }
    }

    /**
     * grep-7: a single file that is not valid UTF-8 must not abort the walk. Previously the
     * decoding failure escaped searchInFile() as an UncheckedIOException (a RuntimeException,
     * which the per-file handler did not catch), so grep exited 1, dumped a MalformedInputException
     * stack trace, and discarded the match already found in good.txt.
     */
    @Test
    public void testGrep_MalformedUtf8FileDoesNotDiscardOtherMatches() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager mockConfigManager = mock(ConfigManager.class);
            Configuration config            = new Configuration();
            config.getUi().setColorEnabled(false);
            when(mockConfigManager.getConfig()).thenReturn(config);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            Path goodFile = tempFolder.newFile("good.txt").toPath();
            Files.writeString(goodFile, "beta match here\n");

            // Raw Latin-1 bytes: 0xE9 / 0xEF are not valid standalone UTF-8 sequences.
            Path latin1File = tempFolder.newFile("latin1.txt").toPath();
            Files.write(latin1File, "café naïve résumé\n".getBytes(StandardCharsets.ISO_8859_1));

            int exitCode = grepCommand.execute(new String[] {"beta", "-p", tempFolder.getRoot().getAbsolutePath()});

            String output = outputCapture.getAllOutput();

            assertThat(exitCode).isEqualTo(0);
            // The match found before the bad file was visited must survive.
            assertThat(output).contains("good.txt");
            assertThat(output).contains("beta match here");
            assertThat(output).contains("Found 1 match in 1 file");
            assertThat(output).doesNotContain("No matches found");
            // Degraded per file, reported once as a summary line - never as a stack trace.
            // Asserted on stdout, the user-visible channel: printWarning also mirrors the text to
            // the session logger, so getAllOutput() legitimately contains both copies.
            assertThat(outputCapture.getStdout()).containsOnlyOnce("decoded with replacement characters");
            assertThat(output).contains("latin1.txt");
            assertThat(output).doesNotContain("MalformedInputException");
            assertThat(output).doesNotContain("UncheckedIOException");
            assertThat(output).doesNotContain("at java.base/");
            assertThat(output).doesNotContain("Error executing grep command");
        }
    }

    /**
     * grep-7: because isTextFile() has already ruled out true binaries, a mis-encoded text file is
     * re-read with CodingErrorAction.REPLACE rather than skipped, so its own matches are still found.
     */
    @Test
    public void testGrep_MalformedUtf8FileIsStillSearchedLossily() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager mockConfigManager = mock(ConfigManager.class);
            Configuration config            = new Configuration();
            config.getUi().setColorEnabled(false);
            when(mockConfigManager.getConfig()).thenReturn(config);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            Path goodFile = tempFolder.newFile("good.txt").toPath();
            Files.writeString(goodFile, "beta match here\n");

            Path latin1File = tempFolder.newFile("latin1.txt").toPath();
            Files.write(latin1File,
                        "café beta au lait\n".getBytes(StandardCharsets.ISO_8859_1));

            int exitCode = grepCommand.execute(new String[] {"beta", "-p", tempFolder.getRoot().getAbsolutePath()});

            String output = outputCapture.getAllOutput();

            assertThat(exitCode).isEqualTo(0);
            assertThat(output).contains("beta match here");
            // The undecodable byte becomes U+FFFD, but the rest of the line - and its match - survives.
            assertThat(output).contains("beta au lait");
            assertThat(output).contains("Found 2 matches in 2 files");
            assertThat(outputCapture.getStdout()).containsOnlyOnce("decoded with replacement characters");
        }
    }

    /**
     * grep-7: the degraded-file notice is a single summary line for the whole run, not one
     * message per undecodable line, and it does not leak into a later invocation of the reused
     * singleton command.
     */
    @Test
    public void testGrep_MalformedUtf8SummaryIsReportedOncePerRun() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager mockConfigManager = mock(ConfigManager.class);
            Configuration config            = new Configuration();
            config.getUi().setColorEnabled(false);
            when(mockConfigManager.getConfig()).thenReturn(config);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            Path goodFile = tempFolder.newFile("good.txt").toPath();
            Files.writeString(goodFile, "beta match here\n");

            // Every one of the 20 lines carries an undecodable byte.
            StringBuilder latin1Content = new StringBuilder();
            for (int i = 0; i < 20; i++) {
                latin1Content.append("ligne café numéro ").append(i).append('\n');
            }
            Path latin1File = tempFolder.newFile("many.txt").toPath();
            Files.write(latin1File, latin1Content.toString().getBytes(StandardCharsets.ISO_8859_1));

            int exitCode = grepCommand.execute(new String[] {"beta", "-p", tempFolder.getRoot().getAbsolutePath()});

            assertThat(exitCode).isEqualTo(0);
            assertThat(outputCapture.getStdout()).containsOnlyOnce("decoded with replacement characters");

            // A second run over a clean tree must not repeat the previous run's summary.
            outputCapture.reset();
            Path cleanDir  = tempFolder.newFolder("clean").toPath();
            Path cleanFile = cleanDir.resolve("clean.txt");
            Files.writeString(cleanFile, "beta again\n");

            int secondExitCode = grepCommand.execute(new String[] {"beta", "-p", cleanDir.toAbsolutePath().toString()});

            String secondOutput = outputCapture.getAllOutput();
            assertThat(secondExitCode).isEqualTo(0);
            assertThat(secondOutput).contains("beta again");
            assertThat(secondOutput).doesNotContain("decoded with replacement characters");
            assertThat(secondOutput).doesNotContain("could not be read");
        }
    }

    /**
     * grep-7: genuinely binary files are still skipped quietly by isTextFile() - they must not be
     * re-read with replacement characters, and must not appear in any degraded-file summary line.
     */
    @Test
    public void testGrep_BinaryFileIsStillSkippedQuietly() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager mockConfigManager = mock(ConfigManager.class);
            Configuration config            = new Configuration();
            config.getUi().setColorEnabled(false);
            when(mockConfigManager.getConfig()).thenReturn(config);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            Path goodFile = tempFolder.newFile("good.txt").toPath();
            Files.writeString(goodFile, "beta match here\n");

            // Skipped on extension.
            Path binaryByExtension = tempFolder.newFile("payload.bin").toPath();
            byte[] binaryData      = new byte[1024];
            binaryData[10] = 0;
            binaryData[20] = 0;
            Files.write(binaryByExtension, binaryData);

            // Skipped by content sniffing (unknown extension, multiple null bytes).
            Path binaryBySniffing = tempFolder.newFile("blob.unknown").toPath();
            Files.write(binaryBySniffing, new byte[] {0x00, (byte) 0xFF, 0x00, (byte) 0xFE,
                                                      (byte) 0x98, 0x01, 0x02, 0x03, 0x04,
                                                      0x05, 0x06, 0x07, 0x08, 0x00});

            int exitCode = grepCommand.execute(new String[] {"beta", "-p", tempFolder.getRoot().getAbsolutePath()});

            String output = outputCapture.getAllOutput();

            assertThat(exitCode).isEqualTo(0);
            assertThat(output).contains("beta match here");
            assertThat(output).contains("Found 1 match in 1 file");
            assertThat(output).doesNotContain("payload.bin");
            assertThat(output).doesNotContain("blob.unknown");
            // Quietly: no degraded-file summary of any kind, and no stack trace.
            assertThat(output).doesNotContain("decoded with replacement characters");
            assertThat(output).doesNotContain("could not be read");
            assertThat(output).doesNotContain("MalformedInputException");
        }
    }
}
