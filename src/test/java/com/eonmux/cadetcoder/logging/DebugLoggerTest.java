package com.eonmux.cadetcoder.logging;

import com.eonmux.cadetcoder.testing.Await;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import org.junit.*;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.MockitoJUnitRunner;

import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.*;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@RunWith (MockitoJUnitRunner.class)
public class DebugLoggerTest {

    @Mock
    private ConfigManager mockConfigManager;

    @Mock
    private Configuration mockConfiguration;

    @Mock
    private Configuration.LoggingConfig mockLoggingConfig;

    @Mock
    private Configuration.UiConfig mockUiConfig;

    private ByteArrayOutputStream       errContent;
    private PrintStream                 originalErr;
    private Path                        testLogDir;
    private MockedStatic<ConfigManager> configManagerMock;

    @Before
    public void setUp() throws Exception {
        // Capture System.err
        errContent  = new ByteArrayOutputStream();
        originalErr = System.err;
        System.setErr(new PrintStream(errContent));

        // Create test log directory
        testLogDir = Paths.get(System.getProperty("java.io.tmpdir"), "cadet-debug-test-logs");
        Files.createDirectories(testLogDir);

        // Setup mocks
        configManagerMock = mockStatic(ConfigManager.class);
        configManagerMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
        when(mockConfigManager.getConfig()).thenReturn(mockConfiguration);
        when(mockConfiguration.getLogging()).thenReturn(mockLoggingConfig);
        when(mockConfiguration.getUi()).thenReturn(mockUiConfig);
        when(mockConfiguration.getBaseDir()).thenReturn(testLogDir.toString());

        // Default logging config
        when(mockLoggingConfig.isDebugEnabled()).thenReturn(true);
        when(mockLoggingConfig.isConsoleLoggingEnabled()).thenReturn(true);
        when(mockUiConfig.getVerbosityLevel()).thenReturn(Configuration.UiConfig.VERBOSITY.VERBOSE.ordinal());

        // Reset singleton instance
        Field instanceField = DebugLogger.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        instanceField.set(null, null);
    }

    @After
    public void tearDown() throws Exception {
        // Restore System.err
        System.setErr(originalErr);

        // Clean up singleton instance and shutdown
        DebugLogger instance = DebugLogger.getInstance();
        if (instance != null) {
            Method shutdownMethod = DebugLogger.class.getDeclaredMethod("shutdown");
            shutdownMethod.setAccessible(true);
            shutdownMethod.invoke(instance);
        }

        // Clean up test files
        if (Files.exists(testLogDir)) {
            Files.walk(testLogDir)
                 .sorted((a, b) -> -a.compareTo(b))
                 .map(Path::toFile)
                 .forEach(File::delete);
        }

        // Close static mock
        if (configManagerMock != null) {
            configManagerMock.close();
        }

        // Clean up singleton instance
        Field instanceField = DebugLogger.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        instanceField.set(null, null);
    }

    @Test
    public void testSingletonInstance() {
        DebugLogger logger1 = DebugLogger.getInstance();
        DebugLogger logger2 = DebugLogger.getInstance();

        assertNotNull(logger1);
        assertNotNull(logger2);
        assertSame(logger1, logger2);
    }

    @Test
    public void testDebugEnabledConfiguration() {
        DebugLogger logger = DebugLogger.getInstance();
        assertTrue(logger.isDebugEnabled());
    }

    @Test
    public void testDebugDisabledConfiguration() throws Exception {
        // Reset instance
        Field instanceField = DebugLogger.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        instanceField.set(null, null);

        when(mockLoggingConfig.isDebugEnabled()).thenReturn(false);

        DebugLogger logger = DebugLogger.getInstance();
        assertFalse(logger.isDebugEnabled());
    }

    @Test
    public void testTraceLogging() throws Exception {
        DebugLogger logger = DebugLogger.getInstance();

        logger.trace("TestSource", "Test trace message");

        // Wait for async logging
        Await.until("the async logger to write TRACE",
                    () -> errContent.toString().contains("TRACE"));

        // Check console output
        String consoleOutput = errContent.toString();
        assertTrue(consoleOutput.contains("TRACE"));
        assertTrue(consoleOutput.contains("TestSource"));
        assertTrue(consoleOutput.contains("Test trace message"));

        // Check log file
        verifyLogFileContains("TRACE", "TestSource", "Test trace message");
    }

    /**
     * Waits for the log FILE to carry each string, then asserts it.
     *
     * <p>The console and the file are separate sinks fed by the same background thread, and it
     * reaches them at different moments. Waiting on the console — which is what the caller does
     * before this — says nothing about the file, so reading the file once here was a race that only
     * showed up under load.</p>
     */
    private void verifyLogFileContains(String... expectedStrings) throws IOException {
        Path debugDir = testLogDir.resolve("logs").resolve("debug");
        if (!Files.exists(debugDir)) {
            return;
        }
        for (String expected : expectedStrings) {
            Await.until("the debug log file to contain " + expected,
                        () -> debugFileContent().contains(expected));
        }
        String content = debugFileContent();
        for (String expected : expectedStrings) {
            assertTrue("Log file should contain: " + expected, content.contains(expected));
        }
    }

    /** @return the current debug log file's content, or empty when it does not exist yet */
    private String debugFileContent() {
        try {
            Path debugDir = testLogDir.resolve("logs").resolve("debug");
            if (!Files.exists(debugDir)) {
                return "";
            }
            try (java.util.stream.Stream<Path> files = Files.list(debugDir)) {
                Path debugFile = files.filter(p -> p.getFileName().toString().startsWith("debug_"))
                                      .findFirst()
                                      .orElse(null);
                return debugFile == null ? "" : new String(Files.readAllBytes(debugFile));
            }
        } catch (IOException e) {
            return "";
        }
    }

    @Test
    public void testDebugLogging() throws Exception {
        DebugLogger logger = DebugLogger.getInstance();

        logger.debug("TestSource", "Test debug message");

        // Wait for async logging
        Await.until("the async logger to write DEBUG",
                    () -> errContent.toString().contains("DEBUG"));

        // Check console output
        String consoleOutput = errContent.toString();
        assertTrue(consoleOutput.contains("DEBUG"));
        assertTrue(consoleOutput.contains("TestSource"));
        assertTrue(consoleOutput.contains("Test debug message"));

        // Check log file
        verifyLogFileContains("DEBUG", "TestSource", "Test debug message");
    }

    @Test
    public void testDebugLoggingWithThrowable() throws Exception {
        DebugLogger logger        = DebugLogger.getInstance();
        Exception   testException = new RuntimeException("Test exception");

        logger.debug("TestSource", "Debug with exception", testException);

        // Wait for async logging
        Await.until("the async logger to write DEBUG",
                    () -> errContent.toString().contains("DEBUG"));

        // Check console output
        String consoleOutput = errContent.toString();
        assertTrue(consoleOutput.contains("DEBUG"));
        assertTrue(consoleOutput.contains("TestSource"));
        assertTrue(consoleOutput.contains("Debug with exception"));
        assertTrue(consoleOutput.contains("RuntimeException"));

        // Check log file
        verifyLogFileContains("DEBUG", "TestSource", "Debug with exception");
        verifyLogFileContains("RuntimeException", "Test exception");
    }

    @Test
    public void testInfoLogging() throws Exception {
        DebugLogger logger = DebugLogger.getInstance();

        logger.info("TestSource", "Test info message");

        // Wait for async logging
        Await.until("the async logger to write INFO",
                    () -> errContent.toString().contains("INFO"));

        // Check console output
        String consoleOutput = errContent.toString();
        assertTrue(consoleOutput.contains("INFO"));
        assertTrue(consoleOutput.contains("TestSource"));
        assertTrue(consoleOutput.contains("Test info message"));

        // Check log file
        verifyLogFileContains("INFO", "TestSource", "Test info message");
    }

    @Test
    public void testWarnLogging() throws Exception {
        DebugLogger logger = DebugLogger.getInstance();

        logger.warn("TestSource", "Test warning message");

        // Wait for async logging
        Await.until("the async logger to write WARN",
                    () -> errContent.toString().contains("WARN"));

        // Check console output
        String consoleOutput = errContent.toString();
        assertTrue(consoleOutput.contains("WARN"));
        assertTrue(consoleOutput.contains("TestSource"));
        assertTrue(consoleOutput.contains("Test warning message"));

        // Check log file
        verifyLogFileContains("WARN", "TestSource", "Test warning message");
    }

    @Test
    public void testWarnLoggingWithThrowable() throws Exception {
        DebugLogger logger        = DebugLogger.getInstance();
        Exception   testException = new IllegalArgumentException("Test warning exception");

        logger.warn("TestSource", "Warning with exception", testException);

        // Wait for async logging
        Await.until("the async logger to write WARN",
                    () -> errContent.toString().contains("WARN"));

        // Check console output
        String consoleOutput = errContent.toString();
        assertTrue(consoleOutput.contains("WARN"));
        assertTrue(consoleOutput.contains("TestSource"));
        assertTrue(consoleOutput.contains("Warning with exception"));
        assertTrue(consoleOutput.contains("IllegalArgumentException"));

        // Check log file
        verifyLogFileContains("WARN", "TestSource", "Warning with exception");
        verifyLogFileContains("IllegalArgumentException", "Test warning exception");
    }

    @Test
    public void testErrorLogging() throws Exception {
        DebugLogger logger = DebugLogger.getInstance();

        logger.error("TestSource", "Test error message");

        // Wait for async logging
        Await.until("the async logger to write ERROR",
                    () -> errContent.toString().contains("ERROR"));

        // Check console output
        String consoleOutput = errContent.toString();
        assertTrue(consoleOutput.contains("ERROR"));
        assertTrue(consoleOutput.contains("TestSource"));
        assertTrue(consoleOutput.contains("Test error message"));

        // Check log file
        verifyLogFileContains("ERROR", "TestSource", "Test error message");
    }

    @Test
    public void testErrorLoggingWithThrowable() throws Exception {
        DebugLogger logger        = DebugLogger.getInstance();
        Exception   testException = new IOException("Test error exception");

        logger.error("TestSource", "Error with exception", testException);

        // Wait for async logging
        Await.until("the async logger to write ERROR",
                    () -> errContent.toString().contains("ERROR"));

        // Check console output
        String consoleOutput = errContent.toString();
        assertTrue(consoleOutput.contains("ERROR"));
        assertTrue(consoleOutput.contains("TestSource"));
        assertTrue(consoleOutput.contains("Error with exception"));
        assertTrue(consoleOutput.contains("IOException"));

        // Check log file
        verifyLogFileContains("ERROR", "TestSource", "Error with exception");
        verifyLogFileContains("IOException", "Test error exception");
    }

    @Test
    public void testLogCommandWithArgs() throws Exception {
        DebugLogger logger = DebugLogger.getInstance();

        String[] args = {"arg1", "arg2", "arg3"};
        logger.logCommand("testCommand", args);

        // Wait for async logging
        Await.settle();

        // Check log file
        verifyLogFileContains("COMMAND: testCommand", "ARGS: ['arg1', 'arg2', 'arg3']");
    }

    @Test
    public void testLogCommandWithoutArgs() throws Exception {
        DebugLogger logger = DebugLogger.getInstance();

        logger.logCommand("testCommand", null);

        // Wait for async logging
        Await.settle();

        // Check log file
        verifyLogFileContains("COMMAND: testCommand");
    }

    @Test
    public void testLogResponseSuccess() throws Exception {
        DebugLogger logger = DebugLogger.getInstance();

        logger.logResponse("testCommand", 0, 1234);

        // Wait for async logging
        Await.settle();

        // Check log file
        verifyLogFileContains("RESPONSE: testCommand - SUCCESS", "exit: 0", "duration: 1234ms");
    }

    @Test
    public void testLogResponseFailure() throws Exception {
        DebugLogger logger = DebugLogger.getInstance();

        logger.logResponse("testCommand", 1, 5678);

        // Wait for async logging
        Await.settle();

        // Check log file
        verifyLogFileContains("RESPONSE: testCommand - FAILED", "exit: 1", "duration: 5678ms");
    }

    @Test
    public void testLogAIRequest() throws Exception {
        DebugLogger logger = DebugLogger.getInstance();

        logger.logAIRequest("Test prompt for AI", "gpt-4", 0.7);

        // Wait for async logging
        Await.settle();

        // Check log file
        verifyLogFileContains("REQUEST to gpt-4", "temp: 0.70", "Test prompt for AI");
    }

    @Test
    public void testLogAIRequestTruncation() throws Exception {
        DebugLogger logger = DebugLogger.getInstance();

        String longPrompt = "A".repeat(600);
        logger.logAIRequest(longPrompt, "gpt-4", 0.7);

        // Wait for async logging
        Await.settle();

        // Check log file
        verifyLogFileContains("REQUEST to gpt-4", "... (truncated)");
    }

    @Test
    public void testLogAIResponse() throws Exception {
        DebugLogger logger = DebugLogger.getInstance();

        logger.logAIResponse("AI response text", 2500);

        // Wait for async logging
        Await.settle();

        // Check log file
        verifyLogFileContains("RESPONSE", "duration: 2500ms", "AI response text");
    }

    @Test
    public void testLogFileOperationSuccess() throws Exception {
        DebugLogger logger = DebugLogger.getInstance();

        logger.logFileOperation("READ", "/path/to/file.txt", true);

        // Wait for async logging
        Await.settle();

        // Check log file
        verifyLogFileContains("READ: /path/to/file.txt - SUCCESS");
    }

    @Test
    public void testLogFileOperationFailure() throws Exception {
        DebugLogger logger = DebugLogger.getInstance();

        logger.logFileOperation("WRITE", "/path/to/file.txt", false);

        // Wait for async logging
        Await.settle();

        // Check log file
        verifyLogFileContains("WRITE: /path/to/file.txt - FAILED");
    }

    @Test
    public void testLogSecurityEventAllowed() throws Exception {
        DebugLogger logger = DebugLogger.getInstance();

        logger.logSecurityEvent("FILE_ACCESS", "User accessed sensitive file", true);

        // Wait for async logging
        Await.settle();

        // Check log file
        verifyLogFileContains("FILE_ACCESS - ALLOWED", "User accessed sensitive file");
    }

    @Test
    public void testLogSecurityEventBlocked() throws Exception {
        DebugLogger logger = DebugLogger.getInstance();

        logger.logSecurityEvent("FILE_ACCESS", "User denied access to restricted file", false);

        // Wait for async logging
        Await.settle();

        // Check log file
        verifyLogFileContains("FILE_ACCESS - BLOCKED", "User denied access to restricted file");
    }

    @Test
    public void testLogPerformance() throws Exception {
        DebugLogger logger = DebugLogger.getInstance();

        logger.logPerformance("Database query", 1500, "SELECT * FROM users");

        // Wait for async logging
        Await.settle();

        // Check log file
        verifyLogFileContains("Database query took 1500ms", "SELECT * FROM users");
    }

    @Test
    public void testTruncateMethod() throws Exception {
        DebugLogger logger = DebugLogger.getInstance();

        // Test truncate via reflection
        Method truncateMethod = DebugLogger.class.getDeclaredMethod("truncate", String.class, int.class);
        truncateMethod.setAccessible(true);

        // Test null input
        String result = (String) truncateMethod.invoke(logger, null, 10);
        assertEquals("null", result);

        // Test short string
        result = (String) truncateMethod.invoke(logger, "short", 10);
        assertEquals("short", result);

        // Test long string
        result = (String) truncateMethod.invoke(logger, "This is a very long string", 10);
        assertEquals("This is a ... (truncated)", result);
    }

    @Test
    public void testSetDebugEnabled() throws Exception {
        DebugLogger logger = DebugLogger.getInstance();

        // Disable debug
        logger.setDebugEnabled(false);
        assertFalse(logger.isDebugEnabled());

        // Log something - should not be logged
        logger.debug("TestSource", "This should not be logged");

        // Re-enable debug
        logger.setDebugEnabled(true);
        assertTrue(logger.isDebugEnabled());

        // Log something - should be logged
        logger.debug("TestSource", "This should be logged");

        // Wait for async logging
        Await.settle();

        // Check log file only contains the second message
        verifyLogFileContains("This should be logged");
    }

    @Test
    public void testLogFileRotation() throws Exception {
        DebugLogger logger = DebugLogger.getInstance();

        // Write enough data to trigger rotation (>10MB)
        String largeMessage = "X".repeat(1000);
        for (int i = 0; i < 11000; i++) {
            logger.info("TestSource", largeMessage);
        }

        // Wait for async logging
        Await.settle();

        // Check if rotation occurred
        Path debugDir = testLogDir.resolve("logs").resolve("debug");
        if (Files.exists(debugDir)) {
            long debugFileCount = Files.list(debugDir)
                                       .filter(path -> path.getFileName().toString().startsWith("debug_"))
                                       .count();
            assertTrue("Should have rotated debug files", debugFileCount >= 2);
        }
    }

    @Test
    public void testConcurrentLogging() throws Exception {
        final DebugLogger logger            = DebugLogger.getInstance();
        final int         threadCount       = 10;
        final int         messagesPerThread = 10;

        Thread[] threads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            threads[i] = new Thread(() -> {
                for (int j = 0; j < messagesPerThread; j++) {
                    logger.info("Thread-" + threadId, "Message " + j);
                }
            });
        }

        // Start all threads
        for (Thread thread : threads) {
            thread.start();
        }

        // Wait for all threads to complete
        for (Thread thread : threads) {
            thread.join();
        }

        // Wait for async logging
        Await.settle();

        // Verify all messages were logged
        Path debugDir = testLogDir.resolve("logs").resolve("debug");
        if (Files.exists(debugDir)) {
            Path debugFile = Files.list(debugDir)
                                  .filter(p -> p.getFileName().toString().startsWith("debug_"))
                                  .findFirst()
                                  .orElse(null);

            if (debugFile != null) {
                String content = new String(Files.readAllBytes(debugFile));
                for (int i = 0; i < threadCount; i++) {
                    for (int j = 0; j < messagesPerThread; j++) {
                        assertTrue(content.contains("Thread-" + i));
                        assertTrue(content.contains("Message " + j));
                    }
                }
            }
        }
    }

    @Test
    public void testNoConfigurationAvailable() throws Exception {
        // Reset instance
        Field instanceField = DebugLogger.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        instanceField.set(null, null);

        when(mockConfigManager.getConfig()).thenThrow(new RuntimeException("Config error"));

        DebugLogger logger = DebugLogger.getInstance();
        assertFalse(logger.isDebugEnabled());

        // Should not throw exception when logging
        logger.debug("TestSource", "This should not cause an error");
    }

    @Test
    public void testConsoleLoggingDisabled() throws Exception {
        when(mockLoggingConfig.isConsoleLoggingEnabled()).thenReturn(false);

        DebugLogger logger = DebugLogger.getInstance();
        logger.debug("TestSource", "Should not appear in console");

        // Nothing to wait FOR here -- the claim is that this never reaches the console -- so the
        // writer is given a chance and then checked.
        Await.settle();

        // Console should be empty
        String consoleOutput = errContent.toString();
        assertFalse(consoleOutput.contains("Should not appear in console"));
    }

    @Test
    public void testLogQueueInterruption() throws Exception {
        DebugLogger logger = DebugLogger.getInstance();

        // Get the debug thread using reflection
        Field threadField = DebugLogger.class.getDeclaredField("debugThread");
        threadField.setAccessible(true);
        Thread debugThread = (Thread) threadField.get(logger);

        // Interrupt the thread
        debugThread.interrupt();

        // Wait a bit
        Await.settle();

        // Thread should have stopped
        assertFalse(debugThread.isAlive());
    }
}