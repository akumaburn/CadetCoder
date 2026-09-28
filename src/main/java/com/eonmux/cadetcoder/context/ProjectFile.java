package com.eonmux.cadetcoder.context;

import com.eonmux.cadetcoder.security.SecurityValidator;
import com.eonmux.cadetcoder.util.TextFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * Whether a name may become a file in a prompt.
 *
 * <h2>Why the two questions live together</h2>
 *
 * <p>Everything that puts a whole file in front of a model has to ask the same two things: does the
 * name resolve to a file genuinely inside the project, and is that file one that may be sent to a
 * provider at all. A request naming {@code ../../etc/passwd} and a configured priority file naming
 * it are the same danger arriving by two doors, and a boundary enforced by whichever door remembers
 * it is not a boundary.</p>
 */
final class ProjectFile {

    private ProjectFile() {
    }

    /**
     * The named path, if it exists and is genuinely inside the project.
     *
     * <p>Resolved twice: once as it was written, so {@code ../../etc/passwd} is refused, and once
     * through whatever links it passes through, so that a link committed into the project cannot
     * borrow the project's boundary to read outside it.</p>
     *
     * @param name the name as it was written, in a request or in the configuration
     * @param root the directory it resolves against, and the boundary it may not cross
     * @return the resolved path, or {@code null} if it is not a file inside the project
     */
    static Path inside(String name, Path root) {
        try {
            Path file = root.resolve(name).normalize();
            if (!file.startsWith(root)) {
                return null;
            }
            return file.toRealPath().startsWith(root.toRealPath()) ? file : null;
        } catch (InvalidPathException | IOException notAFileInThisProject) {
            return null;
        }
    }

    /**
     * Whether a file may be read into a prompt.
     *
     * <p>A file that cannot be read is not reported here. The caller finds out when it reads it,
     * and is the one that owes the user a word about it.</p>
     *
     * @param file        the resolved path
     * @param credentials the check for files that hold secrets, reused across a whole batch
     * @return whether it is a regular text file of a size context will carry, holding no credential
     */
    static boolean mayBeShown(Path file, SecurityValidator credentials) {
        try {
            return !credentials.isSensitiveCredentialFile(file)
                   && Files.isRegularFile(file)
                   && Files.size(file) <= ContextEngine.MAX_CONTEXT_FILE_BYTES
                   && TextFiles.isTextFile(file);
        } catch (IOException unreadable) {
            return false;
        }
    }
}
