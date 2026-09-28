package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.commands.ShellKeys.Action;
import com.eonmux.cadetcoder.commands.ShellKeys.State;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What each key the shell answers to actually means.
 *
 * <h2>The defect</h2>
 *
 * <p>This was a two-hundred-line chain of {@code if}s inside the event loop, where every branch both
 * decided what a key meant and carried it out. Nothing in it could be asked a question without
 * starting a terminal UI, so the whole keyboard -- every key the README advertises -- was covered by
 * exactly one test, of the Ctrl+C chord, and only because that one decision had already been lifted
 * out as a static.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>That each advertised key maps to the action the shell performs for it; that the keys which are
 * not the shell's fall through to the line being typed rather than being swallowed; that Tab is
 * swallowed rather than obeyed while a command is waiting for an answer, because the answer is what
 * Tab would move away from; and the guarantee that outranks the rest -- that Ctrl+C interrupts a
 * running command even when text is selected, so a selection left on screen cannot turn the
 * interrupt key into a copy.</p>
 */
public class EveryKeyTheShellAnswersToTest {

    // KeyModifiers constructor/of() order is (ctrl, alt, shift).
    private static final KeyModifiers CTRL       = KeyModifiers.of(true, false, false);
    private static final KeyModifiers CTRL_SHIFT = KeyModifiers.of(true, false, true);
    private static final KeyModifiers SHIFT      = KeyModifiers.of(false, false, true);

    private static final State IDLE      = new State(false, false, false, false);
    private static final State BUSY      = new State(true, false, false, false);
    private static final State PROMPTING = new State(false, true, false, false);
    private static final State SELECTED  = new State(false, false, true, false);

    private static Action of(KeyCode code) {
        return ShellKeys.classify(KeyEvent.ofKey(code), IDLE);
    }

    private static Action of(KeyCode code, KeyModifiers modifiers) {
        return ShellKeys.classify(KeyEvent.ofKey(code, modifiers), IDLE);
    }

    @Test
    public void theFunctionKeysAreTheShellsOwn() {
        assertThat(of(KeyCode.F1)).isEqualTo(Action.OPEN_HELP);
        assertThat(of(KeyCode.F2)).isEqualTo(Action.INTERRUPT);
        assertThat(of(KeyCode.F3)).isEqualTo(Action.SHOW_HISTORY);
        assertThat(of(KeyCode.F4)).isEqualTo(Action.TOGGLE_SELECT_MODE);
        // Shift+Tab, the obvious candidate for this, already walks the pane cycle backwards.
        assertThat(of(KeyCode.F5)).isEqualTo(Action.OPEN_OVERVIEW);
    }

    @Test
    public void escapeBacksOutOfWhateverIsInnermost() {
        // One action, whichever of the five things is currently true: the shell resolves which,
        // because which one applies is a fact about the screen and not about the key.
        assertThat(of(KeyCode.ESCAPE)).isEqualTo(Action.DISMISS);
        assertThat(ShellKeys.classify(KeyEvent.ofKey(KeyCode.ESCAPE), PROMPTING))
                .isEqualTo(Action.DISMISS);
    }

    @Test
    public void enterSendsWhatHasBeenTyped() {
        assertThat(of(KeyCode.ENTER)).isEqualTo(Action.SUBMIT);
    }

    @Test
    public void ctrlQQuitsAndCtrlLClears() {
        assertThat(ShellKeys.classify(KeyEvent.ofChar('q', CTRL), IDLE)).isEqualTo(Action.QUIT);
        assertThat(ShellKeys.classify(KeyEvent.ofChar('Q', CTRL), IDLE)).isEqualTo(Action.QUIT);
        assertThat(ShellKeys.classify(KeyEvent.ofChar('l', CTRL), IDLE))
                .isEqualTo(Action.CLEAR_OUTPUT);
        assertThat(ShellKeys.classify(KeyEvent.ofChar('L', CTRL), IDLE))
                .isEqualTo(Action.CLEAR_OUTPUT);
    }

    @Test
    public void escapeTakesTheModesBackInTheOrderTheyWereEntered() {
        // The press that cancels a question must not also drop a selection made before the question
        // appeared, so these are taken one at a time, innermost first.
        assertThat(ShellKeys.dismissal(true, true, true, true, true))
                .isEqualTo(ShellKeys.Dismissal.PROMPT);
        assertThat(ShellKeys.dismissal(false, true, true, true, true))
                .isEqualTo(ShellKeys.Dismissal.SELECT_MODE);
        assertThat(ShellKeys.dismissal(false, false, true, true, true))
                .isEqualTo(ShellKeys.Dismissal.SELECTION);
        assertThat(ShellKeys.dismissal(false, false, false, true, true))
                .isEqualTo(ShellKeys.Dismissal.FOCUS);
    }

    @Test
    public void escapeClearsTheLineWhenThereIsNoModeLeftToLeave() {
        // The line is the only one of these that is not a mode, so it comes last: Escape on a
        // half-written line that was going nowhere is the other half of Ctrl+U.
        assertThat(ShellKeys.dismissal(false, false, false, false, true))
                .isEqualTo(ShellKeys.Dismissal.INPUT);
        assertThat(ShellKeys.dismissal(false, false, false, false, false))
                .isEqualTo(ShellKeys.Dismissal.NOTHING);
    }

    @Test
    public void ctrlUthrowsAwayTheLineBeingTyped() {
        // The shells' key for it. Ctrl+C is the other habit and is spoken for here: on an idle
        // shell it asks whether to quit, so without this the only way to drop a long line was to
        // hold Backspace down.
        assertThat(ShellKeys.classify(KeyEvent.ofChar('u', CTRL), IDLE))
                .isEqualTo(Action.CLEAR_INPUT);
        assertThat(ShellKeys.classify(KeyEvent.ofChar('U', CTRL), IDLE))
                .isEqualTo(Action.CLEAR_INPUT);
        assertThat(ShellKeys.classify(KeyEvent.ofChar('u', CTRL), PROMPTING))
                .as("an answer being typed is a line like any other")
                .isEqualTo(Action.CLEAR_INPUT);
    }

    @Test
    public void theSameLettersWithoutCtrlAreJustLetters() {
        assertThat(ShellKeys.classify(KeyEvent.ofChar('q'), IDLE)).isEqualTo(Action.NONE);
        assertThat(ShellKeys.classify(KeyEvent.ofChar('l'), IDLE)).isEqualTo(Action.NONE);
        assertThat(ShellKeys.classify(KeyEvent.ofChar('c'), IDLE)).isEqualTo(Action.NONE);
        assertThat(ShellKeys.classify(KeyEvent.ofChar('u'), IDLE)).isEqualTo(Action.NONE);
    }

    @Test
    public void tabMovesBetweenResultsAndShiftTabGoesBack() {
        assertThat(ShellKeys.classify(KeyEvent.ofKey(KeyCode.TAB), IDLE))
                .isEqualTo(Action.FOCUS_NEXT);
        assertThat(ShellKeys.classify(KeyEvent.ofKey(KeyCode.TAB, SHIFT), IDLE))
                .isEqualTo(Action.FOCUS_PREVIOUS);
    }

    @Test
    public void tabIsSwallowedWhileACommandIsWaitingForAnAnswer() {
        // Swallowed rather than passed on: the answer is being typed on the line Tab would move
        // away from, and a tab character in the middle of it is not what was meant either.
        assertThat(ShellKeys.classify(KeyEvent.ofKey(KeyCode.TAB), PROMPTING))
                .isEqualTo(Action.IGNORE);
    }

    @Test
    public void theScrollKeysSayWhichWayAndHowFar() {
        assertThat(of(KeyCode.PAGE_UP)).isEqualTo(Action.PAGE_UP);
        assertThat(of(KeyCode.PAGE_DOWN)).isEqualTo(Action.PAGE_DOWN);
        assertThat(of(KeyCode.UP)).isEqualTo(Action.LINE_UP);
        assertThat(of(KeyCode.DOWN)).isEqualTo(Action.LINE_DOWN);
    }

    @Test
    public void ctrlHomeAndCtrlEndGoToTheEndsOfWhateverIsShowing() {
        assertThat(of(KeyCode.HOME, CTRL)).isEqualTo(Action.GO_TOP);
        assertThat(of(KeyCode.END, CTRL)).isEqualTo(Action.GO_TAIL);
    }

    @Test
    public void homeAndEndWithoutCtrlBelongToTheLineBeingTyped() {
        // The distinction is the whole reason Ctrl is required: without it these move the caret
        // within what is being typed, and answering them here would take that away.
        assertThat(of(KeyCode.HOME)).isEqualTo(Action.NONE);
        assertThat(of(KeyCode.END)).isEqualTo(Action.NONE);
    }

    @Test
    public void theEditingKeysAreNotTheShells() {
        assertThat(of(KeyCode.BACKSPACE)).isEqualTo(Action.NONE);
        assertThat(of(KeyCode.DELETE)).isEqualTo(Action.NONE);
        assertThat(of(KeyCode.LEFT)).isEqualTo(Action.NONE);
        assertThat(of(KeyCode.RIGHT)).isEqualTo(Action.NONE);
    }

    @Test
    public void ctrlShiftCCopiesAndLeavesTheSelectionUp() {
        assertThat(ShellKeys.classify(KeyEvent.ofChar('C', CTRL_SHIFT), SELECTED))
                .isEqualTo(Action.COPY_SELECTION);
    }

    @Test
    public void abareCtrlCCopiesAndThenLetsGo() {
        // So the next press of the same key quits, which is what a second Ctrl+C means everywhere
        // else. Ctrl+Shift+C is the explicit form and keeps the selection for copying again.
        assertThat(ShellKeys.classify(KeyEvent.ofChar('c', CTRL), SELECTED))
                .isEqualTo(Action.COPY_SELECTION_THEN_CLEAR);
    }

    @Test
    public void ctrlCInterruptsARunningCommandEvenWithTextSelected() {
        // The one that matters: a selection left on screen must not turn the interrupt key into a
        // copy while something is running, or there would be no way to stop it.
        assertThat(ShellKeys.classify(KeyEvent.ofChar('c', CTRL), new State(true, false, true, false)))
                .isEqualTo(Action.INTERRUPT);
        assertThat(ShellKeys.classify(KeyEvent.ofChar('c', CTRL), BUSY)).isEqualTo(Action.INTERRUPT);
    }

    @Test
    public void ctrlCAsksBeforeQuittingWhenThereIsNothingToStopAndNothingToCopy() {
        // Asking is the point: see AsessionIsNotEndedByOneStrayKeystrokeTest.
        assertThat(ShellKeys.classify(KeyEvent.ofChar('c', CTRL), IDLE))
                .isEqualTo(Action.CONFIRM_QUIT);
        assertThat(ShellKeys.classify(KeyEvent.ofChar('c', CTRL), new State(false, false, false, true)))
                .isEqualTo(Action.QUIT);
    }
}
