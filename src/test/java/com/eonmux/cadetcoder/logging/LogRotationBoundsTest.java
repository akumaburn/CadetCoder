package com.eonmux.cadetcoder.logging;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.MockitoJUnitRunner;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * The configured size limit has to actually limit the size.
 *
 * <p>Every {@code CadetLogger} used to open its own writer on the one configured path and keep its
 * own byte counter, so each of the twenty-one logger names in this codebase counted only the bytes
 * it had written itself. Nobody rotated until their own share crossed the cap, which means the file
 * grew to roughly the cap multiplied by the number of loggers -- and when one of them finally did
 * rotate, it truncated a slot the other twenty were still appending to.</p>
 *
 * <p>So this writes through several logger names at once, which is what the running program does,
 * and then measures the files.</p>
 */
@RunWith (MockitoJUnitRunner.Silent.class)
public class LogRotationBoundsTest {

    private static final int MAX_LOG_SIZE_MB = 1;
    private static final int MAX_LOG_FILES   = 3;
    private static final long CAP_BYTES      = MAX_LOG_SIZE_MB * 1024L * 1024L;

    /** Enough to overrun a 1MB cap several times over, spread across several logger names. */
    private static final int    LINES   = 9_000;
    private static final String PADDING = "x".repeat(400);

    @Mock private ConfigManager               mockConfigManager;
    @Mock private Configuration               mockConfiguration;
    @Mock private Configuration.LoggingConfig mockLoggingConfig;

    private Path                        logDir;
    private MockedStatic<ConfigManager> configManagerMock;

    @Before
    public void setUp() throws Exception {
        logDir = Paths.get(System.getProperty("java.io.tmpdir"), "cadet-rotation-bounds-test");
        deleteTree(logDir);
        Files.createDirectories(logDir);

        configManagerMock = mockStatic(ConfigManager.class);
        configManagerMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
        when(mockConfigManager.getConfig()).thenReturn(mockConfiguration);
        when(mockConfiguration.getLogging()).thenReturn(mockLoggingConfig);
        when(mockConfiguration.getBaseDir()).thenReturn(logDir.toString());
        when(mockLoggingConfig.getLogFile()).thenReturn("cadet.log");
        when(mockLoggingConfig.getMaxLogSize()).thenReturn(MAX_LOG_SIZE_MB);
        when(mockLoggingConfig.getMaxLogFiles()).thenReturn(MAX_LOG_FILES);

        CadetLogger.reopenLogFile();
    }

    @After
    public void tearDown() throws Exception {
        if (configManagerMock != null) {
            configManagerMock.close();
        }
        // Put the shared writer back on the real configured file before leaving.
        CadetLogger.reopenLogFile();

        java.lang.reflect.Field cache = CadetLogger.class.getDeclaredField("loggerCache");
        cache.setAccessible(true);
        ((java.util.Map<?, ?>) cache.get(null)).clear();

        deleteTree(logDir);
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        Files.walk(root).sorted((a, b) -> -a.compareTo(b)).map(Path::toFile).forEach(File::delete);
    }

    private List<Path> logFiles() throws Exception {
        try (java.util.stream.Stream<Path> entries = Files.list(logDir)) {
            return entries.filter(path -> path.getFileName().toString().startsWith("cadet.log"))
                          .collect(Collectors.toList());
        }
    }

    private long totalBytes() throws Exception {
        long total = 0;
        for (Path file : logFiles()) {
            total += Files.size(file);
        }
        return total;
    }

    /** Writes far past the cap through several names, then waits for the queue to settle. */
    private void floodTheLog() throws Exception {
        String[] names = {"Rotation.alpha", "Rotation.beta", "Rotation.gamma"};
        for (int line = 0; line < LINES; line++) {
            CadetLogger.getLogger(names[line % names.length])
                       .infoToFile("rotation line " + line + " " + PADDING);
        }

        long previous = -1;
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
            long current = totalBytes();
            if (current == previous && current > 0) {
                return;
            }
            previous = current;
        }
    }

    @Test
    public void noSingleSlotGrowsPastTheConfiguredSize() throws Exception {
        floodTheLog();

        // One line may straddle the threshold, since the check happens after the write.
        long allowance = CAP_BYTES + (PADDING.length() * 4L);
        for (Path file : logFiles()) {
            assertThat(Files.size(file))
                    .as("%s must be bounded by the configured %d MB, whoever wrote into it",
                        file.getFileName(), MAX_LOG_SIZE_MB)
                    .isLessThanOrEqualTo(allowance);
        }
    }

    @Test
    public void theLogNeverOccupiesMoreThanSizeTimesCount() throws Exception {
        floodTheLog();

        long budget = CAP_BYTES * MAX_LOG_FILES + (PADDING.length() * 4L * MAX_LOG_FILES);
        assertThat(totalBytes())
                .as("the whole point of maxLogSize x maxLogFiles is that this is the ceiling")
                .isLessThanOrEqualTo(budget);
    }

    @Test
    public void rotationDoesNotExceedTheConfiguredNumberOfFiles() throws Exception {
        floodTheLog();

        assertThat(logFiles())
                .as("slots are reused, so the count is bounded too")
                .hasSizeLessThanOrEqualTo(MAX_LOG_FILES);
    }
}
