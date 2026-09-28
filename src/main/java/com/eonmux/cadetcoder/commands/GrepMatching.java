package com.eonmux.cadetcoder.commands;

/**
 * What counts as a match on a line, and how much of it is worth recording.
 *
 * <h2>Why the output shape is part of the matching rule</h2>
 *
 * <p>A run that will only print a file name or a count never shows a highlighted line, so the
 * offsets inside each match are collected only when something will use them. Deciding that at print
 * time instead means every line of every result is matched a second time for offsets that are then
 * thrown away.</p>
 *
 * <h2>Why "shows lines" is a separate question from "highlights them"</h2>
 *
 * <p>These two used to be one, and {@code -A}, {@code -B} and {@code -C} were gated on the
 * highlighting answer. {@code -v} suppresses highlighting -- nothing on a line selected for NOT
 * matching is worth picking out -- but it prints lines like any other run, so the surrounding lines
 * it had asked for were read, dropped, and never mentioned again. That is the shape most in need of
 * them: what is interesting about a line that does not match is usually the lines that do.</p>
 *
 * @param invertMatch whether a line matches by NOT matching
 * @param countOnly   whether only the number of matches per file will be shown
 * @param filesOnly   whether only the names of matching files will be shown
 */
record GrepMatching(boolean invertMatch, boolean countOnly, boolean filesOnly) {

    /** Whether the offsets within a matching line are worth collecting. */
    boolean highlights() {
        return !invertMatch && showsLines();
    }

    /** Whether any line of a file is printed, which is what makes its neighbours worth keeping. */
    boolean showsLines() {
        return !countOnly && !filesOnly;
    }
}
