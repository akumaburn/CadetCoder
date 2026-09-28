package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.util.TextFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.stream.Stream;

/**
 * What can be said about a file without reading it out.
 *
 * <h2>Why the line count is here</h2>
 *
 * <p>It is the one fact that cannot be had from the file system, and it is the one a caller most
 * often wants: how many lines decides whether a file can be read in one go or has to be read in
 * ranges. Counting them reads the file, so it is done once and only for a text file that is not
 * enormous.</p>
 *
 * @param path        the file this describes
 * @param exists      whether there is anything there
 * @param directory   whether it is a directory
 * @param sizeBytes   how large it is
 * @param modified    when it was last written
 * @param text        whether its contents are text
 * @param lines       how many lines it has, or {@code -1} when that was not counted
 * @param entries     how many entries a directory holds, or {@code -1} for a file
 * @param permissions the permissions, in the usual nine characters, or an empty string
 */
record FileFacts(Path path, boolean exists, boolean directory, long sizeBytes, Instant modified,
                 boolean text, long lines, int entries, String permissions) {

    /** Above this, the line count is not worth a full read to produce. */
    private static final long TOO_LARGE_TO_COUNT = 50L * 1024 * 1024;

    /** What is not there. */
    static FileFacts missing(Path path) {
        return new FileFacts(path, false, false, 0, null, false, -1, -1, "");
    }

    /**
     * Everything about one path.
     *
     * @param path the file or directory to describe
     * @return the facts, or {@link #missing} when there is nothing there
     * @throws IOException if the file system cannot be asked
     */
    static FileFacts of(Path path) throws IOException {
        if (!Files.exists(path)) {
            return missing(path);
        }
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
        String              permissions = permissionsOf(path);

        if (attributes.isDirectory()) {
            return new FileFacts(path, true, true, attributes.size(),
                                 attributes.lastModifiedTime().toInstant(), false, -1,
                                 entriesIn(path), permissions);
        }
        boolean text = TextFiles.isTextFile(path);
        return new FileFacts(path, true, false, attributes.size(),
                             attributes.lastModifiedTime().toInstant(), text,
                             countedLines(path, text, attributes.size()), -1, permissions);
    }

    /** How many lines a text file has, or {@code -1} when counting them is not worth it. */
    private static long countedLines(Path file, boolean text, long size) {
        if (!text || size > TOO_LARGE_TO_COUNT) {
            return -1;
        }
        if (size == 0) {
            return 0;
        }
        try (Stream<String> lines = Files.lines(file)) {
            return lines.count();
        } catch (IOException | RuntimeException unreadable) {
            // Sniffing said text and the read disagreed. The rest of the facts still stand.
            return -1;
        }
    }

    /** How many entries a directory holds. */
    private static int entriesIn(Path directory) {
        try (Stream<Path> held = Files.list(directory)) {
            return (int) held.count();
        } catch (IOException unreadable) {
            return -1;
        }
    }

    /** The permissions as nine characters, or an empty string where the system has no such thing. */
    private static String permissionsOf(Path path) {
        if (Files.getFileAttributeView(path, PosixFileAttributeView.class) == null) {
            return "";
        }
        try {
            return PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
        } catch (IOException | UnsupportedOperationException unavailable) {
            return "";
        }
    }

}
