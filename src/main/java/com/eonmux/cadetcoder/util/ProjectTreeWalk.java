package com.eonmux.cadetcoder.util;

import com.eonmux.cadetcoder.config.ConfigManager;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Whether a directory encountered during a project walk should be skipped.
 *
 * <h2>Why the start directory is the whole point</h2>
 *
 * <p>Every project walk in this codebase starts at {@code Paths.get(".")}, and
 * {@code Paths.get(".").getFileName()} is {@code "."} -- which {@code startsWith(".")}. Four copies
 * of this predicate tested the name without exempting the directory the walk began at, so they
 * pruned the start directory and visited <em>nothing</em>. They did not fail; they returned "not
 * found" for every input, which is indistinguishable from an honest miss. Three other copies had
 * already been fixed individually, which is exactly why the remaining four were never noticed.</p>
 *
 * <p>So there is one copy now. The start directory is never pruned, whatever it is called.</p>
 */
public final class ProjectTreeWalk {

    /**
     * Directories whose contents are build output or vendored dependencies.
     *
     * <p>Not a security boundary -- these are skipped because searching them wastes time and buries
     * the project's own files, not because reading them would be unsafe.</p>
     */
    private static final Set<String> BUILD_OUTPUT_DIRECTORIES =
            Set.of("target", "build", "node_modules", "dist", "out", "bin", "obj");

    private ProjectTreeWalk() {
    }

    /**
     * The directory names a project walk treats as build output or vendored dependencies.
     *
     * <p>Exposed so a caller that cannot use {@link #isPruned} -- because it matches names against
     * user-configured patterns rather than walking paths -- still derives its list from here rather
     * than restating it. Hidden directories are not in this set: they are pruned by the leading
     * dot, which is a rule about the name rather than a name of its own.</p>
     *
     * @return an immutable set of directory names
     */
    public static Set<String> buildOutputDirectories() {
        return BUILD_OUTPUT_DIRECTORIES;
    }

    /**
     * Whether a walk that began at {@code startPath} should skip {@code directory}.
     *
     * @param startPath where the walk began; never pruned, so a project inside a hidden or
     *                  build-named directory is still searchable
     * @param directory the directory the visitor is about to descend into
     * @return {@code true} to skip the subtree
     */
    public static boolean isPruned(Path startPath, Path directory) {
        if (directory == null || directory.equals(startPath)) {
            return false;
        }
        Path name = directory.getFileName();
        if (name == null) {
            return false;
        }
        return isPrunedName(name.toString());
    }

    /**
     * Whether a single path component names a directory a project walk skips.
     *
     * <p>{@code "."} and {@code ".."} are relative-path components, not hidden directories, and are
     * never pruned by name.</p>
     *
     * @param name one path component
     * @return {@code true} when a directory of that name is skipped
     */
    public static boolean isPrunedName(String name) {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..")) {
            return false;
        }
        return name.charAt(0) == '.' || isExcludedName(name);
    }

    /**
     * Whether a directory name is one the user has excluded.
     *
     * <h2>Why this is not simply the list above</h2>
     *
     * <p>Thirteen places in this tool walk the project tree. Four of them read
     * {@code indexing.excludePatterns} and the rest used the list written here, which is only the
     * SHIPPED value of that setting. The two agreed until somebody changed the setting, and then
     * {@code grep} skipped a directory that {@code read}, {@code edit}, {@code refactor} and the
     * agent's own file search still walked into -- with nothing to show for it but inconsistent
     * results between commands that describe themselves as searching the same project.</p>
     *
     * <p>Held apart from {@link #isPrunedName} because hidden directories are a separate rule that
     * a caller may want to lift on its own: {@code ls -a} shows what starts with a dot, and shows
     * it whatever the exclude list says, because the user asked for it by name.</p>
     *
     * @param name one path component
     * @return {@code true} when a directory of that name is excluded by configuration
     */
    public static boolean isExcludedName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        for (Exclusion exclusion : exclusions()) {
            if (exclusion.matches(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Drops the compiled exclude list so the next walk reads the configuration again.
     *
     * <p>For {@code config indexing.excludePatterns}, which changes the answer, and for tests, which
     * would otherwise inherit whichever configuration happened to be loaded first.</p>
     */
    public static synchronized void forgetConfiguredExclusions() {
        compiledFor = null;
        compiled    = List.of();
    }

    /** One configured pattern, resolved once into the way it will be matched. */
    private record Exclusion(Pattern regex, String literal) {

        /**
         * @param pattern a configured pattern
         * @return it as a regular expression, or as a literal name when it is not valid regex
         */
        static Exclusion of(String pattern) {
            try {
                return new Exclusion(Pattern.compile(pattern), pattern);
            } catch (PatternSyntaxException notRegex) {
                // "*.tmp" is what a person writes when they mean a glob. Refusing it would make a
                // walk fail over a setting; ignoring it would silently stop excluding something.
                return new Exclusion(null, pattern);
            }
        }

        boolean matches(String name) {
            return regex != null ? regex.matcher(name).matches() : literal.equals(name);
        }
    }

    /** The configured array the {@link #compiled} list was built from; null until one is. */
    private static String[]        compiledFor;
    /** {@link #compiledFor}, compiled. Patterns are matched per directory, so this is not rebuilt. */
    private static List<Exclusion> compiled = List.of();

    /**
     * The exclude list, compiled, rebuilt only when the configuration has changed.
     *
     * @return one entry per configured pattern
     */
    private static synchronized List<Exclusion> exclusions() {
        String[] configured = configuredPatterns();
        if (!Arrays.equals(configured, compiledFor)) {
            List<Exclusion> rebuilt = new ArrayList<>(configured.length);
            for (String pattern : configured) {
                if (pattern != null && !pattern.isBlank()) {
                    rebuilt.add(Exclusion.of(pattern));
                }
            }
            compiled    = List.copyOf(rebuilt);
            compiledFor = configured;
        }
        return compiled;
    }

    /**
     * What the user's configuration says to exclude.
     *
     * @return the configured patterns, or the shipped list when the configuration cannot be read --
     *         a walk is not worth failing over a setting, and the shipped list is what the setting
     *         holds by default anyway
     */
    private static String[] configuredPatterns() {
        try {
            String[] configured = ConfigManager.getInstance().getConfig()
                                               .getIndexing().getExcludePatterns();
            return configured == null ? shippedPatterns() : configured;
        } catch (RuntimeException unreadable) {
            return shippedPatterns();
        }
    }

    private static String[] shippedPatterns() {
        return BUILD_OUTPUT_DIRECTORIES.stream().sorted().toArray(String[]::new);
    }
}
