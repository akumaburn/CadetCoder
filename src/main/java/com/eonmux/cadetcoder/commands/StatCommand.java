package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.security.SecurityValidator;
import com.eonmux.cadetcoder.ui.UnifiedOutput;

import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * What a file is, without reading it out.
 *
 * <h2>Why this is a command</h2>
 *
 * <p>Deciding what to do with a file usually needs facts about the file rather than its contents.
 * Whether it is there. How large. How many lines, so a read can be budgeted or split. When it was
 * last written, so a build can be judged stale. Whether it is text at all. Each of those used to
 * cost a {@code read} of the whole file, or an {@code ls} of its directory and a guess.</p>
 */
@CommandLine.Command (name = "stat", description = "Report a file's size, kind and line count")
public class StatCommand extends LoggingCommandSupport implements CommandRegistry.Command {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final SecurityValidator security = new SecurityValidator();

    @Override
    public int execute(String[] args) {
        startCommandLogging("stat", args);
        if (args == null || args.length == 0) {
            OutputFormatter.printError("No file was named.");
            OutputFormatter.printInfo("Usage: " + CommandUsage.render(getUsage()));
            completeCommandLogging(1);
            return 1;
        }

        // Every path is reported, and the exit code says whether all of them were found. Stopping
        // at the first miss would hide the facts about the files that are there, which is the
        // opposite of what a caller asked several paths for.
        int worst = 0;
        for (String given : args) {
            worst = Math.max(worst, report(given));
        }
        completeCommandLogging(worst);
        return worst;
    }

    /** One path's facts, or the reason there are none. */
    private int report(String given) {
        Path file  = ProjectFile.at(given);
        String why = ProjectFile.reasonNotToTouch(file, security);
        if (why != null) {
            OutputFormatter.printError(why);
            return 1;
        }
        try {
            FileFacts facts = FileFacts.of(file);
            if (!facts.exists()) {
                OutputFormatter.printError("No such file: " + given);
                return 1;
            }
            UnifiedOutput.println(described(given, facts));
            return 0;
        } catch (IOException unreadable) {
            OutputFormatter.printError("Could not read " + given + ": " + unreadable.getMessage());
            return 1;
        }
    }

    /**
     * One line of facts about one path.
     *
     * <h2>Why a directory has no size here</h2>
     *
     * <p>The file system reports one, and it is the size of the directory's own inode rather than
     * of anything in it. Printed beside an entry count it invites the reader to treat it as the size
     * of the contents, which it is not, so a directory is described by what it holds instead.</p>
     */
    private static String described(String given, FileFacts facts) {
        StringBuilder said = new StringBuilder(given).append(": ");
        said.append(facts.directory() ? "directory" : facts.text() ? "text file" : "binary file");

        if (facts.directory()) {
            if (facts.entries() >= 0) {
                said.append(", ").append(counted(facts.entries(), "entry", "entries"));
            }
        } else {
            said.append(", ").append(facts.sizeBytes()).append(" bytes");
            if (facts.lines() >= 0) {
                said.append(", ").append(counted(facts.lines(), "line", "lines"));
            }
        }
        if (!facts.permissions().isEmpty()) {
            said.append(", ").append(facts.permissions());
        }
        said.append(", modified ").append(WHEN.format(facts.modified()));
        return said.toString();
    }

    /** A count with the right one of two words after it. */
    private static String counted(long howMany, String one, String several) {
        return howMany + " " + (howMany == 1 ? one : several);
    }

    @Override
    public String getUsage() {
        return "stat <path> [<path>...]\n"
             + "  Reports kind, size, line count, permissions and last modification.\n"
             + "  Every path given is reported; the exit code is non-zero if any was missing.";
    }
}
