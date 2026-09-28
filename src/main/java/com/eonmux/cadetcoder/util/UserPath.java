package com.eonmux.cadetcoder.util;

/**
 * A path as a person typed it, resolved to one the filesystem understands.
 *
 * <h2>Why this is not the shell's job here</h2>
 *
 * <p>A shell expands {@code ~} before the process ever sees it, and Java does not expand it at all.
 * Between those two facts sits every path this tool is given that no shell touched: a setting typed
 * at the interactive prompt, a value in a configuration file somebody edited by hand, an argument
 * that arrived quoted. Handed to a file API unchanged, {@code ~/work} names a directory called
 * {@code ~} in whatever the working directory happens to be -- which is created without complaint,
 * reported as though it were the home directory, and found by nobody afterwards.</p>
 *
 * <h2>Why only a leading tilde</h2>
 *
 * <p>{@code ~} is a home reference only as the whole path or as its first segment. Elsewhere it is
 * an ordinary character in an ordinary name, and rewriting {@code /srv/back~ups} would move a
 * directory somebody really has. {@code ~otheruser} is left alone as well: this expands the home
 * directory this process is running as, and inventing another user's is a guess about a machine's
 * account layout.</p>
 */
public final class UserPath {

    /** What a path is when it is the home directory and nothing more. */
    private static final String HOME = "~";

    /** What a path begins with when it is inside the home directory. */
    private static final String UNDER_HOME = "~/";

    private UserPath() {
    }

    /**
     * The same path, with a leading tilde replaced by the home directory.
     *
     * @param path a path as it was given; may be {@code null} or empty
     * @return the path the filesystem should be asked about, unchanged when it names no home
     */
    public static String expanded(String path) {
        if (path == null || path.isEmpty()) {
            return path;
        }
        String home = System.getProperty("user.home");
        if (home == null || home.isEmpty()) {
            // Nothing to expand to. Returning the path as given keeps the failure where the caller
            // can see it -- a path with a tilde in it -- rather than turning it into "null/work".
            return path;
        }
        if (path.equals(HOME)) {
            return home;
        }
        if (path.startsWith(UNDER_HOME)) {
            return home + path.substring(HOME.length());
        }
        return path;
    }
}
