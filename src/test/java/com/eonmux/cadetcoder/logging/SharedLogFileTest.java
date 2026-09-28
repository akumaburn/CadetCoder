package com.eonmux.cadetcoder.logging;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One log file has one writer.
 *
 * <p>{@code CadetLogger} cached an instance per logger NAME, and every instance ran its own
 * {@code initializeFileLogging}: its own {@code PrintWriter} on the one configured path, its own
 * writer thread, its own shutdown hook, and -- the part that does damage -- its own
 * {@code currentFileSize} and {@code currentFileIndex}. Twenty-one names are constructed across the
 * codebase.</p>
 *
 * <p>So the size limit bounded nothing: each writer counted only the bytes it had written itself, so
 * the file grew to roughly the configured cap MULTIPLIED by the number of loggers before anyone
 * rotated. And rotation was destructive: the first writer to cross its own threshold reopened slot
 * 1 with {@code truncate}, while the other twenty kept appending to handles on files that had been
 * renamed underneath them, writing into rotated slots that a later rotation would then truncate.
 * Log lines from a session were interleaved across slots and partially overwritten -- exactly the
 * situation the log exists to survive.</p>
 */
public class SharedLogFileTest {

    private static long writerThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                     .filter(thread -> thread.getName().startsWith("CadetLogger-Thread"))
                     .count();
    }

    private static Path configuredLogFile() {
        Configuration config = ConfigManager.getInstance().getConfig();
        Path declared = Paths.get(config.getLogging().getLogFile());
        return declared.isAbsolute() ? declared : Paths.get(config.getBaseDir()).resolve(declared);
    }

    @Test
    public void everyLoggerWritesThroughASingleThread() {
        CadetLogger.getLogger("SharedLogFileTest.first").infoToFile("first");
        CadetLogger.getLogger("SharedLogFileTest.second").infoToFile("second");
        CadetLogger.getLogger("SharedLogFileTest.third").infoToFile("third");

        assertThat(writerThreads())
                .as("a writer thread per logger name means several handles on one path, each "
                    + "rotating on a byte count that only it can see")
                .isEqualTo(1);
    }

    @Test
    public void aLineFromEachLoggerStillReachesTheFile() throws Exception {
        String marker = "shared-log-marker-" + UUID.randomUUID();
        CadetLogger.getLogger("SharedLogFileTest.alpha").infoToFile(marker + "-alpha");
        CadetLogger.getLogger("SharedLogFileTest.beta").infoToFile(marker + "-beta");

        Path logFile = configuredLogFile();
        String contents = "";
        for (int attempt = 0; attempt < 100 && !contents.contains(marker + "-beta"); attempt++) {
            Thread.sleep(20);
            contents = Files.exists(logFile) ? Files.readString(logFile, StandardCharsets.UTF_8) : "";
        }

        assertThat(contents)
                .as("sharing a writer must not cost anyone their log lines")
                .contains(marker + "-alpha")
                .contains(marker + "-beta");
    }

    @Test
    public void theLoggerNameStillIdentifiesWhoWroteTheLine() throws Exception {
        String marker = "shared-log-attribution-" + UUID.randomUUID();
        CadetLogger.getLogger("SharedLogFileTest.attributed").infoToFile(marker);

        Path logFile = configuredLogFile();
        String line = "";
        for (int attempt = 0; attempt < 100 && !line.contains(marker); attempt++) {
            Thread.sleep(20);
            if (Files.exists(logFile)) {
                line = Files.readAllLines(logFile, StandardCharsets.UTF_8).stream()
                            .filter(text -> text.contains(marker))
                            .findFirst()
                            .orElse("");
            }
        }

        assertThat(line)
                .as("one shared sink must not turn every line into an anonymous one")
                .contains("SharedLogFileTest.attributed");
    }
}
