package com.eonmux.cadetcoder.util;

import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * A path a model wrote from memory, resolved to one this project actually has.
 *
 * <h2>Why a command repairs the path it was given</h2>
 *
 * <p>Most calls to {@code ls} and {@code grep} come from a model, and a model that has not yet read
 * the project writes the path it has seen in a thousand tutorials: {@code path/to/src},
 * {@code src/main/java/com/example/}, {@code found_path/}. Each of those produces a run that finds
 * nothing and reports no reason, which costs a turn and teaches the model nothing.</p>
 *
 * <h2>What is never repaired</h2>
 *
 * <p>A path that exists is left exactly as written -- that check comes first, before anything else
 * is asked. {@code src} and {@code test} are on the list because tutorials use them as stand-ins,
 * but they are also real directories in most projects, and a project that has them wants them
 * searched. The two commands that need this used to answer it separately and disagreed about that
 * rule: one checked existence first and the other did not, so a project with a real
 * {@code com/example} package had {@code ls} silently redirected somewhere else while {@code grep}
 * looked exactly where it was told.</p>
 */
public final class PlaceholderPath {

    /** How far below the working directory a named directory is looked for. */
    private static final int SEARCH_DEPTH = 3;

    private PlaceholderPath() {}

    /**
     * The path to actually use.
     *
     * @param path what was asked for, or {@code null} for the working directory
     * @param warn where to report a search that could not be run
     * @return the path as given when it exists, otherwise the best real path found for it
     */
    public static String resolved(String path, Consumer<String> warn) {
        if (path == null) {
            return ".";
        }
        if (Files.exists(Paths.get(path)) || !looksLikeStandIn(path)) {
            return path;
        }
        String dirName = Paths.get(path).getFileName().toString();
        String found   = directoryNamed(dirName, warn);
        return found != null ? found : conventionalPath(dirName, path);
    }

    /**
     * Whether a path looks like a stand-in rather than somewhere in this project.
     *
     * <p>Asked by a caller that already knows the path does not exist, to decide whether to say
     * "no such directory" or "that looks like a placeholder".</p>
     *
     * @param path the path that was asked for
     * @return whether it has the shape of an example
     */
    public static boolean looksLikeStandIn(String path) {
        return path.contains("/example/")
               || path.startsWith("path/to/")
               || path.startsWith("src/main/java/com/example/")
               || path.startsWith("found_path/")
               || path.contains("placeholder")
               || path.contains("example.")
               || path.contains("/path/to/")
               || path.equals("src")
               || path.equals("test");
    }

    /**
     * The first directory of this name within {@value #SEARCH_DEPTH} levels of the working
     * directory, or {@code null} when there is none.
     */
    private static String directoryNamed(String dirName, Consumer<String> warn) {
        try {
            Path                    startPath = Paths.get(".");
            AtomicReference<String> found     = new AtomicReference<>();

            Files.walkFileTree(startPath, EnumSet.noneOf(FileVisitOption.class), SEARCH_DEPTH,
                               new SimpleFileVisitor<>() {
                                   @Override
                                   public FileVisitResult preVisitDirectory(
                                           Path dir, BasicFileAttributes attrs) {
                                       // Build output and hidden directories are skipped -- but
                                       // never the directory the walk started at, which is "." and
                                       // would otherwise prune the entire search.
                                       if (ProjectTreeWalk.isPruned(startPath, dir)) {
                                           return FileVisitResult.SKIP_SUBTREE;
                                       }
                                       Path name = dir.getFileName();
                                       if (name != null && name.toString().equals(dirName)) {
                                           found.set(dir.toString());
                                           return FileVisitResult.TERMINATE;
                                       }
                                       return FileVisitResult.CONTINUE;
                                   }
                               });
            return found.get();
        } catch (Exception e) {
            warn.accept("Error searching for directory: " + e.getMessage());
            return null;
        }
    }

    /**
     * Where a directory of this name conventionally lives, when this project actually has it.
     *
     * @param dirName      the last segment of the path that was asked for
     * @param originalPath the path that was asked for, returned when nothing better exists
     * @return a real directory, or {@code originalPath}
     */
    private static String conventionalPath(String dirName, String originalPath) {
        if (dirName.equals("src") || originalPath.contains("src")) {
            String found = firstExisting("src");
            if (found != null) {
                return found;
            }
        }
        if (dirName.equals("test") || originalPath.contains("test")) {
            String found = firstExisting("src/test", "test", "tests");
            if (found != null) {
                return found;
            }
        }
        if (dirName.equals("java") || originalPath.contains("java")) {
            String found = firstExisting("src/main/java", "src/java");
            if (found != null) {
                return found;
            }
        }
        String fromPackage = packageDirectory(originalPath);
        return fromPackage != null ? fromPackage : originalPath;
    }

    /** The first of these that exists and is a directory, or {@code null}. */
    private static String firstExisting(String... candidates) {
        for (String candidate : candidates) {
            Path path = Paths.get(candidate);
            if (Files.exists(path) && Files.isDirectory(path)) {
                return path.toString();
            }
        }
        return null;
    }

    /**
     * The source directory a Java package name points at, when the path carries one.
     *
     * <p>A path like {@code found_path/com/eonmux/cadetcoder} names a package, not a directory: the
     * package part of it does exist, under {@code src/main/java}.</p>
     */
    private static String packageDirectory(String originalPath) {
        if (!originalPath.contains("com/") && !originalPath.contains("org/")) {
            return null;
        }
        StringBuilder packagePath   = new StringBuilder();
        boolean       insidePackage = false;
        for (String part : originalPath.split("/")) {
            if (insidePackage || part.equals("com") || part.equals("org")) {
                insidePackage = true;
                packagePath.append(part).append("/");
            }
        }
        if (!insidePackage) {
            return null;
        }
        Path resolved = Paths.get("src/main/java").resolve(packagePath.toString());
        return Files.exists(resolved) && Files.isDirectory(resolved) ? resolved.toString() : null;
    }
}
