package com.eonmux.cadetcoder.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/**
 * Replacing a file's contents without ever leaving a half-written one behind.
 *
 * <h2>Why a command never writes straight to its target</h2>
 *
 * <p>A direct write truncates the file first. Anything that goes wrong after that -- a full disk, a
 * killed process, the user taking the run back -- leaves the target shorter than it was and with no
 * copy of what it said, which for a source file being edited is the one outcome worse than the edit
 * failing. Staging beside the target and moving the finished file into place makes the change either
 * complete or absent, and never partial.</p>
 *
 * <h2>Why the staging file goes next to the target</h2>
 *
 * <p>An atomic move only holds within one filesystem, so a temporary file in the system temp
 * directory would degrade to a copy on any machine where the project is on a different mount --
 * exactly the guarantee this exists for, lost silently.</p>
 *
 * <h2>Why the target's permissions are put back on the staging file</h2>
 *
 * <p>A file is what it is allowed to do as well as what it says. {@link Files#createTempFile}
 * creates the staging file readable and writable by its owner and nobody else, which is right for a
 * temporary file, and the move then carries that onto the target. Editing a build script therefore
 * left it at {@code 0600}: the content was correct, the script no longer ran, and nothing said so.
 * The permissions the target already has are the ones it should still have afterwards.</p>
 */
public final class AtomicFileWrite {

    private AtomicFileWrite() {
    }

    /**
     * Replaces {@code target}'s contents with {@code content}, encoded as UTF-8.
     *
     * @param target  the file to write; its parent directory must exist
     * @param content what it should say afterwards
     * @throws IOException if staging or the move fails, in which case {@code target} is untouched
     */
    public static void writeString(Path target, String content) throws IOException {
        write(target, content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Replaces {@code target}'s contents with {@code bytes}.
     *
     * @param target the file to write; its parent directory must exist
     * @param bytes  what it should contain afterwards
     * @throws IOException if staging or the move fails, in which case {@code target} is untouched
     */
    public static void write(Path target, byte[] bytes) throws IOException {
        Path parent  = target.getParent();
        Path staging = parent == null
                       ? Files.createTempFile("." + target.getFileName() + ".", ".tmp")
                       : Files.createTempFile(parent, "." + target.getFileName() + ".", ".tmp");
        try {
            Files.write(staging, bytes);
            carryPermissionsOver(target, staging);
            moveIntoPlace(staging, target);
        } catch (IOException e) {
            deleteQuietly(staging);
            throw e;
        }
    }

    /**
     * Gives the staged file whatever permissions the target already has.
     *
     * <p>A file that is not there yet has none to give, and keeps the staging file's own, which is
     * the narrower of the two defaults and the right one for a file nobody has said anything about.
     * A filesystem with no POSIX permissions at all -- Windows, and any mount whose view is
     * unavailable -- has nothing to carry over either. Neither is a reason to fail a write: the
     * content is what the caller asked for, and the mode being preserved is one the target does not
     * have.</p>
     */
    private static void carryPermissionsOver(Path target, Path staging) throws IOException {
        Set<PosixFilePermission> existing;
        try {
            existing = Files.getPosixFilePermissions(target);
        } catch (UnsupportedOperationException | IOException noneToCarryOver) {
            return;
        }
        Files.setPosixFilePermissions(staging, existing);
    }

    /** Moves the staged file onto the target, atomically where the filesystem allows it. */
    private static void moveIntoPlace(Path staging, Path target) throws IOException {
        try {
            Files.move(staging, target,
                       StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            // The filesystem cannot promise atomicity. The staging file still means the target is
            // never seen half-written, which is the part that matters.
            Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Removes a staging file that is no longer wanted.
     *
     * <p>Failing to delete it is not worth failing the write for, and the write has already failed
     * for a reason the caller is about to be told about. It is not swallowed silently: the reason
     * the first failure gives is the one worth reporting, and a second one on top of it would
     * replace the explanation with the cleanup's.</p>
     */
    private static void deleteQuietly(Path staging) {
        try {
            Files.deleteIfExists(staging);
        } catch (IOException ignored) {
            staging.toFile().deleteOnExit();
        }
    }
}
