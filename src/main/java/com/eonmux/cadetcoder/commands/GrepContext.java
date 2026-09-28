package com.eonmux.cadetcoder.commands;

/**
 * How many lines either side of a match are worth showing.
 *
 * <h2>Why a match alone is not an answer</h2>
 *
 * <p>A search returns the line the pattern is on. One line of a method body says nothing about the
 * method, so a caller that found what it wanted then spent a second read on the file to see what
 * the line was inside of. Every other grep answers this with {@code -A}, {@code -B} and
 * {@code -C}.</p>
 *
 * @param before how many lines before each match to include
 * @param after  how many lines after each match to include
 */
record GrepContext(int before, int after) {

    /** Neither side, which is what a search asks for unless it says otherwise. */
    static final GrepContext NONE = new GrepContext(0, 0);

    GrepContext {
        before = Math.max(0, before);
        after  = Math.max(0, after);
    }

    /** Whether any surrounding line is wanted at all. */
    boolean wanted() {
        return before > 0 || after > 0;
    }
}
