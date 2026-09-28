package com.eonmux.cadetcoder.patch;

import java.util.List;

/**
 * One run of changed lines, and the unchanged lines that locate it.
 *
 * <h2>Why both sides are held in full</h2>
 *
 * <p>A hunk is written as one list with a marker on each line, which is convenient to read and
 * awkward to use. Applying it needs the lines the file is expected to hold and the lines to put
 * there, so both are built once while the patch is read. The line numbers in the header are a hint
 * about where to look; the expected lines are what actually decides whether the hunk fits.</p>
 *
 * <h2>Why the missing final newline is carried rather than dropped</h2>
 *
 * <p>A file whose last line has no newline after it is a different file from one whose last line
 * does, and a patch that only adds or takes away that newline has nothing else in it at all. The
 * {@code \ No newline at end of file} marker is the only place the format says so, and it describes
 * whichever side of the hunk the line above it belongs to. Dropping it left such a patch applying
 * cleanly, changing nothing, and reporting success.</p>
 *
 * @param oldStart the line the old text is expected to start at, counted from one
 * @param newStart the line the new text starts at, counted from one
 * @param before   the lines the file is expected to hold, in order
 * @param after    the lines to put in their place, in order
 * @param beforeEndsWithoutNewline whether the patch says the last line of {@code before} ends the
 *                                 file with no newline after it
 * @param afterEndsWithoutNewline  whether the patch says the last line of {@code after} ends the
 *                                 file with no newline after it
 */
public record PatchedHunk(int oldStart, int newStart, List<String> before, List<String> after,
                          boolean beforeEndsWithoutNewline, boolean afterEndsWithoutNewline) {

    public PatchedHunk {
        before = List.copyOf(before);
        after  = List.copyOf(after);
    }

    /**
     * A hunk that says nothing about the file's final newline, which is every hunk whose patch
     * carried no {@code \ No newline at end of file} marker.
     */
    public PatchedHunk(int oldStart, int newStart, List<String> before, List<String> after) {
        this(oldStart, newStart, before, after, false, false);
    }

    /**
     * Whether the patch said anything at all about the file's final newline.
     *
     * <p>Silence is not the same as saying the newline is there: a hunk from a patch that carried
     * no marker leaves the file's own ending alone, and only a hunk that carried one moves it.</p>
     */
    public boolean speaksOfFinalNewline() {
        return beforeEndsWithoutNewline || afterEndsWithoutNewline;
    }
}
