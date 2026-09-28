package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.error.ErrorHandler;
import com.eonmux.cadetcoder.logging.DebugLogger;
import com.eonmux.cadetcoder.security.SecurityValidator;
import com.eonmux.cadetcoder.ui.UnifiedOutput;
import com.eonmux.cadetcoder.util.ProjectTreeWalk;
import com.eonmux.cadetcoder.ui.ThemedOutputFormatter;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.PatternSyntaxException;

@CommandLine.Command (
        name = "ls",
        description = "List files and directories"
)
public class LSCommand extends LoggingCommandSupport implements CommandRegistry.Command, CommandRegistry.InterruptibleCommand, java.util.concurrent.Callable<Integer> {

    @CommandLine.Parameters (index = "0", defaultValue = ".", description = "Directory path to list")
    private String path;

    @CommandLine.Option (names = {"-a", "--all"}, description = "Include hidden files (starting with .)")
    private boolean showAll;

    @CommandLine.Option (names = {"-l", "--long"}, description = "Use long listing format")
    private boolean longFormat;

    @CommandLine.Option (names = {"-r", "--reverse"}, description = "Reverse order while sorting")
    private boolean reverseSort;

    @CommandLine.Option (names = {"-t", "--time"}, description = "Sort by modification time")
    private boolean sortByTime;

    @CommandLine.Option (names = {"-S", "--size"}, description = "Sort by file size")
    private boolean sortBySize;

    @CommandLine.Option (names = {"-R", "--recursive"}, description = "List subdirectories recursively")
    private boolean recursive;

    @CommandLine.Option (names = {"-d", "--dirs-first"}, description = "List directories before files")
    private boolean dirsFirst;

    @CommandLine.Option (names = {"-i", "--ignore"}, description = "Glob patterns to ignore", arity = "0..*")
    private List<String> ignorePatterns = new ArrayList<>();

    @CommandLine.Option (names = {"--max-depth"}, description = "Maximum depth for recursive listing")
    private int maxDepth = Integer.MAX_VALUE;

    @CommandLine.Option (names = {"-h", "--help"}, usageHelp = true, description = "Show this help message")
    private boolean helpRequested;

    /** How many entries a recursive walk looks at between one interrupt check and the next. */
    private static final int ENTRIES_BETWEEN_INTERRUPT_CHECKS = 10;

    private Configuration     config;
    private List<PathMatcher> ignoreMatchers;

    /**
     * What one recursive walk has seen, counted across every level of it.
     *
     * <p>Per-instance because the registry hands each {@code ls} a fresh {@code LSCommand}, and
     * because the walk is recursive: tallies declared inside {@link #listRecursive} were allocated
     * again at every level, so each level counted only its own entries and only the outermost
     * level's count was ever reported.</p>
     */
    private final AtomicInteger walked    = new AtomicInteger();
    private final AtomicInteger descended = new AtomicInteger();
    private final AtomicInteger skipped   = new AtomicInteger();
    
    private CommandRegistry.InterruptionContext interruptionContext;
    
    @Override
    public void setInterruptionContext(CommandRegistry.InterruptionContext context) {
        this.interruptionContext = context;
    }
    
    /**
     * Checks if the command should be interrupted
     * @return true if the command should be interrupted
     */
    @Override
    public boolean shouldInterrupt() {
        return CommandRegistry.InterruptibleCommand.stopWasAsked(interruptionContext);
    }

    /**
     * Runs one listing, on an instance that has never run one before.
     *
     * <p>The registry builds a single {{@code LSCommand}} and hands it every {{@code ls}} for the
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
        LSCommand invocation = new LSCommand();
        invocation.interruptionContext = interruptionContext;
        return invocation.executeOnce(args);
    }

    private int executeOnce(String[] args) {
        try {
            startCommandLogging("ls", args);
            logStep("Initializing ls command");

            // An empty argv is still a real invocation and is parsed like any other: it is how a
            // bare "ls" asks for the current directory. Only a null one skips parsing, because
            // then picocli has already bound the fields.
            if (args != null) {
                // Parse the command line to populate fields
                CommandLine cmd = new CommandLine(this);
                try {
                    logStep("Parsing command line arguments");
                    cmd.parseArgs(args);
                    if (helpRequested) {
                        logStep("Help requested, displaying usage");
                        // The same text `help ls` shows. Two spellings of one command's options,
                        // reachable by two routes, is one to keep in step for no gain.
                        OutputFormatter.println(CommandUsage.render(getUsage()));
                        completeCommandLogging(0);
                        return 0;
                    }
                } catch (CommandLine.ParameterException e) {
                    // Reported the same way grep and glob report it: picocli's own sentence, marked
                    // as an error, followed by this command's own option list. It used to print an
                    // unmarked "Error: ..." line (styled as ordinary body text in the shell) under a
                    // developer-shaped duplicate from logError, and then push picocli's generated
                    // table through as a single blob -- a second description of the same options,
                    // in a different shape and wording from the one `help ls` renders.
                    logErrorQuietly("execute", "Command line parsing failed", e);
                    OutputFormatter.printError(e.getMessage());
                    OutputFormatter.printInfo("Usage: " + CommandUsage.render(getUsage()));

                    completeCommandLogging(1);
                    return 1;
                }
            }

            // If path is still null, use default
            if (path == null) {
                path = ".";
            }

            addContext("path", path);
            addContext("recursive", recursive);
            addContext("longFormat", longFormat);
            addContext("showAll", showAll);

            // Validate parameters (normalizes empty path, max depth, ignore patterns)
            if (!validateParameters()) {
                completeCommandLogging(1);
                return 1;
            }

            // Enforce the same project-boundary / sensitive-file policy the other
            // filesystem commands use, so the model cannot enumerate arbitrary
            // absolute paths outside the configured project root.
            if (!isPathAccessAllowed(path)) {
                completeCommandLogging(1);
                return 1;
            }

            config = ConfigManager.getInstance().getConfig();

            try {
                logStep("Resolving path", path);

                // Resolve the requested path. An existing path is used verbatim and is
                // NEVER treated as a placeholder; placeholder substitution only kicks in
                // when the path does not exist, and any substitution is re-validated
                // against the security policy below.
                Path targetPath = resolveTargetPath(path);
                if (targetPath == null) {
                    completeCommandLogging(1);
                    return 1;
                }
                addContext("targetPath", targetPath.toString());

                logStep("Setting up ignore matchers");
                setupIgnoreMatchers();

                if (Files.isDirectory(targetPath)) {
                    logStep("Listing directory", targetPath.toString());
                    if (recursive) {
                        logStep("Performing recursive listing", "Max depth: " + maxDepth);
                        listRecursive(targetPath, 0);
                    } else {
                        listDirectory(targetPath);
                    }
                } else {
                    // If it's a file, just show the file
                    logStep("Displaying single file", targetPath.toString());
                    displayFile(targetPath, Files.readAttributes(targetPath, BasicFileAttributes.class));
                }

                completeCommandLogging(0);
                return 0;
            } catch (NoSuchFileException e) {
                logErrorQuietly("execute", "File or directory not found", e);
                OutputFormatter.printError("File or directory not found: " + e.getMessage());
                OutputFormatter.printInfo("Check if the path exists or try using a different path");
                completeCommandLogging(1);
                return 1;
            } catch (AccessDeniedException e) {
                logErrorQuietly("execute", "Access denied to file or directory", e);
                OutputFormatter.printError("Access denied: " + e.getMessage());
                OutputFormatter.printInfo("Check file permissions or try running with elevated privileges");
                completeCommandLogging(1);
                return 1;
            } catch (IOException e) {
                logErrorQuietly("execute", "File system operation failed", e);
                OutputFormatter.printError("File system error: " + e.getMessage());
                OutputFormatter.printInfo("Check if the file system is accessible and has sufficient permissions");
                completeCommandLogging(1);
                return 1;
            }
        } catch (Exception e) {
            logErrorQuietly("execute", "LS command execution failed", e);
            OutputFormatter.printError("Error executing ls command: " + e.getMessage());
            OutputFormatter.printInfo("Usage: " + CommandUsage.render(getUsage()));
            completeCommandLogging(1);
            return 1;
        }
    }


    @Override
    public String getUsage() {
        return "ls [path] [options]   (path defaults to the current directory)\n"
             + "  -a, --all               Include hidden files\n"
             + "  -l, --long              Long listing format\n"
             + "  -R, --recursive         List subdirectories recursively\n"
             + "      --max-depth <n>     Maximum depth when recursing\n"
             + "  -t, --time              Sort by modification time\n"
             + "  -S, --size              Sort by file size\n"
             + "  -r, --reverse           Reverse the sort order\n"
             + "  -d, --dirs-first        List directories before files\n"
             + "  -i, --ignore <glob>...  Glob patterns to skip\n"
             + "  -h, --help              Show this option list\n"
             + "  Options accept --flag=value or --flag value.";
    }

    private void setupIgnoreMatchers() {
        ignoreMatchers = new ArrayList<>();
        FileSystem fs = FileSystems.getDefault();

        for (String pattern : ignorePatterns) {
            try {
                ignoreMatchers.add(fs.getPathMatcher("glob:" + pattern));
            } catch (PatternSyntaxException e) {
                OutputFormatter.printWarning("Invalid ignore pattern: " + pattern);
            }
        }
    }

    private void listDirectory(Path dir) throws IOException {
        List<FileEntry> entries = collectEntries(dir);
        logDataProcessing("directory_listing", "entries found", entries.size(), 0);
        sortEntries(entries);
        displayEntries(dir, entries);
    }

    /**
     * Reads the directory once and returns the included {@link FileEntry} entries (sorted is
     * left to the caller). The single read is reused by both the flat listing and the
     * recursive descent so the directory stream is not opened twice per directory.
     */
    private List<FileEntry> collectEntries(Path dir) throws IOException {
        logStep("Listing directory contents", dir.toString());
        List<FileEntry> entries = new ArrayList<>();
        AtomicInteger processedEntries = new AtomicInteger(0);
        int unreadableEntries = 0;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                // Check for interruption every 50 entries
                if (processedEntries.incrementAndGet() % 50 == 0 && shouldInterrupt()) {
                    logStep("Directory listing", "Interrupted by user after processing " + processedEntries.get() + " entries");
                    OutputFormatter.printWarning("Operation interrupted by user");
                    throw new InterruptedException("User interrupted the operation");
                }

                if (shouldInclude(entry)) {
                    try {
                        BasicFileAttributes attrs = Files.readAttributes(entry, BasicFileAttributes.class);
                        entries.add(new FileEntry(entry, attrs));
                    } catch (IOException e) {
                        // Log and continue if we can't read attributes for a specific file
                        logWarning("File attributes", "Could not read attributes for " + entry + ": " + e.getMessage());
                        unreadableEntries++;
                    }
                }
            }
        } catch (InterruptedException e) {
            // Restore the interrupt status before converting to IOException so callers higher
            // up the stack can still observe that the thread was interrupted.
            Thread.currentThread().interrupt();
            throw new IOException("Operation interrupted", e);
        }

        // Inform the user when some entries could not be read so they aren't silently omitted
        if (unreadableEntries > 0) {
            OutputFormatter.printWarning(unreadableEntries + " entr" + (unreadableEntries == 1 ? "y" : "ies")
                    + " could not be read and were skipped (check file permissions)");
        }

        return entries;
    }

    private void listRecursive(Path dir, int depth) throws IOException {
        // Check for interruption at the start of each recursive call
        if (shouldInterrupt()) {
            logStep("Recursive listing", "Interrupted by user at depth " + depth);
            OutputFormatter.printWarning("Operation interrupted by user");
            throw new IOException("Operation interrupted");
        }

        if (depth > maxDepth) {
            return;
        }

        if (depth > 0) {
            OutputFormatter.printHeader(dir.toString());
        }

        // Read the directory ONCE and reuse the entries for both the flat listing and the
        // recursive descent into subdirectories (previously the stream was opened twice).
        List<FileEntry> entries = collectEntries(dir);
        logDataProcessing("directory_listing", "entries found", entries.size(), 0);
        sortEntries(entries);
        displayEntries(dir, entries);

        // Recursively list subdirectories, reusing the already-collected entries.
        for (FileEntry entry : entries) {
            // Asked every ten ENTRIES, which is what this loop walks. Counted as directories it was
            // both the wrong rate and the wrong word: a directory of 200 files and 2 subdirectories
            // asked twenty times and reported "200 directories", while a tree of directories holding
            // fewer than ten entries each never asked at all.
            if (walked.incrementAndGet() % ENTRIES_BETWEEN_INTERRUPT_CHECKS == 0
                && shouldInterrupt()) {
                logStep("Recursive listing", "Interrupted by user after " + walked.get()
                                             + " entries at depth " + depth);
                OutputFormatter.printWarning("Operation interrupted by user");
                throw new IOException("Operation interrupted");
            }

            if (entry.attrs.isDirectory()) {
                String dirName = entry.path.getFileName().toString();
                if (!ProjectTreeWalk.isExcludedName(dirName) && (showAll || !dirName.startsWith("."))) {
                    descended.incrementAndGet();
                    UnifiedOutput.println();
                    listRecursive(entry.path, depth + 1);
                } else {
                    skipped.incrementAndGet();
                }
            }
        }

        // Reported once, by the call that started the walk. The tallies used to be allocated fresh
        // at every level, so this line described the top directory alone and every deeper level's
        // counting was thrown away.
        if (depth == 0) {
            logDataProcessing("recursive_listing", "entries walked", walked.get(), 0);
            logDataProcessing("recursive_listing", "directories descended into", descended.get(), 0);
            logDataProcessing("recursive_listing", "directories skipped", skipped.get(), 0);
        }
    }

    private boolean shouldInclude(Path path) {
        String fileName = path.getFileName().toString();

        // Check hidden files
        if (!showAll && fileName.startsWith(".")) {
            return false;
        }

        // Check ignore patterns
        for (PathMatcher matcher : ignoreMatchers) {
            if (matcher.matches(path.getFileName())) {
                return false;
            }
        }

        return true;
    }

    private void sortEntries(List<FileEntry> entries) {
        Comparator<FileEntry> comparator;

        if (sortByTime) {
            comparator = Comparator.comparing(e -> e.attrs.lastModifiedTime());
        } else if (sortBySize) {
            comparator = Comparator.comparingLong(e -> e.attrs.size());
        } else {
            comparator = Comparator.comparing(e -> e.path.getFileName().toString().toLowerCase());
        }

        if (dirsFirst) {
            comparator = Comparator.comparing((FileEntry e) -> !e.attrs.isDirectory())
                                   .thenComparing(comparator);
        }

        if (reverseSort) {
            comparator = comparator.reversed();
        }

        entries.sort(comparator);
    }

    private void displayEntries(Path dir, List<FileEntry> entries) {
        if (entries.isEmpty()) {
            // Log empty directory to debug log
            DebugLogger debugLogger = DebugLogger.getInstance();
            debugLogger.debug("Directory Listing", String.format("DIRECTORY: %s", dir.toString()));
            debugLogger.debug("Directory Listing", "ENTRIES: 0");
            debugLogger.debug("Directory Listing", "CONTENT: <empty directory>");
            
            OutputFormatter.printInfo("Empty directory");
            return;
        }

        int  totalFiles = 0;
        int  totalDirs  = 0;
        long totalSize  = 0;
        StringBuilder directoryListing = new StringBuilder();

        for (FileEntry entry : entries) {
            String entryOutput;
            if (longFormat) {
                // Capture long format output
                String name = entry.path.getFileName().toString();
                String type = entry.attrs.isDirectory() ? "d" : "-";
                String permissions = getPermissions(entry.path);
                String size = entry.attrs.isDirectory() ? "-" : formatSize(entry.attrs.size());
                String modified = formatTime(entry.attrs.lastModifiedTime());
                entryOutput = String.format("%s%s %8s %s %s", type, permissions, size, modified, name);
                directoryListing.append(entryOutput).append("\n");
                
                displayFileLong(entry);
            } else {
                String name = entry.path.getFileName().toString();
                if (entry.attrs.isDirectory()) {
                    entryOutput = name + "/";
                } else if (Files.isExecutable(entry.path)) {
                    entryOutput = name + "*";
                } else {
                    entryOutput = name;
                }
                directoryListing.append(entryOutput).append("\n");
                
                displayFile(entry.path, entry.attrs);
            }

            if (entry.attrs.isDirectory()) {
                totalDirs++;
            } else {
                totalFiles++;
                totalSize += entry.attrs.size();
            }
        }

        UnifiedOutput.println();
        String summaryOutput = String.format(
                "%d file%s, %d director%s, total size: %s",
                totalFiles, totalFiles == 1 ? "" : "s",
                totalDirs, totalDirs == 1 ? "y" : "ies",
                formatSize(totalSize)
                                               );
        OutputFormatter.printInfo(summaryOutput);
        directoryListing.append("\nSUMMARY: ").append(summaryOutput);
        
        // Log directory listing to debug log
        DebugLogger debugLogger = DebugLogger.getInstance();
        debugLogger.debug("Directory Listing", String.format("DIRECTORY: %s", dir.toString()));
        debugLogger.debug("Directory Listing", String.format("TOTAL_ENTRIES: %d", entries.size()));
        debugLogger.debug("Directory Listing", String.format("FILES: %d", totalFiles));
        debugLogger.debug("Directory Listing", String.format("DIRECTORIES: %d", totalDirs));
        debugLogger.debug("Directory Listing", String.format("TOTAL_SIZE: %d bytes", totalSize));
        debugLogger.debug("Directory Listing", String.format("FORMAT: %s", longFormat ? "long" : "short"));
        debugLogger.debug("Directory Listing", String.format("LISTING_LENGTH: %d characters", directoryListing.length()));
        debugLogger.debug("Directory Listing", "=== DIRECTORY LISTING START ===");
        debugLogger.debug("Directory Listing", directoryListing.toString());
        debugLogger.debug("Directory Listing", "=== DIRECTORY LISTING END ===");
    }

    private void displayFile(Path path, BasicFileAttributes attrs) {
        String name = path.getFileName().toString();

        // Colour, not status. printInfo/printSuccess prepend the console's information and success
        // markers, so a directory printed as "ℹ src/" and an executable as "✓ build*" -- and in the
        // long format the marker landed in the middle of the row, after the permissions and date.
        // printPath/printAccent2/printAccent1 colour the text and add nothing to it.
        if (attrs.isDirectory()) {
            ThemedOutputFormatter.printPath(name + "/");
        } else if (Files.isExecutable(path)) {
            ThemedOutputFormatter.printAccent2(name + "*");
        } else if (isSourceName(name)) {
            ThemedOutputFormatter.printAccent1(name);
        } else {
            UnifiedOutput.println(name);
        }
    }

    private void displayFileLong(FileEntry entry) {
        Path                path  = entry.path;
        BasicFileAttributes attrs = entry.attrs;

        String type        = attrs.isDirectory() ? "d" : "-";
        String permissions = getPermissions(path);
        String size        = attrs.isDirectory() ? "-" : formatSize(attrs.size());
        String modified    = formatTime(attrs.lastModifiedTime());
        String name        = path.getFileName().toString();

        // Format the complete line without ANSI codes
        String formattedLine = String.format("%s%s %8s %s ", type, permissions, size, modified);
        
        UnifiedOutput.print(formattedLine);
        if (attrs.isDirectory()) {
            ThemedOutputFormatter.printPath(name + "/");
        } else if (Files.isExecutable(path)) {
            ThemedOutputFormatter.printAccent2(name + "*");
        } else if (isSourceName(name)) {
            ThemedOutputFormatter.printAccent1(name);
        } else {
            UnifiedOutput.println(name);
        }
    }

    /** File extensions rendered in the source-code accent colour. */
    private static final java.util.Set<String> SOURCE_SUFFIXES =
            java.util.Set.of(".java", ".py", ".js", ".ts", ".cpp", ".c");

    /** Whether {@code name} names a source file, by extension. */
    private static boolean isSourceName(String name) {
        for (String suffix : SOURCE_SUFFIXES) {
            if (name.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private String getPermissions(Path path) {
        StringBuilder perms = new StringBuilder("rwxrwxrwx");

        if (!Files.isReadable(path)) {
            perms.setCharAt(0, '-');
            perms.setCharAt(3, '-');
            perms.setCharAt(6, '-');
        }

        if (!Files.isWritable(path)) {
            perms.setCharAt(1, '-');
            perms.setCharAt(4, '-');
            perms.setCharAt(7, '-');
        }

        if (!Files.isExecutable(path)) {
            perms.setCharAt(2, '-');
            perms.setCharAt(5, '-');
            perms.setCharAt(8, '-');
        }

        return perms.toString();
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + "B";
        }
        int    exp = (int) (Math.log(bytes) / Math.log(1024));
        // Clamp the unit index so exabyte-scale sizes (exp-1 > 5) do not throw
        // StringIndexOutOfBoundsException (matches GlobCommand.formatSize).
        String pre = "KMGTPE".charAt(Math.min(exp - 1, 5)) + "";
        return String.format("%.1f%s", bytes / Math.pow(1024, exp), pre);
    }

    /** Width of the modification-time column, so the size and name columns stay aligned. */
    private static final int TIME_COLUMN_WIDTH = 12;

    /** e.g. {@code "1 day ago"}, {@code "3 days ago"}. */
    private static String relative(long count, String singular, String plural) {
        return count + " " + (count == 1 ? singular : plural) + " ago";
    }

    /** Right-aligns a relative time in the fixed column. */
    private static String padTime(String text) {
        return text.length() >= TIME_COLUMN_WIDTH
               ? text
               : " ".repeat(TIME_COLUMN_WIDTH - text.length()) + text;
    }

    private String formatTime(FileTime fileTime) {
        long millis = System.currentTimeMillis() - fileTime.toMillis();

        if (millis < 0) {
            // Modification time is in the future (clock skew, time zone, or misconfiguration)
            return padTime("future date");
        } else if (millis < 60_000) {
            return padTime("just now");
        } else if (millis < 3_600_000) {
            // Pluralised, and padded once at the end rather than by each branch spelling out its own
            // trailing spaces -- which is how "just now" ended up one column narrower than the rest.
            return padTime(relative(millis / 60_000, "min", "mins"));
        } else if (millis < 86_400_000) {
            return padTime(relative(millis / 3_600_000, "hr", "hrs"));
        } else if (millis < 604_800_000L) {
            return padTime(relative(millis / 86_400_000, "day", "days"));
        } else {
            // For older files, show the date
            java.time.Instant instant = fileTime.toInstant();
            java.time.LocalDateTime dateTime = java.time.LocalDateTime.ofInstant(
                    instant, java.time.ZoneId.systemDefault());
            return dateTime.format(java.time.format.DateTimeFormatter.ofPattern("MMM dd HH:mm"));
        }
    }

    @Override
    public Integer call() throws Exception {
        return execute(null);
    }

    /**
     * Gates listing behind the shared {@link SecurityValidator} policy: rejects paths that
     * escape the configured project root (unless {@code allowOutsideProject} is enabled),
     * sensitive credential / system files, and path-traversal attempts. Emits a clear,
     * user-facing error and returns {@code false} when access is denied.
     *
     * @param requestedPath the raw path requested by the caller
     * @return {@code true} if listing is permitted, {@code false} otherwise
     */
    private boolean isPathAccessAllowed(String requestedPath) {
        SecurityValidator validator = new SecurityValidator();

        if (validator.isSensitiveCredentialFile(requestedPath)) {
            logSecurityEvent("LS_ACCESS_DENIED", "Sensitive credential file blocked: " + requestedPath, false);
            OutputFormatter.printError("Access denied: cannot list sensitive credential files");
            OutputFormatter.printInfo("Path: " + requestedPath);
            return false;
        }

        if (!validator.isFileAccessAllowed(requestedPath)) {
            logSecurityEvent("LS_ACCESS_DENIED", "Path blocked by security policy: " + requestedPath, false);
            OutputFormatter.printError("Access denied: path blocked by security policy");
            OutputFormatter.printInfo("Path: " + requestedPath);
            OutputFormatter.printInfo("Listing is restricted to the project root unless 'allowOutsideProject' is enabled");
            return false;
        }

        logSecurityEvent("LS_ACCESS_GRANTED", "Path access allowed: " + requestedPath, true);
        return true;
    }

    /**
     * Resolves the requested path to an existing, policy-approved {@link Path} to list.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>If the path exists, it is used verbatim. An existing path is never reclassified
     *       as a placeholder, so a real directory is never silently rewritten.</li>
     *   <li>If the path does not exist, placeholder substitution is attempted. A substituted
     *       path is only accepted when it actually exists AND passes the security policy.</li>
     *   <li>Otherwise the command fails fast with "Path does not exist: &lt;path&gt;".</li>
     * </ol>
     *
     * @param requestedPath the validated (non-empty) requested path
     * @return the resolved absolute path to list, or {@code null} if resolution failed (an
     *         error has already been reported to the user)
     */
    private Path resolveTargetPath(String requestedPath) {
        Path direct = Paths.get(requestedPath).toAbsolutePath().normalize();
        if (Files.exists(direct)) {
            // Existing path: use as-is, never treat as a placeholder so a real directory is
            // never silently rewritten.
            return direct;
        }

        // Path does not exist: try placeholder substitution (filesystem search) before failing.
        // A substituted path is only accepted when it actually exists AND passes the policy.
        String substituted = com.eonmux.cadetcoder.util.PlaceholderPath.resolved(
                requestedPath, message -> logWarning("Directory search", message));
        if (!substituted.equals(requestedPath)) {
            Path candidate = Paths.get(substituted).toAbsolutePath().normalize();
            if (Files.exists(candidate) && isPathAccessAllowed(substituted)) {
                logStep("Path substitution", "Substituted '" + requestedPath + "' with '" + substituted + "'");
                OutputFormatter.printInfo("Substituted path: '" + requestedPath + "' → '" + substituted + "'");
                return candidate;
            }
        }

        // Fail fast: do not fuzzy-resolve into an unrelated file/directory.
        reportPathDoesNotExist(requestedPath);
        return null;
    }

    /**
     * Reports a clear "path does not exist" error, adding a placeholder hint when the
     * requested path looks like an unresolved placeholder.
     */
    private void reportPathDoesNotExist(String requestedPath) {
        logErrorQuietly("execute", "Path does not exist: " + requestedPath);
        OutputFormatter.printError("Path does not exist: " + requestedPath);
        OutputFormatter.printInfo("Check if the path exists or try using a different path");

        if (com.eonmux.cadetcoder.util.PlaceholderPath.looksLikeStandIn(requestedPath)) {
            StringBuilder suggestion = new StringBuilder(
                    "The path appears to be a placeholder. Try using an actual path");
            if (!requestedPath.equals("src") && Files.exists(Paths.get("src"))) {
                suggestion.append(" like 'src' or '.'");
            } else {
                suggestion.append(" like '.' for the current directory");
            }
            OutputFormatter.printInfo(suggestion.toString());
        }
    }
    
    /**
     * Validates command parameters and provides helpful error messages
     * @return true if parameters are valid, false otherwise
     */
    private boolean validateParameters() {
        boolean valid = true;
        
        // Validate path
        if (path == null || path.trim().isEmpty()) {
            logErrorQuietly("validateParameters", "Path is empty");
            OutputFormatter.printError("Path cannot be empty");
            OutputFormatter.printInfo("Using current directory (.) as fallback");
            path = ".";
        }
        
        // Validate max depth
        if (maxDepth < 0) {
            logWarning("validateParameters", "Invalid max depth: " + maxDepth);
            OutputFormatter.printWarning("Max depth cannot be negative, using unlimited depth");
            maxDepth = Integer.MAX_VALUE;
        }
        
        // Validate ignore patterns
        for (String pattern : ignorePatterns) {
            if (pattern == null || pattern.trim().isEmpty()) {
                logWarning("validateParameters", "Empty ignore pattern");
                OutputFormatter.printWarning("Empty ignore pattern will be ignored");
                continue;
            }
            
            try {
                // Test if the pattern is valid
                FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            } catch (PatternSyntaxException e) {
                logWarning("validateParameters", "Invalid ignore pattern: " + pattern);
                OutputFormatter.printWarning("Invalid ignore pattern: " + pattern + " - " + e.getMessage());
                OutputFormatter.printInfo("Pattern will be ignored. Use valid glob patterns like *.java or *.txt");
            }
        }
        
        return valid;
    }
    

    private static class FileEntry {
        final Path                path;
        final BasicFileAttributes attrs;

        FileEntry(Path path, BasicFileAttributes attrs) {
            this.path  = path;
            this.attrs = attrs;
        }
    }
}