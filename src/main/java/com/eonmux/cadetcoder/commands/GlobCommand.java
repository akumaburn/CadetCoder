package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.error.ErrorHandler;
import com.eonmux.cadetcoder.ui.UnifiedOutput;
import com.eonmux.cadetcoder.util.ProjectTreeWalk;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.regex.PatternSyntaxException;

@CommandLine.Command (
        name = "glob",
        description = "Find files matching glob patterns"
)
public class GlobCommand extends LoggingCommandSupport implements CommandRegistry.Command, java.util.concurrent.Callable<Integer> {

    @CommandLine.Parameters (index = "0", description = "Glob pattern to match files (e.g., **/*.java, src/**/*.ts)")
    private String pattern;

    @CommandLine.Option (names = {"-p", "--path"},
                         description = "Base path to search from (default: current directory)")
    private String basePath = ".";

    @CommandLine.Option (names = {"-l", "--limit"}, description = "Maximum number of results to return")
    private Integer limit;

    @CommandLine.Option (names = {"-s", "--sort"}, description = "Sort by: name, time, size (default: time)")
    private String sortBy = "time";

    @CommandLine.Option (names = {"-r", "--reverse"}, description = "Reverse sort order")
    private boolean reverseSort;

    @CommandLine.Option (names = {"-d", "--include-dirs"}, description = "Include directories in results")
    private boolean includeDirs;

    @CommandLine.Option (names = {"-f", "--full-path"}, description = "Show full absolute paths")
    private boolean showFullPath;

    @CommandLine.Option (names = {"--max-depth"},
                         description = "Maximum directory depth to search, matching Files.walkFileTree semantics: "
                                       + "0 = the start directory only (no files below it are visited), "
                                       + "1 = the start directory and its immediate children, and so on. "
                                       + "Must not be negative.")
    private int maxDepth = Integer.MAX_VALUE;

    @CommandLine.Option (names = {"-h", "--help"}, usageHelp = true, description = "Show this help message")
    private boolean helpRequested;

    private Configuration config;

    /**
     * How many matches {@code --limit} discarded, so the count reported can say so.
     *
     * <p>The number was computed and thrown away, and the closing line then reported
     * {@code matches.size()} -- the size AFTER the cut. A tree with 5,000 Java files searched with
     * {@code -l 20} ended with "Found 20 matches", which is not what the search found and not a
     * number anyone could act on. Per-instance rather than per-call because {@code execute} builds
     * a fresh command for every run.</p>
     */
    private int omittedByLimit;

    @Override
    public Integer call() throws Exception {
        return execute(null);
    }

    /**
     * Runs one glob, on an instance that has never run one before.
     *
     * <p>The registry builds a single {{@code GlobCommand}} and hands it every {{@code glob}} for the
     * life of the session, so every field of this class is otherwise shared between runs. That used
     * to be answered by putting each field back by hand before parsing -- a second statement of the
     * defaults the declarations already make, and one that goes out of date the moment a field is
     * added without anybody remembering it.</p>
     *
     * <p>A {{@code null}} argument vector is {{@link #call()}}'s dispatch, which has already parsed
     * into this instance: that instance is the invocation's, and there is nothing to hand over.</p>
     *
     * @param args the argument vector, or {{@code null}} when picocli has already bound the fields
     * @return the exit code
     */
    @Override
    public int execute(String[] args) {
        if (args == null) {
            return executeOnce(null);
        }
        return new GlobCommand().executeOnce(args);
    }

    private int executeOnce(String[] args) {
        try {
            startCommandLogging("glob", args);
            logStep("Initializing glob command");

            // A null argv means picocli's Callable dispatch (call()) has already bound the fields;
            // re-parsing null would throw, so this run uses them as they are.
            if (args != null) {
                // Parse command line arguments
                CommandLine cmd = new CommandLine(this);
                try {
                    cmd.parseArgs(args);
                    if (helpRequested) {
                        logStep("Help requested, displaying usage");
                        OutputFormatter.println(CommandUsage.render(getUsage()));
                        completeCommandLogging(0);
                        return 0;
                    }
                } catch (CommandLine.ParameterException e) {
                    // The user-facing line is picocli's own sentence, followed by the usage text;
                    // logging it quietly keeps the operation name and exception class in the log
                    // where they help, instead of on screen where they do not.
                    logErrorQuietly("execute", "Command line parsing failed", e);
                    OutputFormatter.printError(e.getMessage());
                    OutputFormatter.printInfo("Usage: " + CommandUsage.render(getUsage()));
                    completeCommandLogging(1);
                    return 1;
                }
            }

            // Validate that a pattern is available regardless of entry point.
            if (pattern == null || pattern.trim().isEmpty()) {
                logErrorQuietly("execute", "Missing required parameter: pattern");
                OutputFormatter.printError("Missing required parameter: pattern");
                OutputFormatter.printInfo("Usage: " + CommandUsage.render(getUsage()));
                completeCommandLogging(1);
                return 1;
            }

            // Validate max-depth. Files.walkFileTree treats 0 as "start directory only" and rejects
            // negatives with an IllegalArgumentException; surface that as a clear, user-friendly message
            // instead of a vague generic-catch failure.
            if (maxDepth < 0) {
                logErrorQuietly("execute", String.format("Invalid --max-depth: %d", maxDepth));
                OutputFormatter.printError("Invalid --max-depth: " + maxDepth
                        + " (must be 0 or greater; 0 searches only the start directory)");
                completeCommandLogging(1);
                return 1;
            }

            logStep("Glob configuration", String.format("Pattern: %s, Base path: %s, Sort: %s, Limit: %s",
                pattern, basePath, sortBy, limit != null ? limit.toString() : "none"));

            config = ConfigManager.getInstance().getConfig();

            Path startPath = Paths.get(basePath).toAbsolutePath().normalize();
            
            logStep("Starting path validation", String.format("Path: %s", startPath));

            if (!Files.exists(startPath)) {
                logError("execute", String.format("Path does not exist: %s", basePath));
                ErrorHandler.getInstance().handleException(new Exception("Path does not exist: " + basePath));
                completeCommandLogging(1);
                return 1;
            }

            logStep("Beginning file search", String.format("Pattern: %s, Max depth: %d", pattern, maxDepth));
            long searchStartTime = System.currentTimeMillis();
            List<FileMatch> matches = findMatchingFiles(startPath);
            long searchDuration = System.currentTimeMillis() - searchStartTime;
            
            logPerformance("File pattern matching", searchDuration);
            logDataProcessing("search", "files found", matches.size(), searchDuration);

            if (matches.isEmpty()) {
                logWarning("No matches found", String.format("No files found matching pattern: %s", pattern));
                OutputFormatter.printWarning("No files found matching pattern: " + pattern);
            } else {
                logStep("Sorting and displaying results", String.format("Found %d matches", matches.size()));
                long sortStartTime = System.currentTimeMillis();
                sortMatches(matches);
                long sortDuration = System.currentTimeMillis() - sortStartTime;
                
                logPerformance("File sorting", sortDuration);
                displayResults(matches, startPath);
            }

            completeCommandLogging(0);
            return 0;
        } catch (PatternSyntaxException e) {
            logError("execute", "Invalid glob pattern", e);
            ErrorHandler.getInstance().handleException(new Exception("Invalid glob pattern: " + e.getMessage(), e));
            completeCommandLogging(1);
            return 1;
        } catch (IOException e) {
            logError("execute", "File system operation failed", e);
            ErrorHandler.getInstance().handleException(e);
            completeCommandLogging(1);
            return 1;
        } catch (Exception e) {
            // Report the specific failure through the standard error channel (ErrorHandler),
            // preserving the root cause instead of silently swallowing it with a vague message.
            logError("execute", "Glob command execution failed", e);
            ErrorHandler.getInstance().handleException(
                    new Exception("Glob command failed: " + e.getMessage(), e));
            completeCommandLogging(1);
            return 1;
        }
    }

    private List<FileMatch> findMatchingFiles(Path startPath) throws IOException {
        logStep("Creating file matcher", String.format("Pattern: %s", pattern));
        List<FileMatch> matches = new ArrayList<>();
        PathMatcher     matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);

        final int[] visitedDirs = {0};
        final int[] visitedFiles = {0};
        final int[] skippedDirs = {0};

        Files.walkFileTree(startPath, EnumSet.noneOf(FileVisitOption.class), maxDepth, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                visitedDirs[0]++;
                String dirName = dir.getFileName() != null ? dir.getFileName().toString() : "";
                // Never skip the explicitly-provided start directory, even if it is hidden;
                // only prune hidden/excluded *sub*directories below it.
                if (!dir.equals(startPath) && ProjectTreeWalk.isPrunedName(dirName)) {
                    skippedDirs[0]++;
                    return FileVisitResult.SKIP_SUBTREE;
                }

                if (includeDirs && shouldMatch(dir, startPath, matcher)) {
                    try {
                        matches.add(new FileMatch(dir, attrs));
                    } catch (IOException e) {
                        logWarning("Directory access error", String.format("Error accessing directory: %s (%s)", dir, e.getMessage()));
                        OutputFormatter.printError("Error accessing directory: " + dir + " (" + e.getMessage() + ")");
                    }
                }

                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                visitedFiles[0]++;
                if (shouldMatch(file, startPath, matcher)) {
                    try {
                        matches.add(new FileMatch(file, attrs));
                    } catch (IOException e) {
                        logWarning("File access error", String.format("Error accessing file: %s (%s)", file, e.getMessage()));
                        OutputFormatter.printError("Error accessing file: " + file + " (" + e.getMessage() + ")");
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                logWarning("File access error", String.format("Skipping unreadable path: %s (%s)", file, exc.getMessage()));
                return FileVisitResult.CONTINUE;
            }
        });

        logDataProcessing("file_tree", "directories visited", visitedDirs[0], 0);
        logDataProcessing("file_tree", "directories skipped", skippedDirs[0], 0);
        logDataProcessing("file_tree", "files examined", visitedFiles[0], 0);
        logDataProcessing("search", "pattern matches", matches.size(), 0);

        return matches;
    }

    /**
     * Returns the file-name component of a match as a String, or an empty string when the path
     * has no file name (such as a filesystem root). Used by the name-sort comparator to stay
     * null-safe.
     */
    private static String fileNameOf(FileMatch match) {
        Path name = match.path.getFileName();
        return name != null ? name.toString() : "";
    }

    private void sortMatches(List<FileMatch> matches) {
        logStep("Setting up sort comparator", String.format("Sort by: %s, Reverse: %b", sortBy, reverseSort));
        Comparator<FileMatch> comparator;

        switch (sortBy.toLowerCase()) {
            case "name":
                // A path with no file name (e.g. a filesystem root) yields a null getFileName();
                // treat it as an empty name so the comparator never NPEs.
                comparator = Comparator.comparing(GlobCommand::fileNameOf);
                break;
            case "size":
                comparator = Comparator.comparingLong(m -> m.size);
                break;
            case "time":
            default:
                comparator = Comparator.comparing(m -> m.modifiedTime);
                break;
        }

        if (reverseSort) {
            comparator = comparator.reversed();
        }

        int originalSize = matches.size();
        matches.sort(comparator);
        logStep("Matches sorted successfully", String.format("Sorted %d items", originalSize));

        if (limit != null && limit > 0 && matches.size() > limit) {
            omittedByLimit = matches.size() - limit;
            matches.subList(limit, matches.size()).clear();
            logDataProcessing("limit", "results limited", limit, 0);
        }
    }

    /** Prefix marking a directory in the listing. */
    private static final String DIRECTORY_TAG = "[DIR] ";

    /** Widest the path column is allowed to grow, so one deep path does not indent every row. */
    private static final int MAX_PATH_COLUMN = 72;

    /**
     * A path cut to the column it has to sit in, keeping the end of it.
     *
     * <p>A path longer than the column used to be printed whole, which pushed its own timestamp past
     * everything else's and broke the alignment the column exists to provide -- so in a listing of
     * one package, the longest file names were the rows that came out crooked. The end is what is
     * kept, because a path identifies a file by its last segments and the rows sharing a directory
     * all begin alike.</p>
     *
     * @param path  the path as it would be shown
     * @param width the column it has
     * @return the path, or its tail behind an ellipsis
     */
    private static String fitPath(String path, int width) {
        if (path.length() <= width || width < 2) {
            return path;
        }
        return "…" + path.substring(path.length() - (width - 1));
    }

    private void displayResults(List<FileMatch> matches, Path startPath) {
        logStep("Displaying results", String.format("Show full path: %b, Total matches: %d", showFullPath, matches.size()));

        OutputFormatter.printHeader("Files matching pattern: " + pattern);

        int fileCount = 0;
        int dirCount = 0;
        long totalSize = 0;

        // The path column was a hardcoded 60 characters. Anything longer overflowed it and pushed
        // its own timestamp out of line -- so in a listing of one package, the three longest file
        // names were the ones that broke the alignment the column existed to provide -- while a
        // listing of short names wasted half the terminal. Measure what is actually being printed.
        int pathWidth = 0;
        for (FileMatch match : matches) {
            Path displayPath = showFullPath ? match.path.toAbsolutePath() : startPath.relativize(match.path);
            int width = displayPath.toString().length() + (match.isDirectory ? DIRECTORY_TAG.length() : 0);
            pathWidth = Math.max(pathWidth, width);
        }
        // ... but do not let one very long path set the column for everything else.
        pathWidth = Math.min(pathWidth, MAX_PATH_COLUMN);

        // The timestamps are not all the same length ("1 day ago" against "6 days ago"), so the size
        // that follows one needs a column of its own or it lands in a different place on every row.
        int timeWidth = 0;
        for (FileMatch match : matches) {
            timeWidth = Math.max(timeWidth, formatTime(match.modifiedTime).length());
        }

        for (FileMatch match : matches) {
            if (match.isDirectory) {
                dirCount++;
            } else {
                fileCount++;
                totalSize += match.size;
            }

            Path displayPath = showFullPath ? match.path.toAbsolutePath() : startPath.relativize(match.path);

            String type = match.isDirectory ? DIRECTORY_TAG : "";
            String size = match.isDirectory ? "" : String.format(" (%s)", formatSize(match.size));
            String time = formatTime(match.modifiedTime);
            String name = type + displayPath;

            // Stripped: a directory has no size, so the padding under the size column would other-
            // wise run off the end of its row as whitespace nobody can see and every copy carries.
            UnifiedOutput.println(String.format("%-" + pathWidth + "s  %-" + timeWidth + "s%s",
                    fitPath(name, pathWidth), time, size).stripTrailing());
        }

        UnifiedOutput.println();
        int found = matches.size() + omittedByLimit;
        OutputFormatter.printSuccess(String.format("Found %d match%s",
                found,
                found == 1 ? "" : "es"
                                                  ));
        if (omittedByLimit > 0) {
            OutputFormatter.printInfo(String.format(
                    "Showing the first %d; %d more omitted by --limit. Raise or drop it to see them.",
                    matches.size(), omittedByLimit));
        }
        
        logDataProcessing("display", "files displayed", fileCount, 0);
        logDataProcessing("display", "directories displayed", dirCount, 0);
        if (totalSize > 0) {
            logDataProcessing("display", "total file size bytes", (int)totalSize, 0);
        }
    }

    /**
     * Decides whether a discovered path matches the user's glob, using the documented semantics:
     *
     * <ul>
     *   <li>{@code *} matches within a single path component (it does not cross directory
     *       separators), so {@code *.java} matches only top-level {@code .java} files relative to
     *       the start directory.</li>
     *   <li>{@code **} crosses directory boundaries, so {@code **}{@code /*.java} matches
     *       {@code .java} files at any depth, including in the start directory itself.</li>
     * </ul>
     *
     * The pattern is tested against the path relative to the start directory (the primary,
     * documented form) and, as a convenience, against the absolute path. This lets a leading
     * {@code **} match start-directory entries (whose relative form has no directory prefix) while
     * NOT broadening a single {@code *} pattern into an implicit recursive match. Per-component
     * subpath matching is intentionally omitted because it widened {@code *} patterns to behave
     * like {@code **}, contradicting the catalog examples.
     */
    private boolean shouldMatch(Path path, Path basePath, PathMatcher matcher) {
        Path relativePath = basePath.relativize(path);

        boolean matchesRelative = matcher.matches(relativePath);
        boolean matchesFromRoot = matcher.matches(path);

        return matchesRelative || matchesFromRoot;
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        int    exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(Math.min(exp - 1, 5)) + "";
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }

    /** Renders a count with the singular or plural form of its unit. */
    private static String plural(long count, String singular, String plural) {
        return count + " " + (count == 1 ? singular : plural);
    }

    private String formatTime(FileTime fileTime) {
        long millis = System.currentTimeMillis() - fileTime.toMillis();

        if (millis < 60_000) {
            return "just now";
        } else if (millis < 3_600_000) {
            return plural(millis / 60_000, "min", "mins") + " ago";
        } else if (millis < 86_400_000) {
            // Pluralised: a file touched an hour ago read "1 hours ago", and one touched yesterday
            // read "1 days ago", in a listing whose whole job is to be scanned quickly.
            return plural(millis / 3_600_000, "hour", "hours") + " ago";
        } else if (millis < 2_592_000_000L) {
            return plural(millis / 86_400_000, "day", "days") + " ago";
        } else {
            // For older files, show the date (consistent with LSCommand)
            java.time.Instant instant = fileTime.toInstant();
            java.time.LocalDateTime dateTime = java.time.LocalDateTime.ofInstant(
                    instant, java.time.ZoneId.systemDefault());
            return dateTime.format(java.time.format.DateTimeFormatter.ofPattern("MMM dd HH:mm"));
        }
    }


    @Override
    public String getUsage() {
        return "glob <pattern> [options]   (* matches within one path segment, ** is recursive)\n"
             + "  -p, --path <dir>        Directory to search (default: current directory)\n"
             + "  -l, --limit <n>         Maximum number of results\n"
             + "  -s, --sort <name|time|size>  Sort order (default: time)\n"
             + "  -r, --reverse           Reverse the sort order\n"
             + "  -d, --include-dirs      Include directories in the results\n"
             + "  -f, --full-path         Show absolute paths\n"
             + "      --max-depth <n>     Maximum directory depth\n"
             + "  -h, --help              Show this option list\n"
             + "  Options accept --flag=value or --flag value. Quote a pattern containing spaces.";
    }

    private static class FileMatch {
        final Path     path;
        final FileTime modifiedTime;
        final long     size;
        final boolean  isDirectory;

        FileMatch(Path path, BasicFileAttributes attrs) throws IOException {
            this.path         = path;
            this.modifiedTime = attrs.lastModifiedTime();
            this.size         = attrs.size();
            this.isDirectory  = attrs.isDirectory();
        }
    }
}