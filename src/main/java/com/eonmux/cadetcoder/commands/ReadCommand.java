package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.logging.DebugLogger;
import com.eonmux.cadetcoder.security.SecurityValidator;
import com.eonmux.cadetcoder.ui.ProgramOutput;
import com.eonmux.cadetcoder.ui.UnifiedOutput;
import com.eonmux.cadetcoder.util.FilePathResolver;
import com.eonmux.cadetcoder.util.TextFiles;
import com.eonmux.cadetcoder.ui.InteractivePrompts;
import picocli.CommandLine.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.Callable;

@Command (name = "read", description = "Read file contents with line numbers")
public class ReadCommand extends LoggingCommandSupport implements CommandRegistry.Command, CommandRegistry.InterruptibleCommand, Callable<Integer> {

    // Maximum number of lines to include in debug logs
    private static final int MAX_LOG_LINES = 20;

    /** How much of a file a read returns when the caller does not say. */
    private static final int DEFAULT_LIMIT = 2000;

    /** Where a read starts when the caller does not say; line numbers are 1-based. */
    private static final int DEFAULT_OFFSET = 1;

    @Parameters (index = "0", description = "The file path to read")
    private String filePath;

    private CommandRegistry.InterruptionContext interruptionContext;

    @Override
    public void setInterruptionContext(CommandRegistry.InterruptionContext context) {
        this.interruptionContext = context;
    }

    @Option (names = {"-l", "--limit"}, description = "Number of lines to read")
    private Integer limit = DEFAULT_LIMIT;

    @Option (names = {"-o", "--offset"}, description = "Line number to start reading from (1-based)")
    private Integer offset = DEFAULT_OFFSET;

    /**
     * Runs one read, on an instance that has never run one before.
     *
     * <p>The registry builds a single {@code ReadCommand} and hands it every {@code read} for the
     * life of the session, so {@code limit} and {@code offset} are otherwise shared between runs.
     * That used to be answered by putting them back by hand here -- a second statement of the
     * defaults the declarations already make, and one that goes out of date the moment an option is
     * added without anybody remembering it.</p>
     *
     * @param args the argument vector
     * @return the exit code
     */
    @Override
    public int execute(String[] args) {
        ReadCommand invocation = new ReadCommand();
        invocation.interruptionContext = interruptionContext;
        return invocation.executeOnce(args);
    }

    private int executeOnce(String[] args) {
        try {
            startCommandLogging("read", args);

            // The command catalog advertises --limit=<n>/--offset=<n>, so accept that spelling as well
            // as the space-separated form. Without this the documented syntax was silently ignored and
            // the whole-file default read happened instead of the requested range.
            args = CommandOptions.expandInlineValues(args,
                    java.util.Set.of("-l", "--limit", "-o", "--offset"));

            // Parse arguments manually for CommandRegistry compatibility
            if (args.length == 0) {
                logErrorQuietly("execute", "No file path provided");
                OutputFormatter.printError("No file path provided");
                OutputFormatter.printInfo("Usage: " + CommandUsage.render(getUsage()));
                completeCommandLogging(1);
                return 1;
            }

            String targetPath = args[0];
            addContext("filePath", targetPath);
            logStep("Parsing arguments", String.format("File: %s, Default limit: %d, Default offset: %d", targetPath, limit, offset));

            // Parse additional options if provided
            try {
                for (int i = 1; i < args.length; i++) {
                    if ((args[i].equals("-l") || args[i].equals("--limit")) && i + 1 < args.length) {
                        try {
                            limit = Integer.parseInt(args[++i]);
                            if (limit <= 0) {
                                OutputFormatter.printWarning("Invalid limit value: " + limit + ". Using default: " + DEFAULT_LIMIT);
                                limit = DEFAULT_LIMIT;
                            }
                            logStep("Setting line limit", String.valueOf(limit));
                        } catch (NumberFormatException e) {
                            OutputFormatter.printWarning("Invalid limit format: " + args[i] + ". Using default: " + DEFAULT_LIMIT);
                            limit = DEFAULT_LIMIT;
                        }
                    } else if ((args[i].equals("-o") || args[i].equals("--offset")) && i + 1 < args.length) {
                        try {
                            offset = Integer.parseInt(args[++i]);
                            if (offset <= 0) {
                                OutputFormatter.printWarning("Invalid offset value: " + offset + ". Using default: " + DEFAULT_OFFSET);
                                offset = DEFAULT_OFFSET;
                            }
                            logStep("Setting line offset", String.valueOf(offset));
                        } catch (NumberFormatException e) {
                            OutputFormatter.printWarning("Invalid offset format: " + args[i] + ". Using default: " + DEFAULT_OFFSET);
                            offset = DEFAULT_OFFSET;
                        }
                    } else if ((args[i].equals("-l") || args[i].equals("--limit") ||
                                args[i].equals("-o") || args[i].equals("--offset")) && i + 1 >= args.length) {
                        // Known option flag provided without a value - warn the user instead of ignoring silently
                        OutputFormatter.printWarning("Option " + args[i] + " requires a value. Ignoring.");
                        logWarning("Argument parsing", "Missing value for option: " + args[i]);
                    } else if (args[i].startsWith("-") && !args[i].equals("-l") && !args[i].equals("--limit") &&
                              !args[i].equals("-o") && !args[i].equals("--offset")) {
                        // Unknown option - provide helpful message
                        OutputFormatter.printWarning("Unknown option: " + args[i] + ". Ignoring.");
                        logWarning("Argument parsing", "Unknown option: " + args[i]);
                    } else if (!args[i].startsWith("-")) {
                        // An extra positional is almost always a second FILE, and it used to be
                        // dropped without a word: "read a.java b.java" silently read only a.java, so
                        // the caller believed it had seen both. Say so, and name the command that
                        // actually reads several files.
                        OutputFormatter.printWarning("read takes ONE file; ignoring extra argument: " + args[i]);
                        OutputFormatter.printInfo("To read several files in one call, use: multiread "
                                + targetPath + " " + args[i] + " ...");
                        logWarning("Argument parsing", "Ignored extra positional: " + args[i]);
                    }
                }
            } catch (Exception e) {
                // Recover from argument parsing errors
                logWarning("Argument parsing", "Error parsing arguments: " + e.getMessage());
                OutputFormatter.printWarning("Error parsing arguments. Using defaults: limit="
                        + DEFAULT_LIMIT + ", offset=" + DEFAULT_OFFSET);
                limit = DEFAULT_LIMIT;
                offset = DEFAULT_OFFSET;
            }

            addContext("limit", limit);
            addContext("offset", offset);

            int result = readFile(targetPath);
            completeCommandLogging(result);
            return result;
        } catch (Exception e) {
            logErrorQuietly("execute", "Error reading file", e);
            OutputFormatter.printError("Error reading file: " + e.getMessage());
            
            // Provide more helpful error message based on exception type
            if (e instanceof NoSuchFileException) {
                OutputFormatter.printInfo("File not found: " + e.getMessage());
                OutputFormatter.printInfo("Try using a relative or absolute path, or check if the file exists.");
            } else if (e instanceof AccessDeniedException) {
                OutputFormatter.printInfo("Access denied: " + e.getMessage());
                OutputFormatter.printInfo("Check file permissions or try running with elevated privileges.");
            } else if (e instanceof IOException) {
                OutputFormatter.printInfo("I/O error: " + e.getMessage());
                OutputFormatter.printInfo("Check if the file is accessible and not corrupted.");
            } else if (e instanceof NumberFormatException) {
                OutputFormatter.printInfo("Invalid number format: " + e.getMessage());
                OutputFormatter.printInfo("Make sure limit and offset are valid integers.");
            }
            
            OutputFormatter.printInfo("Usage: " + CommandUsage.render(getUsage()));
            completeCommandLogging(1);
            return 1;
        }
    }

    private int readFile(String filePath) throws IOException {
        logStep("Starting file read", filePath);

        // Check if this is a placeholder path and try to substitute it
        String substitutedPath = substitutePlaceholderPath(filePath);
        if (!substitutedPath.equals(filePath)) {
            logStep("Path substitution", "Substituted '" + filePath + "' with '" + substitutedPath + "'");
            OutputFormatter.printInfo("Substituted path: '" + filePath + "' → '" + substitutedPath + "'");
            filePath = substitutedPath;
        }

        // Reject sensitive system files first so the user gets a specific,
        // actionable message rather than the generic security-policy denial
        // (the generic SecurityValidator check below would otherwise shadow it).
        if (isSensitiveSystemFile(filePath)) {
            logSecurityEvent("FILE_ACCESS_DENIED", "Sensitive system file blocked: " + filePath, false);
            OutputFormatter.printError("Cannot read sensitive system files");
            OutputFormatter.printInfo("File path: " + filePath);
            return 1;
        }

        // Security validation
        logStep("Performing security validation");
        SecurityValidator validator   = new SecurityValidator();
        DebugLogger       debugLogger = DebugLogger.getInstance();

        boolean accessAllowed = validator.isFileAccessAllowed(filePath);
        if (!accessAllowed) {
            logSecurityEvent("FILE_ACCESS_DENIED", "File access blocked by security policy: " + filePath, false);
            debugLogger.logSecurityEvent("FILE_READ", filePath, false);
            OutputFormatter.printError("Access denied: File access blocked by security policy");
            OutputFormatter.printInfo("File path: " + filePath);
            return 1;
        }

        logSecurityEvent("FILE_ACCESS_GRANTED", "File access allowed: " + filePath, true);
        debugLogger.logSecurityEvent("FILE_READ", filePath, true);

        // Prefer the exact requested path: only fall back to a project-wide
        // search when the path does not exist. This avoids reading an unintended
        // same-named file elsewhere in the project when the caller gave a path
        // that already resolves to a real file (read-7).
        Path currentDir   = Paths.get(System.getProperty("user.dir"));
        Path requestedAbs = Paths.get(filePath).isAbsolute()
                ? Paths.get(filePath).normalize()
                : currentDir.resolve(filePath).normalize();

        Path path;
        if (Files.exists(requestedAbs)) {
            path = requestedAbs;
        } else {
            // The requested path does not exist; search for alternatives.
            FilePathResolver.ResolvedPath resolved = FilePathResolver.resolve(filePath, currentDir, false);
            Path                          resultPath = handleResolution(resolved, validator);
            if (resultPath == null) {
                return 1;
            }
            path = resultPath;

            // Surface which file was actually read when it differs from the
            // requested path, so the caller knows a fallback search was used.
            if (!path.normalize().equals(requestedAbs)) {
                OutputFormatter.printInfo("Requested '" + filePath + "' was not found; reading: " + path);
                logStep("Path fallback", "Requested '" + filePath + "' -> reading '" + path + "'");
            }
        }

        // Validate the resolved path
        path = validatePath(path);
        if (path == null) {
            return 1;
        }

        if (!Files.isRegularFile(path)) {
            OutputFormatter.printError("Not a regular file: " + path);
            return 1;
        }

        // Check if file is too large before reading
        long    fileSize         = Files.size(path);
        boolean useLargeFileMode = fileSize > 500 * 1024; // 500KB threshold for streaming

        if (useLargeFileMode) {
            OutputFormatter.printWarning("File is large (" + fileSize / 1024 + "KB). Using streaming mode.");
            if (fileSize > 10 * 1024 * 1024) { // 10MB
                OutputFormatter.printWarning("File is very large. Consider using offset and limit parameters.");
            }
        }

        // Whether this file can be shown is the same question grep, index and edit ask, and it is
        // asked in the same place. read's own answer -- a NUL byte in the first 8KB or a strict
        // UTF-8 decode failure -- refused files those three handle without complaint.
        if (!TextFiles.isTextFile(path)) {
            logStep("Binary detection", "File is not text: " + path);
            OutputFormatter.printError("Cannot display file: binary content");
            OutputFormatter.printInfo("File path: " + path);
            return 1;
        }

        OutputFormatter.printHeader("File: " + path);

        // The file is about to be put in front of whoever asked, which is what earns the right to
        // edit it later. Recorded here rather than at each of the several endings below, because a
        // record that is too generous only ever allows an edit; one that misses an ending refuses
        // an edit of a file the model is looking at. See ReadBeforeEdit.
        ReadBeforeEdit.sawContents(path);

        int startLine = Math.max(1, offset);
        // Widen the end-bound computation so a large valid offset/limit clamps
        // instead of overflowing to a negative int (which would display nothing).
        long endLineL = (long) startLine + limit - 1;
        int  endLine  = (int) Math.min(endLineL, Integer.MAX_VALUE);

        // For large files, use streaming approach
        if (useLargeFileMode) {
            return readLargeFile(path, startLine, endLine);
        }

        // For small files, use the existing approach
        List<String> lines = TextFiles.readText(path).lines().toList();

        // If the requested start line is past the end of a non-empty file, warn
        // and return so the small-file path matches the large-file behavior
        // instead of silently producing an empty successful result. Empty files
        // keep their dedicated "File is empty" handling below.
        if (!lines.isEmpty() && startLine > lines.size()) {
            OutputFormatter.printError("Requested offset " + startLine +
                                       " is beyond end of file (" + lines.size() + " lines). Nothing to display.");
            return 1;
        }

        endLine = (int) Math.min(lines.size(), endLineL);

        if (lines.isEmpty()) {
            // Log empty file to debug log
            logDebug("File Read", String.format("FILE: %s", path.toString()));
            logDebug("File Read", String.format("SIZE: %d bytes", fileSize));
            logDebug("File Read", String.format("LINES: 0"));
            logDebug("File Read", "CONTENT: <empty file>");

            OutputFormatter.printWarning("File is empty");
            return 0;
        }

        // Capture file content for debug logging
        StringBuilder contentCapture = new StringBuilder();

        // Print lines with numbers (cat -n format)
        boolean marked = ProgramOutput.begin();
        try {
            for (int i = startLine - 1; i < endLine; i++) {
                String line = lines.get(i);
                // Truncate lines longer than 200 characters
                if (line.length() > 200) {
                    line = line.substring(0, 200) + "... (truncated)";
                }
                String numbered = numberedLine(i + 1, line);
                UnifiedOutput.print(numbered);

                // Capture for debug log
                contentCapture.append(numbered);
            }
        } finally {
            ProgramOutput.end(marked);
        }

        // Log file read operation to debug log
        String capturedContent = contentCapture.toString();
        logDebug("File Read", String.format("FILE: %s", path.toString()));
        logDebug("File Read", String.format("SIZE: %d bytes", fileSize));
        logDebug("File Read", String.format("TOTAL_LINES: %d", lines.size()));
        logDebug("File Read", String.format("DISPLAYED_LINES: %d-%d", startLine, endLine));
        logDebug("File Read", String.format("CONTENT_LENGTH: %d characters", capturedContent.length()));

        // Limit the number of lines logged
        String[] contentLines = capturedContent.split("\n");
        StringBuilder limitedContent = new StringBuilder();
        int linesToLog = Math.min(contentLines.length, MAX_LOG_LINES);

        for (int i = 0; i < linesToLog; i++) {
            limitedContent.append(contentLines[i]).append("\n");
        }

        if (contentLines.length > MAX_LOG_LINES) {
            limitedContent.append(String.format("... (%d more lines not shown) ...\n", contentLines.length - MAX_LOG_LINES));
        }

        logDebug("File Read", "=== FILE CONTENT START ===");
        logDebug("File Read", limitedContent.toString());
        logDebug("File Read", "=== FILE CONTENT END ===");

        if (endLine < lines.size()) {
            OutputFormatter.printInfo("Showing lines " + startLine + "-" + endLine + " of " + lines.size());
        }

        return 0;
    }

    /**
     * Resolves the outcome of a {@link FilePathResolver} fallback search into a
     * concrete path to read, or {@code null} when the read should be aborted
     * (an error message has already been printed in that case).
     *
     * <p>Behavior:
     * <ul>
     *   <li>Single auto-resolved match: re-runs the resolved path through the
     *       full security access check before accepting it (read-2), matching
     *       the alternatives branch.</li>
     *   <li>Multiple matches in non-interactive/agentic mode: does NOT prompt on
     *       stdin (which would stall the agentic loop); instead returns the
     *       alternatives as an error message so the model can retry with an
     *       exact path (read-3).</li>
     *   <li>Multiple matches in interactive mode: prompts the user to select.</li>
     *   <li>No match: prints the resolver error message.</li>
     * </ul>
     *
     * @param resolved  the resolver result for a non-existent requested path
     * @param validator the security validator used for access checks
     * @return the path to read, or {@code null} if the read must be aborted
     */
    private Path handleResolution(FilePathResolver.ResolvedPath resolved, SecurityValidator validator) {
        if (resolved.exists()) {
            // Single auto-resolved match: enforce the full security access check
            // before reading, the same as the alternatives branch (read-2).
            Path match = resolved.getPath();
            if (!validator.isFileAccessAllowed(match.toString())) {
                logSecurityEvent("FILE_ACCESS_DENIED",
                                 "Auto-resolved file blocked by security policy: " + match, false);
                OutputFormatter.printError("Access denied: Resolved file blocked by security policy");
                OutputFormatter.printInfo("File path: " + match);
                return null;
            }
            return match;
        }

        if (resolved.hasAlternatives()) {
            // In non-interactive/agentic mode never block on stdin: return the
            // alternatives as an error so the model retries with an exact path.
            boolean interactive = InteractivePrompts.isOn();
            if (!interactive) {
                OutputFormatter.printError(resolved.getErrorMessage());
                OutputFormatter.printInfo("Multiple files match. Re-run '" + CommandUsage.prefix() + "read' with an exact path from the list above.");
                logStep("Alternatives (non-interactive)", "Returned alternatives without prompting");
                return null;
            }

            OutputFormatter.printError(resolved.getErrorMessage());
            Path selected = resolved.selectFromAlternatives();
            if (selected == null) {
                return null;
            }
            // Validate the user-selected path.
            if (!validator.isFileAccessAllowed(selected.toString())) {
                OutputFormatter.printError("Access denied: Selected file blocked by security policy");
                return null;
            }
            return selected;
        }

        OutputFormatter.printError(resolved.getErrorMessage());
        return null;
    }

    private Path validatePath(Path filePath) {
        if (filePath == null) {
            return null;
        }

        try {
            // Defense-in-depth: re-check the resolved path for sensitive system files.
            if (isSensitiveSystemFile(filePath.toString())) {
                OutputFormatter.printError("Cannot read sensitive system files");
                return null;
            }

            return filePath;
        } catch (Exception e) {
            OutputFormatter.printError("Invalid file path: " + filePath);
            return null;
        }
    }

    /**
     * Determines whether a path points at a well-known sensitive system file
     * (credentials, keys, password databases) that must never be read.
     *
     * @param pathStr the path to inspect (raw or resolved)
     * @return true if the path is a sensitive system file
     */
    private static boolean isSensitiveSystemFile(String pathStr) {
        if (pathStr == null) {
            return false;
        }
        if (pathStr.contains("/etc/passwd") || pathStr.contains("/etc/shadow")) {
            return true;
        }
        // Everything else -- ~/.ssh and ~/.gnupg included -- comes from the shared denylist, so
        // `read` and `bash` refuse the same files. The .ssh/.gnupg rule used to live only here,
        // which meant the credential check bash consults did not know about it.
        return new SecurityValidator().isSensitiveCredentialFile(pathStr);
    }

    /**
     * Opens a file the way everything else in the tool opens one.
     *
     * <p>UTF-8, with a byte that is not valid UTF-8 shown as U+FFFD rather than ending the read.
     * A strict reader here turned one mis-encoded byte into a failure for the whole file, so a
     * Latin-1 source file that {@code grep} searches and the index stores could not be looked
     * at by the one command whose job is to show it. The replacement characters are in the output,
     * where the reader can see them.</p>
     *
     * @param path the file to read
     * @return a reader over its text
     * @throws IOException if the file cannot be opened
     */
    private static BufferedReader shownAsText(Path path) throws IOException {
        return new BufferedReader(new InputStreamReader(Files.newInputStream(path),
                                                        TextFiles.lossyUtf8Decoder()));
    }

    /**
     * One line of the file as it is shown: its number, a bar, and the line exactly as it is.
     *
     * <h2>Why a bar</h2>
     *
     * <p>Two spaces separated the number from the line, so a line indented by three spaces showed
     * five, and a model copying it into {@code OLD:} could not tell which were the line's own. A
     * tab separates it unambiguously, but the terminal's tab stops then put the text at column 9 or
     * column 17 depending on how many digits the number had. After the bar every character is the
     * line's, and the bar is always at column 7.</p>
     *
     * @param number the line's number, counting from one
     * @param line   the line, without its line ending
     * @return the line to print, ending in a line separator
     */
    static String numberedLine(int number, String line) {
        return String.format("%6d\u2502%s%n", number, line);
    }

    private int readLargeFile(Path path, int startLine, int endLine) throws IOException {
        int  lineNumber = 1;
        int  linesRead  = 0;
        long totalLines = 0;

        // First pass: count total lines if needed (only for files < 50MB)
        if (Files.size(path) < 50 * 1024 * 1024) {
            try (BufferedReader counter = shownAsText(path)) {
                while (counter.readLine() != null) {
                    totalLines++;
                    
                    // Check for interruption every 1000 lines
                    if (totalLines % 1000 == 0 && interruptionContext != null && interruptionContext.isInterrupted()) {
                        logStep("Line counting", "Interrupted by user");
                        OutputFormatter.printWarning("Operation interrupted by user");
                        return 1;
                    }
                }
            }
        }

        // Second pass: read and display requested lines
        StringBuilder contentCapture = new StringBuilder();
        try (BufferedReader reader = shownAsText(path)) {
            String line;

            // Skip lines before startLine
            while (lineNumber < startLine && (line = reader.readLine()) != null) {
                lineNumber++;
                
                // Check for interruption every 1000 lines
                if (lineNumber % 1000 == 0 && interruptionContext != null && interruptionContext.isInterrupted()) {
                    logStep("Line skipping", "Interrupted by user");
                    OutputFormatter.printWarning("Operation interrupted by user");
                    return 1;
                }
            }

            // Read and display lines
            boolean marked = ProgramOutput.begin();
            try {
                while (lineNumber <= endLine && (line = reader.readLine()) != null) {
                    // Check for interruption every 100 lines
                    if (linesRead > 0 && linesRead % 100 == 0 && interruptionContext != null && interruptionContext.isInterrupted()) {
                        ProgramOutput.end(marked);
                        marked = false;
                        logStep("Line reading", "Interrupted by user after reading " + linesRead + " lines");
                        OutputFormatter.printWarning("Operation interrupted by user after reading " + linesRead + " lines");
                        return 0;  // Return 0 since we've successfully read some lines
                    }

                    // Truncate lines longer than 200 characters
                    if (line.length() > 200) {
                        line = line.substring(0, 200) + "... (truncated)";
                    }
                    String formattedLine = numberedLine(lineNumber, line);
                    UnifiedOutput.print(formattedLine);

                    // Capture for debug log
                    contentCapture.append(formattedLine);

                    lineNumber++;
                    linesRead++;
                }
            } finally {
                ProgramOutput.end(marked);
            }

            // Continue counting if we haven't already
            if (totalLines == 0 && lineNumber <= endLine) {
                totalLines = lineNumber - 1;
                long countedLines = 0;
                while (reader.readLine() != null) {
                    totalLines++;
                    countedLines++;
                    
                    // Check for interruption every 1000 lines
                    if (countedLines % 1000 == 0 && interruptionContext != null && interruptionContext.isInterrupted()) {
                        logStep("Line counting", "Interrupted by user after counting " + countedLines + " additional lines");
                        // We can still display what we've read so far
                        break;
                    }
                }
            }

            // Nothing was displayed at all.
            //
            // Asked of what was read rather than of the skip counter. That counter stops for two
            // different reasons -- it reached the requested offset, or the file ran out -- and the
            // two coincide EXACTLY at the first line past the end. A 10,000-line file read from
            // offset 10,001 therefore left the counter equal to the offset, the past-the-end branch
            // never fired, and the command printed a header, no content, and reported success.
            // Offset 10,002 was answered correctly, so the hole was one line wide. The small-file
            // path has always asked it this way.
            if (linesRead == 0) {
                logDebug("File Read", String.format("FILE: %s", path.toString()));
                logDebug("File Read", String.format("SIZE: %d bytes (large file mode)", Files.size(path)));
                if (lineNumber == 1) {
                    logDebug("File Read", "LINES: 0");
                    logDebug("File Read", "CONTENT: <empty file>");
                    OutputFormatter.printWarning("File is empty");
                    return 0;
                }
                logDebug("File Read", String.format("REQUESTED_LINE: %d", startLine));
                logDebug("File Read", String.format("ACTUAL_LINES: %d", lineNumber - 1));
                logDebug("File Read", "CONTENT: <requested line beyond file end>");
                OutputFormatter.printError("Requested offset " + startLine
                                           + " is beyond end of file (" + (lineNumber - 1)
                                           + " lines). Nothing to display.");
                return 1;
            }

            // Display info about what was shown
            if (linesRead > 0) {
                if (totalLines > 0 && endLine < totalLines) {
                    OutputFormatter.printInfo("Showing lines " +
                                              startLine +
                                              "-" +
                                              (startLine + linesRead - 1) +
                                              " of " +
                                              totalLines);
                } else if (totalLines == 0) {
                    OutputFormatter.printInfo("Showed " + linesRead + " lines starting from line " + startLine);
                }
            }
        }

        // Log large file read operation to debug log
        String capturedContent = contentCapture.toString();
        long fileSize = Files.size(path);
        logDebug("File Read", String.format("FILE: %s", path.toString()));
        logDebug("File Read", String.format("SIZE: %d bytes (large file mode)", fileSize));
        logDebug("File Read", String.format("TOTAL_LINES: %d", totalLines > 0 ? totalLines : linesRead));
        logDebug("File Read", String.format("DISPLAYED_LINES: %d-%d", startLine, startLine + linesRead - 1));
        logDebug("File Read", String.format("CONTENT_LENGTH: %d characters", capturedContent.length()));

        // Limit the number of lines logged
        String[] contentLines = capturedContent.split("\n");
        StringBuilder limitedContent = new StringBuilder();
        int linesToLog = Math.min(contentLines.length, MAX_LOG_LINES);

        for (int i = 0; i < linesToLog; i++) {
            limitedContent.append(contentLines[i]).append("\n");
        }

        if (contentLines.length > MAX_LOG_LINES) {
            limitedContent.append(String.format("... (%d more lines not shown) ...\n", contentLines.length - MAX_LOG_LINES));
        }

        logDebug("File Read", "=== FILE CONTENT START ===");
        logDebug("File Read", limitedContent.toString());
        logDebug("File Read", "=== FILE CONTENT END ===");

        return 0;
    }


    @Override
    public String getUsage() {
        return "read <file_path> [-l|--limit <lines>] [-o|--offset <start_line>]";
    }

    @Override
    public Integer call() throws Exception {
        // Route the picocli path through execute() with reconstructed args so both entry points
        // share the same argument handling and command-logging lifecycle.
        List<String> argList = new java.util.ArrayList<>();
        if (filePath != null) {
            argList.add(filePath);
        }
        if (limit != null) {
            argList.add("--limit");
            argList.add(String.valueOf(limit));
        }
        if (offset != null) {
            argList.add("--offset");
            argList.add(String.valueOf(offset));
        }
        return execute(argList.toArray(new String[0]));
    }
    
    /**
     * Substitutes placeholder paths with actual paths
     * @param path The path that might be a placeholder
     * @return The substituted path or the original path if no substitution was found
     */
    private String substitutePlaceholderPath(String path) {
        if (path == null) {
            return "";
        }
        
        // Check if this looks like a placeholder path
        if (isPlaceholderPath(path)) {
            // Extract the filename from the placeholder
            String fileName = Paths.get(path).getFileName().toString();
            
            // Try to find the file in common locations
            String actualPath = searchForFile(fileName);
            if (actualPath != null) {
                return actualPath;
            }
            
            // Try heuristic path resolution for common files
            return getHeuristicPath(fileName, path);
        }
        
        return path;
    }
    
    /**
     * Checks if a path looks like a placeholder that needs substitution
     */
    private boolean isPlaceholderPath(String path) {
        return path.contains("/example/") ||
               path.startsWith("path/to/") ||
               path.startsWith("src/main/java/com/example/") ||
               path.startsWith("found_path/") ||
               (path.contains(".java") && !path.contains("/")) || // Just a filename
               path.contains("placeholder") ||
               path.contains("example.") ||
               path.contains("/path/to/");
    }
    
    /**
     * Searches for a file in the current project directory
     */
    private String searchForFile(String fileName) {
        try {
            Path startPath = Paths.get(".");
            java.util.concurrent.atomic.AtomicReference<String> found = 
                    new java.util.concurrent.atomic.AtomicReference<>();

            Files.walkFileTree(startPath, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs) {
                    if (com.eonmux.cadetcoder.util.ProjectTreeWalk.isPruned(startPath, dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
                    if (file.getFileName().toString().equals(fileName)) {
                        found.set(file.toString());
                        return FileVisitResult.TERMINATE;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });

            return found.get();
        } catch (Exception e) {
            logWarning("File search", "Error searching for file: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Uses heuristics to guess the most likely path for a file
     */
    private String getHeuristicPath(String fileName, String originalPath) {
        // For Java files, try common project structures
        if (fileName.endsWith(".java")) {
            // Try to find the file in common locations
            Path[] commonPaths = {
                    Paths.get("src/main/java").resolve(fileName),
                    Paths.get("src/test/java").resolve(fileName),
                    Paths.get(".").resolve(fileName)
            };

            for (Path path : commonPaths) {
                if (Files.exists(path)) {
                    return path.toString();
                }
            }
            
            // If the original path contains package hints, try to use them
            if (originalPath.contains("com/") || originalPath.contains("org/")) {
                String[] parts = originalPath.split("/");
                StringBuilder packagePath = new StringBuilder();
                boolean foundPackage = false;
                
                for (String part : parts) {
                    if (foundPackage || part.equals("com") || part.equals("org")) {
                        foundPackage = true;
                        packagePath.append(part).append("/");
                    }
                }
                
                if (foundPackage) {
                    Path srcPath = Paths.get("src/main/java").resolve(packagePath.toString()).resolve(fileName);
                    if (Files.exists(srcPath)) {
                        return srcPath.toString();
                    }
                }
            }
        }

        return originalPath;
    }
}
