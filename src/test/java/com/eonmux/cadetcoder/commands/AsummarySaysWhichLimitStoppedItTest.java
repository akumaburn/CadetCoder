package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.InterruptSignal;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.test.TestOutputCapture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * A batch that stopped says which of the two things stopped it.
 *
 * <p><b>The defect</b>: the summary re-derived the reason at print time from one template, and the
 * template said the line budget had run out. A batch the user stopped therefore reported
 * "--max-total-lines exhausted" with most of that budget unspent, and advised raising a limit that
 * was never the constraint -- advice that cannot work, for a cause that did not happen.</p>
 */
class AsummarySaysWhichLimitStoppedItTest {

    @TempDir
    Path directory;

    private String originalWorkingDir;

    private TestOutputCapture output;

    @BeforeEach
    void setUp() {
        // The searched directory is the project, as it is when a user searches their own code.
        originalWorkingDir = System.getProperty("user.dir");
        System.setProperty("user.dir", directory.toAbsolutePath().toString());
        output = new TestOutputCapture();
        output.startCapture();
        InterruptSignal.clear();
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.dir", originalWorkingDir);
        InterruptSignal.clear();
        output.stopCapture();
    }

    private Path[] someFiles(int count, int linesEach) throws IOException {
        Path[] made = new Path[count];
        for (int i = 0; i < count; i++) {
            StringBuilder body = new StringBuilder();
            for (int line = 0; line < linesEach; line++) {
                body.append("line ").append(line).append('\n');
            }
            made[i] = Files.writeString(directory.resolve("file" + i + ".txt"), body.toString());
        }
        return made;
    }

    private int multiread(String... args) {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager manager = mock(ConfigManager.class);
            when(manager.getConfig()).thenReturn(new Configuration());
            configMock.when(ConfigManager::getInstance).thenReturn(manager);

            return new MultiReadCommand().execute(args);
        }
    }

    @Test
    void abatchTheUserStoppedSaysItWasStopped() throws IOException {
        Path[] files = someFiles(4, 2);
        InterruptSignal.request();

        multiread(files[0].toString(), files[1].toString(), files[2].toString(),
                  files[3].toString());

        assertThat(output.getAllOutput())
                .contains("Not read - stopped")
                .doesNotContain("--max-total-lines")
                .doesNotContain("exhausted");
    }

    @Test
    void abatchThatRanOutOfLinesSaysThatInstead() throws IOException {
        Path[] files = someFiles(4, 40);

        multiread(files[0].toString(), files[1].toString(), files[2].toString(),
                  files[3].toString(), "--max-total-lines=50");

        assertThat(output.getAllOutput())
                .contains("--max-total-lines=50")
                .contains("exhausted")
                .doesNotContain("Not read - stopped");
    }

    @Test
    void abatchThatReadEverythingMentionsNeither() throws IOException {
        Path[] files = someFiles(2, 2);

        assertThat(multiread(files[0].toString(), files[1].toString())).isZero();

        assertThat(output.getAllOutput())
                .doesNotContain("Not read - stopped")
                .doesNotContain("exhausted");
    }
}
