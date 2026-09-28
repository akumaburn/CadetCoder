package com.eonmux.cadetcoder.commands;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * One line of a result, and where in it the pattern matched.
 *
 * <h2>Why the positions are collected separately</h2>
 *
 * <p>Highlighting needs every occurrence in the line, not just the first, and a line is matched
 * once but printed once per run. Collecting the offsets while the matcher is still on that line is
 * the only place they are cheap to get; recovering them at print time would mean matching every
 * result line a second time. They stay empty for a run that will not highlight -- an inverted
 * match, a count, or a file listing.</p>
 *
 * <h2>Why a line carries whether it matched</h2>
 *
 * <p>A result may include lines around each match, and those lines did not match anything. Empty
 * positions do not say so, because they are also empty for an inverted match and for a run that
 * shows no lines at all. The flag is what separates the two, and it decides how the line is
 * numbered.</p>
 */
final class GrepMatch {

    final int         lineNumber;
    final String      line;
    final boolean     matched;
    final List<int[]> matchPositions = new ArrayList<>();

    GrepMatch(int lineNumber, String line) {
        this(lineNumber, line, true);
    }

    private GrepMatch(int lineNumber, String line, boolean matched) {
        this.lineNumber = lineNumber;
        this.line       = line;
        this.matched    = matched;
    }

    /** A line kept for what it surrounds rather than for what is in it. */
    static GrepMatch context(int lineNumber, String line) {
        return new GrepMatch(lineNumber, line, false);
    }

    void addMatchPosition(int start, int end) {
        matchPositions.add(new int[] {start, end});
    }

    /**
     * How many of these lines the pattern actually selected.
     *
     * <h2>Why this is not the size of the lists</h2>
     *
     * <p>A result carries the lines around each match as well as the matches themselves, so its
     * size is a description of the output rather than a finding about the project: one match with
     * four neighbours is one match. Counted here because two places say the number out loud -- the
     * summary line, and the warning a search that was stopped part-way prints -- and while each
     * counted for itself they disagreed, the same {@code -C 2} run reporting nine matches on one
     * line and forty-five on the next.</p>
     *
     * @param perFile the lines each file contributed
     * @return how many of them the pattern selected
     */
    static int matchedIn(Collection<List<GrepMatch>> perFile) {
        return (int) perFile.stream().flatMap(List::stream).filter(line -> line.matched).count();
    }

    /**
     * Where the first match on this line starts, counted from one.
     *
     * @return the column, or {@code 0} when nothing on this line matched or the offsets were not
     *         collected
     */
    int column() {
        return matchPositions.isEmpty() ? 0 : matchPositions.get(0)[0] + 1;
    }
}
