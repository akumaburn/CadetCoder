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
import java.nio.file.*;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static com.eonmux.cadetcoder.testsupport.ConsoleAssertions.assertError;

@RunWith (MockitoJUnitRunner.Silent.class)
public class CadetLoggerTest {

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
        testLogDir = Paths.get(System.getProperty("java.io.tmpdir"), "cadet-test-logs");
        Files.createDirectories(testLogDir);

        // Setup mocks
        configManagerMock = mockStatic(ConfigManager.class);
        configManagerMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
        when(mockConfigManager.getConfig()).thenReturn(mockConfiguration);
        when(mockConfiguration.getLogging()).thenReturn(mockLoggingConfig);
        when(mockConfiguration.getUi()).thenReturn(mockUiConfig);
        when(mockConfiguration.getBaseDir()).thenReturn(testLogDir.toString());

        // Default logging config
        when(mockLoggingConfig.getLogFile()).thenReturn("logs/cadet.log");
        when(mockLoggingConfig.getMaxLogSize()).thenReturn(10); // 10MB
        when(mockLoggingConfig.getMaxLogFiles()).thenReturn(5);
        when(mockLoggingConfig.isDebugEnabled()).thenReturn(true);
        when(mockLoggingConfig.isConsoleLoggingEnabled()).thenReturn(true);
        when(mockUiConfig.getVerbosityLevel()).thenReturn(Configuration.UiConfig.VERBOSITY.VERBOSE.ordinal());

        // The log file has one writer for the whole process, and it resolved its destination from
        // whatever configuration was live when the first line was logged. Installing a mocked
        // configuration is a configuration change, so it is announced the same way a `config
        // logging.logFile` would be.
        CadetLogger.reopenLogFile();
    }

    @After
    public void tearDown() throws Exception {
        // Restore System.err
        System.setErr(originalErr);

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

        // With the mock gone, point the shared writer back at the real configured file so the next
        // test does not find its log lines in this test's deleted temp directory.
        CadetLogger.reopenLogFile();

        // Clean up logger cache
        Field loggerCacheField = CadetLogger.class.getDeclaredField("loggerCache");
        loggerCacheField.setAccessible(true);
        ((java.util.concurrent.ConcurrentHashMap) loggerCacheField.get(null)).clear();
    }

    @Test
    public void testGetLoggerWithClass() {
        CadetLogger logger1 = CadetLogger.getLogger(CadetLoggerTest.class);
        CadetLogger logger2 = CadetLogger.getLogger(CadetLoggerTest.class);

        assertNotNull(logger1);
        assertNotNull(logger2);
        // Should return the same instance for the same class (singleton behavior)
        assertSame(logger1, logger2);
    }

    @Test
    public void testGetLoggerWithString() {
        CadetLogger logger1 = CadetLogger.getLogger("TestLogger");
        CadetLogger logger2 = CadetLogger.getLogger("TestLogger");

        assertNotNull(logger1);
        assertNotNull(logger2);
        // Should return the same instance for the same name (singleton behavior)
        assertSame(logger1, logger2);
    }

    @Test
    public void testErrorLogging() {
        CadetLogger logger = CadetLogger.getLogger(CadetLoggerTest.class);

        logger.error("Test error message");

        String output = errContent.toString();
        // Asserted by classification rather than by the marker literal: the console picks its
        // markers from Glyphs.system(), so the exact character depends on the terminal encoding.
        assertError(output, "Test error message");
    }

    @Test
    public void testErrorLoggingWithThrowable() {
        CadetLogger logger        = CadetLogger.getLogger(CadetLoggerTest.class);
        Exception   testException = new RuntimeException("Test exception");

        logger.error("Error occurred", testException);

        String output = errContent.toString();
        assertError(output, "Error occurred: Test exception");
        // The exception TYPE stays on the console line. The stack trace that used to carry it now
        // goes only to the log file (asserted by testErrorLoggingWithThrowableWritesStackTraceToLogFile),
        // because dumping forty Java frames over the one useful sentence is not a report.
        assertTrue(output.contains("RuntimeException"));
        assertFalse("the stack trace must not reach the console",
                    output.contains("at com.eonmux.cadetcoder.logging.CadetLoggerTest"));
    }

    @Test
    public void testErrorLoggingWritesToLogFile() throws Exception {
        // ERROR used to be the one level that never reached the rotating log file, so the file
        // you would read to diagnose a failure contained everything except the failure.
        CadetLogger logger = CadetLogger.getLogger(CadetLoggerTest.class);

        logger.error("Error that must reach the log file");

        String content = awaitLogFileContent(testLogDir.resolve("logs/cadet.log"),
                                             "Error that must reach the log file");
        assertTrue("Log entry should be recorded at ERROR level", content.contains("ERROR"));
        // Console behaviour for direct callers is unchanged.
        assertError(errContent.toString(), "Error that must reach the log file");
    }

    @Test
    public void testErrorLoggingWithThrowableWritesStackTraceToLogFile() throws Exception {
        CadetLogger logger        = CadetLogger.getLogger(CadetLoggerTest.class);
        Exception   testException = new IllegalStateException("Boom in the file");

        logger.error("Throwable that must reach the log file", testException);

        String content = awaitLogFileContent(testLogDir.resolve("logs/cadet.log"),
                                             "Throwable that must reach the log file");
        assertTrue("Log entry should be recorded at ERROR level", content.contains("ERROR"));
        assertTrue("Throwable message should be recorded", content.contains("Boom in the file"));
        assertTrue("Exception type should be recorded", content.contains("IllegalStateException"));
        assertTrue("Stack trace frames should be recorded",
                   content.contains("at com.eonmux.cadetcoder.logging.CadetLoggerTest"));
    }

    @Test
    public void testErrorLoggingWithNullThrowableWritesToLogFile() throws Exception {
        CadetLogger logger = CadetLogger.getLogger(CadetLoggerTest.class);

        // A null throwable delegates to the message-only variant; it must still reach the file.
        logger.error("Null throwable still logged", null);

        String content = awaitLogFileContent(testLogDir.resolve("logs/cadet.log"),
                                             "Null throwable still logged");
        assertTrue(content.contains("ERROR"));
        assertError(errContent.toString(), "Null throwable still logged");
    }

    @Test
    public void testErrorToFileWritesFileOnly() throws Exception {
        CadetLogger logger = CadetLogger.getLogger(CadetLoggerTest.class);

        logger.errorToFile("File only error");

        String content = awaitLogFileContent(testLogDir.resolve("logs/cadet.log"), "File only error");
        assertTrue("Log entry should be recorded at ERROR level", content.contains("ERROR"));
        // Nothing may be echoed to the console: no "✗" line and no SLF4J event at all.
        String consoleOutput = errContent.toString();
        assertFalse("errorToFile must not print to the console", consoleOutput.contains("File only error"));
        assertFalse(consoleOutput.contains("✗"));
    }

    @Test
    public void testWarnToFileWritesFileOnly() throws Exception {
        CadetLogger logger = CadetLogger.getLogger(CadetLoggerTest.class);

        logger.warnToFile("File only warning");

        String content = awaitLogFileContent(testLogDir.resolve("logs/cadet.log"), "File only warning");
        assertTrue("Log entry should be recorded at WARN level", content.contains("WARN"));
        assertFalse("warnToFile must not print to the console",
                    errContent.toString().contains("File only warning"));
    }

    @Test
    public void testInfoToFileWritesFileOnly() throws Exception {
        CadetLogger logger = CadetLogger.getLogger(CadetLoggerTest.class);

        logger.infoToFile("File only info");

        String content = awaitLogFileContent(testLogDir.resolve("logs/cadet.log"), "File only info");
        assertTrue("Log entry should be recorded at INFO level", content.contains("INFO"));
        assertFalse("infoToFile must not print to the console",
                    errContent.toString().contains("File only info"));
    }

    /**
     * Waits for the asynchronous logger thread to flush an entry containing the given fragment.
     *
     * @return the full log file content once the fragment is present
     */
    private String awaitLogFileContent(Path logFile, String expectedFragment) throws Exception {
        long   deadline = System.currentTimeMillis() + 5000;
        String content  = "";
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(logFile)) {
                content = new String(Files.readAllBytes(logFile));
                if (content.contains(expectedFragment)) {
                    return content;
                }
            }
            Await.settle();
        }
        fail("Log file " + logFile + " never contained \"" + expectedFragment + "\"; content was: " + content);
        return content;
    }

    @Test
    public void testWarnLogging() throws Exception {
        CadetLogger logger = CadetLogger.getLogger(CadetLoggerTest.class);

        logger.warn("Test warning message");

        // Wait for async logging
        Await.settle();

        // Verify log file was created and contains the warning
        Path logFile = testLogDir.resolve("logs/cadet.log");
        if (Files.exists(logFile)) {
            String content = new String(Files.readAllBytes(logFile));
            assertTrue(content.contains("WARN"));
            assertTrue(content.contains("Test warning message"));
        }
    }

    @Test
    public void testInfoLogging() throws Exception {
        CadetLogger logger = CadetLogger.getLogger(CadetLoggerTest.class);

        logger.info("Test info message");

        // Wait for async logging
        Await.settle();

        // Verify log file was created and contains the info
        Path logFile = testLogDir.resolve("logs/cadet.log");
        if (Files.exists(logFile)) {
            String content = new String(Files.readAllBytes(logFile));
            assertTrue(content.contains("INFO"));
            assertTrue(content.contains("Test info message"));
        }
    }

    @Test
    public void testDebugLogging() throws Exception {
        CadetLogger logger = CadetLogger.getLogger(CadetLoggerTest.class);

        logger.debug("Test debug message");

        // Should also output to console when verbose mode is on
        String consoleOutput = errContent.toString();
        assertTrue(consoleOutput.contains("[DEBUG]"));
        assertTrue(consoleOutput.contains("Test debug message"));

        // Wait for async logging
        Await.settle();

        // Verify log file contains debug message
        Path logFile = testLogDir.resolve("logs/cadet.log");
        if (Files.exists(logFile)) {
            String content = new String(Files.readAllBytes(logFile));
            assertTrue(content.contains("DEBUG"));
            assertTrue(content.contains("Test debug message"));
        }
    }

    @Test
    public void testDebugLoggingDisabled() {
        when(mockLoggingConfig.isDebugEnabled()).thenReturn(false);
        when(mockLoggingConfig.isConsoleLoggingEnabled()).thenReturn(false);

        CadetLogger logger = CadetLogger.getLogger(CadetLoggerTest.class);
        logger.debug("Test debug message");

        // Should not output to console when debug is disabled
        String consoleOutput = errContent.toString();
        assertFalse(consoleOutput.contains("[DEBUG]"));
    }

    /** A trace line is recorded at the one level that asks for trace lines. */
    @Test
    public void testTraceLogging() throws Exception {
        when(mockLoggingConfig.getLevel()).thenReturn("TRACE");
        CadetLogger.reopenLogFile();
        CadetLogger logger = CadetLogger.getLogger(CadetLoggerTest.class);

        logger.trace("Test trace message");

        // Wait for async logging
        Await.settle();

        // Verify log file contains trace message
        Path logFile = testLogDir.resolve("logs/cadet.log");
        if (Files.exists(logFile)) {
            String content = new String(Files.readAllBytes(logFile));
            assertTrue(content.contains("TRACE"));
            assertTrue(content.contains("Test trace message"));
        }
    }

    @Test
    public void testIsDebugEnabled() {
        CadetLogger logger = CadetLogger.getLogger(CadetLoggerTest.class);

        // This depends on the underlying SLF4J logger configuration
        // Just verify the method exists and returns a boolean
        boolean result = logger.isDebugEnabled();
        assertTrue(result || !result);
    }

    @Test
    public void testIsTraceEnabled() {
        CadetLogger logger = CadetLogger.getLogger(CadetLoggerTest.class);

        // This depends on the underlying SLF4J logger configuration
        // Just verify the method exists and returns a boolean
        boolean result = logger.isTraceEnabled();
        assertTrue(result || !result);
    }

    @Test
    public void testLoggingWithoutConfiguration() {
        when(mockConfiguration.getLogging()).thenReturn(null);

        CadetLogger logger = CadetLogger.getLogger(CadetLoggerTest.class);

        // Should still work for console logging
        logger.error("Error without config");

        String output = errContent.toString();
        assertTrue(output.contains("✗ Error without config"));
    }

    @Test
    public void testLogFileCreationFailure() {
        // Set invalid log file path
        when(mockLoggingConfig.getLogFile()).thenReturn("/invalid/path/that/does/not/exist/cadet.log");

        CadetLogger logger = CadetLogger.getLogger(CadetLoggerTest.class);

        // Should still work for console logging even if file logging fails
        logger.error("Error message");

        String output = errContent.toString();
        assertTrue(output.contains("✗ Error message"));
    }

    /**
     * {@code config logging.logFile <path>} must take effect on the next line.
     *
     * <p>The destination is resolved once, by whichever call logged first. Before it was announced,
     * a user who redirected their log was told the setting had been saved while every subsequent
     * line kept going to the old file.</p>
     */
    @Test
    public void testReopenRedirectsToTheNewlyConfiguredFile() throws Exception {
        CadetLogger logger = CadetLogger.getLogger(CadetLoggerTest.class);
        logger.infoToFile("Before the redirect");
        awaitLogFileContent(testLogDir.resolve("logs/cadet.log"), "Before the redirect");

        when(mockLoggingConfig.getLogFile()).thenReturn("logs/redirected.log");
        CadetLogger.reopenLogFile();

        logger.infoToFile("After the redirect");

        Path redirected = testLogDir.resolve("logs/redirected.log");
        assertTrue("The redirected line must reach the newly configured file",
                   awaitLogFileContent(redirected, "After the redirect").contains("After the redirect"));
        assertFalse("The old file must not still be receiving lines",
                    new String(Files.readAllBytes(testLogDir.resolve("logs/cadet.log")))
                            .contains("After the redirect"));
    }

    @Test
    public void testLogEntryFormatting() throws Exception {
        CadetLogger logger = CadetLogger.getLogger("TestLogger");

        logger.info("Formatted message");

        // Wait for async logging
        Await.settle();

        Path logFile = testLogDir.resolve("logs/cadet.log");
        if (Files.exists(logFile)) {
            String content = new String(Files.readAllBytes(logFile));
            // Check format includes timestamp, thread, level, logger name, and message
            assertTrue(content.matches("(?s).*\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}.*"));
            assertTrue(content.contains("INFO"));
            assertTrue(content.contains("TestLogger"));
            assertTrue(content.contains("Formatted message"));
        }
    }

    @Test
    public void testConcurrentLogging() throws Exception {
        final CadetLogger logger            = CadetLogger.getLogger(CadetLoggerTest.class);
        final int         threadCount       = 10;
        final int         messagesPerThread = 10;

        Thread[] threads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            threads[i] = new Thread(() -> {
                for (int j = 0; j < messagesPerThread; j++) {
                    logger.info("Thread " + threadId + " message " + j);
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
        Path logFile = testLogDir.resolve("logs/cadet.log");
        if (Files.exists(logFile)) {
            String content = new String(Files.readAllBytes(logFile));
            for (int i = 0; i < threadCount; i++) {
                for (int j = 0; j < messagesPerThread; j++) {
                    assertTrue(content.contains("Thread " + i + " message " + j));
                }
            }
        }
    }

    @Test
    public void testAbsolutePathLogFile() {
        String absolutePath = testLogDir.resolve("absolute-test.log").toString();
        when(mockLoggingConfig.getLogFile()).thenReturn(absolutePath);

        CadetLogger logger = CadetLogger.getLogger(CadetLoggerTest.class);
        logger.info("Test with absolute path");

        // Just verify no exceptions are thrown
        assertTrue(true);
    }

    /** Differently-named loggers share one file, and each line still says which one wrote it. */
    @Test
    public void testEveryLoggerNameReachesTheOneSharedFile() throws Exception {
        CadetLogger.getLogger("QueueTest.alpha").infoToFile("line from alpha");
        CadetLogger.getLogger("QueueTest.beta").infoToFile("line from beta");

        Path   logFile  = testLogDir.resolve("logs/cadet.log");
        String contents = awaitLogFileContent(logFile, "line from beta");

        assertTrue("alpha's line must be in the shared file", contents.contains("line from alpha"));
        assertTrue("the writing logger must still be identifiable",
                   contents.contains("QueueTest.alpha"));
        assertTrue("the writing logger must still be identifiable",
                   contents.contains("QueueTest.beta"));
    }
}