package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.test.TestOutputCapture;
import com.eonmux.cadetcoder.ui.CollapsedOutput;
import com.eonmux.cadetcoder.ui.CommandOutputVisibility;
import com.eonmux.cadetcoder.ui.TuiMode;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A command's output is collapsed where it can be opened again, and summarised where it cannot.
 *
 * <h2>The defect</h2>
 *
 * <p>Hidden output printed its first four lines and a count of the rest -- five lines per command,
 * in a run that issues a dozen of them, and the four shown were whichever four came first rather
 * than any four worth reading. The count named a setting to turn on and a run to do again, because
 * the text itself had been thrown away.</p>
 *
 * <p>In the shell the text is now kept and marked, so the announcement above it -- the command and
 * the model's reason for running it -- can be clicked to read the whole of it. On the plain command
 * line, where there is nothing to click, the head is still kept: a step's own diagnostics lead its
 * output, and hiding those where they cannot be recovered would leave a failure explained by a
 * number.</p>
 */
public class AstepsOutputIsCollapsedWhereItCanBeOpenedTest {

    private static final String LONG_OUTPUT =
            "Command executed: glob src/**/*.java\n\nCommand Output:\nOne.java\nTwo.java\n"
            + "Three.java\nFour.java\nFive.java\nSix.java\n";

    private TestOutputCapture output;
    private String            previousTui;
    private String            previousVisibility;

    @Before
    public void setUp() {
        previousTui        = System.getProperty(TuiMode.OVERRIDE_PROPERTY);
        previousVisibility = System.getProperty(CommandOutputVisibility.PROPERTY);
        System.setProperty(CommandOutputVisibility.PROPERTY, "false");
        output = new TestOutputCapture();
        output.startCapture();
    }

    @After
    public void tearDown() {
        output.stopCapture();
        restore(TuiMode.OVERRIDE_PROPERTY, previousTui);
        restore(CommandOutputVisibility.PROPERTY, previousVisibility);
    }

    private static void restore(String property, String previous) {
        if (previous == null) {
            System.clearProperty(property);
        } else {
            System.setProperty(property, previous);
        }
    }

    @Test
    public void theShellIsSentTheWholeOfItSoTheResultCanBeOpened() {
        System.setProperty(TuiMode.OVERRIDE_PROPERTY, "true");

        StepOutput.printForConsole(LONG_OUTPUT);
        String shown = output.getAllOutput();

        assertThat(shown).contains(CollapsedOutput.OPEN, CollapsedOutput.CLOSE);
        assertThat(shown).contains("One.java", "Six.java");
        assertThat(CollapsedOutput.visible(java.util.List.of(shown.split("\n"))))
                .as("none of the body is drawn until the result is opened")
                .noneMatch(line -> line.contains("Six.java"));
    }

    @Test
    public void aConsoleWithNothingToClickKeepsTheHeadAndCountsTheRest() {
        System.clearProperty(TuiMode.OVERRIDE_PROPERTY);

        StepOutput.printForConsole(LONG_OUTPUT);
        String shown = output.getAllOutput();

        assertThat(shown).doesNotContain(CollapsedOutput.OPEN);
        assertThat(shown).contains("One.java");
        assertThat(shown).contains("2 more lines hidden");
        assertThat(shown).doesNotContain("Six.java");
    }

    @Test
    public void outputShortEnoughToReadIsPrintedWhereverItIs() {
        System.setProperty(TuiMode.OVERRIDE_PROPERTY, "true");

        StepOutput.printForConsole("Command Output:\nError: File not found: Foo.java\n");
        String shown = output.getAllOutput();

        assertThat(shown).contains("File not found: Foo.java");
        assertThat(shown).doesNotContain(CollapsedOutput.OPEN);
    }

    @Test
    public void nothingIsPrintedForAStepWithNoOutputAtAll() {
        StepOutput.printForConsole("");

        assertThat(output.getAllOutput()).isEmpty();
    }

    @Test
    public void theSettingStillShowsEverythingInline() {
        System.setProperty(CommandOutputVisibility.PROPERTY, "true");
        System.setProperty(TuiMode.OVERRIDE_PROPERTY, "true");

        StepOutput.printForConsole(LONG_OUTPUT);
        String shown = output.getAllOutput();

        assertThat(shown).contains("One.java", "Six.java");
        assertThat(shown).doesNotContain(CollapsedOutput.OPEN);
    }
}
