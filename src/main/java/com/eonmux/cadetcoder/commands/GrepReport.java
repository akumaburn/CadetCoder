package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.ui.ColorTheme;
import com.eonmux.cadetcoder.ui.ColorThemeManager;
import com.eonmux.cadetcoder.ui.Glyphs;
import com.eonmux.cadetcoder.ui.UnifiedOutput;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * What a search found, and what it could not look at.
 *
 * <h2>Why what was missed is said out loud</h2>
 *
 * <p>A search that quietly omits a file gives an answer nobody can tell apart from an honest miss.
 * A file that is not valid UTF-8, one that could not be opened, a directory that could not be
 * listed, a directory the walk never descended into because it is hidden or excluded, a file the
 * credential denylist refused, a file that resolves somewhere this tool does not read from -- each
 * is reported once, after the results, so a single problem file cannot flood the output and a
 * refusal never displaces the matches themselves.</p>
 */
final class GrepReport {

    /** How many names one summary line lists before it starts counting instead. */
    private static final int MAX_REPORTED_FILE_NAMES = 5;

    private static final Glyphs HEADER_GLYPHS = Glyphs.system();

    /** What separates two groups of lines that are not next to each other in the file. */
    private static final String GAP = "--";

    private final GrepMatching          matching;
    private final boolean               showLineNumbers;
    private final boolean               showColumns;
    private final boolean               colorEnabled;
    private final LoggingCommandSupport log;

    /**
     * @param matching        what the run was looking for, which decides the shape of the output
     * @param showLineNumbers whether each matching line is numbered
     * @param showColumns     whether each matching line says where in it the pattern matched
     * @param colorEnabled    whether matches within a line are highlighted
     * @param log             where the report also goes, in full, for the debug log
     */
    GrepReport(GrepMatching matching, boolean showLineNumbers, boolean showColumns,
               boolean colorEnabled, LoggingCommandSupport log) {
        this.matching        = matching;
        this.showLineNumbers = showLineNumbers;
        this.showColumns     = showColumns;
        this.colorEnabled    = colorEnabled;
        this.log             = log;
    }

    /**
     * Prints the matches, in whichever of the three shapes this run asked for.
     *
     * @param results     what was found, by file
     * @param pattern     what was looked for, for the debug log
     * @param searchPath  where it was looked for, for the debug log
     */
    void show(Map<Path, List<GrepMatch>> results, String pattern, String searchPath) {
        Path          currentDir = Paths.get(".").toAbsolutePath().normalize();
        StringBuilder captured   = new StringBuilder();

        for (Map.Entry<Path, List<GrepMatch>> entry : results.entrySet()) {
            Path relativePath = currentDir.relativize(entry.getKey().toAbsolutePath());
            if (matching.filesOnly()) {
                // Primary result: routed through a non-gated channel so file names are not
                // suppressed at MINIMAL verbosity (printInfo only emits at NORMAL+).
                println(relativePath.toString(), captured);
            } else if (matching.countOnly()) {
                println(String.format("%s:%d", relativePath, entry.getValue().size()), captured);
            } else {
                showFile(relativePath.toString(), entry.getValue(), captured);
            }
        }

        int totalFiles   = results.size();
        int totalMatches = GrepMatch.matchedIn(results.values());
        String summary = String.format("Found %d match%s in %d file%s",
                                       totalMatches, totalMatches == 1 ? "" : "es",
                                       totalFiles, totalFiles == 1 ? "" : "s");
        OutputFormatter.printSuccess(summary);
        captured.append("SUMMARY: ").append(summary).append("\n");

        record(pattern, searchPath, totalFiles, totalMatches, captured);
    }

    /** One file's header and its matching lines. */
    private void showFile(String header, List<GrepMatch> matches, StringBuilder captured) {
        OutputFormatter.printHeader(header);
        // The capture buffer (debug log only) mirrors the console rather than hand-building the
        // header shape, which had already drifted from what printHeader emits.
        captured.append(HEADER_GLYPHS.headerMarker()).append(' ').append(header)
                .append(HEADER_GLYPHS.headerCloser()).append('\n');

        int previousLine = 0;
        for (GrepMatch match : matches) {
            if (previousLine != 0 && match.lineNumber > previousLine + 1) {
                UnifiedOutput.println(GAP);
                captured.append(GAP).append("\n");
            }
            previousLine = match.lineNumber;

            String rendered = gutter(match) + body(match);
            UnifiedOutput.println(rendered);
            captured.append(rendered).append("\n");
        }
        UnifiedOutput.println();
        captured.append("\n");
    }

    /** Prints one primary result line and keeps a copy for the debug log. */
    private static void println(String line, StringBuilder captured) {
        OutputFormatter.println(line);
        captured.append(line).append("\n");
    }

    /** The whole of what was shown, for the debug log. */
    private void record(String pattern, String searchPath, int totalFiles, int totalMatches,
                        StringBuilder captured) {
        log.logDebug("Search Results", String.format("PATTERN: %s", pattern));
        log.logDebug("Search Results", String.format("SEARCH_PATH: %s", searchPath));
        log.logDebug("Search Results", String.format("TOTAL_FILES: %d", totalFiles));
        log.logDebug("Search Results", String.format("TOTAL_MATCHES: %d", totalMatches));
        log.logDebug("Search Results",
                     String.format("OPTIONS: count=%b, files_only=%b, line_numbers=%b, invert=%b",
                                   matching.countOnly(), matching.filesOnly(), showLineNumbers,
                                   matching.invertMatch()));
        log.logDebug("Search Results",
                     String.format("RESULTS_LENGTH: %d characters", captured.length()));
        log.logDebug("Search Results", "=== SEARCH RESULTS START ===");
        log.logDebug("Search Results", captured.toString());
        log.logDebug("Search Results", "=== SEARCH RESULTS END ===");
    }

    /**
     * Emits at most one summary line per category for what the walk did not read as it stood: files
     * re-read with replacement characters because they are not valid UTF-8, files that could not be
     * searched at all, directories that could not be listed, and directories that were never
     * descended into because they are hidden or excluded.
     *
     * @param search the walk that has just finished
     */
    void showDegradations(GrepSearch search) {
        List<Path> lossy = search.lossilyDecodedFiles();
        if (!lossy.isEmpty()) {
            log.logDataProcessing("search", "files decoded with replacement characters",
                                  lossy.size(), 0);
            OutputFormatter.printWarning(String.format(
                    "%d file%s not valid UTF-8, decoded with replacement characters: %s",
                    lossy.size(), lossy.size() == 1 ? " is" : "s are", names(lossy)));
        }

        List<Path> files = search.unsearchableFiles();
        if (!files.isEmpty()) {
            log.logDataProcessing("search", "files skipped after read errors", files.size(), 0);
            OutputFormatter.printWarning(String.format(
                    "%d file%s could not be read and %s skipped: %s",
                    files.size(), files.size() == 1 ? "" : "s",
                    files.size() == 1 ? "was" : "were", names(files)));
        }

        List<Path> dirs = search.unsearchableDirs();
        if (!dirs.isEmpty()) {
            log.logDataProcessing("search", "directories skipped after read errors", dirs.size(), 0);
            OutputFormatter.printWarning(String.format(
                    "%d director%s could not be read and %s not searched: %s",
                    dirs.size(), dirs.size() == 1 ? "y" : "ies",
                    dirs.size() == 1 ? "was" : "were", names(dirs)));
        }

        // Last, because it is the only one of the four that is a decision rather than a failure.
        // It is still an omission: a caller who greps for a workflow and is told "No matches found"
        // has no way to tell that from a project whose .github directory was never entered, and the
        // line ends by saying what to type to enter it.
        List<Path> pruned = search.prunedDirectories();
        if (!pruned.isEmpty()) {
            log.logDataProcessing("search", "directories not descended into", pruned.size(), 0);
            OutputFormatter.printWarning(String.format(
                    "%d director%s hidden or excluded and %s not searched: %s "
                    + "(name one in --include to search it anyway)",
                    pruned.size(), pruned.size() == 1 ? "y is" : "ies are",
                    pruned.size() == 1 ? "was" : "were", names(pruned)));
        }
    }

    /**
     * Says, once and after the matches, which files the security rules kept this search out of.
     *
     * <p>{@code read} and {@code ls} refuse these files out loud, so this says so too -- the
     * omission is exactly the one a person would want to know about. Two rules, and so two lines,
     * because they answer different questions: one file holds credentials, the other resolves
     * somewhere this tool does not read from at all.</p>
     *
     * @param search the walk that has just finished
     */
    void showRefusals(GrepSearch search) {
        List<Path> credentials = search.refusedCredentialFiles();
        if (!credentials.isEmpty()) {
            log.logSecurityEvent("GREP_ACCESS_DENIED",
                                 "Credential files skipped: " + names(credentials), false);
            OutputFormatter.printWarning(String.format("%d credential file%s skipped: %s",
                                                       credentials.size(),
                                                       credentials.size() == 1 ? " was" : "s were",
                                                       names(credentials)));
        }

        List<Path> unsafe = search.refusedUnsafePaths();
        if (!unsafe.isEmpty()) {
            log.logSecurityEvent("GREP_ACCESS_DENIED",
                                 "Paths outside what a search may read skipped: " + names(unsafe),
                                 false);
            OutputFormatter.printWarning(String.format(
                    "%d file%s skipped because %s outside what a search may read: %s",
                    unsafe.size(), unsafe.size() == 1 ? "" : "s",
                    unsafe.size() == 1 ? "it resolves" : "they resolve", names(unsafe)));
        }
    }

    /**
     * A bounded, comma-separated list of file names, relative to the current directory where
     * possible. At most {@link #MAX_REPORTED_FILE_NAMES} are listed; any remainder is a count.
     */
    private static String names(List<Path> files) {
        Path          currentDir = Paths.get(".").toAbsolutePath().normalize();
        StringBuilder names      = new StringBuilder();
        int           shown      = Math.min(files.size(), MAX_REPORTED_FILE_NAMES);

        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                names.append(", ");
            }
            names.append(forDisplay(files.get(i), currentDir));
        }
        if (files.size() > shown) {
            names.append(", and ").append(files.size() - shown).append(" more");
        }
        return names.toString();
    }

    /** The path relative to the current directory, or the absolute path when they share no root. */
    private static String forDisplay(Path file, Path currentDir) {
        try {
            return currentDir.relativize(file.toAbsolutePath()).toString();
        } catch (IllegalArgumentException e) {
            return file.toString();
        }
    }

    /**
     * What precedes a result line: its number, and where in it the pattern matched.
     *
     * <p>A colon after the number marks a line the pattern selected; a dash marks a line kept only
     * for what it surrounds. Those are GNU grep's own conventions, so anything that already reads
     * grep output reads this. A surrounding line carries no column, because nothing on it
     * matched.</p>
     */
    private String gutter(GrepMatch match) {
        if (!showLineNumbers) {
            return "";
        }
        if (!match.matched) {
            return String.format("%5d- ", match.lineNumber);
        }
        if (showColumns && match.column() > 0) {
            return String.format("%5d:%d: ", match.lineNumber, match.column());
        }
        return String.format("%5d: ", match.lineNumber);
    }

    /** The line itself, with each match in it picked out where that applies. */
    private String body(GrepMatch match) {
        return match.matchPositions.isEmpty()
               ? match.line
               : highlighted(match.line, match.matchPositions);
    }

    /** One line with each match in it picked out, when this terminal shows colour at all. */
    private String highlighted(String line, List<int[]> positions) {
        if (positions.isEmpty() || !colorEnabled) {
            return line;
        }
        ColorTheme    theme       = ColorThemeManager.getCurrentTheme();
        StringBuilder highlighted = new StringBuilder();
        int           lastEnd     = 0;

        for (int[] position : positions) {
            if (!within(position, line)) {
                continue;
            }
            if (position[0] >= lastEnd) {
                highlighted.append(line, lastEnd, position[0]);
            }
            highlighted.append(theme.colorize(line.substring(position[0], position[1]),
                                              ColorTheme.ColorType.ACCENT1));
            lastEnd = Math.max(lastEnd, position[1]);
        }
        if (lastEnd < line.length()) {
            highlighted.append(line.substring(lastEnd));
        }
        return highlighted.toString();
    }

    /** Whether a recorded match position still describes somewhere in this line. */
    private static boolean within(int[] position, String line) {
        return position.length == 2 && position[0] >= 0 && position[1] >= 0
               && position[0] <= position[1]
               && position[0] <= line.length() && position[1] <= line.length();
    }
}
