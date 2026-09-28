package com.eonmux.cadetcoder.commands;

import java.util.ArrayList;
import java.util.List;

/**
 * Thread-safe, bounded buffer of output lines for the interactive shell.
 *
 * <p>Command output (captured from {@code System.out}/{@code System.err} via
 * {@link com.eonmux.cadetcoder.ui.OutputRouter} on background command threads) and shell
 * messages are appended here; the TamboUI render thread takes a consistent snapshot each frame.
 * Text without a trailing newline is held in a {@code pending} buffer and merged with the next
 * append, so streamed/partial output renders as coherent whole lines. The line list is capped to
 * {@code maxLines}; the oldest lines are dropped first (a simple scrollback ring).</p>
 *
 * <p>All mutating and snapshot operations are synchronized on a private lock, so the buffer is
 * safe to share between the command threads that produce output and the render thread that
 * consumes it.</p>
 */
final class ShellOutputBuffer {

    private final int          maxLines;
    private final Object       lock    = new Object();
    private final List<String> lines   = new ArrayList<>();
    private       String       pending = "";

    ShellOutputBuffer(int maxLines) {
        this.maxLines = Math.max(1, maxLines);
    }

    /**
     * Append raw text that may contain any number of embedded newlines and may or may not end
     * with one. Complete lines are committed to the buffer; a trailing partial line is retained
     * as {@code pending} until completed by a later append.
     */
    void append(String text) {
        if (text == null) {
            return;
        }
        synchronized (lock) {
            String   combined = pending + text;
            String[] parts    = combined.split("\n", -1);
            for (int i = 0; i < parts.length - 1; i++) {
                lines.add(parts[i]);
            }
            pending = parts[parts.length - 1];
            trim();
        }
    }

    /** Append a single complete line (a trailing newline is supplied). */
    void appendLine(String text) {
        append((text == null ? "" : text) + "\n");
    }

    /** Discard all buffered output, including any pending partial line. */
    void clear() {
        synchronized (lock) {
            lines.clear();
            pending = "";
        }
    }

    /**
     * A point-in-time copy of every complete line plus the current pending partial line (if any).
     * The returned list is a fresh copy and safe to iterate without holding the lock.
     */
    List<String> snapshot() {
        synchronized (lock) {
            List<String> out = new ArrayList<>(lines.size() + 1);
            out.addAll(lines);
            if (!pending.isEmpty()) {
                out.add(pending);
            }
            return out;
        }
    }

    /** Number of renderable lines (complete lines plus a pending partial line, if present). */
    int size() {
        synchronized (lock) {
            return lines.size() + (pending.isEmpty() ? 0 : 1);
        }
    }

    private void trim() {
        while (lines.size() > maxLines) {
            lines.remove(0);
        }
    }
}
