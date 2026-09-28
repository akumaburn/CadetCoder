package com.eonmux.cadetcoder.security;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * Restricts a file holding credentials to its owner.
 *
 * <h2>Why callers do not do this themselves</h2>
 *
 * <p>CadetCoder writes more than one file containing a long-lived credential -- the configuration,
 * which holds provider API keys, and the GitHub OAuth token it exchanges for Copilot access. Only
 * the first of those was ever restricted, so on a machine with a permissive umask the second was
 * created readable by every local account. A rule applied by whichever writer remembered it is not
 * a rule, so it is stated once here and every credential file goes through it.</p>
 *
 * <p>Not every filesystem has POSIX permissions. Where they are unavailable this reports success:
 * there is nothing to set, and treating that as a failure would put a warning in front of every
 * Windows user for a condition they cannot act on.</p>
 */
public final class OwnerOnlyFile {

    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");

    /** The same for a directory, which also needs the owner's permission to enter it. */
    private static final Set<PosixFilePermission> OWNER_ONLY_DIRECTORY =
            PosixFilePermissions.fromString("rwx------");

    private OwnerOnlyFile() {
    }

    /**
     * Makes {@code path} readable and writable by its owner alone.
     *
     * <p>Returns rather than throws, and returns rather than prints, so the caller decides how
     * loudly to react -- a warning during a login flow and an error during a config save are the
     * same failure told to different audiences.</p>
     *
     * @param path the file to restrict; a {@code null} or absent path is nothing to do
     * @return {@code null} when the file is now owner-only or the filesystem has no POSIX
     *         permissions to set, otherwise a description of what went wrong
     */
    public static String restrict(Path path) {
        if (path == null || !Files.exists(path)) {
            return null;
        }
        try {
            if (!Files.getFileStore(path).supportsFileAttributeView(PosixFileAttributeView.class)) {
                return null;
            }
            Files.setPosixFilePermissions(path, OWNER_ONLY);
            return null;
        } catch (UnsupportedOperationException noPosix) {
            return null;
        } catch (IOException e) {
            return e.getMessage();
        }
    }

    /**
     * Makes a directory accessible to its owner alone.
     *
     * <p>Protects every file inside it, including files written with a looser mode, so one call
     * covers the sessions, logs and index the tool keeps under its base directory.</p>
     *
     * @param directory the directory to restrict; a {@code null} or absent path is nothing to do
     * @return {@code null} when the directory is now owner-only or the filesystem has no POSIX
     *         permissions to set, otherwise a description of what went wrong
     */
    public static String restrictDirectory(Path directory) {
        if (directory == null || !Files.isDirectory(directory)) {
            return null;
        }
        try {
            if (!Files.getFileStore(directory).supportsFileAttributeView(PosixFileAttributeView.class)) {
                return null;
            }
            Files.setPosixFilePermissions(directory, OWNER_ONLY_DIRECTORY);
            return null;
        } catch (UnsupportedOperationException noPosix) {
            return null;
        } catch (IOException e) {
            return e.getMessage();
        }
    }

    /**
     * Makes sure {@code path} exists and is owner-only before anything is written into it.
     *
     * <h2>Why creating and restricting have to be one act</h2>
     *
     * <p>{@link #restrict} can only narrow a file that is there; on a file that is not, it is a
     * no-op that reads like a precaution. A caller that restricted, then wrote, was therefore
     * creating the file at whatever the umask allowed -- world-readable on a great many machines --
     * with the credential already in it, and narrowing it afterwards. The window is short and the
     * contents are a long-lived token, which is the wrong pair of properties. Asking for the
     * permissions at the moment of creation leaves no window at all: a umask can only take
     * permissions away, never add them.</p>
     *
     * @param path the file to create restricted; a {@code null} path is nothing to do
     * @return {@code null} when the file now exists and is owner-only, or the filesystem has no
     *         POSIX permissions to set, otherwise a description of what went wrong
     */
    public static String createOwnerOnly(Path path) {
        if (path == null) {
            return null;
        }
        if (Files.exists(path)) {
            return restrict(path);
        }
        try {
            Files.createFile(path, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
            return null;
        } catch (FileAlreadyExistsException raced) {
            return restrict(path);
        } catch (UnsupportedOperationException noPosix) {
            return createPlainly(path);
        } catch (IOException e) {
            return e.getMessage();
        }
    }

    /** A filesystem with no permissions to ask for still needs the file. */
    private static String createPlainly(Path path) {
        try {
            Files.createFile(path);
            return null;
        } catch (FileAlreadyExistsException raced) {
            return null;
        } catch (IOException e) {
            return e.getMessage();
        }
    }
}
