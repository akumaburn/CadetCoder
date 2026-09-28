package com.eonmux.cadetcoder.security;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Decides whether a path may be written to.
 *
 * <h2>Why the rule lives here rather than in each command</h2>
 *
 * <p>Every command that writes a file has to answer the same question, and the answer has to be the
 * same one: a policy enforced by three commands out of four is not a policy, it is a gap with a
 * documentation problem. {@code write}, {@code edit} and {@code multiedit} each carried their own
 * near-identical copy of this rule, and {@code notebookedit} -- which writes a file like any of them
 * -- had no copy at all and accepted any path it was handed.</p>
 *
 * <h2>The rule</h2>
 *
 * <ol>
 *   <li>{@link SecurityValidator#isFileAccessAllowed} must accept it. That covers the traversal
 *       pattern, the dangerous-system-path denylist, the credential-file denylist, and the
 *       configured project-containment policy.</li>
 *   <li>The system temporary directory is allowed, matched as a normalized path prefix rather than
 *       as a substring, so a directory merely containing the word "tmp" gains nothing. This
 *       applies only when {@code security.allowOutsideProject} is on, because the first rule
 *       already refuses every path outside the project when it is off.</li>
 *   <li>Otherwise the normalized path must lie inside the working directory. This holds regardless
 *       of {@code security.allowOutsideProject}: a relative traversal and an absolute path that
 *       lands outside are the same escape, and both are refused rather than warned about.</li>
 * </ol>
 *
 * <p>The decision is returned rather than printed, so each caller keeps its own logging and its own
 * failure value.</p>
 */
public final class WritePathPolicy {

    /** The outcome of examining one path. */
    public enum Decision {

        /** The path may be written. */
        ALLOWED,

        /** Refused by the shared security policy: traversal, a system path, or a credential file. */
        DENIED_BY_POLICY,

        /** A well-formed path, but one that lies outside the working directory. */
        OUTSIDE_WORKING_DIRECTORY,

        /** The path could not be resolved at all. */
        MALFORMED;

        public boolean isAllowed() {
            return this == ALLOWED;
        }
    }

    private WritePathPolicy() {
    }

    /**
     * Examines one path without writing anything or reporting anything.
     *
     * @param filePath the path a command is about to write to; {@code null} is {@link
     *                 Decision#MALFORMED}
     * @return what the caller is permitted to do
     */
    public static Decision decide(Path filePath) {
        if (filePath == null) {
            return Decision.MALFORMED;
        }

        try {
            if (!new SecurityValidator().isFileAccessAllowed(filePath.toString())) {
                return Decision.DENIED_BY_POLICY;
            }

            Path currentDir     = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
            Path normalizedFile = normalize(filePath, currentDir);

            String systemTmpDir = System.getProperty("java.io.tmpdir");
            if (systemTmpDir != null
                && normalizedFile.startsWith(Paths.get(systemTmpDir).toAbsolutePath().normalize())) {
                return Decision.ALLOWED;
            }

            return normalizedFile.startsWith(currentDir)
                   ? Decision.ALLOWED
                   : Decision.OUTSIDE_WORKING_DIRECTORY;
        } catch (Exception unresolvable) {
            return Decision.MALFORMED;
        }
    }

    /**
     * The path as the policy read it: absolute, with any {@code ..} segments collapsed.
     *
     * @param filePath   the path as supplied
     * @param currentDir the directory a relative path is resolved against
     * @return the normalized absolute path
     */
    public static Path normalize(Path filePath, Path currentDir) {
        return (filePath.isAbsolute() ? filePath : currentDir.resolve(filePath))
                .toAbsolutePath().normalize();
    }

    /**
     * What to tell the user about a refusal.
     *
     * @param decision the refusal; {@link Decision#ALLOWED} has no message
     * @param filePath the path that was examined, named in the malformed-path message
     * @return a message ready to print, or {@code null} when nothing was refused
     */
    public static String reasonFor(Decision decision, Path filePath) {
        switch (decision) {
            case DENIED_BY_POLICY:
                return "Access denied: Path validation failed";
            case OUTSIDE_WORKING_DIRECTORY:
                return "Access denied: Absolute paths outside working directory are not allowed";
            case MALFORMED:
                return "Invalid file path: " + filePath;
            default:
                return null;
        }
    }
}
