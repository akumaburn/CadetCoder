package com.eonmux.cadetcoder.commands;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The lines one file contributes to the result, matches and their surroundings together.
 *
 * <h2>Why the surrounding lines are chosen here rather than at print time</h2>
 *
 * <p>The file is read once, as a stream, and a line that was not kept is gone. Deciding at print
 * time which neighbours to show would mean reading every matching file a second time. So the lines
 * before a match are held in a small ring while the file goes past, and the lines after a match are
 * counted down as they arrive.</p>
 *
 * <p>A line is offered to exactly one of the two, so a line cannot be shown twice when two matches
 * are close enough for their surroundings to overlap.</p>
 */
final class GrepLines {

    private final GrepContext       context;
    private final Deque<GrepMatch>  before    = new ArrayDeque<>();
    private final List<GrepMatch>   collected = new ArrayList<>();

    /** How many more lines after the last match are still wanted. */
    private int afterLeft;

    GrepLines(GrepContext context) {
        this.context = context == null ? GrepContext.NONE : context;
    }

    /** Takes a line the pattern selected. */
    void matched(GrepMatch match) {
        collected.addAll(before);
        before.clear();
        collected.add(match);
        afterLeft = context.after();
    }

    /**
     * Takes a line the pattern did not select.
     *
     * <p>Kept only when it is near a match. A line still owed to the match just seen is added
     * straight away; otherwise it waits in the ring in case a match follows it.</p>
     */
    void unmatched(int lineNumber, String line) {
        if (!context.wanted()) {
            return;
        }
        if (afterLeft > 0) {
            collected.add(GrepMatch.context(lineNumber, line));
            afterLeft--;
            return;
        }
        if (context.before() == 0) {
            return;
        }
        before.addLast(GrepMatch.context(lineNumber, line));
        if (before.size() > context.before()) {
            before.removeFirst();
        }
    }

    /** Every line this file contributes, in file order. */
    List<GrepMatch> collected() {
        return collected;
    }
}
