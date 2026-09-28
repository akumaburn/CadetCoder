package com.eonmux.cadetcoder.logging;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * A configuration that names no log file switches file logging off -- it does not turn every log
 * line into console output.
 *
 * <p>The shared writer resolves its destination from the configuration. When it has none, an entry
 * has nowhere to go, and the writer used to report that to its caller the same way it reports a full
 * queue. {@code CadetLogger} then printed the entry on the console, so every message the program
 * logged appeared a second time -- beside the line its caller had already rendered, and including
 * the {@code infoToFile} entries that are meant for the file alone. One badly-timed first log line
 * (the destination is resolved once, on the first entry) was enough to leave the whole process in
 * that state.</p>
 *
 * <p>The two cases are not the same thing. A full queue loses a line that the configuration asked to
 * keep, and saying so is the only way the user finds out. No destination is what the configuration
 * asked for, and there is nothing to report.</p>
 */
public class LoggingWithoutALogFileTest {

    private ByteArrayOutputStream       errContent;
    private ByteArrayOutputStream       outContent;
    private PrintStream                 originalErr;
    private PrintStream                 originalOut;
    private Path                        baseDir;
    private MockedStatic<ConfigManager> configManagerMock;
    private Configuration.LoggingConfig loggingConfig;

    @Before
    public void setUp() throws Exception {
        originalErr = System.err;
        originalOut = System.out;
        errContent  = new ByteArrayOutputStream();
        outContent  = new ByteArrayOutputStream();
        System.setErr(new PrintStream(errContent, true, StandardCharsets.UTF_8.name()));
        System.setOut(new PrintStream(outContent, true, StandardCharsets.UTF_8.name()));

        baseDir = Files.createTempDirectory("cadet-no-log-file");

        ConfigManager          configManager = mock(ConfigManager.class);
        Configuration          configuration = mock(Configuration.class);
        Configuration.UiConfig uiConfig      = mock(Configuration.UiConfig.class);
        loggingConfig = mock(Configuration.LoggingConfig.class);

        when(loggingConfig.getMaxLogSize()).thenReturn(10);
        when(loggingConfig.getMaxLogFiles()).thenReturn(5);
        when(uiConfig.getVerbosityLevel()).thenReturn(Configuration.UiConfig.VERBOSITY.NORMAL.ordinal());
        when(uiConfig.isColorEnabled()).thenReturn(false);
        when(configuration.getLogging()).thenReturn(loggingConfig);
        when(configuration.getUi()).thenReturn(uiConfig);
        when(configuration.getBaseDir()).thenReturn(baseDir.toString());
        when(configManager.getConfig()).thenReturn(configuration);

        configManagerMock = mockStatic(ConfigManager.class);
        configManagerMock.when(ConfigManager::getInstance).thenReturn(configManager);
    }

    @After
    public void tearDown() throws Exception {
        System.setErr(originalErr);
        System.setOut(originalOut);

        if (configManagerMock != null) {
            configManagerMock.close();
        }
        // With the mock gone, point the shared writer back at the real configured file so the next
        // test does not find its log lines missing.
        CadetLogger.reopenLogFile();

        if (Files.exists(baseDir)) {
            Files.walk(baseDir)
                 .sorted((a, b) -> -a.compareTo(b))
                 .map(Path::toFile)
                 .forEach(File::delete);
        }
    }

    private String console() {
        return outContent.toString(StandardCharsets.UTF_8) + errContent.toString(StandardCharsets.UTF_8);
    }

    private static int occurrencesOf(String needle, String haystack) {
        int count = 0;
        for (int index = haystack.indexOf(needle); index >= 0; index = haystack.indexOf(needle, index + 1)) {
            count++;
        }
        return count;
    }

    @Test
    public void anErrorIsReportedOnceWhenThereIsNoLogFile() {
        when(loggingConfig.getLogFile()).thenReturn(null);
        CadetLogger.reopenLogFile();

        String message = "no-log-file-error-" + UUID.randomUUID();
        CadetLogger.getLogger("LoggingWithoutALogFileTest.error").error(message);

        assertThat(occurrencesOf(message, console()))
                .as("the caller already rendered this line; the logger must not render it again "
                    + "merely because there is no file to put it in")
                .isEqualTo(1);
    }

    @Test
    public void aFileOnlyEntryStaysOffTheConsoleWhenThereIsNoLogFile() {
        when(loggingConfig.getLogFile()).thenReturn(null);
        CadetLogger.reopenLogFile();

        String message = "no-log-file-info-" + UUID.randomUUID();
        CadetLogger.getLogger("LoggingWithoutALogFileTest.info").infoToFile(message);

        assertThat(console())
                .as("infoToFile is diagnostic; with nowhere to record it, it is dropped, not shown")
                .doesNotContain(message);
    }

    /** {@code logging.logFile=""} resolves to the base directory, which is not a file. */
    @Test
    public void aBlankSettingMeansNoLogFileRatherThanABrokenOne() {
        when(loggingConfig.getLogFile()).thenReturn("   ");
        CadetLogger.reopenLogFile();

        String message = "blank-log-file-" + UUID.randomUUID();
        CadetLogger.getLogger("LoggingWithoutALogFileTest.blank").infoToFile(message);

        assertThat(console())
                .as("a blank setting is a log switched off, not a log that failed to open")
                .doesNotContain(message)
                .doesNotContain("Failed to open log file");
    }

    @Test
    public void loggingResumesWhenALogFileIsConfiguredAgain() throws Exception {
        when(loggingConfig.getLogFile()).thenReturn(null);
        CadetLogger.reopenLogFile();
        CadetLogger.getLogger("LoggingWithoutALogFileTest.resume").infoToFile("dropped while off");

        Path logFile = baseDir.resolve("logs/cadet.log");
        when(loggingConfig.getLogFile()).thenReturn("logs/cadet.log");
        CadetLogger.reopenLogFile();

        String message = "resumed-" + UUID.randomUUID();
        CadetLogger.getLogger("LoggingWithoutALogFileTest.resume").infoToFile(message);

        String contents = "";
        for (int attempt = 0; attempt < 100 && !contents.contains(message); attempt++) {
            Thread.sleep(20);
            contents = Files.exists(logFile) ? Files.readString(logFile, StandardCharsets.UTF_8) : "";
        }

        assertThat(contents)
                .as("switching the log back on must actually start writing again")
                .contains(message);
        assertThat(contents)
                .as("lines logged while there was nowhere to put them are gone, not replayed")
                .doesNotContain("dropped while off");
    }

    /** Switching off and on again must not leave a second thread appending to the same file. */
    @Test
    public void switchingTheLogOffAndOnLeavesOneWriterThread() {
        when(loggingConfig.getLogFile()).thenReturn("logs/cadet.log");
        CadetLogger.reopenLogFile();
        when(loggingConfig.getLogFile()).thenReturn(null);
        CadetLogger.reopenLogFile();
        when(loggingConfig.getLogFile()).thenReturn("logs/cadet.log");
        CadetLogger.reopenLogFile();

        long writerThreads = Thread.getAllStackTraces().keySet().stream()
                                   .filter(thread -> thread.getName().startsWith("CadetLogger-Thread"))
                                   .count();

        assertThat(writerThreads)
                .as("a thread per switch means several handles on one path, each rotating on a byte "
                    + "count only it can see")
                .isEqualTo(1);
    }
}
