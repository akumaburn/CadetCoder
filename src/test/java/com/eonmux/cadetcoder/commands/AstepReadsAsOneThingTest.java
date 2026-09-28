package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import com.eonmux.cadetcoder.ui.AnsiStripper;
import com.eonmux.cadetcoder.ui.OutputLineStyler;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * How one step of an agent run is laid out, and how a listing keeps its columns.
 *
 * <p><b>What it looked like</b>: a step put its command on one line and the model's reason for it on
 * another, under the same marker the run's telemetry and the step's outcome were already using. Four
 * unrelated things then shared one column of markers, so a reader scanning the transcript could not
 * tell which was which without reading each line through.</p>
 */
public class AstepReadsAsOneThingTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

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

    private List<String> printedLines() {
        return AnsiStripper.strip(output.getAllOutput()).lines().collect(Collectors.toList());
    }

    // ---------------------------------------------------------------- the step

    @Test
    public void aStepsReasonBelongsToTheStepRatherThanToTheRun() {
        ChatCommand.AIAction action = new ChatCommand.AIAction(
                "read", new String[] {"README.md"},
                "Read the main README before adding anything to it");

        ChatActions.announce(action);

        List<String> lines = printedLines();
        assertThat(lines).hasSize(2);
        assertThat(OutputLineStyler.classify(lines.get(0)))
                .isEqualTo(OutputLineStyler.Kind.SUBHEADER);
        assertThat(lines.get(0)).contains("read README.md");
        assertThat(lines.get(1))
                .as("the reason continues the step, so it is indented under it and carries no marker")
                .isEqualTo("  Read the main README before adding anything to it");
    }

    @Test
    public void theReasonDoesNotWearTheInformationMarker() {
        ChatActions.announce(new ChatCommand.AIAction(
                "glob", new String[] {"*.java"}, "Check what is in the package"));

        assertThat(printedLines())
                .as("telemetry wears that marker; a reason is part of the step above it")
                .noneMatch(line -> OutputLineStyler.classify(line) == OutputLineStyler.Kind.INFO);
    }

    @Test
    public void aStepWithNoStatedReasonIsOneLine() {
        ChatActions.announce(new ChatCommand.AIAction("read", new String[] {"README.md"}, "   "));

        assertThat(printedLines()).hasSize(1);
    }

    // ---------------------------------------------------------------- the listing

    @Test
    public void aLongPathDoesNotPushItsOwnTimestampOutOfLine() throws Exception {
        // The path column is capped so one deep path does not indent every row. A path longer than
        // the cap used to be printed whole, which moved its timestamp past everything else's --
        // so in a listing of one package, the longest names were the crooked rows.
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager manager = mock(ConfigManager.class);
            when(manager.getConfig()).thenReturn(new Configuration());
            configMock.when(ConfigManager::getInstance).thenReturn(manager);

            tempFolder.newFolder("com", "eonmux", "cadetcoder", "commands");
            // Longer than MAX_PATH_COLUMN, so it is the row that used to break the alignment.
            aged("com/eonmux/cadetcoder/commands/AnExceptionallyLongCommandClassNameIndeed.java", 6);
            // A different age, so the timestamps are different lengths and the size column after
            // them has to be held open by something other than the text in front of it.
            aged("com/eonmux/cadetcoder/commands/Short.java", 1);

            new GlobCommand().execute(
                    new String[] {"**/*.java", "-p", tempFolder.getRoot().getAbsolutePath()});

            List<String> rows = printedLines().stream()
                    .filter(line -> line.contains(".java") && line.contains(" ago"))
                    .collect(Collectors.toList());

            assertThat(rows).hasSize(2);
            assertThat(rows.get(0)).contains("6 days ago");
            assertThat(rows.get(1)).contains("1 day ago");
            assertThat(rows.get(0).indexOf("6 days ago"))
                    .as("both timestamps start in the same column")
                    .isEqualTo(rows.get(1).indexOf("1 day ago"));
            assertThat(rows.get(0).indexOf(" ("))
                    .as("so do both sizes, though the timestamps are different lengths")
                    .isEqualTo(rows.get(1).indexOf(" ("));
            assertThat(rows.get(0))
                    .as("the path too long for its column is cut, keeping the end that names it")
                    .contains("…").contains("CommandClassNameIndeed.java");
        }
    }

    /** Creates a file and dates it, so the listing shows an age rather than "just now". */
    private void aged(String path, int daysOld) throws Exception {
        java.io.File created = tempFolder.newFile(path);
        long when = System.currentTimeMillis() - daysOld * 86_400_000L - 60_000L;
        assertThat(created.setLastModified(when)).isTrue();
    }

    @Test
    public void aRowCarriesNoTrailingWhitespace() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager manager = mock(ConfigManager.class);
            when(manager.getConfig()).thenReturn(new Configuration());
            configMock.when(ConfigManager::getInstance).thenReturn(manager);

            tempFolder.newFolder("pkg");
            tempFolder.newFile("pkg/One.java");

            new GlobCommand().execute(
                    new String[] {"**/*.java", "-p", tempFolder.getRoot().getAbsolutePath()});

            assertThat(printedLines())
                    .as("padding that runs off the end of a row is invisible, and every copy keeps it")
                    .allSatisfy(line -> assertThat(line).isEqualTo(line.stripTrailing()));
        }
    }
}
