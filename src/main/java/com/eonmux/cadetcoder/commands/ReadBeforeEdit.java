package com.eonmux.cadetcoder.commands;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Whether a model may change a file it has never looked at.
 *
 * <h2>The failure this exists for</h2>
 *
 * <p>An edit names the text to replace, and a model that has not read the file writes that text
 * from memory. Memory is what a long run has least of: the read happened forty turns ago, or in
 * another run, or never. So the old text is close to what the file says and not equal to it, the
 * match fails, and the run spends a turn on an edit that changed nothing -- then usually another
 * one guessing again. The same guess against {@code write} does not fail at all. It succeeds, and
 * replaces a file the model never saw with what it imagined the file contained.</p>
 *
 * <p>Requiring the read first turns all of that into one refusal that says what to do. The model
 * reads the file, the text it then quotes is the text that is there, and the edit applies.</p>
 *
 * <h2>Why only a model is held to it</h2>
 *
 * <p>A person running {@code multiedit} from a terminal has the file open in front of them, and a
 * fresh process has read nothing, so the rule would refuse every one-shot invocation and every
 * script. {@link ModelDispatch#isModelDriven()} already marks the commands a model asked for, and
 * all three loops -- chat, agent, and the harness -- dispatch through it.</p>
 *
 * <h2>What counts as having read a file</h2>
 *
 * <p>Reading it, and writing it. A model that has just written a file knows what is in it as surely
 * as one that has read it, and refusing to edit a file the run itself created would be a rule about
 * nothing. A file that does not exist yet is not gated at all: there is nothing to have read, and
 * creating a file is not the operation this is about.</p>
 *
 * <h2>Why the record is not scoped to one run</h2>
 *
 * <p>It would be better if it were: a file read in a previous run is not in this run's context. But
 * a run has no identity down here, workers run several at once in one process, and a reset by one
 * of them would refuse the others' edits for reasons they could not see. A record that is too
 * generous never refuses an edit that should have been allowed, which is the failure that would
 * make this rule intolerable to work under.</p>
 */
final class ReadBeforeEdit {

    /** Absolute paths whose contents somebody in this process has actually seen. */
    private static final Set<String> SEEN = ConcurrentHashMap.newKeySet();

    private ReadBeforeEdit() {
    }

    /**
     * Records that a file's contents have been put in front of the model.
     *
     * <p>Quiet about anything it cannot make sense of. This is called from the middle of commands
     * that have already done their own error handling, and a bookkeeping failure is not a reason to
     * fail a read that worked.</p>
     *
     * @param file the file that was read or written; {@code null} is ignored
     */
    static void sawContents(Path file) {
        String key = keyFor(file);
        if (key != null) {
            SEEN.add(key);
        }
    }

    /**
     * Why this file may not be changed, if it may not.
     *
     * @param file    the file about to be changed
     * @param command the command asking, named as the model would type it
     * @return {@code null} when the change may go ahead, otherwise what to tell the model
     */
    static String reasonNotToChange(Path file, String command) {
        if (!ModelDispatch.isModelDriven()) {
            return null;
        }
        String key = keyFor(file);
        if (key == null || SEEN.contains(key)) {
            return null;
        }
        if (!Files.isRegularFile(file)) {
            // Nothing there to have read. Creating a file is not what this rule is about, and a
            // directory is refused by the command itself for better reasons than this one.
            return null;
        }
        return "Error: " + file.getFileName() + " has not been read in this session, so "
               + command + " would be working from a guess at what it contains. Nothing was "
               + "changed. Read it first: read " + file;
    }

    /** Forgets every file. Intended for tests, which must not inherit each other's reads. */
    static void forgetEverything() {
        SEEN.clear();
    }

    /**
     * @param file any path
     * @return the absolute path it names, or {@code null} when it names nothing usable
     */
    private static String keyFor(Path file) {
        if (file == null) {
            return null;
        }
        try {
            String absolute = file.toAbsolutePath().normalize().toString();
            // Two spellings of one path must not read as two files. Case is the spelling that
            // differs without the path differing, on the systems where it does.
            return isCaseInsensitive() ? absolute.toLowerCase(Locale.ROOT) : absolute;
        } catch (RuntimeException unusable) {
            return null;
        }
    }

    /** Whether this file system tells two paths apart by case. */
    private static boolean isCaseInsensitive() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win") || os.contains("mac");
    }
}
