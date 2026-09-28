package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.commands.ShellKeys.Action;
import com.eonmux.cadetcoder.commands.ShellKeys.State;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ctrl+C on an idle shell asks before it ends the session.
 *
 * <p><b>The defect</b>: one press of Ctrl+C with nothing running and nothing selected quit
 * immediately and silently. In every other shell and REPL that chord cancels what is in hand, so it
 * is the key a user reaches for out of habit -- and reaching for it here ended the session, taking
 * the conversation's live context with it. The banner has always advertised Ctrl+Q for quitting,
 * which is the deliberate key and still quits on the first press.</p>
 */
public class AsessionIsNotEndedByOneStrayKeystrokeTest {

    private static final KeyModifiers CTRL       = KeyModifiers.of(true, false, false);
    private static final KeyModifiers CTRL_SHIFT = KeyModifiers.of(true, false, true);

    private static final State IDLE       = new State(false, false, false, false);
    private static final State ASKED      = new State(false, false, false, true);
    private static final State BUSY       = new State(true, false, false, false);
    private static final State SELECTED   = new State(false, false, true, false);

    private static Action ctrlC(State state) {
        return ShellKeys.classify(KeyEvent.ofChar('c', CTRL), state);
    }

    @Test
    public void thefirstPressAsksRatherThanQuits() {
        assertThat(ctrlC(IDLE))
                .as("the key every other REPL uses to cancel must not end the session unasked")
                .isEqualTo(Action.CONFIRM_QUIT);
    }

    @Test
    public void thesecondPressQuits() {
        assertThat(ctrlC(ASKED)).isEqualTo(Action.QUIT);
    }

    @Test
    public void ctrlQstillQuitsOnTheFirstPress() {
        assertThat(ShellKeys.classify(KeyEvent.ofChar('q', CTRL), IDLE))
                .as("the advertised quit key is the deliberate one and stays immediate")
                .isEqualTo(Action.QUIT);
    }

    @Test
    public void arunningCommandIsInterruptedRatherThanAskedAbout() {
        assertThat(ctrlC(BUSY)).isEqualTo(Action.INTERRUPT);
        assertThat(ctrlC(new State(true, false, false, true)))
                .as("a pending question about quitting does not turn the interrupt key into a quit")
                .isEqualTo(Action.INTERRUPT);
    }

    @Test
    public void aselectionIsStillCopiedRatherThanAskedAbout() {
        assertThat(ctrlC(SELECTED)).isEqualTo(Action.COPY_SELECTION_THEN_CLEAR);
        assertThat(ShellKeys.classify(KeyEvent.ofChar('C', CTRL_SHIFT), SELECTED))
                .isEqualTo(Action.COPY_SELECTION);
    }

    @Test
    public void anythingElseTakesTheQuestionBack() {
        assertThat(ShellKeys.endsTheQuestion(KeyEvent.ofChar('a')))
                .as("typing anything means the user is still working")
                .isTrue();
        assertThat(ShellKeys.endsTheQuestion(KeyEvent.ofKey(KeyCode.ENTER))).isTrue();
        assertThat(ShellKeys.endsTheQuestion(KeyEvent.ofChar('c', CTRL)))
                .as("the chord being answered is the one keystroke that does not withdraw it")
                .isFalse();
    }
}
