package com.eonmux.cadetcoder.patch;

/**
 * What a patch does to one file as a whole.
 *
 * <h2>Why this is read off the headers</h2>
 *
 * <p>{@code git diff} writes {@code /dev/null} on the side of a file that does not exist, so a new
 * file reads {@code --- /dev/null} and a removed one reads {@code +++ /dev/null}. That is the only
 * part of the patch that says which of the three things is happening. A {@code new file mode} line
 * agrees with it when git wrote the patch, and is absent when anything else did.</p>
 */
public enum PatchKind {

    /** The file does not exist yet and the patch is its whole contents. */
    CREATE,

    /** The file exists and some of its lines change. */
    MODIFY,

    /** The file exists and the patch removes it. */
    DELETE
}
