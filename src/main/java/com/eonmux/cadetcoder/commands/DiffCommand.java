package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.security.SecurityValidator;
import com.eonmux.cadetcoder.ui.ProgramOutput;
import com.eonmux.cadetcoder.util.TextFiles;
import com.eonmux.cadetcoder.util.UnifiedDiff;

import picocli.CommandLine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * What two files differ by.
 *
 * <h2>Why this is a command rather than something the model works out</h2>
 *
 * <p>An agent asked whether an edit did what was intended had to read both files in full and
 * compare them itself. That puts the whole of both files in the prompt and gets the answer wrong on
 * anything long. Reviewing a change, checking an edit and summarising work done all start with this
 * question.</p>
 *
 * <h2>Why the output goes straight into patch</h2>
 *
 * <p>It is a unified diff, so {@code patch} reads it back, {@code git apply} reads it, and a person
 * reads it. The two commands are one interface rather than two.</p>
 */
@CommandLine.Command (name = "diff", description = "Show what two files differ by")
public class DiffCommand extends LoggingCommandSupport implements CommandRegistry.Command {

    /** Options that take a value, so {@code --flag=value} and {@code --flag value} both work. */
    private static final Set<String> VALUED = Set.of("-U", "--context");

    private final SecurityValidator security = new SecurityValidator();

    @Override
    public int execute(String[] args) {
        try {
            startCommandLogging("diff", args);
            Options options = Options.read(CommandOptions.expandInlineValues(args, VALUED));
            if (options == null) {
                return refuse("Two files are needed.");
            }

            String before = textOf(options.first);
            String after  = textOf(options.second);
            if (before == null || after == null) {
                return finish(1);
            }

            report(options, before, after);
            return finish(0);
        } catch (RuntimeException unexpected) {
            logErrorQuietly("execute", "diff failed", unexpected);
            OutputFormatter.printError("Could not compare the files: " + unexpected.getMessage());
            return finish(1);
        }
    }

    /** Prints the difference, in whichever of the two shapes was asked for. */
    private void report(Options options, String before, String after) {
        if (options.summaryOnly) {
            UnifiedDiff.Tally tally = UnifiedDiff.tally(before, after);
            OutputFormatter.printSuccess(options.first + " -> " + options.second + ": "
                                         + tally.added() + " added, " + tally.removed()
                                         + " removed");
            return;
        }
        String diff = UnifiedDiff.between(options.first, before, options.second, after,
                                          options.context);
        if (diff.isEmpty()) {
            OutputFormatter.printSuccess("No difference between " + options.first + " and "
                                         + options.second);
            return;
        }
        // Printed as it is rather than through printInfo, so a diff is not suppressed at the quieter
        // verbosities. It is the whole answer, not a remark about the answer.
        ProgramOutput.println(diff);
    }

    /**
     * One file's text, with every reason it cannot be read reported.
     *
     * @return the text, or {@code null} having said why not
     */
    private String textOf(String given) {
        Path file   = ProjectFile.at(given);
        String why  = ProjectFile.reasonNotToTouch(file, security);
        if (why != null) {
            OutputFormatter.printError(why);
            return null;
        }
        if (!Files.isRegularFile(file)) {
            OutputFormatter.printError("Not a file: " + given);
            return null;
        }
        try {
            if (!TextFiles.isTextFile(file)) {
                OutputFormatter.printError("Not a text file: " + given);
                return null;
            }
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            OutputFormatter.printError("Could not read " + given + ": " + unreadable.getMessage());
            return null;
        }
    }

    private int refuse(String message) {
        OutputFormatter.printError(message);
        OutputFormatter.printInfo("Usage: " + CommandUsage.render(getUsage()));
        return finish(1);
    }

    private int finish(int exitCode) {
        completeCommandLogging(exitCode);
        return exitCode;
    }

    /** What one invocation asked for. */
    private static final class Options {

        private String  first;
        private String  second;
        private int     context = UnifiedDiff.DEFAULT_CONTEXT;
        private boolean summaryOnly;

        /**
         * Reads an argument vector.
         *
         * @return the options, or {@code null} when two files were not named
         */
        static Options read(String[] args) {
            Options options = new Options();
            if (args == null) {
                return null;
            }
            for (int i = 0; i < args.length; i++) {
                String token = args[i];
                if ("--stat".equals(token)) {
                    options.summaryOnly = true;
                } else if (("-U".equals(token) || "--context".equals(token)) && i + 1 < args.length) {
                    options.context = number(args[++i], options.context);
                } else if (token != null && !token.startsWith("-")) {
                    options.name(token);
                }
            }
            return options.second == null ? null : options;
        }

        private void name(String path) {
            if (first == null) {
                first = path;
            } else if (second == null) {
                second = path;
            }
        }

        /** A number the caller wrote, or what it was before when the text is not one. */
        private static int number(String written, int wasBefore) {
            try {
                return Math.max(0, Integer.parseInt(written.trim()));
            } catch (NumberFormatException notANumber) {
                return wasBefore;
            }
        }
    }

    @Override
    public String getUsage() {
        return "diff <file> <file> [options]\n"
             + "  -U, --context <n>  Unchanged lines to show around each change (default "
             + UnifiedDiff.DEFAULT_CONTEXT + ")\n"
             + "      --stat         Report how many lines changed, without the lines themselves\n"
             + "  The output is a unified diff, which `patch` reads back.";
    }
}
