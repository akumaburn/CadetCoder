package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.test.TestOutputCapture;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import com.eonmux.cadetcoder.test.ProjectFolder;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Checks placed where the thing they check has already been decided.
 *
 * <p>Each of these reads as a guard and is not one: a limit measured in the wrong unit, a conflict
 * tested for after one of its two halves has been erased, a truncation compared against a length
 * that is not the thing being truncated. They all pass silently, which is what makes them worth
 * pinning rather than just fixing.</p>
 */
public class AguardOnlyWorksWhereItCanStillActTest {

    @Rule
    public TemporaryFolder tempFolder = new ProjectFolder();

    private TestOutputCapture output;

    @Before
    public void setUp() {
        output = new TestOutputCapture();
        output.startCapture();
    }

    @After
    public void tearDown() {
        output.stopCapture();
    }

    /**
     * The size limit is named for bytes and the file is written as UTF-8.
     *
     * <p>Compared against {@code String.length()} -- UTF-16 code units -- the check could only ever
     * let too much through, because UTF-8 is never shorter. Text outside the BMP-ASCII range passed
     * a limit it was several times over.</p>
     */
    @Test
    public void asizeLimitStatedInBytesIsEnforcedInBytes() throws IOException {
        // Three UTF-8 bytes per character: 1.2 MB on disk, while measuring 400,000 in chars --
        // comfortably under the 1,048,576 the one-megabyte setting states.
        String threeByteChars = "中".repeat(400_000);
        long   limit          = 1024L * 1024L;
        assertThat(threeByteChars.length()).isLessThan((int) limit);
        assertThat(threeByteChars.getBytes(StandardCharsets.UTF_8).length).isGreaterThan((int) limit);

        Path target = tempFolder.getRoot().toPath().resolve("oversized.txt");

        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager manager = mock(ConfigManager.class);
            Configuration config  = new Configuration();
            config.getSecurity().setMaxFileContentSize(1);
            when(manager.getConfig()).thenReturn(config);
            configMock.when(ConfigManager::getInstance).thenReturn(manager);

            int exitCode = new WriteCommand().execute(new String[] {target.toString(),
                                                                    threeByteChars});

            assertThat(exitCode).as("this is over the limit the setting states").isEqualTo(1);
            assertThat(Files.exists(target)).isFalse();
        }
    }

    /** Text that fits in bytes is still written. */
    @Test
    public void contentInsideTheLimitIsStillWritten() throws IOException {
        Path target = tempFolder.getRoot().toPath().resolve("fine.txt");

        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager manager = mock(ConfigManager.class);
            Configuration config  = new Configuration();
            config.getSecurity().setMaxFileContentSize(1);
            config.getSecurity().setRequireConfirmation(false);
            when(manager.getConfig()).thenReturn(config);
            configMock.when(ConfigManager::getInstance).thenReturn(manager);

            assertThat(new WriteCommand().execute(new String[] {target.toString(), "short"}))
                    .isZero();
            assertThat(Files.readString(target)).isEqualTo("short");
        }
    }

    /**
     * The conflict between {@code --case-sensitive} and {@code --ignore-case} is reported.
     *
     * <p>It was tested for after the run had already resolved {@code --ignore-case} by clearing the
     * other flag, so the condition could never hold: the whole block was dead and the search ran
     * case-insensitively without a word about the flag it had discarded.</p>
     */
    @Test
    public void twocontradictoryCaseFlagsAreReportedRatherThanSilentlyResolved() throws IOException {
        Path file = tempFolder.newFile("subject.txt").toPath();
        Files.writeString(file, "Needle\n");

        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager manager = mock(ConfigManager.class);
            when(manager.getConfig()).thenReturn(new Configuration());
            configMock.when(ConfigManager::getInstance).thenReturn(manager);

            new GrepCommand().execute(new String[] {"needle", "-p",
                                                    tempFolder.getRoot().getAbsolutePath(),
                                                    "--case-sensitive", "--ignore-case"});

            assertThat(output.getAllOutput())
                    .contains("Both --case-sensitive and --ignore-case options specified");
        }
    }

    /** Asking for only one of them is not a conflict. */
    @Test
    public void oneCaseFlagOnItsOwnIsNotReportedAsAconflict() throws IOException {
        Path file = tempFolder.newFile("subject.txt").toPath();
        Files.writeString(file, "Needle\n");

        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager manager = mock(ConfigManager.class);
            when(manager.getConfig()).thenReturn(new Configuration());
            configMock.when(ConfigManager::getInstance).thenReturn(manager);

            new GrepCommand().execute(new String[] {"needle", "-p",
                                                    tempFolder.getRoot().getAbsolutePath(),
                                                    "--ignore-case"});

            assertThat(output.getAllOutput())
                    .as("nothing was contradicted; the default is not a request")
                    .doesNotContain("Both --case-sensitive and --ignore-case");
        }
    }
}
