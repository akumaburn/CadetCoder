package com.eonmux.cadetcoder.logging;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * The debug log obeys the same size and file-count limits as every other log this tool writes.
 *
 * <p><b>The defect</b>: the debug log rotated at a hardcoded ten megabytes -- ignoring
 * {@code logging.maxLogSize}, which every other log honours -- and rotation meant incrementing a
 * slot number and opening the next file, for ever. {@code logging.maxLogFiles} was never consulted,
 * so nothing ever wrapped and nothing was ever reused: a long {@code --debug} run wrote
 * {@code debug_….log}, {@code debug_….1.log}, {@code .2}, {@code .3} with no end, and each new run
 * added a fresh timestamped set beside the last. The one log written only when a user has asked for
 * detail was the one log that could fill the disk.</p>
 */
public class TheDebugLogIsBoundedLikeEveryOtherLogTest {

    private static final int MAX_LOG_SIZE_MB = 1;
    private static final int MAX_LOG_FILES   = 3;

    /** Enough to overrun a one-megabyte cap several times over. */
    private static final int    LINES   = 12_000;
    private static final String PADDING = "x".repeat(400);

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private MockedStatic<ConfigManager> configManager;

    @Before
    public void setUp() throws Exception {
        forgetLogger();

        Configuration config = new Configuration();
        config.setBaseDir(folder.getRoot().getAbsolutePath());
        config.getLogging().setDebugEnabled(true);
        config.getLogging().setMaxLogSize(MAX_LOG_SIZE_MB);
        config.getLogging().setMaxLogFiles(MAX_LOG_FILES);

        ConfigManager configured = mock(ConfigManager.class);
        when(configured.getConfig()).thenReturn(config);

        configManager = mockStatic(ConfigManager.class);
        configManager.when(ConfigManager::getInstance).thenReturn(configured);
    }

    @After
    public void tearDown() throws Exception {
        try {
            if (configManager != null) {
                configManager.close();
            }
        } finally {
            forgetLogger();
        }
    }

    private static void forgetLogger() throws Exception {
        Field instance = DebugLogger.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    private static void shutDown(DebugLogger logger) throws Exception {
        Method shutdown = DebugLogger.class.getDeclaredMethod("shutdown");
        shutdown.setAccessible(true);
        shutdown.invoke(logger);
    }

    private Path debugDirectory() {
        return folder.getRoot().toPath().resolve("logs").resolve("debug");
    }

    private List<Path> debugFiles() throws IOException {
        List<Path> found = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(debugDirectory(), "*.log")) {
            for (Path entry : entries) {
                found.add(entry);
            }
        }
        return found;
    }

    @Test
    public void rotationWrapsInsteadOfOpeningAnewFileForEver() throws Exception {
        DebugLogger logger = DebugLogger.getInstance();

        for (int line = 0; line < LINES; line++) {
            logger.debug("a-test", "line-" + line + " " + PADDING);
        }
        shutDown(logger);

        List<Path> files = debugFiles();
        assertThat(files)
                .as("logging.maxLogFiles is %d, so that is how many there may be", MAX_LOG_FILES)
                .hasSizeLessThanOrEqualTo(MAX_LOG_FILES);
        assertThat(files)
                .as("far more than the cap was written, so it must have rotated at least once")
                .hasSizeGreaterThan(1);

        long total = 0;
        for (Path file : files) {
            total += Files.size(file);
        }
        // A slot is rotated once it has crossed the cap, so each may overshoot by one entry.
        long overshoot = MAX_LOG_FILES * 64L * 1024L;
        assertThat(total)
                .as("the whole debug log is bounded by the configured size across the configured files")
                .isLessThanOrEqualTo(MAX_LOG_FILES * MAX_LOG_SIZE_MB * 1024L * 1024L + overshoot);
    }

    /** What earlier runs left behind is pruned, so a directory does not grow one set per run. */
    @Test
    public void theDebugLogsOfEarlierRunsArePrunedToTheSameLimit() throws Exception {
        Files.createDirectories(debugDirectory());
        for (int run = 1; run <= 8; run++) {
            Path old = debugDirectory().resolve("debug_2020010" + run + "_120000.log");
            Files.writeString(old, "an earlier run");
            Files.setLastModifiedTime(old, java.nio.file.attribute.FileTime.fromMillis(run * 1000L));
        }

        DebugLogger logger = DebugLogger.getInstance();
        logger.debug("a-test", "this run");
        shutDown(logger);

        assertThat(debugFiles())
                .as("at most the configured number of debug logs survives a new run")
                .hasSizeLessThanOrEqualTo(MAX_LOG_FILES);
    }
}
