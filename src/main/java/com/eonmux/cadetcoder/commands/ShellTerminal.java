package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ui.Clipboard;

import dev.tamboui.terminal.Backend;

import java.util.List;
import java.util.function.Supplier;

/**
 * The things the shell asks of the terminal itself, rather than of the frame it is drawing.
 *
 * <p>Writes escape sequences straight past the UI framework, and is best-effort: a terminal that
 * does not understand one simply does not change.</p>
 *
 * <h2>Why the backend is looked up each time</h2>
 *
 * <p>The runner is built after the shell is, and it is gone again once the loop exits. Holding the
 * backend would mean holding one that is either not there yet or already closed, so what is held is
 * the way to ask for it.</p>
 */
final class ShellTerminal {

    private final Supplier<Backend> backend;

    /**
     * @param backend how to reach the terminal, answering {@code null} when the UI is not running
     */
    ShellTerminal(Supplier<Backend> backend) {
        this.backend = backend;
    }

    /**
     * Puts text on the clipboard.
     *
     * <p>By OSC-52 first, which the terminal forwards to whichever machine the person is actually
     * sitting at -- the case that matters over SSH, where a native clipboard tool would copy to the
     * far end and nowhere useful. The native tool is tried as well, because not every terminal
     * implements OSC-52 and neither route reports back whether it worked.</p>
     *
     * @param text what to copy
     */
    void copy(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        write(List.of(Clipboard.osc52(text)));
        Clipboard.systemCopy(text);
    }

    /** Writes sequences to the terminal, if there is one and it will take them. */
    private void write(List<String> sequences) {
        try {
            Backend target = backend == null ? null : backend.get();
            if (target == null) {
                return;
            }
            for (String sequence : sequences) {
                target.writeRaw(sequence);
            }
            target.flush();
        } catch (Exception ignored) {
            // Best effort: a terminal that rejects the sequence simply does not change behaviour,
            // and there is no answer to read back that would say whether it did.
        }
    }
}
