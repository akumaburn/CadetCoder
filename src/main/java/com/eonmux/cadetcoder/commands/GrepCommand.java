package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ExitCode;
import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.error.ErrorHandler;
import com.eonmux.cadetcoder.util.PlaceholderPath;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

@CommandLine.Command (
        name = "grep",
        description = "Search file contents with a regular expression"
)
public class GrepCommand extends LoggingCommandSupport implements CommandRegistry.Command, CommandRegistry.InterruptibleCommand, java.util.concurrent.Callable<Integer> {

    private CommandRegistry.InterruptionContext interruptionContext;

    @Override
    public void setInterruptionContext(CommandRegistry.InterruptionContext context) {
        this.interruptionContext = context;
    }

    @CommandLine.Parameters (index = "0..*", description = "Regular expression pattern to search for (multiple words will be joined)")
    private String[] patternParts;

    @CommandLine.Option (names = {"-p", "--path"}, description = "Path to search in (default: current directory)")
    private String searchPath = ".";

    // --include has no short flag: conventional grep -i means ignore-case, which is now
    // (re)assigned to -i/--ignore-case below. The include glob is matched against the path
    // relative to the search root, so patterns like **/*.java work as well as bare *.java.
    @CommandLine.Option (names = {"--include"}, description = "File path glob to include, matched relative to the search path (e.g., *.java, **/*.txt)")
    private String includePattern;

    @CommandLine.Option (names = {"-e", "--exclude"}, description = "File path glob to exclude, matched relative to the search path (e.g., *.log, **/*.tmp)")
    private String excludePattern;

    @CommandLine.Option (names = {"-c", "--count"}, description = "Show only count of matches per file")
    private boolean countOnly;

    @CommandLine.Option (names = {"-l", "--files-with-matches"},
                         description = "Show only names of files containing matches")
    private boolean filesOnly;

    // Line numbers are shown by default. The option is negatable so users can turn them off
    // with --no-line-number; -n/--line-number keeps the default-on behavior (honest flag,
    // no change to existing output).
    @CommandLine.Option (names = {"-n", "--line-number"}, negatable = true,
                         description = "Show line numbers with output lines (on by default; use --no-line-number to disable)")
    private boolean showLineNumbers = true;

    @CommandLine.Option (names = {"-v", "--invert-match"}, description = "Select non-matching lines")
    private boolean invertMatch;

    @CommandLine.Option (names = {"-w", "--word"}, description = "Match whole words only")
    private boolean wholeWord;

    @CommandLine.Option (names = {"-x", "--line"}, description = "Match whole lines only")
    private boolean wholeLine;

    @CommandLine.Option (names = {"--case-sensitive"}, description = "Case sensitive matching (default)")
    private boolean caseSensitive = true;

    /** Whether {@code --case-sensitive} was actually typed, which the field above cannot say. */
    private boolean caseSensitiveAsked;

    // -i now means ignore-case, matching conventional grep. -I is retained as an alias for
    // backward compatibility with existing callers/scripts.
    @CommandLine.Option (names = {"-i", "-I", "--ignore-case"}, description = "Case insensitive matching")
    private boolean ignoreCase;

    @CommandLine.Option (names = {"-A", "--after-context"},
                         description = "Show this many lines after each match")
    private int afterContext;

    @CommandLine.Option (names = {"-B", "--before-context"},
                         description = "Show this many lines before each match")
    private int beforeContext;

    @CommandLine.Option (names = {"-C", "--context"},
                         description = "Show this many lines either side of each match")
    private int aroundContext;

    @CommandLine.Option (names = {"--column"},
                         description = "Show the column where the first match on each line starts")
    private boolean showColumns;

    @CommandLine.Option (names = {"--max-depth"}, description = "Maximum directory depth to search")
    private int maxDepth = Integer.MAX_VALUE;

    @CommandLine.Option (names = {"-h", "--help"}, usageHelp = true, description = "Show this help message")
    private boolean helpRequested;

    private Configuration config;
    private PathMatcher   includeMatcher;
    private PathMatcher   excludeMatcher;

    @Override
    public Integer call() throws Exception {
        // Reconstruct the argument vector from the already-parsed picocli fields and delegate
        // to execute(), so the Callable (picocli subcommand dispatch) path shares the exact
        // same command-logging lifecycle, validateParameters() handling, and per-exception
        // user messaging as the CommandRegistry path. Options are emitted first, followed by a
        // "--" separator so a pattern beginning with '-' is treated as a positional.
        List<String> reconstructed = new ArrayList<>();

        if (searchPath != null && !searchPath.equals(".")) {
            reconstructed.add("--path");
            reconstructed.add(searchPath);
        }
        if (includePattern != null) {
            reconstructed.add("--include");
            reconstructed.add(includePattern);
        }
        if (excludePattern != null) {
            reconstructed.add("--exclude");
            reconstructed.add(excludePattern);
        }
        if (countOnly) {
            reconstructed.add("--count");
        }
        if (filesOnly) {
            reconstructed.add("--files-with-matches");
        }
        if (!showLineNumbers) {
            reconstructed.add("--no-line-number");
        }
        if (invertMatch) {
            reconstructed.add("--invert-match");
        }
        if (wholeWord) {
            reconstructed.add("--word");
        }
        if (wholeLine) {
            reconstructed.add("--line");
        }
        if (ignoreCase) {
            reconstructed.add("--ignore-case");
        }
        if (afterContext > 0) {
            reconstructed.add("--after-context");
            reconstructed.add(Integer.toString(afterContext));
        }
        if (beforeContext > 0) {
            reconstructed.add("--before-context");
            reconstructed.add(Integer.toString(beforeContext));
        }
        if (aroundContext > 0) {
            reconstructed.add("--context");
            reconstructed.add(Integer.toString(aroundContext));
        }
        if (showColumns) {
            reconstructed.add("--column");
        }
        if (maxDepth != Integer.MAX_VALUE) {
            reconstructed.add("--max-depth");
            reconstructed.add(Integer.toString(maxDepth));
        }

        reconstructed.add("--");
        if (patternParts != null) {
            Collections.addAll(reconstructed, patternParts);
        }

        return execute(reconstructed.toArray(new String[0]));
    }

    /**
     * Runs one search, on an instance that has never run one before.
     *
     * <p>The registry builds a single {@code GrepCommand} and hands it every {@code grep} for the
     * life of the session, so every field of this class -- the parsed options, the compiled
     * matchers, the lists of files the walk could not read or the denylist refused -- is otherwise
     * shared between runs. That used to be answered by putting each field back by hand before
     * parsing, which is a second statement of the defaults the declarations already make, and the
     * second statement drifted: the credential files a search refused were never cleared, so the
     * next search announced files it had never looked at, in directories it had never entered, and
     * the count grew with every run.</p>
     *
     * @param args the argument vector
     * @return the exit code
     */
    @Override
    public int execute(String[] args) {
        GrepCommand invocation = new GrepCommand();
        invocation.interruptionContext = interruptionContext;
        return invocation.executeOnce(args);
    }

    /**
     * How many lines either side of each match this run asked for.
     *
     * <p>{@code -C} sets both sides, and a side named on its own wins over it, which is how every
     * other grep reads {@code -C 3 -A 1}.</p>
     */
    private GrepContext wantedContext() {
        return new GrepContext(beforeContext > 0 ? beforeContext : aroundContext,
                               afterContext > 0 ? afterContext : aroundContext);
    }

    private int executeOnce(String[] args) {
        try {
            startCommandLogging("grep", args);
            logStep("Initializing grep command");

            // Parse command line arguments
            CommandLine cmd = new CommandLine(this);
            try {
                // Asked of the parse rather than of the field. `caseSensitive` DEFAULTS to true and
                // the flag can only ever set it to true, so the field cannot tell a run that asked
                // for case sensitivity from one that said nothing -- which is why the conflict
                // below has to be read off what was actually typed.
                caseSensitiveAsked = cmd.parseArgs(args).hasMatchedOption("--case-sensitive");
                if (helpRequested) {
                    logStep("Help requested, displaying usage");
                    // The same text `help grep` shows. Two spellings of one command's options,
                    // reachable by two routes, is one to keep in step for no gain.
                    OutputFormatter.println(CommandUsage.render(getUsage()));
                    completeCommandLogging(0);
                    return 0;
                }
            } catch (CommandLine.ParameterException e) {
                // The user-facing line is picocli's own sentence, followed by the usage text.
                // Reporting through logError as well printed a second, developer-shaped line above
                // it -- naming the internal operation and the Java exception class -- and, before
                // that, a stack trace.
                logErrorQuietly("execute", "Command line parsing failed", e);
                OutputFormatter.printError(e.getMessage());
                OutputFormatter.printInfo("Usage: " + CommandUsage.render(getUsage()));
                
                completeCommandLogging(1);
                return 1;
            }

            config = ConfigManager.getInstance().getConfig();

            if (ignoreCase) {
                caseSensitive = false;
            }

            // Validate and build pattern
            if (patternParts == null || patternParts.length == 0) {
                logErrorQuietly("execute", "Missing required parameter: pattern");
                OutputFormatter.printError("Missing required parameter: pattern");
                OutputFormatter.printInfo("Usage: " + CommandUsage.render(getUsage()));
                completeCommandLogging(1);
                return 1;
            }
            
            // Apply fuzzy parsing to pattern parts
            patternParts = GrepPattern.cleaned(patternParts, this);
            String pattern = GrepPattern.withBarsAsAlternation(String.join(" ", patternParts));
            logStep("Pattern parsing", "Final pattern: '" + pattern + "'");
            
            // Check if search path is a placeholder and try to substitute it
            String originalPath = searchPath;
            searchPath = PlaceholderPath.resolved(searchPath, message -> logWarning("Directory search", message));
            if (!searchPath.equals(originalPath)) {
                logStep("Path substitution", "Substituted '" + originalPath + "' with '" + searchPath + "'");
                OutputFormatter.printInfo("Substituted path: '" + originalPath + "' → '" + searchPath + "'");
            }
            
            logStep("Grep configuration", String.format("Pattern: '%s', Path: %s, Case sensitive: %b, Include: %s, Exclude: %s", 
                pattern, searchPath, caseSensitive, includePattern, excludePattern));
            logStep("Grep options", String.format("Count only: %b, Files only: %b, Line numbers: %b, Invert: %b, Whole word: %b", 
                countOnly, filesOnly, showLineNumbers, invertMatch, wholeWord));
            logStep("Grep context", String.format("Before: %d, After: %d, Columns: %b",
                wantedContext().before(), wantedContext().after(), showColumns));

            // Validate parameters before proceeding
            if (!validateParameters()) {
                completeCommandLogging(1);
                return 1;
            }
            
            // No failing branch here any more: a pattern that does not compile as an expression is
            // searched for as plain text instead. See compilePattern.
            long    compileStartTime = System.currentTimeMillis();
            Pattern regexPattern     = compilePattern(pattern);

            long compileDuration = System.currentTimeMillis() - compileStartTime;
            logPerformance("Pattern compilation", compileDuration);
            
            try {
                setupMatchers();
            } catch (Exception e) {
                logErrorQuietly("execute", "Error setting up file pattern matchers", e);
                OutputFormatter.printError("Invalid file pattern: " + e.getMessage());
                if (includePattern != null) {
                    OutputFormatter.printInfo("Include pattern: " + includePattern + " may be invalid");
                }
                if (excludePattern != null) {
                    OutputFormatter.printInfo("Exclude pattern: " + excludePattern + " may be invalid");
                }
                OutputFormatter.printInfo("File patterns should be valid glob patterns like *.java or **/*.txt");
                completeCommandLogging(1);
                return 1;
            }

            logStep("Beginning file search", String.format("Max depth: %d", maxDepth));
            long searchStartTime = System.currentTimeMillis();

            // Normalized so relative-glob matching and the start-directory equality check behave
            // consistently even when searchPath is "." or contains "." / "..".
            GrepScope scope = new GrepScope(Paths.get(searchPath).toAbsolutePath().normalize(),
                                            maxDepth, includePattern, includeMatcher,
                                            excludeMatcher);
            GrepMatching matching = new GrepMatching(invertMatch, countOnly, filesOnly);
            GrepContext  context  = wantedContext();
            GrepSearch   search   = new GrepSearch(scope, matching, context, this::shouldInterrupt,
                                                   this);
            GrepReport   report   = new GrepReport(matching, showLineNumbers, showColumns,
                                                   config.getUi().isColorEnabled(), this);

            Map<Path, List<GrepMatch>> results = search.matches(regexPattern);
            long searchDuration = System.currentTimeMillis() - searchStartTime;

            logPerformance("File search", searchDuration);
            logDataProcessing("search", "files with matches", results.size(), searchDuration);

            // The lines each file contributed include the ones kept for what they surround, so the
            // figure comes from the one rule that separates the two. Sized instead, the warning
            // below announced a -C 2 run's forty-five lines as forty-five matches, directly under
            // the per-file line that had counted the same nine correctly.
            int totalMatches = GrepMatch.matchedIn(results.values());
            logDataProcessing("search", "total matches", totalMatches, 0);

            // What a search that did not finish found is not what is in the project. Asked before
            // the results are characterised, because both descriptions below are wrong for it: an
            // empty map means "stopped before the first match", not "there are none", and a
            // non-empty one is a floor rather than a count. Reported either way, and exited 0, the
            // user was told a search had been done that had not.
            boolean cutShort = search.wasCutShort();

            if (results.isEmpty() && !cutShort) {
                // Zero matches is a successful search, not an error: keep exit code 0 (siblings do
                // not treat empty results as failure). One line -- the warning already says "ran,
                // found nothing", which is what a second info line below it existed to say.
                logWarning("No matches found", String.format("No matches found for pattern: %s", pattern));
                OutputFormatter.printWarning("No matches found for pattern: " + pattern);
            } else if (!results.isEmpty()) {
                logStep("Displaying results",
                        String.format("Files: %d, Matches: %d", results.size(), totalMatches));
                report.show(results, pattern, searchPath);
            }

            // What could not be read is reported exactly once, after the results, so a problem file
            // never displaces the matches themselves.
            report.showDegradations(search);
            report.showRefusals(search);

            if (cutShort) {
                OutputFormatter.printWarning(
                        "Search stopped before it had looked everywhere; " + totalMatches
                        + (totalMatches == 1 ? " match" : " matches")
                        + " found so far. There may be more.");
                completeCommandLogging(ExitCode.INTERRUPTED);
                return ExitCode.INTERRUPTED;
            }
            completeCommandLogging(0);
            return 0;
        } catch (NoSuchFileException e) {
            logErrorQuietly("execute", "File or directory not found", e);
            OutputFormatter.printError("File or directory not found: " + e.getMessage());
            OutputFormatter.printInfo("Check if the path exists or try using a different path");
            
            // Suggest alternative paths if this was a placeholder path
            if (PlaceholderPath.looksLikeStandIn(searchPath)) {
                OutputFormatter.printInfo("You used what appears to be a placeholder path. Try using an actual path like 'src' or '.'");
            }
            
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
        } catch (Exception e) {
            logErrorQuietly("execute", "Grep command execution failed", e);
            OutputFormatter.printError("Error executing grep command: " + e.getMessage());
            OutputFormatter.printInfo("Usage: " + CommandUsage.render(getUsage()));
            completeCommandLogging(1);
            return 1;
        }
    }

    /**
     * The caller's expression with {@code -w} and {@code -x} applied around it.
     *
     * <h2>Why the expression is bracketed before anything is put either side of it</h2>
     *
     * <p>Alternation binds more loosely than anything else in a regular expression, so wrapping by
     * plain concatenation does not wrap: {@code \b} + {@code cat|dog} + {@code \b} reads as "a
     * word-initial cat" or "a word-final dog", and a whole-word search for {@code cat|dog} matched
     * {@code catalog}. {@code -x} broke the same way, {@code ^cat|dog$} being "starts with cat" or
     * "ends with dog".</p>
     *
     * <p>The bracket is non-capturing, so it adds no group: a plain {@code (...)} would become
     * group one and renumber every back-reference the caller wrote behind it, which is a quieter
     * way of breaking the same search.</p>
     */
    private Pattern compilePattern(String pattern) {
        try {
            return compiledFrom(pattern);
        } catch (PatternSyntaxException notAnExpression) {
            // Searched for as plain text instead of refused. A pattern that does not compile is by
            // definition not a working regular expression, so no search that used to work can
            // change meaning -- and what the caller wrote is almost always exactly what they meant
            // to find. `grep placeBuy(` cost a whole turn to be told its bracket was unclosed, and
            // the turn after that the search was abandoned rather than corrected.
            OutputFormatter.printWarning(
                    "'" + pattern + "' is not a regular expression ("
                    + notAnExpression.getDescription()
                    + "), so it was searched for as plain text. Write \\( for a literal bracket, "
                    + "\\. for a literal dot, and so on, to search for an expression instead.");
            return compiledFrom(Pattern.quote(pattern));
        }
    }

    /**
     * One pattern compiled with this run's options applied to it.
     *
     * @param regex the expression, already quoted if it is to be taken literally
     * @return the compiled pattern
     */
    private Pattern compiledFrom(String regex) {
        String withOptions = regex;

        if (wholeWord) {
            withOptions = "\\b" + grouped(withOptions) + "\\b";
        }

        if (wholeLine) {
            withOptions = "^" + grouped(withOptions) + "$";
        }

        int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE;
        if (wholeLine) {
            flags |= Pattern.MULTILINE;
        }

        return Pattern.compile(withOptions, flags);
    }

    /** One expression as a single term, without becoming a group anything can refer back to. */
    private static String grouped(String regex) {
        return "(?:" + regex + ")";
    }

    /** Compiles the {@code --include} and {@code --exclude} globs, if this run gave any. */
    private void setupMatchers() {
        FileSystem fs = FileSystems.getDefault();
        includeMatcher = (includePattern != null) ? fs.getPathMatcher("glob:" + includePattern) : null;
        excludeMatcher = (excludePattern != null) ? fs.getPathMatcher("glob:" + excludePattern) : null;
    }
    
    /**
     * Checks if the command should be interrupted
     * @return true if the command should be interrupted
     */
    @Override
    public boolean shouldInterrupt() {
        return CommandRegistry.InterruptibleCommand.stopWasAsked(interruptionContext);
    }


    @Override
    public String getUsage() {
        // The option list is spelled out rather than hidden behind "[options]": `/help grep` renders
        // getUsage(), and picocli's real table was only reachable via `grep -h`.
        return "grep <pattern> [options]   (recursive by default)\n"
             + "  -p, --path <dir>        Directory to search (default: current directory)\n"
             + "      --include <glob>    Only these files, relative to --path (e.g. **/*.java)\n"
             + "  -e, --exclude <glob>    Skip these files (e.g. **/*.log)\n"
             + "      --max-depth <n>     Maximum directory depth\n"
             + "  -i, --ignore-case       Case-insensitive, or -I; --case-sensitive is the default\n"
             + "  -w, --word              Match whole words only\n"
             + "  -x, --line              Match whole lines only\n"
             + "  -v, --invert-match      Select NON-matching lines\n"
             + "  -n, --line-number       Show line numbers -- the default, so this changes\n"
             + "                          nothing; --no-line-number is what omits them\n"
             + "  -c, --count             Only the match count per file\n"
             + "  -l, --files-with-matches  Only the names of matching files\n"
             + "  -A, --after-context <n>   Also show n lines after each match\n"
             + "  -B, --before-context <n>  Also show n lines before each match\n"
             + "  -C, --context <n>         Also show n lines either side of each match\n"
             + "      --column            Show where in the line the first match starts\n"
             + "  A surrounding line is numbered with a dash; a matching line keeps the colon,\n"
             + "  and -- marks a gap between two groups.\n"
             + "  -h, --help              Show this option list\n"
             + "  Options accept --flag=value or --flag value. Quote a pattern containing spaces.";
    }
    
    /**
     * Validates command parameters and provides helpful error messages
     * @return true if parameters are valid, false otherwise
     */
    private boolean validateParameters() {
        boolean valid = true;
        
        // Validate search path
        if (searchPath == null || searchPath.trim().isEmpty()) {
            logErrorQuietly("validateParameters", "Search path is empty");
            OutputFormatter.printError("Search path cannot be empty");
            OutputFormatter.printInfo("Using current directory (.) as fallback");
            searchPath = ".";
        }
        
        // Check if search path exists
        Path path = Paths.get(searchPath);
        if (!Files.exists(path)) {
            logWarning("validateParameters", "Search path does not exist: " + searchPath);
            OutputFormatter.printWarning("Search path does not exist: " + searchPath);
            
            // Try to suggest alternatives
            if (PlaceholderPath.looksLikeStandIn(searchPath)) {
                OutputFormatter.printInfo("The path appears to be a placeholder. Try using an actual path like 'src' or '.'");
            } else {
                OutputFormatter.printInfo("Check if the path exists or try using a different path");
            }
            
            // Don't fail immediately, let the command try to resolve the path
        }
        
        // Validate max depth
        if (maxDepth < 0) {
            logErrorQuietly("validateParameters", "Invalid max depth: " + maxDepth);
            OutputFormatter.printError("Max depth cannot be negative");
            OutputFormatter.printInfo("Using default max depth (unlimited)");
            // Self-corrected to an unlimited depth, so the search can still proceed.
            // Keep 'valid' true so both entry points (execute and call) behave consistently.
            maxDepth = Integer.MAX_VALUE;
        }
        
        // Validate include/exclude patterns
        if (includePattern != null && includePattern.trim().isEmpty()) {
            logWarning("validateParameters", "Include pattern is empty");
            OutputFormatter.printWarning("Include pattern is empty, ignoring");
            includePattern = null;
        }
        
        if (excludePattern != null && excludePattern.trim().isEmpty()) {
            logWarning("validateParameters", "Exclude pattern is empty");
            OutputFormatter.printWarning("Exclude pattern is empty, ignoring");
            excludePattern = null;
        }
        
        // Check for conflicting options
        if (countOnly && filesOnly) {
            logWarning("validateParameters", "Both count-only and files-only options specified");
            OutputFormatter.printWarning("Both --count and --files-with-matches options specified");
            OutputFormatter.printInfo("Using --files-with-matches option (showing file names only)");
            countOnly = false;
        }
        
        // Asked of what the user TYPED, not of what has since been normalised. The run resolves
        // --ignore-case by clearing caseSensitive before this method is called, so by the time the
        // conflict was tested for, one half of it had already been erased: the condition could never
        // hold, the whole block was dead, and `grep foo --case-sensitive --ignore-case` searched
        // case-insensitively without a word about the flag it had discarded.
        if (caseSensitiveAsked && ignoreCase) {
            logWarning("validateParameters", "Both case-sensitive and ignore-case options specified");
            OutputFormatter.printWarning("Both --case-sensitive and --ignore-case options specified");
            OutputFormatter.printInfo("Using --ignore-case option (case insensitive search)");
            caseSensitive = false;
        }
        
        return valid;
    }
}
