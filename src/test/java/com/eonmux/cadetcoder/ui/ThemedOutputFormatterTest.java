package com.eonmux.cadetcoder.ui;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.logging.CadetLogger;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.*;
import org.mockito.MockedStatic;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static com.eonmux.cadetcoder.testsupport.ConsoleAssertions.assertError;
import static com.eonmux.cadetcoder.testsupport.ConsoleAssertions.assertHeader;
import static com.eonmux.cadetcoder.testsupport.ConsoleAssertions.assertSubheader;
import static com.eonmux.cadetcoder.testsupport.ConsoleAssertions.assertSuccess;
import static com.eonmux.cadetcoder.testsupport.ConsoleAssertions.assertWarning;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.*;

/**
 * Test class for ThemedOutputFormatter
 */
public class ThemedOutputFormatterTest {

    private TestOutputCapture outputCapture;
    private com.eonmux.cadetcoder.ui.TuiTheme originalTheme;

    @Before
    public void setUp() {
        outputCapture = new TestOutputCapture();
        originalTheme = com.eonmux.cadetcoder.ui.TuiThemeManager.getCurrentTheme();
        ThemedOutputFormatter.clearColorCache();
    }

    @After
    public void tearDown() {
        outputCapture.restore();
        if (originalTheme != null) {
            com.eonmux.cadetcoder.ui.TuiThemeManager.setTheme(originalTheme.getName());
        }
        ThemedOutputFormatter.clearColorCache();
    }

    @Test
    public void testPrintHeader() {
        ThemedOutputFormatter.printHeader("Test Header");

        // Classification rather than the literal decoration: the marker is chosen at runtime from
        // the terminal's encoding (Glyphs.system()), and what the shell actually depends on is that
        // the line classifies as a header.
        assertHeader(outputCapture.getStdout(), "Test Header");
    }

    @Test
    public void testPrintSubheader() {
        ThemedOutputFormatter.printSubheader("Test Subheader");

        assertSubheader(outputCapture.getStdout(), "Test Subheader");
    }

    @Test
    public void testPrintSuccess() {
        ThemedOutputFormatter.printSuccess("Operation completed");

        assertSuccess(outputCapture.getStdout(), "Operation completed");
    }

    @Test
    public void testPrintWarning() {
        ThemedOutputFormatter.printWarning("Warning message");

        assertWarning(outputCapture.getStdout(), "Warning message");
    }

    @Test
    public void testPrintError() {
        ThemedOutputFormatter.printError("Error occurred");

        String errorOutput = outputCapture.getStderr();
        assertError(errorOutput, "Error occurred");

        // The regression this guards: CadetLogger.error() used to echo a message printError had
        // already printed, and SLF4J added a third copy, so one error surfaced three times. It was
        // asserted as doesNotContain("✗") because that was the logger echo's marker; "✗" is now the
        // formatter's own error marker as well, so only the count still distinguishes one copy from
        // three -- and unlike the marker, the count says exactly what the requirement is.
        assertThat(countOccurrences(outputCapture.getAllOutput(), "Error occurred")).isEqualTo(1);
    }

    @Test
    public void testPrintErrorLogsToFileOnly() throws Exception {
        Path baseDir = Files.createTempDirectory("themed-formatter-error-log");
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            mockConfig(configMock, baseDir);
            resetLoggerState();

            ThemedOutputFormatter.printError("Formatter error to file");

            // Exactly one user-facing line, on stderr, with no console echo from the logger.
            String errorOutput = outputCapture.getStderr();
            assertError(errorOutput, "Formatter error to file");
            assertThat(countOccurrences(outputCapture.getAllOutput(), "Formatter error to file")).isEqualTo(1);

            // ... and the message is still recorded in the rotating log file.
            String logContent = awaitLogFileContent(baseDir.resolve("logs/cadet.log"),
                                                    "Formatter error to file");
            assertThat(logContent).contains("ERROR");
        } finally {
            resetLoggerState();
            deleteRecursively(baseDir);
        }
    }

    @Test
    public void testPrintWarningLogsToFileOnly() throws Exception {
        Path baseDir = Files.createTempDirectory("themed-formatter-warn-log");
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            mockConfig(configMock, baseDir);
            resetLoggerState();

            ThemedOutputFormatter.printWarning("Formatter warning to file");

            assertWarning(outputCapture.getStdout(), "Formatter warning to file");
            assertThat(countOccurrences(outputCapture.getAllOutput(), "Formatter warning to file")).isEqualTo(1);

            String logContent = awaitLogFileContent(baseDir.resolve("logs/cadet.log"),
                                                    "Formatter warning to file");
            assertThat(logContent).contains("WARN");
        } finally {
            resetLoggerState();
            deleteRecursively(baseDir);
        }
    }

    /**
     * Points ConfigManager at a throwaway base directory so CadetLogger writes its log file there.
     */
    private void mockConfig(MockedStatic<ConfigManager> configMock, Path baseDir) {
        ConfigManager               mockConfigManager = mock(ConfigManager.class);
        Configuration               mockConfiguration = mock(Configuration.class);
        Configuration.UiConfig      mockUiConfig      = mock(Configuration.UiConfig.class);
        Configuration.LoggingConfig mockLoggingConfig = mock(Configuration.LoggingConfig.class);

        when(mockUiConfig.getVerbosityLevel()).thenReturn(Configuration.UiConfig.VERBOSITY.NORMAL.ordinal());
        when(mockUiConfig.isColorEnabled()).thenReturn(false);
        when(mockLoggingConfig.getLogFile()).thenReturn("logs/cadet.log");
        when(mockLoggingConfig.getMaxLogSize()).thenReturn(10);
        when(mockLoggingConfig.getMaxLogFiles()).thenReturn(5);
        when(mockConfiguration.getUi()).thenReturn(mockUiConfig);
        when(mockConfiguration.getLogging()).thenReturn(mockLoggingConfig);
        when(mockConfiguration.getBaseDir()).thenReturn(baseDir.toString());
        when(mockConfigManager.getConfig()).thenReturn(mockConfiguration);
        configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
    }

    /**
     * Drops the cached CadetLogger instances so the next call builds one against the mocked config
     * (and so later tests do not reuse a logger bound to a deleted temp directory), and points the
     * shared log writer at whatever configuration is live now.
     *
     * <p>The log file has a single writer for the whole process, and it resolved its destination
     * from whatever configuration was live when the first line was logged. Installing -- or
     * removing -- a mocked configuration is a configuration change, so it is announced the same way
     * a {@code config logging.logFile} would be.</p>
     */
    private void resetLoggerState() throws Exception {
        Field cacheField = CadetLogger.class.getDeclaredField("loggerCache");
        cacheField.setAccessible(true);
        ((ConcurrentHashMap<?, ?>) cacheField.get(null)).clear();

        Field formatterLogger = ThemedOutputFormatter.class.getDeclaredField("logger");
        formatterLogger.setAccessible(true);
        formatterLogger.set(null, null);

        CadetLogger.reopenLogFile();
    }

    /**
     * Waits for the asynchronous logger thread to flush an entry containing the given fragment.
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
            Thread.sleep(25);
        }
        fail("Log file " + logFile + " never contained \"" + expectedFragment + "\"; content was: " + content);
        return content;
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            count++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return count;
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        Files.walk(root)
             .sorted((a, b) -> -a.compareTo(b))
             .map(Path::toFile)
             .forEach(File::delete);
    }

    @Test
    public void testPrintInfo() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager          mockConfigManager = mock(ConfigManager.class);
            Configuration          mockConfig        = mock(Configuration.class);
            Configuration.UiConfig mockUiConfig      = mock(Configuration.UiConfig.class);

            when(mockUiConfig.getVerbosityLevel()).thenReturn(Configuration.UiConfig.VERBOSITY.NORMAL.ordinal());
            when(mockConfig.getUi()).thenReturn(mockUiConfig);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            ThemedOutputFormatter.printInfo("Info message");

            String output = AnsiStripper.strip(outputCapture.getStdout());
            assertThat(output).contains("Info message");
            assertThat(output).doesNotContain(Glyphs.system().infoMarker());
        }
    }

    @Test
    public void testPrintInfo_VerbosityTooLow() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager          mockConfigManager = mock(ConfigManager.class);
            Configuration          mockConfig        = mock(Configuration.class);
            Configuration.UiConfig mockUiConfig      = mock(Configuration.UiConfig.class);

            when(mockUiConfig.getVerbosityLevel()).thenReturn(Configuration.UiConfig.VERBOSITY.MINIMAL.ordinal());
            when(mockConfig.getUi()).thenReturn(mockUiConfig);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            ThemedOutputFormatter.printInfo("Info message");

            String output = outputCapture.getStdout();
            assertThat(output).isEmpty();
        }
    }

    @Test
    public void testPrintCommand() {
        ThemedOutputFormatter.printCommand("git commit -m \"message\"");

        String output = outputCapture.getStdout();
        assertThat(output).contains("git commit -m \"message\"");
    }

    @Test
    public void testPrintPath() {
        ThemedOutputFormatter.printPath("/path/to/file.txt");

        String output = outputCapture.getStdout();
        assertThat(output).contains("/path/to/file.txt");
    }

    @Test
    public void testPrintString() {
        ThemedOutputFormatter.printString("string value");

        String output = outputCapture.getStdout();
        assertThat(output).contains("string value");
    }

    @Test
    public void testPrintAccent1() {
        ThemedOutputFormatter.printAccent1("accent text");

        String output = outputCapture.getStdout();
        assertThat(output).contains("accent text");
    }

    @Test
    public void testPrintAccent2() {
        ThemedOutputFormatter.printAccent2("accent2 text");

        String output = outputCapture.getStdout();
        assertThat(output).contains("accent2 text");
    }

    @Test
    public void testPrintDim() {
        ThemedOutputFormatter.printDim("dim text");

        String output = outputCapture.getStdout();
        assertThat(output).contains("dim text");
    }

    @Test
    public void testPrintBold() {
        ThemedOutputFormatter.printBold("bold text");

        String output = outputCapture.getStdout();
        assertThat(output).contains("bold text");
    }

    @Test
    public void testPrintCodeBlock_WithColors() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager          mockConfigManager = mock(ConfigManager.class);
            Configuration          mockConfig        = mock(Configuration.class);
            Configuration.UiConfig mockUiConfig      = mock(Configuration.UiConfig.class);

            when(mockUiConfig.isColorEnabled()).thenReturn(true);
            when(mockConfig.getUi()).thenReturn(mockUiConfig);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            ThemedOutputFormatter.printCodeBlock("public class Test {}");

            String output = outputCapture.getStdout();
            assertThat(output).contains("public class Test {}");
            assertThat(output).contains("```");
        }
    }

    @Test
    public void testPrintCodeBlock_WithoutColors() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager          mockConfigManager = mock(ConfigManager.class);
            Configuration          mockConfig        = mock(Configuration.class);
            Configuration.UiConfig mockUiConfig      = mock(Configuration.UiConfig.class);

            when(mockUiConfig.isColorEnabled()).thenReturn(false);
            when(mockConfig.getUi()).thenReturn(mockUiConfig);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            ThemedOutputFormatter.printCodeBlock("public class Test {}");

            String output = outputCapture.getStdout();
            assertThat(output).contains("public class Test {}");
            assertThat(output).contains("```");
            // Should not contain ANSI escape codes
            assertThat(output).doesNotContain("\u001B[");
        }
    }

    @Test
    public void testPrintTable_EmptyRows() {
        List<String[]> emptyRows = List.of();

        ThemedOutputFormatter.printTable(emptyRows, true);

        String output = outputCapture.getStdout();
        assertThat(output).isEmpty();
    }

    @Test
    public void testPrintTable_WithHeader() {
        List<String[]> rows = Arrays.asList(
                new String[] {"Name", "Age", "City"},
                new String[] {"John", "25", "New York"},
                new String[] {"Jane", "30", "London"}
                                           );

        ThemedOutputFormatter.printTable(rows, true);

        String output = outputCapture.getStdout();
        assertThat(output).contains("Name");
        assertThat(output).contains("Age");
        assertThat(output).contains("City");
        assertThat(output).contains("John");
        assertThat(output).contains("Jane");
        assertThat(output).contains("25");
        assertThat(output).contains("30");
        assertThat(output).contains("New York");
        assertThat(output).contains("London");
        // Header separator, drawn with the glyph set's rule character. A run of hyphens was both
        // Markdown's thematic break and its list bullet, so the shell's Markdown renderer replaced
        // this line with a full-width rule of its own.
        assertThat(output).contains(Glyphs.system().rule(4));
        // ... and no row carries trailing padding into a copy-paste.
        for (String line : output.split("\\R")) {
            assertThat(line).as("row should not be right-padded: \"%s\"", line).isEqualTo(stripTrailing(line));
        }
    }

    /** {@code String.stripTrailing()} spelled out, so the assertion above reads as its own intent. */
    private static String stripTrailing(String text) {
        return text.stripTrailing();
    }

    @Test
    public void testPrintTable_WithoutHeader() {
        List<String[]> rows = Arrays.asList(
                new String[] {"John", "25", "New York"},
                new String[] {"Jane", "30", "London"}
                                           );

        ThemedOutputFormatter.printTable(rows, false);

        String output = outputCapture.getStdout();
        assertThat(output).contains("John");
        assertThat(output).contains("Jane");
        assertThat(output).doesNotContain("---"); // No header separator
    }

    @Test
    public void testInitializeTheme() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager          mockConfigManager = mock(ConfigManager.class);
            Configuration          mockConfig        = mock(Configuration.class);
            Configuration.UiConfig mockUiConfig      = mock(Configuration.UiConfig.class);

            when(mockUiConfig.getColorTheme()).thenReturn("solarized-dark");
            when(mockConfig.getUi()).thenReturn(mockUiConfig);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            ThemedOutputFormatter.initializeTheme();

            // Use new Jexer theme manager for verification
            assertThat(com.eonmux.cadetcoder.ui.TuiThemeManager.getCurrentTheme().getName()).isEqualTo("solarized-dark");
        }
    }

    @Test
    public void testInitializeTheme_EmptyThemeName() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager          mockConfigManager = mock(ConfigManager.class);
            Configuration          mockConfig        = mock(Configuration.class);
            Configuration.UiConfig mockUiConfig      = mock(Configuration.UiConfig.class);

            when(mockUiConfig.getColorTheme()).thenReturn("");
            when(mockConfig.getUi()).thenReturn(mockUiConfig);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            com.eonmux.cadetcoder.ui.TuiTheme beforeTheme = com.eonmux.cadetcoder.ui.TuiThemeManager.getCurrentTheme();

            ThemedOutputFormatter.initializeTheme();

            // Theme should remain unchanged
            assertThat(com.eonmux.cadetcoder.ui.TuiThemeManager.getCurrentTheme()).isEqualTo(beforeTheme);
        }
    }

    @Test
    public void testInitializeTheme_ConfigException() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            configMock.when(ConfigManager::getInstance).thenThrow(new RuntimeException("Config error"));

            com.eonmux.cadetcoder.ui.TuiTheme beforeTheme = com.eonmux.cadetcoder.ui.TuiThemeManager.getCurrentTheme();

            // Should not throw exception
            ThemedOutputFormatter.initializeTheme();

            // Theme should remain unchanged
            assertThat(com.eonmux.cadetcoder.ui.TuiThemeManager.getCurrentTheme()).isEqualTo(beforeTheme);
        }
    }

    @Test
    public void testClearColorCache() {
        // Test that clearColorCache doesn't throw exceptions
        ThemedOutputFormatter.clearColorCache();

        // This method clears internal cache, so we just verify it doesn't crash
        assertThat(true).isTrue();
    }

    @Test
    public void testColorEnabled_CircularDependencyPrevention() {
        // Test that color checking doesn't cause circular dependency during config init
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            // Simulate being called during ConfigManager initialization
            configMock.when(ConfigManager::getInstance).thenAnswer(invocation -> {
                // Check if we're in a stack trace that includes ConfigManager initialization
                StackTraceElement[] stack = Thread.currentThread().getStackTrace();
                for (StackTraceElement element : stack) {
                    if (element.getClassName().equals(ConfigManager.class.getName()) &&
                        element.getMethodName().equals("loadConfig")) {
                        return null; // Simulate circular dependency scenario
                    }
                }
                return mock(ConfigManager.class);
            });

            // This should not cause circular dependency issues
            ThemedOutputFormatter.printSuccess("test");

            assertSuccess(outputCapture.getStdout(), "test");
        }
    }

    @Test
    public void aMultiLineMarkedMessageIsIndentedToTheColumnItsFirstLineStartedIn() {
        // A message is not always one line: an error routinely carries a detail line. Only the
        // first physical line used to get the marker, leaving every following line hanging in
        // column 0, unmarked and -- in the shell -- unclassifiable, so it rendered as unrelated
        // body text.
        ThemedOutputFormatter.printWarning("first line\nsecond line\nthird line");

        // ANSI-stripped: this class runs with colour enabled, so each line carries escape codes
        // around the marker -- which is also why classify() strips before matching.
        String[] lines = AnsiStripper.strip(outputCapture.getStdout()).split("\\R");
        String marker  = Glyphs.system().warningMarker();
        assertThat(lines[0]).isEqualTo(marker + " first line");
        assertThat(lines[1]).isEqualTo(" ".repeat(marker.length() + 1) + "second line");
        assertThat(lines[2]).isEqualTo(" ".repeat(marker.length() + 1) + "third line");
    }

    @Test
    public void aMultiLineHeaderClosesOnItsFirstLineNotItsLast() {
        // Only meaningful for the ASCII vocabulary, which has a closing delimiter. Appending it after
        // the LAST line produced "=== line one" / "    line two ===" -- delimiters that no longer
        // wrap anything, and a first line the classifier would not recognise as a header.
        ThemedOutputFormatter.printHeader("line one\nline two");

        String[] lines = AnsiStripper.strip(outputCapture.getStdout()).split("\\R");
        Glyphs   g     = Glyphs.system();
        assertThat(lines[0]).isEqualTo(g.headerMarker() + " line one" + g.headerCloser());
        assertThat(lines[1]).doesNotEndWith(g.headerCloser().isEmpty() ? "\u0000" : g.headerCloser());
        assertThat(OutputLineStyler.classify(lines[0])).isEqualTo(OutputLineStyler.Kind.HEADER);
    }

    @Test
    public void everyMarkedLineClassifiesAsWhatItWasPrintedAs() {
        // The contract the shell depends on: what the formatter emits, OutputLineStyler recognises.
        ThemedOutputFormatter.printSuccess("s");
        ThemedOutputFormatter.printWarning("w");
        ThemedOutputFormatter.printHeader("h");
        ThemedOutputFormatter.printSubheader("sub");
        ThemedOutputFormatter.printError("e");

        String out = AnsiStripper.strip(outputCapture.getStdout());
        assertThat(OutputLineStyler.classify(lineContaining(out, "s"))).isEqualTo(OutputLineStyler.Kind.SUCCESS);
        assertThat(OutputLineStyler.classify(lineContaining(out, "w"))).isEqualTo(OutputLineStyler.Kind.WARNING);
        assertThat(OutputLineStyler.classify(lineContaining(out, "h"))).isEqualTo(OutputLineStyler.Kind.HEADER);
        assertThat(OutputLineStyler.classify(lineContaining(out, "sub"))).isEqualTo(OutputLineStyler.Kind.SUBHEADER);
        assertThat(OutputLineStyler.classify(lineContaining(AnsiStripper.strip(outputCapture.getStderr()), "e")))
                .isEqualTo(OutputLineStyler.Kind.ERROR);
    }

    /** The first line of {@code output} whose text (after the marker and a space) is exactly {@code body}. */
    private static String lineContaining(String output, String body) {
        for (String line : output.split("\\R")) {
            if (line.endsWith(" " + body)) {
                return line;
            }
        }
        return "";
    }

    @Test
    public void aMessageEndingInANewlineDoesNotLeaveALineOfIndent() {
        // Callers routinely end a message with "\n" to leave a gap. Rendering that as a continuation
        // line produced a row containing nothing but the marker's indent -- invisible on screen, but
        // trailing whitespace in anything that copies, greps or logs the output.
        ThemedOutputFormatter.printInfo("Found 10 themes:\n");

        String[] lines = AnsiStripper.strip(outputCapture.getStdout()).split("\\R");
        assertThat(lines).hasSize(1);
        assertThat(lines[0]).isEqualTo("Found 10 themes:");
    }

    @Test
    public void anEmptyMessageIsABlankLineAndNotALoneMarker() {
        // A blank message is how a caller asks for a blank line between two blocks. Marking it
        // produced a line holding a marker, a space and nothing else -- a stray bullet on screen
        // and a line of trailing whitespace in anything that copied the output.
        ThemedOutputFormatter.printWarning("");

        String printed = AnsiStripper.strip(outputCapture.getStdout());
        assertThat(printed.strip()).isEmpty();
        assertThat(printed).doesNotContain(Glyphs.system().warningMarker());
    }

    @Test
    public void aNullMessageIsRenderedAsAnEmptyLineRatherThanThrowing() {
        // Printing must never fail its caller, whatever it is handed.
        ThemedOutputFormatter.printInfo(null);

        String printed = AnsiStripper.strip(outputCapture.getStdout());
        assertThat(printed.strip()).isEmpty();
        assertThat(printed).doesNotContain(Glyphs.system().infoMarker());
    }

    @Test
    public void aMessageContainingACodeFenceCannotSwallowWhatIsPrintedAfterIt() {
        // The hazard: the marker on the first line hides a fence that OPENS the message, while the
        // fence that CLOSES it survives the continuation indent and is then read as an opening
        // fence -- so the shell's Markdown renderer consumes everything printed afterwards, other
        // commands' output included, into one code box.
        ThemedOutputFormatter.printInfo("```java\nint x = 1;\n```\nThat is the change.");
        ThemedOutputFormatter.printSuccess("Done");

        String[] lines = AnsiStripper.strip(outputCapture.getStdout()).split("\\R");
        Glyphs   g     = Glyphs.system();

        // Every line of the block carries the marker, so no line of it can be read as a fence...
        for (int i = 0; i < 4; i++) {
            assertThat(lines[i]).startsWith(g.infoMarker() + " ");
            assertThat(OutputLineStyler.classify(lines[i])).isEqualTo(OutputLineStyler.Kind.INFO);
        }
        // ... and the line printed after it is still its own success line.
        assertThat(OutputLineStyler.classify(lines[4])).isEqualTo(OutputLineStyler.Kind.SUCCESS);
    }

    @Test
    public void aMessageWithNoFenceCarriesNothingInFrontOfAnyOfItsLines() {
        // The ordinary case, and the reason the marker went: a notice is prose, and its position
        // already says what it is. With no marker there is no width to hang the rest of it under,
        // so every line starts where the first one did.
        ThemedOutputFormatter.printInfo("first\nsecond");

        String[] lines = AnsiStripper.strip(outputCapture.getStdout()).split("\\R");
        assertThat(lines[0]).isEqualTo("first");
        assertThat(lines[1]).isEqualTo("second");
    }
}