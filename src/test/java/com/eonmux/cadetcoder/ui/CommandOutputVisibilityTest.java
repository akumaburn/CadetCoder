package com.eonmux.cadetcoder.ui;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Whether command output is echoed, and what is shown in its place. */
public class CommandOutputVisibilityTest {

    private String original;

    @Before
    public void setUp() {
        original = System.getProperty(CommandOutputVisibility.PROPERTY);
        System.clearProperty(CommandOutputVisibility.PROPERTY);
    }

    @After
    public void tearDown() {
        if (original == null) {
            System.clearProperty(CommandOutputVisibility.PROPERTY);
        } else {
            System.setProperty(CommandOutputVisibility.PROPERTY, original);
        }
    }

    @Test
    public void outputIsHiddenByDefault() {
        assertThat(CommandOutputVisibility.isVisible()).isFalse();
    }

    @Test
    public void theSystemPropertyTogglesItBothWays() {
        System.setProperty(CommandOutputVisibility.PROPERTY, "true");
        assertThat(CommandOutputVisibility.isVisible()).isTrue();

        System.setProperty(CommandOutputVisibility.PROPERTY, "false");
        assertThat(CommandOutputVisibility.isVisible()).isFalse();
    }

    @Test
    public void aBlankPropertyFallsBackToTheConfiguredPreference() {
        System.setProperty(CommandOutputVisibility.PROPERTY, "   ");

        // The default configuration has it off; the point is that a blank override does not throw
        // and does not accidentally read as "true".
        assertThat(CommandOutputVisibility.isVisible()).isFalse();
    }

    /**
     * A value that is not one of the two answers must not be read as the answer it is furthest
     * from.
     *
     * <p>{@code Boolean.parseBoolean} maps everything that is not "true" to false, so
     * {@code -Dcadet.showCommandOutput=yes} -- somebody asking for output -- turned output off, and
     * turned it off over a saved preference that had asked for it. {@code ConfigOverrides.asBoolean}
     * refuses exactly this input for exactly this reason, and one setting must not mean one thing
     * typed at the prompt and another handed to the JVM.</p>
     */
    @Test
    public void anUnrecognisedOverrideDoesNotDecideTheOppositeOfWhatItAsksFor() {
        Configuration.UiConfig ui = ConfigManager.getInstance().getConfig().getUi();
        boolean wasShowing = ui.isShowCommandOutput();
        try {
            ui.setShowCommandOutput(true);

            for (String notAnAnswer : new String[]{"yes", "on", "1", "TRUEish", "no"}) {
                System.setProperty(CommandOutputVisibility.PROPERTY, notAnAnswer);

                assertThat(CommandOutputVisibility.isVisible())
                        .as("'%s' says nothing this setting understands, so the saved preference "
                            + "stands rather than being inverted", notAnAnswer)
                        .isTrue();
            }
        } finally {
            ui.setShowCommandOutput(wasShowing);
        }
    }

    @Test
    public void theTwoAnswersAreReadWhateverTheirCase() {
        System.setProperty(CommandOutputVisibility.PROPERTY, "TRUE");
        assertThat(CommandOutputVisibility.isVisible()).isTrue();

        System.setProperty(CommandOutputVisibility.PROPERTY, " False ");
        assertThat(CommandOutputVisibility.isVisible()).isFalse();
    }

    @Test
    public void theSummaryCarriesOrderCommandArgumentsAndOutcome() {
        String line = CommandOutputVisibility.summarize(3, "read", "src/Foo.java", true, "a\nb\nc\n");

        assertThat(line)
                .contains("[3]")
                .contains("read src/Foo.java")
                .contains("ok")
                .contains("3 lines");
    }

    @Test
    public void aFailureIsUnmistakable() {
        String line = CommandOutputVisibility.summarize(1, "bash", "false", false, "");

        assertThat(line).contains("FAILED");
    }

    @Test
    public void anEmptyResultIsStatedRatherThanLookingLikeNothingHappened() {
        assertThat(CommandOutputVisibility.describeSize(null)).isEqualTo("no output");
        assertThat(CommandOutputVisibility.describeSize("")).isEqualTo("no output");
        assertThat(CommandOutputVisibility.describeSize("\n")).isEqualTo("no output");
        assertThat(CommandOutputVisibility.describeSize("one line")).isEqualTo("1 line");
        assertThat(CommandOutputVisibility.describeSize("a\nb")).isEqualTo("2 lines");
        assertThat(CommandOutputVisibility.describeSize("a\nb\n")).isEqualTo("2 lines");
    }

    @Test
    public void aCommandWithNoArgumentsStillRendersCleanly() {
        assertThat(CommandOutputVisibility.summarize(1, "todoread", "", true, "x"))
                .contains("[1] todoread")
                .doesNotContain("  ok  ok");
    }

    @Test
    public void longArgumentsAreShownWhole() {
        String manyPaths = "src/main/java/com/eonmux/cadetcoder/ui/OutputRouter.java"
                + " src/main/java/com/eonmux/cadetcoder/ui/ColorTheme.java"
                + " src/main/java/com/eonmux/cadetcoder/ui/TuiTheme.java"
                + " src/main/java/com/eonmux/cadetcoder/ui/Glyphs.java";

        String line = CommandOutputVisibility.summarize(5, "multiread", manyPaths, true, "x\ny");

        // The command the model ran is the record of what it did; a cut one hid the part that
        // differed from the last run. The console wraps a long line, so nothing is gained by it.
        assertThat(line).contains("multiread " + manyPaths).doesNotContain("\u2026")
                        .contains("[5]").contains("2 lines");
    }

    @Test
    public void argumentsAreFlattenedSoTheyCannotBreakTheLineTheySitOn() {
        // A write payload or a multi-line pattern arrives as one argument containing newlines.
        assertThat(CommandOutputVisibility.describe("write", "notes.txt line one\nline two"))
                .doesNotContain("\n");
    }

    @Test
    public void aLongCommandIsDescribedToItsLastArgument() {
        String command = "cd /home/user/project && python backtest.py --strategy CustomStrategy"
                         + " --from 2023-01-01 --to 2024-12-31 --report returns,drawdown,trades";

        assertThat(CommandOutputVisibility.describe("exec", command)).isEqualTo("exec " + command);
    }

    @Test
    public void aCommandWithNoArgumentsIsDescribedByNameAlone() {
        assertThat(CommandOutputVisibility.describe("todoread", null)).isEqualTo("todoread");
        assertThat(CommandOutputVisibility.describe("todoread", "   ")).isEqualTo("todoread");
    }

    @Test
    public void theOutcomeRecordDoesNotRestateACommandAnnouncedTwoLinesAbove() {
        String line = CommandOutputVisibility.summarizeOutcome(2, true, "a\nb\nc");

        assertThat(line).isEqualTo("[2] ok  (3 lines)");
        assertThat(line).doesNotContain("glob");
    }

    @Test
    public void aFailedOutcomeIsStillUnmistakableInTheTerseForm() {
        assertThat(CommandOutputVisibility.summarizeOutcome(1, false, "")).contains("FAILED");
    }
}
