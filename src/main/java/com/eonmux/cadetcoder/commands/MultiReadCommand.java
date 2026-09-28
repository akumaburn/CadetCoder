package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.ui.AnsiStripper;
import picocli.CommandLine.Command;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Reads several files in one call.
 *
 * <h2>Why</h2>
 *
 * <p>Gathering context is the bulk of what the agentic loop does, and it could only ever read one
 * file per turn. Understanding five files cost five model round trips, five tool dispatches and five
 * chances for the loop to be derailed - and each turn carried the whole transcript, so the cost grew
 * with every file. One call that returns all five is dramatically cheaper and gives the model the
 * whole picture before it has to decide anything.</p>
 *
 * <h2>How</h2>
 *
 * <p>Every file is read by delegating to {@link ReadCommand}, deliberately: path resolution,
 * project-boundary enforcement, the sensitive-credential-file refusal and binary detection all live
 * there, and a batch reader that reimplemented any of it would be a way to read what {@code read}
 * refuses. Each file therefore gets exactly the same treatment it would get on its own, and the
 * familiar {@code File: ...} header keeps every line attributable to its file.</p>
 *
 * <h2>Budgets</h2>
 *
 * <p>Reading many files can trivially exceed the model's context, so three limits apply, and every
 * one of them reports what it dropped rather than silently truncating:</p>
 *
 * <ul>
 *   <li>{@code --max-files} - how many files may be read at all (default {@value #DEFAULT_MAX_FILES}).</li>
 *   <li>{@code --limit} - lines per file, the same meaning and default as {@code read}.</li>
 *   <li>{@code --max-total-lines} - lines across the whole batch (default
 *       {@value #DEFAULT_MAX_TOTAL_LINES}), which is the one that actually protects the context
 *       window.</li>
 * </ul>
 *
 * <p>A file that cannot be read does not abort the batch: the failure is reported in place and the
 * remaining files are still read, because "three of these five exist" is a useful answer.</p>
 */
@Command (name = "multiread", description = "Read several files in one call")
public class MultiReadCommand extends LoggingCommandSupport
        implements CommandRegistry.Command, CommandRegistry.InterruptibleCommand {

    /** Default number of files a single call may read. */
    static final int DEFAULT_MAX_FILES = 50;

    /** Default lines per file; matches {@code read}'s own default. */
    static final int DEFAULT_LIMIT = 2000;

    /** Default number of lines the whole batch may emit. */
    static final int DEFAULT_MAX_TOTAL_LINES = 20000;

    /** Directory depth a glob argument may descend. */
    private static final int GLOB_MAX_DEPTH = 25;

    private CommandRegistry.InterruptionContext interruptionContext;

    @Override
    public void setInterruptionContext(CommandRegistry.InterruptionContext context) {
        this.interruptionContext = context;
    }

    @Override
    public boolean shouldInterrupt() {
        return CommandRegistry.InterruptibleCommand.stopWasAsked(interruptionContext);
    }

    @Override
    public int execute(String[] args) {
        try {
            startCommandLogging("multiread", args);

            // Accept --flag=value as well as --flag value, matching what the catalog promises.
            String[] normalized = CommandOptions.expandInlineValues(args, Set.of(
                    "-l", "--limit", "-o", "--offset",
                    "--max-files", "--max-total-lines"));

            Options options = parseOptions(normalized);
            if (options == null) {
                completeCommandLogging(1);
                return 1;
            }
            if (options.paths.isEmpty()) {
                OutputFormatter.printError("No file paths provided");
                OutputFormatter.printInfo("Usage:");
                OutputFormatter.printInfo("  " + CommandUsage.render(getUsage()));
                completeCommandLogging(1);
                return 1;
            }

            Expansion expansion = expandPaths(options.paths);
            if (expansion.resolved.isEmpty()) {
                OutputFormatter.printError("No files matched: " + String.join(" ", options.paths));
                completeCommandLogging(1);
                return 1;
            }

            int exitCode = readBatch(options, expansion);
            completeCommandLogging(exitCode);
            return exitCode;
        } catch (Exception e) {
            logErrorQuietly("execute", "multiread failed", e);
            OutputFormatter.printError("multiread failed: "
                    + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            completeCommandLogging(1);
            return 1;
        }
    }

    // ------------------------------------------------------------------ options

    /** Parsed invocation options. */
    private static final class Options {
        private final List<String> paths = new ArrayList<>();
        private int limit         = DEFAULT_LIMIT;
        private int offset        = 1;
        private int maxFiles      = DEFAULT_MAX_FILES;
        private int maxTotalLines = DEFAULT_MAX_TOTAL_LINES;
    }

    /**
     * Parses the argument vector.
     *
     * @param args the normalized argument vector
     * @return the options, or {@code null} when a value was missing or unparseable (already reported)
     */
    private Options parseOptions(String[] args) {
        Options options = new Options();
        for (int i = 0; i < args.length; i++) {
            String token = args[i];
            if (token == null) {
                continue;
            }
            switch (token) {
                case "-l":
                case "--limit": {
                    Integer value = readIntValue(args, ++i, token);
                    if (value == null) {
                        return null;
                    }
                    options.limit = value;
                    break;
                }
                case "-o":
                case "--offset": {
                    Integer value = readIntValue(args, ++i, token);
                    if (value == null) {
                        return null;
                    }
                    options.offset = value;
                    break;
                }
                case "--max-files": {
                    Integer value = readIntValue(args, ++i, token);
                    if (value == null) {
                        return null;
                    }
                    options.maxFiles = value;
                    break;
                }
                case "--max-total-lines": {
                    Integer value = readIntValue(args, ++i, token);
                    if (value == null) {
                        return null;
                    }
                    options.maxTotalLines = value;
                    break;
                }
                default:
                    if (token.startsWith("-") && token.length() > 1) {
                        OutputFormatter.printError("Unknown option: " + token);
                        OutputFormatter.printInfo("Usage:");
                        OutputFormatter.printInfo("  " + CommandUsage.render(getUsage()));
                        return null;
                    }
                    if (!token.trim().isEmpty()) {
                        options.paths.add(token.trim());
                    }
                    break;
            }
        }
        return options;
    }

    /** Reads a positive integer option value, reporting and returning {@code null} when invalid. */
    private Integer readIntValue(String[] args, int index, String flag) {
        if (index >= args.length) {
            OutputFormatter.printError("Option " + flag + " requires a value");
            return null;
        }
        try {
            int value = Integer.parseInt(args[index].trim());
            if (value <= 0) {
                OutputFormatter.printError(flag + " must be greater than 0, got: " + args[index]);
                return null;
            }
            return value;
        } catch (NumberFormatException e) {
            OutputFormatter.printError("Invalid value for " + flag + ": '" + args[index] + "'");
            return null;
        }
    }

    // ------------------------------------------------------------------ path expansion

    /** The outcome of turning the requested path arguments into a concrete file list. */
    private static final class Expansion {
        private final List<String> resolved   = new ArrayList<>();
        private final List<String> unmatched  = new ArrayList<>();
    }

    /**
     * Expands the requested arguments into an ordered, de-duplicated file list.
     *
     * <p>An argument containing {@code *} or {@code ?} is treated as a glob and expanded against the
     * working directory; anything else is passed through untouched so that {@link ReadCommand}'s own
     * resolution (including its "did you mean" search for a path that does not exist) still applies.
     * Glob matches are sorted so the same call produces the same order every time.</p>
     *
     * @param requested the raw path arguments
     * @return the expansion result, never {@code null}
     */
    private Expansion expandPaths(List<String> requested) {
        Expansion   expansion = new Expansion();
        Set<String> seen      = new LinkedHashSet<>();

        for (String argument : requested) {
            if (!isGlob(argument)) {
                seen.add(argument);
                continue;
            }
            List<String> matches = expandGlob(argument);
            if (matches.isEmpty()) {
                expansion.unmatched.add(argument);
            } else {
                seen.addAll(matches);
            }
        }
        expansion.resolved.addAll(seen);
        return expansion;
    }

    /** @return {@code true} when the argument should be treated as a glob rather than a literal path */
    private static boolean isGlob(String argument) {
        return argument.indexOf('*') >= 0 || argument.indexOf('?') >= 0;
    }

    /**
     * Expands one glob against the working directory.
     *
     * <p>The walk starts at the longest wildcard-free leading directory of the pattern, so
     * {@code src/main/**}{@code /*.java} does not walk the whole tree. Matching is done on the path
     * spelled the same way the pattern is (relative for a relative pattern), which is what a user
     * types and what {@code glob} already does.</p>
     */
    private List<String> expandGlob(String pattern) {
        List<String> matches = new ArrayList<>();
        try {
            Path        workingDir = Paths.get(System.getProperty("user.dir"));
            boolean     absolute   = Paths.get(pattern).isAbsolute();
            PathMatcher matcher    = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            Path        base       = workingDir.resolve(literalPrefix(pattern)).normalize();

            if (!Files.isDirectory(base)) {
                return matches;
            }
            try (Stream<Path> walk = Files.walk(base, GLOB_MAX_DEPTH)) {
                walk.filter(Files::isRegularFile).forEach(file -> {
                    Path candidate = absolute ? file.toAbsolutePath().normalize()
                                              : workingDir.relativize(file.toAbsolutePath().normalize());
                    if (matcher.matches(candidate)) {
                        matches.add(candidate.toString());
                    }
                });
            }
        } catch (Exception e) {
            // A bad pattern or an unreadable directory yields no matches; the caller reports that
            // the argument matched nothing rather than aborting the whole batch.
            logDebug("multiread", "Glob expansion failed for '" + pattern + "': " + e.getMessage());
        }
        matches.sort(String::compareTo);
        return matches;
    }

    /** @return the leading portion of {@code pattern} up to the first wildcard-bearing segment */
    private static String literalPrefix(String pattern) {
        String[]      segments = pattern.split("/");
        StringBuilder prefix   = new StringBuilder();
        for (String segment : segments) {
            if (segment.indexOf('*') >= 0 || segment.indexOf('?') >= 0) {
                break;
            }
            if (prefix.length() > 0) {
                prefix.append('/');
            }
            prefix.append(segment);
        }
        return prefix.length() == 0 ? "." : prefix.toString();
    }

    // ------------------------------------------------------------------ the batch

    /**
     * Reads every resolved file, honouring the budgets and reporting everything that was skipped.
     *
     * @return {@code 0} when at least one file was read, {@code 1} when none could be
     */
    private int readBatch(Options options, Expansion expansion) {
        List<String> files = expansion.resolved;

        List<String> overflow = List.of();
        if (files.size() > options.maxFiles) {
            overflow = new ArrayList<>(files.subList(options.maxFiles, files.size()));
            files    = files.subList(0, options.maxFiles);
        }

        OutputFormatter.printHeader("Reading " + files.size()
                + (files.size() == 1 ? " file" : " files"));

        int          succeeded      = 0;
        int          failed         = 0;
        int          linesRemaining = options.maxTotalLines;
        List<String> notRead        = new ArrayList<>();
        boolean      stoppedByUser  = false;

        for (int i = 0; i < files.size(); i++) {
            String file = files.get(i);

            // Which of the two stopped the batch is recorded HERE, where it is known. The summary
            // used to re-derive it at print time from a single template, so a batch the user stopped
            // reported "--max-total-lines exhausted" with most of that budget still unspent, and
            // advised raising a limit that was never the constraint.
            if (shouldInterrupt()) {
                notRead.addAll(files.subList(i, files.size()));
                stoppedByUser = true;
                OutputFormatter.printWarning("Interrupted; stopped before reading the remaining files.");
                break;
            }
            if (linesRemaining <= 0) {
                notRead.addAll(files.subList(i, files.size()));
                break;
            }

            OutputFormatter.printSubheader("[" + (i + 1) + "/" + files.size() + "] " + file);

            int perFileLimit = Math.min(options.limit, linesRemaining);
            Capture capture  = readOne(file, perFileLimit, options.offset);

            if (capture.exitCode == 0) {
                succeeded++;
            } else {
                failed++;
            }
            linesRemaining -= capture.lineCount;
        }

        printSummary(succeeded, failed, options, expansion, overflow, notRead, linesRemaining,
                     stoppedByUser);

        if (succeeded == 0) {
            OutputFormatter.printError("multiread: no files could be read");
            return 1;
        }
        return 0;
    }

    /** What one delegated read produced. */
    private static final class Capture {
        private final int exitCode;
        private final int lineCount;

        private Capture(int exitCode, int lineCount) {
            this.exitCode  = exitCode;
            this.lineCount = lineCount;
        }
    }

    /**
     * Reads one file through a fresh {@link ReadCommand}, echoing its output and counting the lines it
     * produced so the batch-wide line budget can be enforced exactly.
     *
     * <p>A fresh instance is used per file rather than the registry's shared singleton, so a batch can
     * never be affected by option state left behind by an earlier command.</p>
     */
    private Capture readOne(String file, int limit, int offset) {
        // Captured per THREAD rather than by swapping System.out: multiread is reachable from the
        // agent loop, which WorkerPool runs on several threads at once, and a global swap there
        // leaves the process printing into a discarded buffer (see CapturedRun).
        StringBuilder failure = new StringBuilder();
        com.eonmux.cadetcoder.ui.CapturedRun.Result run = com.eonmux.cadetcoder.ui.CapturedRun.of(() -> {
            try {
                return new ReadCommand().execute(new String[] {
                        file, "--limit", String.valueOf(limit), "--offset", String.valueOf(offset)});
            } catch (Exception e) {
                failure.append("Failed to read ").append(file).append(": ").append(e.getMessage());
                return 1;
            }
        });

        int    exitCode = run.exitCode();
        String stdout   = run.output();
        String stderr   = failure.toString();

        // Re-emitted after the capture ends, so it goes wherever this thread's output belongs. The
        // capture held no markers, so the file's lines are marked here.
        com.eonmux.cadetcoder.ui.ProgramOutput.print(stdout);
        if (!stderr.isEmpty()) {
            com.eonmux.cadetcoder.ui.UnifiedOutput.printlnErr(stderr);
        }
        return new Capture(exitCode, countLines(stdout));
    }

    /** Counts emitted lines, which is what the batch-wide budget is spent on. */
    private static int countLines(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int lines = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        return text.endsWith("\n") ? lines : lines + 1;
    }

    /**
     * Reports the outcome, naming every file that was NOT read and why.
     *
     * <p>Stated explicitly rather than left implicit: a batch reader that quietly returns fewer files
     * than asked for reads as "that is all there is", which is exactly the wrong conclusion for a
     * model to draw.</p>
     */
    private void printSummary(int succeeded, int failed, Options options, Expansion expansion,
                              List<String> overflow, List<String> notRead, int linesRemaining,
                              boolean stoppedByUser) {
        OutputFormatter.printHeader("multiread summary");
        OutputFormatter.printInfo("Read " + succeeded + (succeeded == 1 ? " file" : " files")
                + (failed > 0 ? ", " + failed + " could not be read" : "")
                + "; " + (options.maxTotalLines - Math.max(linesRemaining, 0)) + " lines emitted.");

        if (!expansion.unmatched.isEmpty()) {
            OutputFormatter.printWarning("No files matched: " + String.join(" ", expansion.unmatched));
        }
        if (!overflow.isEmpty()) {
            OutputFormatter.printWarning("Not read - --max-files=" + options.maxFiles + " reached ("
                    + overflow.size() + " more): " + String.join(" ", overflow));
            OutputFormatter.printInfo("Raise it with --max-files=<n>, or read the rest in another call.");
        }
        if (!notRead.isEmpty() && stoppedByUser) {
            OutputFormatter.printWarning("Not read - stopped (" + notRead.size() + " more): "
                    + String.join(" ", notRead));
            OutputFormatter.printInfo("Read the rest in another call.");
        } else if (!notRead.isEmpty()) {
            OutputFormatter.printWarning("Not read - --max-total-lines=" + options.maxTotalLines
                    + " exhausted (" + notRead.size() + " more): " + String.join(" ", notRead));
            OutputFormatter.printInfo(
                    "Raise it with --max-total-lines=<n>, lower --limit=<n> per file, "
                    + "or read the rest in another call.");
        }
    }


    @Override
    public String getUsage() {
        return "multiread <path...> [options]\n"
             + "  Paths may be literal files or globs (*.java, src/**/*.md).\n"
             + "  -l, --limit <n>            Max lines per file (default " + DEFAULT_LIMIT + ")\n"
             + "  -o, --offset <n>           First line to read in each file (default 1)\n"
             + "      --max-files <n>        Max files to read (default " + DEFAULT_MAX_FILES + ")\n"
             + "      --max-total-lines <n>  Max lines for the whole batch (default "
             + DEFAULT_MAX_TOTAL_LINES + ")\n"
             + "  Options accept --flag=value or --flag value.\n"
             + "  A file that cannot be read is reported in place; the rest are still read.\n"
             + "  Exit 0 if at least one file was read, 1 if none were.";
    }
}
