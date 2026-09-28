package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.commands.ShellKeys.CtrlCAction;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the shell's Ctrl+C / Ctrl+Shift+C handling. The central guarantee is that <b>copying
 * selected text never exits the shell</b>, implemented robustly across terminals (most of which
 * collapse Ctrl+Shift+C to a bare {@code 0x03} with no Shift bit).
 *
 * <p>These exercise the pure static {@link ShellKeys#classifyCtrlC} decision directly so no
 * TUI is started (the shell constructor captures {@code System.out} globally).
 */
public class InteractiveShellKeyChordTest {

    // KeyModifiers constructor/of() order is (ctrl, alt, shift) — verified against the backend.
    private static final KeyModifiers CTRL       = KeyModifiers.of(true, false, false);
    private static final KeyModifiers CTRL_SHIFT = KeyModifiers.of(true, false, true);
    private static final KeyModifiers SHIFT      = KeyModifiers.of(false, false, true);

    private static KeyEvent ctrlShiftC() { return KeyEvent.ofChar('C', CTRL_SHIFT); }
    private static KeyEvent ctrlC()      { return KeyEvent.ofChar('c', CTRL); }

    // --- Ctrl+Shift+C (Shift reported) always copies, never quits, regardless of state ---

    @Test
    public void ctrlShiftC_alwaysCopies_neverQuits() {
        for (boolean processing : new boolean[] {false, true}) {
            for (boolean hasSel : new boolean[] {false, true}) {
                assertThat(ShellKeys.classifyCtrlC(ctrlShiftC(), processing, hasSel, false))
                        .as("Ctrl+Shift+C processing=%s hasSelection=%s", processing, hasSel)
                        .isEqualTo(CtrlCAction.COPY_SELECTION);
            }
        }
        assertThat(ShellKeys.isCopySelectionChord(ctrlShiftC())).isTrue();
    }

    // --- Bare Ctrl+C: the terminal-collapse case the user actually hits ---

    @Test
    public void ctrlC_withSelection_idle_copies_doesNotQuit() {
        // This is the real-world Ctrl+Shift+C path on terminals that drop the Shift bit: text is
        // selected, no command running -> copy, NOT quit.
        assertThat(ShellKeys.classifyCtrlC(ctrlC(), false, true, false))
                .isEqualTo(CtrlCAction.COPY_SELECTION);
    }

    @Test
    public void ctrlC_noSelection_idle_asksThenQuits() {
        // The first press asks; the second, with nothing in between, is the answer. It ended the
        // session on the first press, which is not what the chord means in any other shell.
        assertThat(ShellKeys.classifyCtrlC(ctrlC(), false, false, false))
                .isEqualTo(CtrlCAction.CONFIRM_QUIT);
        assertThat(ShellKeys.classifyCtrlC(ctrlC(), false, false, true))
                .isEqualTo(CtrlCAction.QUIT);
    }

    @Test
    public void ctrlC_whileProcessing_interrupts_evenWithSelection() {
        // A running command must still be interruptible by Ctrl+C even if a stale selection lingers.
        assertThat(ShellKeys.classifyCtrlC(ctrlC(), true, true, false))
                .isEqualTo(CtrlCAction.INTERRUPT);
        assertThat(ShellKeys.classifyCtrlC(ctrlC(), true, false, false))
                .isEqualTo(CtrlCAction.INTERRUPT);
    }

    // --- Non-C keys are not classified ---

    @Test
    public void plainC_isNone() {
        assertThat(ShellKeys.classifyCtrlC(KeyEvent.ofChar('c', KeyModifiers.NONE), false, true, false))
                .isEqualTo(CtrlCAction.NONE);
    }

    @Test
    public void shiftCWithoutCtrl_isNone() {
        assertThat(ShellKeys.classifyCtrlC(KeyEvent.ofChar('C', SHIFT), false, true, false))
                .isEqualTo(CtrlCAction.NONE);
        assertThat(ShellKeys.isCopySelectionChord(KeyEvent.ofChar('C', SHIFT))).isFalse();
    }
}
