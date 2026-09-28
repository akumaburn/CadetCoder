package com.eonmux.cadetcoder.harness.cadet;

import com.eonmux.cadetcoder.harness.Json;
import com.eonmux.cadetcoder.util.ProjectTreeWalk;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What a workspace looks like from outside, small enough to put in an observation.
 *
 * <h2>Why the listing is a courtesy and the digest is the fact</h2>
 *
 * <p>A repository has more files than an observation can carry, so the listing stops. If that were
 * all an observation said about the workspace, everything past the end of it would be invisible: a
 * step that rewrote a file there would produce the identical observation, and a model predicting
 * that nothing changed would be confirmed by a world that had changed. The digest covers every file
 * the walk found, listed or not, so the observation is complete even when the listing is not.</p>
 *
 * <h2>Why size and modification time rather than contents</h2>
 *
 * <p>Hashing every byte of a project on every step costs more than the step does. Size and
 * modification time never miss a real edit. They can miss a rewrite that keeps the same length
 * within one filesystem clock tick, which costs an observation the model has to look at again --
 * the direction that wastes a step rather than the one that confirms a wrong prediction.</p>
 *
 * @param paths  the workspace's files, relative to its root and separated by {@code /}, sorted,
 *               capped at {@link #PATHS_SHOWN}
 * @param total  how many files the walk found, listed or not
 * @param digest what every one of those files was, when the walk ran
 */
public record WorkspaceTree(List<String> paths, int total, String digest) {

    /** How many paths an observation carries before it starts saying "and more". */
    public static final int PATHS_SHOWN = 400;

    /** Hex characters of the workspace digest; enough that two real trees will not collide. */
    private static final int DIGEST_LENGTH = 16;

    /** The size and time recorded for a file whose attributes could not be read. */
    private static final long UNREADABLE = -1L;

    public WorkspaceTree {
        paths = List.copyOf(paths);
    }

    /**
     * Reads a workspace as it is now.
     *
     * <p>Build output, vendored dependencies and hidden directories are skipped, by the same rule
     * every other project walk in this codebase uses -- an agent that watched {@code target/} would
     * see a thousand files change every time it compiled and learn nothing from any of them.</p>
     *
     * @param root the workspace root
     * @return what is in it
     * @throws IllegalArgumentException if the root is not a directory
     * @throws UncheckedIOException     if the root cannot be walked at all, which is a workspace
     *                                  that has gone away underneath the run rather than a file
     *                                  that could not be read
     */
    public static WorkspaceTree of(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            throw new IllegalArgumentException("there is no workspace at " + root);
        }
        Map<String, String> found = walked(root);
        List<String>        shown = new ArrayList<>(found.keySet());
        if (shown.size() > PATHS_SHOWN) {
            shown = new ArrayList<>(shown.subList(0, PATHS_SHOWN));
        }
        return new WorkspaceTree(shown, found.size(),
                                 Json.digestOfText(String.join("\n", found.values()),
                                                   DIGEST_LENGTH));
    }

    /** Whether there are more files than this listing shows. */
    public boolean truncated() {
        return total > paths.size();
    }

    /**
     * Every file under the root, in path order, each with what it was at the time.
     *
     * <p>A file whose attributes cannot be read is recorded rather than dropped. Dropping it would
     * make an unreadable file indistinguishable from a deleted one, and a permission change would
     * read as a deletion that never happened.</p>
     */
    private static Map<String, String> walked(Path root) {
        Map<String, String> found = new TreeMap<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {

                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes about) {
                    return ProjectTreeWalk.isPruned(root, directory) ? FileVisitResult.SKIP_SUBTREE
                                                                     : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes about) {
                    record(found, root, file, about.size(), about.lastModifiedTime().toMillis());
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException failure) {
                    record(found, root, file, UNREADABLE, UNREADABLE);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException failure) {
            throw new UncheckedIOException("the workspace at " + root + " could not be read",
                                           failure);
        }
        return found;
    }

    private static void record(Map<String, String> found, Path root, Path file, long size,
                               long modified) {
        String path = relative(root, file);
        found.put(path, path + " " + size + " " + modified);
    }

    /**
     * A file's path relative to the workspace root, written the same way on every platform.
     *
     * <p>Observations are hashed into the ledger and compared against predictions, so a path that
     * reads {@code src\main} on one machine and {@code src/main} on another is two different worlds
     * to a model.</p>
     */
    private static String relative(Path root, Path file) {
        StringBuilder path = new StringBuilder();
        for (Path part : root.relativize(file)) {
            if (path.length() > 0) {
                path.append('/');
            }
            path.append(part);
        }
        return path.toString();
    }
}
