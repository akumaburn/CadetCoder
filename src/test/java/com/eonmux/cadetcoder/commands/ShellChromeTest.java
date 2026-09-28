package com.eonmux.cadetcoder.commands;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.MouseEventKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The parts of the shell's chrome that can be wrong without anything failing.
 *
 * <p>Each of these is a pure decision lifted out of the render or event path, for the reason
 * {@code classifyCtrlC} was: constructing a shell replaces the process's {@code System.out}, which
 * is not something a question about where a caret goes should be doing.</p>
 */
class ShellChromeTest {

    @Nested
    @DisplayName("Where the caret goes on the input line")
    class CaretColumn {

        @Test
        @DisplayName("Plain text puts the caret one column per character")
        void narrowCharactersAdvanceOneColumnEach() {
            assertThat(ShellWidgets.cursorColumn("hello", 5, 10, 40)).isEqualTo(15);
            assertThat(ShellWidgets.cursorColumn("hello", 0, 10, 40)).isEqualTo(10);
        }

        @Test
        @DisplayName("A double-width character is one caret step and two columns")
        void wideCharactersAdvanceTwoColumns() {
            // The caret index and the column are the same number only for text made entirely of
            // one-column characters. Taking the index as a column left the caret sitting inside the
            // text rather than after it -- and drew the ghost completion there too.
            assertThat(ShellWidgets.cursorColumn("世界", 2, 0, 40))
                    .as("two characters, four columns")
                    .isEqualTo(4);
        }

        @Test
        @DisplayName("A character outside the basic plane is one caret step per code unit")
        void astralCharactersAreMeasuredByWidthNotByCodeUnits() {
            String emoji = "🚀"; // U+1F680, two code units
            assertThat(ShellWidgets.cursorColumn(emoji, emoji.length(), 0, 40))
                    .isLessThanOrEqualTo(2);
        }

        @Test
        @DisplayName("Once the text outgrows the field the caret pins to its last column")
        void theFieldScrollsRatherThanRunningPastItsEdge() {
            String typed = "x".repeat(50);
            assertThat(ShellWidgets.cursorColumn(typed, 50, 3, 10))
                    .as("the field scrolls to keep the caret visible")
                    .isEqualTo(3 + 9);
        }

        @Test
        @DisplayName("Degenerate input does not throw")
        void outOfRangeInputIsClamped() {
            assertThat(ShellWidgets.cursorColumn(null, 4, 7, 10)).isEqualTo(7);
            assertThat(ShellWidgets.cursorColumn("ab", 99, 0, 10)).isEqualTo(2);
            assertThat(ShellWidgets.cursorColumn("ab", -3, 0, 10)).isEqualTo(0);
            assertThat(ShellWidgets.cursorColumn("ab", 1, 5, 0)).isEqualTo(5);
        }
    }

    @Nested
    @DisplayName("The help overlay")
    class HelpOverlay {

        @Test
        @DisplayName("Navigation keys move it")
        void navigationKeysScroll() {
            assertThat(ShellHelp.classifyKey(KeyCode.UP))
                    .isEqualTo(ShellHelp.Action.LINE_UP);
            assertThat(ShellHelp.classifyKey(KeyCode.DOWN))
                    .isEqualTo(ShellHelp.Action.LINE_DOWN);
            assertThat(ShellHelp.classifyKey(KeyCode.PAGE_UP))
                    .isEqualTo(ShellHelp.Action.PAGE_UP);
            assertThat(ShellHelp.classifyKey(KeyCode.PAGE_DOWN))
                    .isEqualTo(ShellHelp.Action.PAGE_DOWN);
            assertThat(ShellHelp.classifyKey(KeyCode.HOME))
                    .isEqualTo(ShellHelp.Action.TOP);
            assertThat(ShellHelp.classifyKey(KeyCode.END))
                    .isEqualTo(ShellHelp.Action.BOTTOM);
        }

        @Test
        @DisplayName("Every other key still closes it")
        void anyOtherKeyCloses() {
            // The guarantee that makes a panel safe to open without knowing how to leave it. Adding
            // scrolling must not cost it, so everything not named above closes as it always did.
            for (KeyCode code : new KeyCode[]{KeyCode.ESCAPE, KeyCode.ENTER, KeyCode.CHAR,
                                              KeyCode.F1, KeyCode.TAB, KeyCode.BACKSPACE,
                                              KeyCode.LEFT, KeyCode.RIGHT}) {
                assertThat(ShellHelp.classifyKey(code))
                        .as("%s closes the overlay", code)
                        .isEqualTo(ShellHelp.Action.CLOSE);
            }
            assertThat(ShellHelp.classifyKey(null))
                    .isEqualTo(ShellHelp.Action.CLOSE);
        }

        @Test
        @DisplayName("The wheel scrolls it and every other mouse report is swallowed")
        void theOverlayIsModalForTheMouseToo() {
            assertThat(ShellHelp.wheelDelta(MouseEventKind.SCROLL_UP, 3)).isEqualTo(-3);
            assertThat(ShellHelp.wheelDelta(MouseEventKind.SCROLL_DOWN, 3)).isEqualTo(3);

            // It was modal for keys only, so a press or a drag reached the transcript hidden behind
            // it -- against the hit map of rows nobody could see, which on release copied whatever
            // those coordinates happened to land on.
            for (MouseEventKind kind : new MouseEventKind[]{MouseEventKind.PRESS,
                                                            MouseEventKind.DRAG,
                                                            MouseEventKind.RELEASE,
                                                            MouseEventKind.MOVE}) {
                assertThat(ShellHelp.wheelDelta(kind, 3))
                        .as("%s must not reach the view behind the overlay", kind)
                        .isZero();
            }
        }
    }

    @Nested
    @DisplayName("Fitting the header bar's context group")
    class HeaderContext {

        private static final String SEP = "  ·  ";

        private String fit(int width) {
            return ShellHeaderBar.fitContext("", "anthropic/claude-opus-4", "master",
                                               "1.2k in/s  340 out/s", SEP, width, text -> "cut");
        }

        private String fitWithModes(int width) {
            return ShellHeaderBar.fitContext("uber  2 timers", "anthropic/claude-opus-4", "master",
                                               "1.2k in/s  340 out/s", SEP, width, text -> "cut");
        }

        @Test
        @DisplayName("Everything is shown when everything fits")
        void allThreePiecesWhenThereIsRoom() {
            assertThat(fit(200))
                    .isEqualTo("anthropic/claude-opus-4" + SEP + "master" + SEP + "1.2k in/s  340 out/s");
        }

        @Test
        @DisplayName("The rates go first: they are a detail of the run")
        void ratesAreDroppedBeforeTheBranch() {
            assertThat(fit(40)).isEqualTo("anthropic/claude-opus-4" + SEP + "master");
        }

        @Test
        @DisplayName("The branch goes next, and the model is what is left")
        void theModelIsTheLastPieceStanding() {
            assertThat(fit(25)).isEqualTo("anthropic/claude-opus-4");
        }

        @Test
        @DisplayName("Below even that, the model is shortened rather than the group truncated")
        void theModelIsElidedRatherThanCut() {
            assertThat(fit(8)).isEqualTo("cut");
        }

        @Test
        @DisplayName("Absent pieces leave no separator behind")
        void emptyPiecesDoNotProduceStraySeparators() {
            assertThat(ShellHeaderBar.fitContext("", "openai/gpt", "", "", SEP, 80, t -> t))
                    .isEqualTo("openai/gpt");
            assertThat(ShellHeaderBar.fitContext("", "openai/gpt", "", "9 out/s", SEP, 80, t -> t))
                    .isEqualTo("openai/gpt" + SEP + "9 out/s");
        }

        @Test
        @DisplayName("A mode that is switched on leads the group")
        void modesComeFirst() {
            assertThat(fitWithModes(200))
                    .isEqualTo("uber  2 timers" + SEP + "anthropic/claude-opus-4" + SEP + "master"
                               + SEP + "1.2k in/s  340 out/s");
        }

        @Test
        @DisplayName("A mode outlives the rates and the branch, because nothing else shows it")
        void modesSurviveTheRatesAndTheBranch() {
            assertThat(fitWithModes(60))
                    .isEqualTo("uber  2 timers" + SEP + "anthropic/claude-opus-4" + SEP + "master");
            assertThat(fitWithModes(45))
                    .isEqualTo("uber  2 timers" + SEP + "anthropic/claude-opus-4");
        }

        @Test
        @DisplayName("The model still outlives the modes when only one of them fits")
        void theModelIsStillTheLastPieceStanding() {
            assertThat(fitWithModes(25)).isEqualTo("anthropic/claude-opus-4");
        }
    }

    @Nested
    @DisplayName("The working directory beside the input line")
    class WorkingDirectory {

        @Test
        @DisplayName("Home is abbreviated and the TAIL is what survives a squeeze")
        void theEndOfAPathIsWhatIdentifiesIt() {
            assertThat(ShellWidgets.shortPath("/home/dev/work/proj", "/home/dev", 40))
                    .isEqualTo("~/work/proj");
            assertThat(ShellWidgets.shortPath("/home/dev/work/proj", "/home/dev", 8))
                    .endsWith("k/proj");
        }
    }
}
