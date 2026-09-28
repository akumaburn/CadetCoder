package com.eonmux.cadetcoder.commands;

import java.nio.file.Path;
import java.nio.file.PathMatcher;

/**
 * Where a search looks, and what it looks at once it is there.
 *
 * <p>Which directories are never descended into is not here: it is one rule for every walk in this
 * tool, read from {@code indexing.excludePatterns} by
 * {@link com.eonmux.cadetcoder.util.ProjectTreeWalk}. Carried as a field, it was a copy taken when
 * the search started and compared for equality, so a configured pattern like {@code build-.*}
 * excluded a directory from the index and from nothing else.</p>
 *
 * <p>The {@code --include} glob is carried both compiled and as the caller wrote it. A
 * {@link PathMatcher} answers only about whole paths, and pruning has to ask something a matcher
 * cannot be asked: whether the caller named a particular directory rather than reaching it through
 * a wildcard.</p>
 *
 * @param start       the search root, absolute and normalised
 * @param maxDepth    how deep below the root to go
 * @param includeGlob the {@code --include} glob as written, or {@code null} for everything
 * @param include     the compiled {@code --include} glob, or {@code null} for everything
 * @param exclude     the compiled {@code --exclude} glob, or {@code null} for nothing
 */
record GrepScope(Path start, int maxDepth, String includeGlob, PathMatcher include,
                 PathMatcher exclude) {
}
