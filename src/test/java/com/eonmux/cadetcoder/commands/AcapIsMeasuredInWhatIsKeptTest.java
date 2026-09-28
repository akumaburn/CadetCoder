package com.eonmux.cadetcoder.commands;

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
 * A cap on captured output counts the separators it also stores.
 *
 * <p><b>The defect</b>: the meter advanced by the line's own length while the buffer received the
 * line AND a separator. Ordinary output therefore overran a thirty-thousand-character cap by about a
 * quarter, one-character lines by double -- and output made entirely of empty lines never advanced
 * the meter at all, so the buffer grew without bound for as long as the command ran. That last one
 * is the exact input a cap exists for.</p>
 */
class AcapIsMeasuredInWhatIsKeptTest {

    private static final int CAP = 30_000;

    @TempDir
    Path directory;

    private TestOutputCapture output;

    @BeforeEach
    void setUp() {
        output = new TestOutputCapture();
        output.startCapture();
    }

    @AfterEach
    void tearDown() {
        output.stopCapture();
    }

    /** Runs a shell line with the security screens answering the way an allowed run does. */
    private int run(String commandLine) {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager                manager  = mock(ConfigManager.class);
            Configuration                config   = mock(Configuration.class);
            Configuration.SecurityConfig security = mock(Configuration.SecurityConfig.class);
            when(manager.getConfig()).thenReturn(config);
            when(config.getSecurity()).thenReturn(security);
            when(security.isReadOnlyMode()).thenReturn(false);
            when(security.isAllowRemoteExecution()).thenReturn(true);
            when(security.isRequireConfirmation()).thenReturn(false);
            configMock.when(ConfigManager::getInstance).thenReturn(manager);

            return new BashCommand().execute(new String[] {commandLine});
        }
    }

    /** A file of {@code lines} copies of {@code text}, each on its own line. */
    private Path fileOf(String name, String text, int lines) throws IOException {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < lines; i++) {
            body.append(text).append('\n');
        }
        return Files.writeString(directory.resolve(name), body.toString());
    }

    @Test
    void outputOfNothingButEmptyLinesIsStillCutOff() throws IOException {
        // Under the old meter an empty line counted as nothing, so no number of them reached the cap.
        Path allEmpty = fileOf("empty-lines.txt", "", 200_000);

        run("cat " + allEmpty);

        String said = output.getAllOutput();
        assertThat(said).contains("(output truncated)");
        assertThat(said.length())
                .as("a cap that empty lines walk straight through is not a cap")
                .isLessThan(CAP * 3);
    }

    @Test
    void ordinaryOutputIsCutNearTheCapRatherThanWellPastIt() throws IOException {
        Path plenty = fileOf("long.txt", "0123456789", 20_000);

        run("cat " + plenty);

        String said = output.getAllOutput();
        assertThat(said).contains("(output truncated)");
        assertThat(said.length())
                .as("the separator is stored, so it has to be counted")
                .isLessThan(CAP * 2);
    }

    @Test
    void outputInsideTheCapIsNotCut() {
        assertThat(run("echo hello there")).isZero();

        assertThat(output.getAllOutput())
                .contains("hello there")
                .doesNotContain("(output truncated)");
    }
}
