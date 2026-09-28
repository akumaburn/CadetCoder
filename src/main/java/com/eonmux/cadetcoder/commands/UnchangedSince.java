package com.eonmux.cadetcoder.commands;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Whether a file is still what it was when a command read it.
 *
 * <h2>Why a command that pauses has to ask</h2>
 *
 * <p>An edit is worked out against the text the command read, and written as a whole file. Between
 * those two moments a command that asks for confirmation is waiting -- for a person, or for a model
 * that may be running other tools in the meantime. Anything written to the file in that gap is not
 * in the text being written back, so applying the edit does not merge with it: it replaces it, with
 * no sign that anything was lost.</p>
 *
 * <p>So the file is read again immediately before the write, and the edit is refused when it no
 * longer matches. Refusing is right rather than cautious: the edit was computed against text that
 * is no longer there, so what it would produce is not what anyone approved.</p>
 */
final class UnchangedSince {

    private UnchangedSince() {
    }

    /**
     * Checks that a file still holds the text a command read from it.
     *
     * @param path the file about to be written
     * @param read what the command read from it earlier, and worked out its edit against
     * @return {@code null} when the file may be written, or a message saying why it may not
     */
    static String reasonNotToWrite(Path path, String read) {
        if (path == null || read == null) {
            return null;
        }
        String now;
        try {
            now = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            // It was readable a moment ago, so something has happened to it. Writing over whatever
            // that is would be worse than stopping.
            return "Error: " + path.getFileName() + " could not be read again before writing ("
                   + unreadable.getMessage() + "). Nothing was written.";
        }
        if (read.equals(now)) {
            return null;
        }
        return "Error: " + path.getFileName() + " changed on disk after it was read, so these edits "
               + "no longer describe it. Nothing was written; read it again and redo the edit.";
    }
}
