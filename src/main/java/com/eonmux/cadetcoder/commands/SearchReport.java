package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.context.ContextMatch;

import java.util.List;

/**
 * Prints what {@code search} found, in the shape {@code grep} prints what it found.
 *
 * <h2>Why this is shaped like grep's report</h2>
 *
 * <p>The two commands answer the same question by different means -- where in this project is the
 * thing I am after -- so an answer from one should be readable by someone who has only ever used
 * the other. A path, then numbered lines, then a count. Before this, {@code search} printed
 * {@code File: } and a thousand raw characters through {@code println}, which carried none of the
 * markers {@code ui/OutputLineStyler} classifies: the results were not a section the shell could
 * navigate to, not styled as anything, and not separable from each other by eye.</p>
 *
 * <p>The line numbers matter more here than in {@code grep}'s output, because a search result is
 * an excerpt with gaps in it. A jump from 41 to 118 is the reader's only signal that two fragments
 * of one file are not adjacent, so the numbers are always shown -- {@code grep}'s {@code -n} has no
 * counterpart here.</p>
 */
final class SearchReport {

    private SearchReport() {
    }

    /**
     * Prints the matches and a closing count.
     *
     * @param matches what the index found, most relevant first
     */
    static void show(List<ContextMatch> matches) {
        for (ContextMatch match : matches) {
            // Primary result: printHeader and println are non-gated, so a found file is not
            // suppressed at MINIMAL verbosity the way printInfo would be.
            OutputFormatter.printHeader(match.path());
            showLines(match.lines());
            if (match.elided() > 0) {
                OutputFormatter.printInfo(String.format(
                        "       %d further matching line%s not shown",
                        match.elided(), match.elided() == 1 ? "" : "s"));
            }
        }

        long files = matches.size();
        long lines = matches.stream().mapToLong(ContextMatch::matchedLineCount).sum();
        OutputFormatter.printSuccess(String.format("Found %d matching line%s in %d file%s",
                                                   lines, lines == 1 ? "" : "s",
                                                   files, files == 1 ? "" : "s"));
    }

    /** One file's lines, with a marker wherever the excerpt skips over part of the file. */
    private static void showLines(List<ContextMatch.Line> lines) {
        int previous = 0;
        for (ContextMatch.Line line : lines) {
            if (previous > 0 && line.number() > previous + 1) {
                OutputFormatter.println("   ...");
            }
            OutputFormatter.println(String.format("%6d: %s", line.number(), line.text()));
            previous = line.number();
        }
    }
}
