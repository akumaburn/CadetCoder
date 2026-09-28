package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.logging.DebugLogger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The lines the shell has been typed, in memory and on disk.
 *
 * <h2>Why a line is written when it is typed</h2>
 *
 * <p>The list used to be held in memory alone and written over the file in one go, at the end, as a
 * step of a clean exit. A session that did not end cleanly -- a crash, a {@code kill}, a closed
 * terminal window -- therefore left no trace of itself at all, and that is exactly the session whose
 * last few commands someone wants to look up. Two shells open at once were worse than that: both
 * read the file at the start and both wrote it back at the end, so whichever quit second replaced
 * everything the first had done with its own list, without a word.</p>
 *
 * <p>Appending as the line is typed answers all of it. Nothing has to end tidily for a line to be
 * kept, a second shell adds to the file instead of replacing it, and a write that is interrupted
 * costs the line being written rather than the file.</p>
 *
 * <h2>Why it is bounded</h2>
 *
 * <p>Nothing ever removed anything. The file grew for as long as the tool was used and was read in
 * full at every start, so the cost of opening a shell was proportional to how long its owner had
 * been using one. Only the most recent lines are of any use -- the shell offers the last twenty and
 * walks back through them one at a time -- so the file is held to {@link #KEPT_LINES}, oldest
 * first out.</p>
 */
final class CommandHistory {

    /**
     * How many lines the file keeps.
     *
     * <p>Far more than anyone walks back through by hand, and small enough that reading it is not
     * something a shell's start time notices.</p>
     */
    static final int KEPT_LINES = 1000;

    private final Path         file;
    private final List<String> entries = new ArrayList<>();

    /** Set once the file has proved unwritable, so the same failure is not logged per keystroke. */
    private boolean troubleAlreadyLogged;

    /**
     * Reads what is already there.
     *
     * <p>The directory is the caller's to create. A file that cannot be read leaves an empty
     * history rather than stopping the shell: not remembering earlier sessions is a smaller loss
     * than not starting.</p>
     *
     * @param file where the history is kept
     */
    CommandHistory(Path file) {
        this.file = file;
        entries.addAll(mostRecent(read()));
        trimFile();
    }

    /** What has been typed, oldest first. */
    List<String> entries() {
        return Collections.unmodifiableList(entries);
    }

    int size() {
        return entries.size();
    }

    boolean isEmpty() {
        return entries.isEmpty();
    }

    String get(int index) {
        return entries.get(index);
    }

    /**
     * Remembers a line, in memory and on disk.
     *
     * @param command the line as it should be recorded; blank and {@code null} are not history
     */
    void add(String command) {
        if (command == null || command.isBlank()) {
            return;
        }
        entries.add(command);
        if (entries.size() > KEPT_LINES) {
            entries.subList(0, entries.size() - KEPT_LINES).clear();
        }
        append(command);
        trimFile();
    }

    private List<String> read() {
        try {
            return Files.exists(file) ? Files.readAllLines(file, StandardCharsets.UTF_8)
                                      : List.of();
        } catch (IOException unreadable) {
            logOnce("read", unreadable);
            return List.of();
        }
    }

    private static List<String> mostRecent(List<String> lines) {
        return lines.size() <= KEPT_LINES ? lines
                                          : lines.subList(lines.size() - KEPT_LINES, lines.size());
    }

    private void append(String command) {
        try {
            Files.writeString(file, command + System.lineSeparator(), StandardCharsets.UTF_8,
                              StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException unwritable) {
            logOnce("append to", unwritable);
        }
    }

    /**
     * Cuts the file back to {@link #KEPT_LINES}, keeping the most recent.
     *
     * <p>What is kept is read back from the file rather than taken from this shell's own list, so
     * that a shell doing the cutting does not throw away what another one has been adding
     * meanwhile.</p>
     */
    private void trimFile() {
        List<String> lines = read();
        if (lines.size() <= KEPT_LINES) {
            return;
        }
        replaceWith(mostRecent(lines));
    }

    /**
     * Replaces the file in one step.
     *
     * <p>Written beside the file and moved onto it, so an interruption leaves the history as it
     * was. Truncating the real file and writing it again would have left it empty or half written,
     * which is the whole of someone's history rather than the part being dropped.</p>
     */
    private void replaceWith(List<String> lines) {
        Path beingWritten = null;
        try {
            Path directory = file.toAbsolutePath().getParent();
            beingWritten = Files.createTempFile(directory, "history", ".log");
            Files.write(beingWritten, lines, StandardCharsets.UTF_8);
            move(beingWritten, file);
            beingWritten = null;
        } catch (IOException couldNotReplace) {
            logOnce("trim", couldNotReplace);
        } finally {
            discard(beingWritten);
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING,
                       StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException notAtomicHere) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void discard(Path leftOver) {
        if (leftOver == null) {
            return;
        }
        try {
            Files.deleteIfExists(leftOver);
        } catch (IOException stillThere) {
            DebugLogger.getInstance().warn("CommandHistory",
                                           "Could not remove " + leftOver + ": "
                                           + stillThere.getMessage());
        }
    }

    /**
     * Records a file failure the first time it happens.
     *
     * <p>Once rather than every time: the cause is almost always standing -- a read-only home
     * directory, a full disk -- so per-line reporting would fill the debug log with one repeated
     * sentence. Nothing is printed to the console, because the shell owns the screen and losing the
     * history file is not worth taking a line of it.</p>
     */
    private void logOnce(String what, IOException failure) {
        if (troubleAlreadyLogged) {
            return;
        }
        troubleAlreadyLogged = true;
        DebugLogger.getInstance().warn("CommandHistory",
                                       "Could not " + what + " the command history at " + file
                                       + "; this session's history is kept in memory only",
                                       failure);
    }
}
