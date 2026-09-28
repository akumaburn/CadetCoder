package com.eonmux.cadetcoder.harness.tools;

import com.eonmux.cadetcoder.harness.Json;

/**
 * What a value looks like once an agent has to read it inside a fixed context.
 *
 * <h2>Why an answer is cut rather than summarised</h2>
 *
 * <p>Every tool answer is spent out of the same context the agent thinks in, so an observation that
 * runs to tens of thousands of characters costs the run its memory of how it got there. Cutting is
 * the only truncation that cannot mislead: what is shown is exactly what is there, and the line that
 * follows says how much was left out, so the agent can ask for the rest by another route rather than
 * reasoning about a summary nobody checked.</p>
 */
final class Compact {

    /** How much of one value an answer spends before it stops being worth reading. */
    static final int STANDARD_LIMIT = 1_200;

    private Compact() {
    }

    /** A value as the agent reads it, cut at the standard limit. */
    static String value(Object value) {
        return value(value, STANDARD_LIMIT);
    }

    /**
     * A value as the agent reads it.
     *
     * @param value what to show
     * @param limit how many characters it may spend
     * @return the rendered value, cut if it had to be
     */
    static String value(Object value, int limit) {
        return clipped(Json.readable(value), limit);
    }

    /**
     * Text cut to a length, saying how much was left out.
     *
     * @param text  what to show
     * @param limit how many characters it may spend
     * @return the text, cut if it had to be
     */
    static String clipped(String text, int limit) {
        if (text == null) {
            return "";
        }
        if (limit < 0 || text.length() <= limit) {
            return text;
        }
        return text.substring(0, limit) + System.lineSeparator()
               + "... (" + (text.length() - limit) + " more characters)";
    }

    /**
     * Text with every line moved in, so a nested answer reads as one.
     *
     * @param text   what to move
     * @param indent what to put in front of each line
     * @return the indented text
     */
    static String indented(String text, String indent) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return indent + text.replace("\n", "\n" + indent);
    }
}
