package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.test.ProjectFolder;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import com.eonmux.cadetcoder.ui.ProgramOutput;
import com.eonmux.cadetcoder.ui.TuiMode;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * In the shell, the lines of a file that {@code read}, {@code multiread} and {@code grep} show are
 * marked as program output.
 *
 * <p>The shell renders a result as Markdown. A file's lines are not Markdown: read as Markdown,
 * {@code **}{@code /*.java} in a glob became bold text and {@code __init__} lost its underscores.
 * See {@link ProgramOutput}.</p>
 */
public class AfilesLinesAreShownAsTheyAreWrittenTest {

    private static final String LINE = "**/*.java and __init__";

    @Rule
    public TemporaryFolder folder = new ProjectFolder();

    private TestOutputCapture output;
    private String            previousTuiMode;

    @Before
    public void setUp() {
        previousTuiMode = System.getProperty(TuiMode.OVERRIDE_PROPERTY);
        System.setProperty(TuiMode.OVERRIDE_PROPERTY, "true");
        output = new TestOutputCapture();
        output.startCapture();
    }

    @After
    public void tearDown() {
        output.stopCapture();
        if (previousTuiMode == null) {
            System.clearProperty(TuiMode.OVERRIDE_PROPERTY);
        } else {
            System.setProperty(TuiMode.OVERRIDE_PROPERTY, previousTuiMode);
        }
    }

    @Test
    public void readMarksTheLinesOfAFile() throws Exception {
        Path file = write("glob.txt");

        assertThat(new ReadCommand().execute(new String[] {file.toString()})).isZero();

        assertMarkedAround(LINE);
    }

    @Test
    public void multireadMarksTheLinesOfEachFile() throws Exception {
        Path file = write("glob.txt");

        assertThat(new MultiReadCommand().execute(new String[] {file.toString()})).isZero();

        assertMarkedAround(LINE);
    }

    @Test
    public void grepMarksTheMatchingLines() throws Exception {
        write("glob.txt");
        try (MockedStatic<ConfigManager> configured = mockStatic(ConfigManager.class)) {
            ConfigManager manager = mock(ConfigManager.class);
            Configuration config  = new Configuration();
            config.getUi().setColorEnabled(false);
            when(manager.getConfig()).thenReturn(config);
            configured.when(ConfigManager::getInstance).thenReturn(manager);

            assertThat(new GrepCommand().execute(
                    new String[] {"__init__", "-p", folder.getRoot().getAbsolutePath()})).isZero();
        }

        assertMarkedAround(LINE);
    }

    private Path write(String name) throws Exception {
        Path file = folder.getRoot().toPath().resolve(name);
        Files.writeString(file, "first\n" + LINE + "\nlast\n");
        return file;
    }

    private void assertMarkedAround(String text) {
        String printed = output.getAllOutput();
        int    at      = printed.indexOf(text);
        assertThat(at).isNotNegative();
        assertThat(printed.lastIndexOf(ProgramOutput.OPEN, at)).isNotNegative();
        assertThat(printed.lastIndexOf(ProgramOutput.OPEN, at))
                .isGreaterThan(printed.lastIndexOf(ProgramOutput.CLOSE, at));
        assertThat(printed.indexOf(ProgramOutput.CLOSE, at)).isGreaterThan(at);
    }
}
