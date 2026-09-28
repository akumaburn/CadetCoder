package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.session.SessionManager;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import com.eonmux.cadetcoder.test.ProjectFolder;
import org.mockito.MockedStatic;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

public class WriteCommandTest {

    @Rule
    public TemporaryFolder tempFolder = new ProjectFolder();

    private WriteCommand         writeCommand;
    private TestOutputCapture    outputCapture;
    private ByteArrayInputStream inputStream;

    @Before
    public void setUp() {
        writeCommand  = new WriteCommand();
        outputCapture = new TestOutputCapture();
        outputCapture.startCapture();
    }

    @After
    public void tearDown() {
        outputCapture.stopCapture();
        if (inputStream != null) {
            System.setIn(System.in);
        }
        resetSingletons();
    }

    private void resetSingletons() {
        try {
            // Reset ConfigManager
            java.lang.reflect.Field configInstance = ConfigManager.class.getDeclaredField("instance");
            configInstance.setAccessible(true);
            configInstance.set(null, null);

            // Reset SessionManager
            java.lang.reflect.Field sessionInstance = SessionManager.class.getDeclaredField("instance");
            sessionInstance.setAccessible(true);
            sessionInstance.set(null, null);
        } catch (Exception e) {
            // Ignore
        }
    }

    @Test
    public void testWriteFile_Success() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class)) {

            // Mock ConfigManager
            ConfigManager mockConfigManager = mock(ConfigManager.class);
            Configuration mockConfig        = new Configuration();
            mockConfig.getSecurity().setReadOnlyMode(false);
            mockConfig.getSecurity().setRequireConfirmation(false);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            // Mock SessionManager
            SessionManager mockSessionManager = mock(SessionManager.class);
            sessionMock.when(SessionManager::getInstance).thenReturn(mockSessionManager);

            // Create test file path
            Path   testFile = tempFolder.getRoot().toPath().resolve("test.txt");
            String content  = "Hello, World!";

            // Execute command
            int exitCode = writeCommand.execute(new String[] {testFile.toString(), content, "-f"});

            // Verify
            assertThat(exitCode).isEqualTo(0);
            assertThat(Files.exists(testFile)).isTrue();
            assertThat(Files.readString(testFile)).isEqualTo(content);
            String output = outputCapture.getStdout();
            assertThat(output).contains("File created:");
        }
    }

    @Test
    public void testWriteFile_Interactive() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class)) {

            // Mock ConfigManager
            ConfigManager mockConfigManager = mock(ConfigManager.class);
            when(mockConfigManager.getConfig()).thenReturn(createTestConfig(false, false));
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            // Mock SessionManager
            SessionManager mockSessionManager = mock(SessionManager.class);
            sessionMock.when(SessionManager::getInstance).thenReturn(mockSessionManager);

            // Create test file path
            Path testFile = tempFolder.getRoot().toPath().resolve("interactive.txt");

            // Mock user input
            String userInput = "Line 1\nLine 2\nEOF\n";
            inputStream = new ByteArrayInputStream(userInput.getBytes());
            System.setIn(inputStream);

            // Execute command
            int exitCode = writeCommand.execute(new String[] {testFile.toString(), "-i", "-f"});

            // Verify
            assertThat(exitCode).isEqualTo(0);
            assertThat(Files.exists(testFile)).isTrue();
            String content = Files.readString(testFile);
            assertThat(content).contains("Line 1");
            assertThat(content).contains("Line 2");
        }
    }

    private Configuration createTestConfig(boolean readOnly, boolean requireConfirmation) {
        Configuration config = new Configuration();
        config.getSecurity().setReadOnlyMode(readOnly);
        config.getSecurity().setRequireConfirmation(requireConfirmation);
        return config;
    }

    @Test
    public void testWriteFile_ExistingFile_NoForce() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class)) {

            // Mock ConfigManager
            ConfigManager mockConfigManager = mock(ConfigManager.class);
            when(mockConfigManager.getConfig()).thenReturn(createTestConfig(false, true));
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            // Mock SessionManager
            SessionManager mockSessionManager = mock(SessionManager.class);
            sessionMock.when(SessionManager::getInstance).thenReturn(mockSessionManager);

            // Create existing file
            Path testFile = tempFolder.newFile("existing.txt").toPath();
            Files.writeString(testFile, "Original content");

            // Mock user input to cancel
            String userInput = "n\n";
            inputStream = new ByteArrayInputStream(userInput.getBytes());
            System.setIn(inputStream);

            // Execute command without force
            int exitCode = writeCommand.execute(new String[] {testFile.toString(), "New content"});

            // Verify - should not overwrite, and must not claim it did. Reporting 0 here said the
            // write had happened, so `cadet write f ... && deploy` deployed the old contents.
            assertThat(exitCode).isEqualTo(com.eonmux.cadetcoder.ExitCode.INTERRUPTED);
            assertThat(exitCode).isNotEqualTo(com.eonmux.cadetcoder.ExitCode.OK);
            assertThat(Files.readString(testFile)).isEqualTo("Original content");
        }
    }

    @Test
    public void testWriteFile_ExistingFile_WithForce() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class)) {

            // Mock ConfigManager
            ConfigManager mockConfigManager = mock(ConfigManager.class);
            when(mockConfigManager.getConfig()).thenReturn(createTestConfig(false, true));
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            // Mock SessionManager
            SessionManager mockSessionManager = mock(SessionManager.class);
            sessionMock.when(SessionManager::getInstance).thenReturn(mockSessionManager);

            // Create existing file
            Path testFile = tempFolder.newFile("existing.txt").toPath();
            Files.writeString(testFile, "Original content");

            // Execute command with force
            int exitCode = writeCommand.execute(new String[] {testFile.toString(), "New content", "-f"});

            // Verify - should overwrite
            assertThat(exitCode).isEqualTo(0);
            assertThat(Files.readString(testFile)).isEqualTo("New content");
        }
    }

    @Test
    public void testWriteFile_ReadOnlyMode() throws Exception {
        // Mock ConfigManager for read-only mode
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class)) {

            // Mock ConfigManager
            ConfigManager mockConfigManager = mock(ConfigManager.class);
            when(mockConfigManager.getConfig()).thenReturn(createTestConfig(true, false));
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            // Mock SessionManager (not used when read-only, but needed for consistency)
            SessionManager mockSessionManager = mock(SessionManager.class);
            sessionMock.when(SessionManager::getInstance).thenReturn(mockSessionManager);

            Path testFile = tempFolder.getRoot().toPath().resolve("readonly.txt");

            // Execute command
            int exitCode = writeCommand.execute(new String[] {testFile.toString(), "content", "-f"});

            // Verify
            assertThat(exitCode).isEqualTo(1);
            String output = outputCapture.getAllOutput();
            assertThat(output).contains("read-only mode is enabled");
        }
    }

    @Test
    public void testWriteFile_NoArguments() throws Exception {
        // Execute command without arguments
        int exitCode = writeCommand.execute(new String[] {});

        // Verify
        assertThat(exitCode).isEqualTo(1);
        String output = outputCapture.getAllOutput();
        assertThat(output).contains("No file path provided");
    }

    @Test
    public void testWriteFile_CreateDirectories() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class)) {

            // Mock ConfigManager
            ConfigManager mockConfigManager = mock(ConfigManager.class);
            when(mockConfigManager.getConfig()).thenReturn(createTestConfig(false, false));
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            // Mock SessionManager
            SessionManager mockSessionManager = mock(SessionManager.class);
            sessionMock.when(SessionManager::getInstance).thenReturn(mockSessionManager);

            // Create nested path
            Path   testFile = tempFolder.getRoot().toPath().resolve("new/dir/test.txt");
            String content  = "Nested content";

            // Execute command
            int exitCode = writeCommand.execute(new String[] {testFile.toString(), content, "-f"});

            // Verify
            assertThat(exitCode).isEqualTo(0);
            assertThat(Files.exists(testFile)).isTrue();
            assertThat(Files.readString(testFile)).isEqualTo(content);
        }
    }
    
    // --- write-8 (data loss): a caller-supplied path is used VERBATIM.
    //     This test previously asserted the opposite: that "path/to/output.txt" was
    //     rewritten ("Substituted path: ... -> ...") to some other location found by
    //     searching the project. Because write keeps no ".backup", that substitution
    //     silently and irrecoverably overwrote whatever file the search happened to hit
    //     first, so the behaviour was inverted: the path is now honoured exactly as given. ---
    @Test
    public void testWriteFile_PlaceholderLikePath_UsedVerbatim() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class)) {

            // Mock ConfigManager
            ConfigManager mockConfigManager = mock(ConfigManager.class);
            when(mockConfigManager.getConfig()).thenReturn(createTestConfig(false, false));
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            // Mock SessionManager
            SessionManager mockSessionManager = mock(SessionManager.class);
            sessionMock.when(SessionManager::getInstance).thenReturn(mockSessionManager);

            // Create a directory structure that the old substitution logic would have
            // searched and retargeted the write into.
            Path srcDir = tempFolder.newFolder("src").toPath();
            Path mainDir = srcDir.resolve("main");
            Files.createDirectory(mainDir);
            Path javaDir = mainDir.resolve("java");
            Files.createDirectory(javaDir);

            // Set the current directory to the temp folder for this test
            String originalDir = System.getProperty("user.dir");
            try {
                System.setProperty("user.dir", tempFolder.getRoot().getAbsolutePath());

                // Execute command with a placeholder-looking path
                String content = "Test content for placeholder path";
                int exitCode = writeCommand.execute(new String[] {"path/to/output.txt", content});

                // Verify: written exactly where asked, with the missing parents created,
                // and no path substitution reported.
                assertThat(exitCode).isEqualTo(0);
                String output = outputCapture.getStdout();
                assertThat(output).doesNotContain("Substituted path");

                Path expectedFile = tempFolder.getRoot().toPath().resolve("path/to/output.txt");
                assertThat(Files.exists(expectedFile)).isTrue();
                assertThat(Files.readString(expectedFile)).isEqualTo(content);

                // The location the old logic substituted in must NOT have been touched.
                assertThat(Files.exists(tempFolder.getRoot().toPath().resolve("output.txt"))).isFalse();
            } finally {
                // Restore the original directory
                System.setProperty("user.dir", originalDir);
            }
        }
    }

    // --- write-8 (data loss) regression: writing a bare "output.txt" must never be
    //     retargeted onto an existing "sub/output.txt" found by walking the project tree. ---
    @Test
    public void testWriteFile_BareOutputTxt_DoesNotClobberSameNamedFileElsewhere() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class)) {

            ConfigManager mockConfigManager = mock(ConfigManager.class);
            when(mockConfigManager.getConfig()).thenReturn(createTestConfig(false, false));
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            SessionManager mockSessionManager = mock(SessionManager.class);
            sessionMock.when(SessionManager::getInstance).thenReturn(mockSessionManager);

            // An unrelated file that merely shares the basename of the write target.
            Path subDir      = tempFolder.newFolder("sub").toPath();
            Path existing    = subDir.resolve("output.txt");
            Files.writeString(existing, "IMPORTANT EXISTING DATA");

            String originalDir = System.getProperty("user.dir");
            try {
                System.setProperty("user.dir", tempFolder.getRoot().getAbsolutePath());

                int exitCode = writeCommand.execute(new String[] {"output.txt", "[NEWCONTENT]"});

                assertThat(exitCode).isEqualTo(0);

                // The unrelated file must be byte-for-byte unchanged.
                assertThat(Files.readString(existing)).isEqualTo("IMPORTANT EXISTING DATA");

                // The file the caller actually named must have been created in the cwd.
                Path target = tempFolder.getRoot().toPath().resolve("output.txt");
                assertThat(Files.exists(target)).isTrue();
                assertThat(Files.readString(target)).isEqualTo("[NEWCONTENT]");

                // A similar existing file may only be SUGGESTED, never used as the target.
                String output = outputCapture.getStdout();
                assertThat(output).doesNotContain("Substituted path");
                assertThat(output).contains("did you mean");
                assertThat(output).contains("output.txt");
            } finally {
                System.setProperty("user.dir", originalDir);
            }
        }
    }

    // --- write-8 (data loss): the same guarantee must hold for the picocli entry point
    //     (Callable.call()), which bypasses execute()'s argument parsing. ---
    @Test
    public void testWriteFile_PicocliCall_UsesPathVerbatim() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class)) {

            ConfigManager mockConfigManager = mock(ConfigManager.class);
            when(mockConfigManager.getConfig()).thenReturn(createTestConfig(false, false));
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            SessionManager mockSessionManager = mock(SessionManager.class);
            sessionMock.when(SessionManager::getInstance).thenReturn(mockSessionManager);

            Path subDir   = tempFolder.newFolder("sub").toPath();
            Path existing = subDir.resolve("result.json");
            Files.writeString(existing, "{\"keep\": true}");

            // picocli populates these fields directly instead of going through execute().
            setField("filePath", "result.json");
            setField("contentParts", new String[] {"{}"});
            setField("force", true);

            String originalDir = System.getProperty("user.dir");
            try {
                System.setProperty("user.dir", tempFolder.getRoot().getAbsolutePath());

                Integer exitCode = writeCommand.call();

                assertThat(exitCode).isEqualTo(0);
                assertThat(Files.readString(existing)).isEqualTo("{\"keep\": true}");
                Path target = tempFolder.getRoot().toPath().resolve("result.json");
                assertThat(Files.exists(target)).isTrue();
                assertThat(Files.readString(target)).isEqualTo("{}");
            } finally {
                System.setProperty("user.dir", originalDir);
            }
        }
    }

    // --- write-8 (data loss): a missing PARENT directory must not make the command fall
    //     back to a similarly named file found elsewhere (the FilePathResolver search path);
    //     the parents are created and the named file is written. ---
    @Test
    public void testWriteFile_MissingParentDir_DoesNotRetargetToExistingFile() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class)) {

            ConfigManager mockConfigManager = mock(ConfigManager.class);
            when(mockConfigManager.getConfig()).thenReturn(createTestConfig(false, false));
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            SessionManager mockSessionManager = mock(SessionManager.class);
            sessionMock.when(SessionManager::getInstance).thenReturn(mockSessionManager);

            Path otherDir = tempFolder.newFolder("other").toPath();
            Path existing = otherDir.resolve("notes.txt");
            Files.writeString(existing, "ORIGINAL NOTES");

            String originalDir = System.getProperty("user.dir");
            try {
                System.setProperty("user.dir", tempFolder.getRoot().getAbsolutePath());

                int exitCode = writeCommand.execute(new String[] {"newdir/notes.txt", "fresh notes"});

                assertThat(exitCode).isEqualTo(0);
                assertThat(Files.readString(existing)).isEqualTo("ORIGINAL NOTES");
                Path target = tempFolder.getRoot().toPath().resolve("newdir/notes.txt");
                assertThat(Files.exists(target)).isTrue();
                assertThat(Files.readString(target)).isEqualTo("fresh notes");
            } finally {
                System.setProperty("user.dir", originalDir);
            }
        }
    }

    /** Sets a private field on the command under test (as picocli does when binding options). */
    private void setField(String name, Object value) throws Exception {
        java.lang.reflect.Field field = WriteCommand.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(writeCommand, value);
    }

    @Test
    public void testWriteFile_InvalidPath() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class)) {

            // Mock ConfigManager
            ConfigManager mockConfigManager = mock(ConfigManager.class);
            when(mockConfigManager.getConfig()).thenReturn(createTestConfig(false, false));
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            // Mock SessionManager
            SessionManager mockSessionManager = mock(SessionManager.class);
            sessionMock.when(SessionManager::getInstance).thenReturn(mockSessionManager);

            // Execute command with an invalid path (contains invalid characters)
            int exitCode = writeCommand.execute(new String[] {"test*.txt", "content"});
            
            // Verify
            assertThat(exitCode).isEqualTo(1);
            String output = outputCapture.getAllOutput();
            assertThat(output).contains("File path contains invalid characters");
        }
    }
    
    @Test
    public void testWriteFile_EmptyPath() throws Exception {
        // Execute command with an empty path
        int exitCode = writeCommand.execute(new String[] {"", "content"});
        
        // Verify
        assertThat(exitCode).isEqualTo(1);
        String output = outputCapture.getAllOutput();
        assertThat(output).contains("File path cannot be empty");
    }
    
    @Test
    public void testWriteFile_ContentSizeExceedsLimit() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class)) {

            // Mock ConfigManager with a very small content size limit
            ConfigManager mockConfigManager = mock(ConfigManager.class);
            Configuration mockConfig = createTestConfig(false, false);
            mockConfig.getSecurity().setMaxFileContentSize(1); // 1MB
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            // Mock SessionManager
            SessionManager mockSessionManager = mock(SessionManager.class);
            sessionMock.when(SessionManager::getInstance).thenReturn(mockSessionManager);

            // Create a large content (2MB)
            StringBuilder largeContent = new StringBuilder();
            for (int i = 0; i < 2 * 1024 * 1024; i++) {
                largeContent.append('a');
            }
            
            // Execute command with large content
            Path testFile = tempFolder.getRoot().toPath().resolve("large.txt");
            int exitCode = writeCommand.execute(new String[] {testFile.toString(), largeContent.toString()});
            
            // Verify
            assertThat(exitCode).isEqualTo(1);
            String output = outputCapture.getAllOutput();
            assertThat(output).contains("Content size exceeds maximum limit");
        }
    }

    // --- write-2: content is treated as a single verbatim parameter ---
    @Test
    public void testWriteFile_PreservesInternalWhitespace() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class)) {

            ConfigManager mockConfigManager = mock(ConfigManager.class);
            when(mockConfigManager.getConfig()).thenReturn(createTestConfig(false, false));
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            SessionManager mockSessionManager = mock(SessionManager.class);
            sessionMock.when(SessionManager::getInstance).thenReturn(mockSessionManager);

            Path   testFile = tempFolder.getRoot().toPath().resolve("verbatim.txt");
            // A single content token carrying runs of internal whitespace must be written
            // verbatim, not collapsed to single spaces.
            String content  = "a    b\tc   d";

            int exitCode = writeCommand.execute(new String[] {testFile.toString(), content, "-f"});

            assertThat(exitCode).isEqualTo(0);
            assertThat(Files.readString(testFile)).isEqualTo(content);
        }
    }

    // --- write-6: WriteCommand no longer blanket-rejects any ".." substring with its own
    //     "Path traversal is not allowed" message. (The shared SecurityValidator may still
    //     gate such names; that gate lives outside WriteCommand and is not asserted here.) ---
    @Test
    public void testWriteFile_DotDotInFilename_NoBlanketTraversalReject() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class)) {

            ConfigManager mockConfigManager = mock(ConfigManager.class);
            when(mockConfigManager.getConfig()).thenReturn(createTestConfig(false, false));
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            SessionManager mockSessionManager = mock(SessionManager.class);
            sessionMock.when(SessionManager::getInstance).thenReturn(mockSessionManager);

            // The ".." here is part of the filename, not a traversal segment. WriteCommand's
            // own parameter validation must no longer emit the removed blanket-reject message.
            Path testFile = tempFolder.getRoot().toPath().resolve("my..notes.txt");

            writeCommand.execute(new String[] {testFile.toString(), "kept", "-f"});

            String output = outputCapture.getAllOutput();
            assertThat(output).doesNotContain("Path traversal is not allowed");
        }
    }

    // --- XC-security-sweep-2 / write-6: relative traversal escaping the working dir is REJECTED ---
    @Test
    public void testWriteFile_RelativeTraversalOutsideWorkingDir_Rejected() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class)) {

            ConfigManager mockConfigManager = mock(ConfigManager.class);
            Configuration mockConfig = createTestConfig(false, false);
            mockConfig.getSecurity().setAllowOutsideProject(true);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            SessionManager mockSessionManager = mock(SessionManager.class);
            sessionMock.when(SessionManager::getInstance).thenReturn(mockSessionManager);

            // A relative path that escapes the working directory after normalization must be
            // denied (defense in depth: blocked by the shared security policy and, for
            // non-".." escapes, by the normalized containment check in validatePath).
            int exitCode = writeCommand.execute(new String[] {"../../etc/passwd", "x", "-f"});

            assertThat(exitCode).isEqualTo(1);
            assertThat(outputCapture.getAllOutput()).contains("Access denied");
        }
    }

    // --- write-1: overwrite is atomic and leaves no staging temp file behind ---
    @Test
    public void testWriteFile_AtomicOverwrite_NoTempLeftover() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class)) {

            ConfigManager mockConfigManager = mock(ConfigManager.class);
            when(mockConfigManager.getConfig()).thenReturn(createTestConfig(false, false));
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            SessionManager mockSessionManager = mock(SessionManager.class);
            sessionMock.when(SessionManager::getInstance).thenReturn(mockSessionManager);

            Path dir      = tempFolder.newFolder("atomic").toPath();
            Path testFile = dir.resolve("data.txt");
            Files.writeString(testFile, "old content that is longer than new");

            int exitCode = writeCommand.execute(new String[] {testFile.toString(), "new", "-f"});

            assertThat(exitCode).isEqualTo(0);
            assertThat(Files.readString(testFile)).isEqualTo("new");

            // The atomic-move staging file must have been cleaned up: only the target
            // file should remain in the directory.
            try (java.util.stream.Stream<Path> entries = Files.list(dir)) {
                assertThat(entries.map(p -> p.getFileName().toString()))
                        .containsExactly("data.txt");
            }
        }
    }

    // --- write-7: interactive size limit is enforced in UTF-8 bytes ---
    @Test
    public void testWriteFile_InteractiveByteLimit_Enforced() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class)) {

            ConfigManager mockConfigManager = mock(ConfigManager.class);
            Configuration mockConfig = createTestConfig(false, false);
            mockConfig.getSecurity().setMaxFileContentSize(1); // 1MB
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            SessionManager mockSessionManager = mock(SessionManager.class);
            sessionMock.when(SessionManager::getInstance).thenReturn(mockSessionManager);

            Path testFile = tempFolder.getRoot().toPath().resolve("ilimit.txt");

            // A single multi-byte line (each 'e' with acute = 2 UTF-8 bytes) that exceeds
            // the 1MB byte budget though its char count is under it. The overflowing line
            // must be rejected before being appended.
            StringBuilder bigLine = new StringBuilder();
            for (int i = 0; i < 600 * 1024; i++) {
                bigLine.append('\u00e9'); // 'e' acute: 600K chars -> ~1.2MB UTF-8 bytes
            }
            String userInput = bigLine + "\nEOF\n";
            inputStream = new ByteArrayInputStream(userInput.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            System.setIn(inputStream);

            int exitCode = writeCommand.execute(new String[] {testFile.toString(), "-i", "-f"});

            assertThat(exitCode).isEqualTo(1);
            assertThat(outputCapture.getAllOutput()).contains("Content size exceeds maximum limit");
            // Nothing should have been written.
            assertThat(Files.exists(testFile)).isFalse();
        }
    }
}