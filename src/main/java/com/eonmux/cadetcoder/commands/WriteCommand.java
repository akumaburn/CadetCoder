package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ExitCode;
import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.security.SecurityValidator;
import com.eonmux.cadetcoder.session.SessionManager;
import com.eonmux.cadetcoder.ui.OutputRouter;
import com.eonmux.cadetcoder.ui.UnifiedOutput;
import com.eonmux.cadetcoder.security.ReadOnlyGuard;
import com.eonmux.cadetcoder.security.WritePathPolicy;
import picocli.CommandLine.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.Scanner;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

@Command (name = "write", description = "Write content to a file")
public class WriteCommand extends LoggingCommandSupport implements CommandRegistry.Command, CommandRegistry.InterruptibleCommand, Callable<Integer> {

    @Parameters (index = "0", description = "The file path to write to")
    private String filePath;
    
    private CommandRegistry.InterruptionContext interruptionContext;

    @Override
    public void setInterruptionContext(CommandRegistry.InterruptionContext context) {
        this.interruptionContext = context;
    }

    @Parameters (index = "1..*", description = "Content to write (or use -i for interactive input)")
    private String[] contentParts;

    @Option (names = {"-i", "--interactive"}, description = "Enter content interactively")
    private boolean interactive;

    @Option (names = {"-f", "--force"}, description = "Force overwrite without confirmation")
    private boolean force;

    /** Maximum number of "did you mean" hints printed for a target that does not exist yet. */
    private static final int MAX_PATH_SUGGESTIONS    = 3;

    /** Depth limit for the (informational) suggestion scan. */
    private static final int SUGGESTION_SEARCH_DEPTH = 5;

    /** Upper bound on files visited by the suggestion scan, so a large tree cannot stall a write. */
    private static final int SUGGESTION_SEARCH_LIMIT = 5000;

    @Override
    public int execute(String[] args) {
        try {
            startCommandLogging("write", args);
            logStep("Initializing write command");
            
            if (args.length == 0) {
                logErrorQuietly("execute", "No file path provided");
                OutputFormatter.printError("No file path provided");
                OutputFormatter.printInfo("Usage: " + CommandUsage.render(getUsage()));
                completeCommandLogging(1);
                return 1;
            }

            String        targetPath     = null;
            StringBuilder content        = new StringBuilder();
            boolean       useInteractive = false;
            boolean       forceWrite     = false;
            boolean       contentSet     = false;

            // Parse arguments. Flags (-i/--interactive, -f/--force) are recognized in any
            // position; the first non-flag token is the file path and the FIRST remaining
            // non-flag token is taken as the verbatim content (a single parameter, since the
            // content is supplied as one token by the dispatcher / a quoted CLI argument). A
            // "--" token ends option parsing so that following tokens (including ones that
            // start with '-') are treated positionally.
            //
            // Content is intentionally NOT re-joined from multiple whitespace-split tokens:
            // doing so would collapse runs of internal whitespace. Any extra positional tokens
            // after the first content token are preserved verbatim (separated by a single
            // space) only as a legacy fallback for unquoted multi-token CLI input.
            logStep("Parsing arguments");
            boolean positionalOnly = false;
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];

                if (!positionalOnly && arg.equals("--")) {
                    positionalOnly = true;
                    continue;
                }

                if (!positionalOnly && (arg.equals("-i") || arg.equals("--interactive"))) {
                    useInteractive = true;
                    logStep("Setting mode", "Interactive input enabled");
                    continue;
                }
                if (!positionalOnly && (arg.equals("-f") || arg.equals("--force"))) {
                    forceWrite = true;
                    logStep("Setting mode", "Force overwrite enabled");
                    continue;
                }

                // Positional token. Reject an unrecognized option in the file-path position:
                // a token starting with '-' must be separated by '--' to be used as a path.
                if (targetPath == null && !positionalOnly && arg.startsWith("-")) {
                    logErrorQuietly("execute", "Unknown option: " + arg);
                    OutputFormatter.printError("Unknown option: " + arg);
                    OutputFormatter.printInfo("Usage: " + CommandUsage.render(getUsage()));
                    completeCommandLogging(1);
                    return 1;
                }

                if (targetPath == null) {
                    targetPath = arg;
                    logStep("Parsing arguments", "File: " + targetPath);
                } else if (!contentSet) {
                    // First content token: store it verbatim without any whitespace handling.
                    content.append(arg);
                    contentSet = true;
                } else {
                    // Legacy fallback for unquoted multi-token CLI content.
                    content.append(" ").append(arg);
                }
            }

            if (targetPath == null) {
                logErrorQuietly("execute", "No file path provided");
                OutputFormatter.printError("No file path provided");
                OutputFormatter.printInfo("Usage: " + CommandUsage.render(getUsage()));
                completeCommandLogging(1);
                return 1;
            }

            addContext("filePath", targetPath);
            addContext("interactive", useInteractive);
            addContext("force", forceWrite);
            if (!useInteractive) {
                addContext("contentLength", content.length());
            }

            int result = writeFile(targetPath, content.toString(), useInteractive, forceWrite);
            completeCommandLogging(result);
            return result;
        } catch (Exception e) {
            logErrorQuietly("execute", "Error writing file", e);
            OutputFormatter.printError("Error writing file: " + e.getMessage());
            
            // Provide more helpful error message based on exception type
            if (e instanceof NoSuchFileException) {
                OutputFormatter.printInfo("File or directory not found: " + e.getMessage());
                OutputFormatter.printInfo("Check if the parent directory exists.");
            } else if (e instanceof AccessDeniedException) {
                OutputFormatter.printInfo("Access denied: " + e.getMessage());
                OutputFormatter.printInfo("Check file permissions or try running with elevated privileges.");
            } else if (e instanceof IOException) {
                OutputFormatter.printInfo("I/O error: " + e.getMessage());
                OutputFormatter.printInfo("Check if the file system is accessible and has sufficient space.");
            }
            
            OutputFormatter.printInfo("Usage: " + CommandUsage.render(getUsage()));
            completeCommandLogging(1);
            return 1;
        }
    }

    private int writeFile(String targetPath, String content, boolean useInteractive, boolean forceWrite) throws
                                                                                                         IOException {
        logStep("Starting file write", targetPath);
        
        // Validate parameters
        if (!validateParameters(targetPath, content, useInteractive)) {
            return 1;
        }
        
        if (ReadOnlyGuard.isEnabled()) {
            logErrorQuietly("writeFile", "Read-only mode is enabled");
            ReadOnlyGuard.blocks("write file");
            return 1;
        }

        // System-directory and dangerous-path enforcement is delegated to
        // SecurityValidator.isFileAccessAllowed (consistent denylist + normalized
        // matching) and the project-containment check in validatePath. A raw,
        // substring-based pre-check here was inconsistent with that policy and is
        // intentionally omitted.

        // The caller-supplied path is used VERBATIM. Earlier versions treated well-known
        // names ("output.txt", "result.json", "output.json", "path/to/...") as placeholders
        // and silently retargeted the write to the first same-named file found anywhere in
        // the project tree. Because write keeps no ".backup" copy, that quietly and
        // irrecoverably destroyed unrelated data (writing "output.txt" clobbered an existing
        // "sub/output.txt"). A write target is therefore never rewritten; when the path looks
        // like it may be a mistake we only print a suggestion (see printPathSuggestions).

        // Security validation
        logStep("Performing security validation");
        SecurityValidator validator = new SecurityValidator();
        if (!validator.isFileAccessAllowed(targetPath)) {
            logSecurityEvent("FILE_WRITE_BLOCKED", "File write blocked by security policy: " + targetPath, false);
            OutputFormatter.printError("Access denied: File write blocked by security policy");
            OutputFormatter.printInfo("File path: " + targetPath);
            return 1;
        }
        
        logSecurityEvent("FILE_WRITE_ALLOWED", "File write allowed: " + targetPath, true);

        // Resolve the path verbatim: a relative path is resolved against the working
        // directory and ".." segments are collapsed (so validatePath can enforce
        // containment) - nothing else. Deliberately NOT FilePathResolver.resolve: that
        // helper searches the project for similar names and, on a single (even partial)
        // match, silently returns a DIFFERENT existing file, which for a write means
        // overwriting a file the caller never named.
        logStep("Resolving file path", targetPath);
        Path currentDir = Paths.get(System.getProperty("user.dir"));
        Path path       = resolveVerbatim(targetPath, currentDir);

        // Additional validation
        path = validatePath(path);
        if (path == null) {
            return 1;
        }

        // Save state for undo functionality
        SessionManager.getInstance().saveState();

        // Check if file exists and needs confirmation
        boolean fileExists = Files.exists(path);

        // Overwriting a file nobody has read replaces contents the model never saw with what it
        // imagined they were, and unlike a failed search-and-replace it SUCCEEDS. See ReadBeforeEdit.
        String unread = ReadBeforeEdit.reasonNotToChange(path, "write");
        if (unread != null) {
            OutputFormatter.printError(unread);
            return 1;
        }

        if (!fileExists) {
            // Purely informational hint; the target above is never changed by it.
            printPathSuggestions(path, currentDir);
        }

        if (fileExists &&
            !forceWrite &&
            ConfigManager.getInstance().getConfig().getSecurity().isRequireConfirmation()) {
            OutputFormatter.printWarning("File already exists: " + path);
            if (!OutputRouter.getInstance().getConfirmation(overwriteQuestion(path, content))) {
                OutputFormatter.printInfo("Write cancelled; nothing was changed. Give -f to "
                                          + "overwrite an existing file without being asked.");
                // The file on disk is the one that was already there. Answering 0 said the write had
                // happened, so `cadet write config.json ... && deploy` deployed the old contents.
                return ExitCode.INTERRUPTED;
            }
        }

        // Get content to write
        String finalContent = content;
        if (useInteractive) {
            logStep("Interactive mode", "Waiting for user input");
            OutputFormatter.printInfo("Enter content (type 'EOF' on a new line to finish):");
            StringBuilder interactiveContent = new StringBuilder();
            long          maxSize            =
                    ConfigManager.getInstance().getConfig().getSecurity().getMaxFileContentBytes();
            int           lineCount          = 0;
            // Track the accumulated size in UTF-8 BYTES (not chars) so the limit is enforced
            // against the same unit the file is written in. The separator is counted too.
            long          contentBytes       = 0;
            byte[]        separatorBytes     =
                    System.lineSeparator().getBytes(java.nio.charset.StandardCharsets.UTF_8);

            // TUI-aware line source: under the TUI, read via the shell prompt (System.in is owned
            // by JLine); otherwise read System.in directly (terminal, pipe, or injected test input).
            Supplier<String> lineReader = interactiveLineReader();

            while (true) {
                String line = lineReader.get();
                if (line == null || line.equals("EOF")) {
                    break;
                }

                // Check for interruption every 100 lines
                if (++lineCount % 100 == 0 && interruptionContext != null && interruptionContext.isInterrupted()) {
                    logStep("Interactive input", "Interrupted by user after " + lineCount + " lines");
                    OutputFormatter.printWarning("Operation interrupted by user");
                    return 1;
                }

                // Enforce the size limit in bytes BEFORE appending the overflowing line, so
                // the content can never exceed the configured maximum.
                long lineBytes = line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length +
                                 (long) separatorBytes.length;
                if (contentBytes + lineBytes > maxSize) {
                    logErrorQuietly("Interactive input", "Content size exceeds maximum limit");
                    OutputFormatter.printError("Content size exceeds maximum limit (" +
                                               (maxSize / 1024 / 1024) +
                                               "MB)");
                    return 1;
                }

                interactiveContent.append(line).append(System.lineSeparator());
                contentBytes += lineBytes;
            }

            finalContent = interactiveContent.toString();
            logStep("Interactive input", "Received " + lineCount + " lines of content");
            addContext("contentLength", finalContent.length());
        }

        // Ensure parent directory exists
        Path parent = path.getParent();
        if (parent != null && !Files.exists(parent)) {
            logStep("Creating directories", "Creating parent directories: " + parent);
            Files.createDirectories(parent);
        }

        // Check if this is a large file
        byte[]  bytes       = finalContent.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        boolean isLargeFile = bytes.length > 1024 * 1024; // 1MB threshold
        if (isLargeFile) {
            logStep("Large file detected", "Content size: " + (bytes.length / 1024 / 1024) + "MB");
            OutputFormatter.printInfo("Writing large file (" + (bytes.length / 1024 / 1024) + "MB)");
        }

        // Write atomically: stage the full content into a sibling temp file, then move it
        // onto the target with ATOMIC_MOVE + REPLACE_EXISTING. This guarantees a reader
        // never observes a truncated file and that a failure (or interruption) leaves any
        // pre-existing target intact. If ATOMIC_MOVE is unsupported by the filesystem we
        // fall back to a (non-atomic) replacing move; the staging file still prevents a
        // partially written target.
        if (!writeAtomically(path, bytes, isLargeFile)) {
            // Interrupted mid-write; the original file (if any) was left untouched.
            return 1;
        }

        // Having written it, the run knows what is in it; see ReadBeforeEdit.
        ReadBeforeEdit.sawContents(path);

        if (fileExists) {
            logStep("File operation complete", "File overwritten: " + path);
            OutputFormatter.printSuccess("File overwritten: " + path);
        } else {
            logStep("File operation complete", "File created: " + path);
            OutputFormatter.printSuccess("File created: " + path);
        }

        return 0;
    }

    /**
     * Writes {@code bytes} to {@code path} atomically by staging the full content in a
     * sibling temporary file and then moving it onto the target. A reader therefore never
     * observes a truncated file, and any failure or user interruption leaves a pre-existing
     * target unchanged.
     *
     * <p>For large content the staging write is chunked so the operation can be interrupted;
     * on interruption the staging file is deleted and the original target is preserved.
     *
     * @param path        the destination path
     * @param bytes       the UTF-8 content to write
     * @param chunked     whether to stage in chunks (large files, interruptible)
     * @return {@code true} if the content was written and moved into place; {@code false} if
     *         the operation was interrupted (target left untouched)
     * @throws IOException if staging or the move fails (target left untouched)
     */
    private boolean writeAtomically(Path path, byte[] bytes, boolean chunked) throws IOException {
        Path parent = path.getParent();
        // Stage in the same directory as the target so the move stays on one filesystem
        // (a prerequisite for ATOMIC_MOVE).
        Path tempPath = (parent != null)
                ? Files.createTempFile(parent, "." + path.getFileName() + ".", ".tmp")
                : Files.createTempFile("." + path.getFileName() + ".", ".tmp");

        try {
            logStep("Staging file write", "Temp: " + tempPath);
            try (java.io.OutputStream out = Files.newOutputStream(tempPath,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                if (chunked) {
                    int chunkSize = 1024 * 1024; // 1MB chunks
                    for (int offset = 0; offset < bytes.length; offset += chunkSize) {
                        if (interruptionContext != null && interruptionContext.isInterrupted()) {
                            logStep("File writing", "Interrupted by user after staging " + offset + " bytes");
                            OutputFormatter.printWarning("Operation interrupted by user");
                            return false;
                        }
                        int length = Math.min(chunkSize, bytes.length - offset);
                        out.write(bytes, offset, length);

                        if (bytes.length > 10 * 1024 * 1024 && offset % (5 * 1024 * 1024) == 0) {
                            logStep("Write progress",
                                    String.format("%.1f%% complete", (double) offset / bytes.length * 100));
                        }
                    }
                } else {
                    out.write(bytes);
                }
            }

            logStep("Committing file write", "Path: " + path);
            try {
                Files.move(tempPath, path,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                // Filesystem cannot guarantee atomicity; fall back to a replacing move. The
                // staging file still prevents a partially written target.
                logStep("Atomic move unsupported", "Falling back to replacing move");
                Files.move(tempPath, path, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            // Staging or move failed: remove the temp file and leave the original intact.
            deleteQuietly(tempPath);
            throw e;
        } finally {
            // If we bailed out without moving (interruption), clean up the staging file.
            if (Files.exists(tempPath)) {
                deleteQuietly(tempPath);
            }
        }
    }

    /** Best-effort deletion of a staging file; failures are logged, never propagated. */
    private void deleteQuietly(Path tempPath) {
        try {
            Files.deleteIfExists(tempPath);
        } catch (IOException e) {
            logWarning("Atomic write", "Failed to delete staging file " + tempPath + ": " + e.getMessage());
        }
    }

    private Path validatePath(Path filePath) {
        WritePathPolicy.Decision decision = WritePathPolicy.decide(filePath);
        if (!decision.isAllowed()) {
            logSecurityEvent("FILE_WRITE_VALIDATION", decision + ": " + filePath, false);
            OutputFormatter.printError(WritePathPolicy.reasonFor(decision, filePath));
            return null;
        }
        logSecurityEvent("FILE_WRITE_VALIDATION", "Path: " + filePath, true);
        return filePath;
    }

    /**
     * Builds a line source for interactive content entry. Under the TUI it reads via the
     * TUI-aware {@link OutputRouter} (the shell prompt, since {@code System.in} is owned by
     * JLine); otherwise it reads {@code System.in} directly so the command works with a real
     * terminal, a pipe, or test-injected input. The {@code System.in} scanner is intentionally
     * not closed (closing it would close the shared standard input stream).
     */
    private Supplier<String> interactiveLineReader() {
        OutputRouter router = OutputRouter.getInstance();
        if (router.isRouting()) {
            return () -> router.getUserInput("");
        }
        Scanner scanner = new Scanner(System.in);
        return () -> scanner.hasNextLine() ? scanner.nextLine() : null;
    }


    /**
     * The question asked before an existing file is replaced, with what it replaces.
     *
     * <p>In auto mode a model answers it and sees nothing but this text, so "Overwrite?" asked it
     * about nothing in particular.</p>
     *
     * @param path    the file
     * @param content what is to be written, or {@code null} when it is typed in afterwards
     * @return the question
     */
    static String overwriteQuestion(Path path, String content) {
        String now;
        try (java.util.stream.Stream<String> lines = Files.lines(path)) {
            now = lines.count() + " lines";
        } catch (IOException | java.io.UncheckedIOException unreadable) {
            now = "contents that could not be counted";
        }
        String next = content == null ? "content to be typed in"
                                      : content.lines().count() + " lines";
        return "Overwrite " + path + ", which holds " + now + ", with " + next + "?";
    }

    @Override
    public String getUsage() {
        return "write <file_path> [content] [-i|--interactive] [-f|--force]";
    }

    @Override
    public Integer call() throws Exception {
        String content = contentParts != null ? String.join(" ", contentParts) : "";
        return writeFile(filePath, content, interactive, force);
    }
    
    /**
     * Resolves the caller-supplied path to the file that will be written, WITHOUT ever
     * changing which file it names: a relative path is resolved against the working
     * directory and ".." segments are collapsed so {@link #validatePath} can enforce
     * containment on a normalized path. There is no project search and no name
     * substitution - the target is exactly the path the caller asked for.
     *
     * @param targetPath the caller-supplied path
     * @param currentDir the working directory relative paths are resolved against
     * @return the normalized target path
     */
    private Path resolveVerbatim(String targetPath, Path currentDir) {
        Path path = Paths.get(targetPath);
        return (path.isAbsolute() ? path : currentDir.resolve(path)).normalize();
    }

    /**
     * Prints a hint when a file with the same name already exists elsewhere under the
     * target's nearest existing ancestor directory (e.g. writing "output.txt" while
     * "sub/output.txt" exists), so a mistyped target is visible before the write.
     *
     * <p>This is PURELY informational: the write target is the caller-supplied path and is
     * never changed by what this finds. The scan is bounded in depth and in visited entries,
     * skips build/cache and hidden directories, and any failure is logged and swallowed so a
     * failed hint can never fail the write.
     *
     * @param target     the (already validated) absolute target path, known not to exist
     * @param currentDir the working directory, used to display suggestions relatively
     */
    private void printPathSuggestions(Path target, Path currentDir) {
        Path base = target.getParent();
        while (base != null && !Files.isDirectory(base)) {
            base = base.getParent();
        }
        if (base == null) {
            return;
        }

        final String  fileName    = target.getFileName().toString();
        final Path    searchRoot  = base;
        final java.util.List<Path> suggestions = new java.util.ArrayList<>();

        try {
            Files.walkFileTree(searchRoot,
                               java.util.EnumSet.noneOf(FileVisitOption.class),
                               SUGGESTION_SEARCH_DEPTH,
                               new SimpleFileVisitor<Path>() {
                private int visited = 0;

                @Override
                public FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs) {
                    // Skip build/cache and hidden directories, but never the start node itself.
                    if (com.eonmux.cadetcoder.util.ProjectTreeWalk.isPruned(searchRoot, dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
                    // Bound the work so a large tree can never stall a write.
                    if (++visited > SUGGESTION_SEARCH_LIMIT) {
                        return FileVisitResult.TERMINATE;
                    }
                    if (file.getFileName().toString().equals(fileName) && !file.equals(target)) {
                        suggestions.add(file);
                        if (suggestions.size() >= MAX_PATH_SUGGESTIONS) {
                            return FileVisitResult.TERMINATE;
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, java.io.IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception e) {
            logWarning("Path suggestion", "Error searching for similar files: " + e.getMessage());
            return;
        }

        if (suggestions.isEmpty()) {
            return;
        }

        logStep("Path suggestion", "Similar file(s) found for " + target + ": " + suggestions);
        OutputFormatter.printInfo("A file with the same name already exists elsewhere:");
        for (Path suggestion : suggestions) {
            OutputFormatter.printInfo("  did you mean '" + displayPath(suggestion, currentDir) + "'?");
        }
        OutputFormatter.printInfo("Creating '" + target + "' as given; the supplied path is never rewritten.");
    }

    /** Renders a suggestion relative to the working directory when it lies inside it. */
    private String displayPath(Path path, Path currentDir) {
        try {
            Path normalizedDir = currentDir.toAbsolutePath().normalize();
            Path normalizedFile = path.toAbsolutePath().normalize();
            if (normalizedFile.startsWith(normalizedDir)) {
                return "." + java.io.File.separator + normalizedDir.relativize(normalizedFile);
            }
        } catch (Exception e) {
            // Fall through to the absolute form; a display detail must never break the write.
        }
        return path.toString();
    }

    /**
     * Validates command parameters and provides helpful error messages
     * @param filePath The file path to validate
     * @param content The content to validate
     * @param useInteractive Whether interactive mode is enabled
     * @return true if parameters are valid, false otherwise
     */
    private boolean validateParameters(String filePath, String content, boolean useInteractive) {
        boolean valid = true;
        
        // Validate file path
        if (filePath == null || filePath.trim().isEmpty()) {
            logErrorQuietly("validateParameters", "File path is empty");
            OutputFormatter.printError("File path cannot be empty");
            OutputFormatter.printInfo("Usage: " + CommandUsage.render(getUsage()));
            return false;
        }
        
        // Path traversal is NOT rejected by a raw ".." substring here: a legitimate
        // filename may contain ".." (e.g. "my..notes.txt"), and a blanket substring
        // reject both produces false positives and gives a weaker guarantee than a
        // normalized containment check. Traversal that actually escapes the working
        // directory is caught after normalization in validatePath (and by
        // SecurityValidator.isFileAccessAllowed's traversal pattern), so we do not
        // pre-reject on the raw string here.

        // Check for invalid characters in file path
        if (filePath.contains("*") || filePath.contains("?") ||
            filePath.contains("<") || filePath.contains(">") || filePath.contains("|")) {
            logErrorQuietly("validateParameters", "File path contains invalid characters: " + filePath);
            OutputFormatter.printError("File path contains invalid characters");
            OutputFormatter.printInfo("File paths cannot contain: * ? < > |");
            return false;
        }
        
        // Check for absolute paths that might be outside the project
        if (filePath.startsWith("/") || filePath.startsWith("\\") || 
            (filePath.length() > 1 && filePath.charAt(1) == ':')) {
            logWarning("validateParameters", "Absolute path detected: " + filePath);
            OutputFormatter.printWarning("Using absolute path: " + filePath);
            OutputFormatter.printInfo("Consider using relative paths for better portability");
        }
        
        // Validate content if not in interactive mode
        if (!useInteractive) {
            // Check if content is null
            if (content == null) {
                logWarning("validateParameters", "Content is null");
                OutputFormatter.printWarning("Content is null, will write an empty file");
                return true; // Allow empty content
            }
            
            // Check content size, in the unit the limit is stated in.
            //
            // The comparison used to be against content.length(), which counts UTF-16 code units,
            // while the file is written as UTF-8 and the setting is named for bytes. UTF-8 is never
            // shorter, so the mismatch could only let too much through: ten million CJK characters
            // measured 10,000,000 against a 10,485,760-byte limit and wrote a 28.6 MB file. The
            // interactive branch has always counted bytes.
            long maxSize = ConfigManager.getInstance().getConfig().getSecurity().getMaxFileContentBytes();
            long size    = content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (size > maxSize) {
                logErrorQuietly("validateParameters", "Content size exceeds maximum limit");
                OutputFormatter.printError("Content size exceeds maximum limit (" + (maxSize / 1024 / 1024) + "MB)");
                return false;
            }
        }
        
        return valid;
    }
}