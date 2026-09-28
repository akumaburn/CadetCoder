package com.eonmux.cadetcoder.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Breaks one line of the tool's own prose to fit a known width.
 *
 * <h2>What it will not do</h2>
 *
 * <p>A word longer than the width is left to overflow rather than split. The words that reach that
 * length here are paths, URLs and model identifiers -- the parts of a message someone is most
 * likely to copy -- and a break inside one produces two fragments that neither open nor resolve.
 * Overflowing is visible and harmless; a severed path is neither.</p>
 *
 * <p>The line's own leading whitespace is repeated on every piece, so an indented listing stays
 * indented instead of having its continuations fall back to column zero.</p>
 */
public final class ProseWrap {

    private ProseWrap() {
    }

    /**
     * One line as the pieces it should be printed on.
     *
     * @param line  the text, without a newline
     * @param width the columns available, or {@link TerminalWidth#UNKNOWN} to leave it alone
     * @return the pieces, in order; always at least one, never {@code null}
     */
    public static List<String> wrap(String line, int width) {
        String text = line == null ? "" : line;
        if (width <= 0 || text.length() <= width) {
            return List.of(text);
        }
        String indent = leadingSpace(text);
        if (indent.length() >= width) {
            return List.of(text);
        }
        List<String>  pieces  = new ArrayList<>();
        StringBuilder current = new StringBuilder(indent);
        boolean       empty   = true;
        for (String word : text.strip().split("\\s+")) {
            if (word.isEmpty()) {
                continue;
            }
            if (!empty && current.length() + 1 + word.length() > width) {
                pieces.add(current.toString());
                current = new StringBuilder(indent);
                empty   = true;
            }
            if (!empty) {
                current.append(' ');
            }
            current.append(word);
            empty = false;
        }
        if (!empty) {
            pieces.add(current.toString());
        }
        return pieces.isEmpty() ? List.of(text) : pieces;
    }

    /** The whitespace a line opens with, which its continuations keep. */
    private static String leadingSpace(String text) {
        int at = 0;
        while (at < text.length() && (text.charAt(at) == ' ' || text.charAt(at) == '\t')) {
            at++;
        }
        return text.substring(0, at);
    }
}
