package com.eonmux.cadetcoder.util;

import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.ui.UnifiedOutput;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Utility class for resolving file paths with intelligent searching capabilities.
 * When a file is not found at the specified path, it searches for similar matches
 * in the project directory.
 */
public class FilePathResolver {

    private static final int MAX_SEARCH_DEPTH = 10;

    /**
     * Resolves a file path, searching for alternatives if the exact path is not found.
     *
     * @param inputPath   The input path (can be absolute, relative, or just a filename)
     * @param baseDir     The base directory to search from (usually the current working directory)
     * @param allowCreate Whether to allow file creation if not found
     * @return A ResolvedPath object containing the resolved path and search information
     */
    public static ResolvedPath resolve(String inputPath, Path baseDir, boolean allowCreate) {
        if (inputPath == null || inputPath.trim().isEmpty()) {
            return new ResolvedPath(null, false, "No file path provided");
        }

        Path normalizedPath = normalizePath(inputPath, baseDir);

        // Check if the exact path exists
        if (Files.exists(normalizedPath)) {
            return new ResolvedPath(normalizedPath, true, null);
        }

        // If allowing creation and the path has a parent directory that exists, return the path
        if (allowCreate && normalizedPath.getParent() != null && Files.exists(normalizedPath.getParent())) {
            return new ResolvedPath(normalizedPath, false, null);
        }

        // Search for similar files
        List<Path> matches = searchForFile(inputPath, baseDir);

        if (matches.isEmpty()) {
            String message = String.format("File not found: %s", inputPath);
            if (!allowCreate) {
                message += "\nNo similar files found in the project directory.";
            }
            return new ResolvedPath(normalizedPath, false, message);
        }

        // If exactly one match, use it
        if (matches.size() == 1) {
            Path   match   = matches.get(0);
            String message = String.format("File not found at '%s', using: %s", inputPath, match);
            OutputFormatter.printInfo(message);
            return new ResolvedPath(match, true, null);
        }

        // Multiple matches found. Every one is listed and every one can be chosen: a list cut at
        // ten left the file the user meant unchoosable whenever it came eleventh.
        StringBuilder message = new StringBuilder();
        message.append(String.format("File not found: %s\n", inputPath));
        message.append("Did you mean one of these?\n");
        for (int i = 0; i < matches.size(); i++) {
            message.append(String.format("  %d. %s\n", i + 1, matches.get(i)));
        }

        return new ResolvedPath(normalizedPath, false, message.toString(), matches);
    }

    /**
     * Normalizes a file path, resolving it against the base directory if relative.
     */
    private static Path normalizePath(String filePath, Path baseDir) {
        Path path = Paths.get(filePath);

        if (!path.isAbsolute()) {
            path = baseDir.resolve(filePath);
        }

        return path.normalize();
    }

    /**
     * Searches for a file in the project directory.
     *
     * @param searchTerm The file name or partial path to search for
     * @param baseDir    The base directory to search from
     * @return List of matching paths, sorted by relevance
     */
    private static List<Path> searchForFile(String searchTerm, Path baseDir) {
        List<Path> matches = new ArrayList<>();

        // Extract just the filename from the search term
        String fileName = Paths.get(searchTerm).getFileName().toString();

        try {
            // How deep to go is the walk's own business rather than a counter kept alongside it.
            // Counted here, the increment in preVisitDirectory had no matching decrement on either
            // way out of it: a subtree that is skipped is never handed to postVisitDirectory, so
            // every pruned and every over-deep directory left the count one higher for good. Past
            // ten of them -- which is a handful of build and hidden directories in any real project
            // -- everything after was skipped, and a file sitting in plain sight was reported as
            // not found.
            Files.walkFileTree(baseDir, EnumSet.noneOf(FileVisitOption.class), MAX_SEARCH_DEPTH,
                               new FileVisitor<Path>() {

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    // Skip hidden directories and build output -- but never the directory the
                    // search started from, whose name may itself be "." or hidden.
                    if (ProjectTreeWalk.isPruned(baseDir, dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }

                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String currentFileName = file.getFileName().toString();

                    // Exact filename match
                    if (currentFileName.equals(fileName)) {
                        matches.add(file);
                    }
                    // Case-insensitive match
                    else if (currentFileName.equalsIgnoreCase(fileName)) {
                        matches.add(file);
                    }
                    // Partial match
                    else if (currentFileName.toLowerCase().contains(fileName.toLowerCase())) {
                        matches.add(file);
                    }

                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            OutputFormatter.printWarning("Error while searching for files: " + e.getMessage());
        }

        // Sort matches by relevance
        return sortByRelevance(matches, searchTerm, baseDir);
    }

    /**
     * Sorts file matches by relevance based on various criteria.
     */
    private static List<Path> sortByRelevance(List<Path> matches, String searchTerm, Path baseDir) {
        String searchFileName = Paths.get(searchTerm).getFileName().toString().toLowerCase();

        return matches.stream()
                      .sorted((p1, p2) -> {
                          String name1 = p1.getFileName().toString().toLowerCase();
                          String name2 = p2.getFileName().toString().toLowerCase();

                          // Exact matches first
                          boolean exact1 = name1.equals(searchFileName);
                          boolean exact2 = name2.equals(searchFileName);
                          if (exact1 != exact2) {
                              return exact1 ? -1 : 1;
                          }

                          // Then by path depth (files closer to base directory first)
                          int depth1 = baseDir.relativize(p1).getNameCount();
                          int depth2 = baseDir.relativize(p2).getNameCount();
                          if (depth1 != depth2) {
                              return Integer.compare(depth1, depth2);
                          }

                          // Then alphabetically
                          return p1.compareTo(p2);
                      })
                      .collect(Collectors.toList());
    }

    /**
     * Result of a path resolution attempt.
     */
    public static class ResolvedPath {
        private final Path       path;
        private final boolean    exists;
        private final String     errorMessage;
        private final List<Path> alternatives;

        public ResolvedPath(Path path, boolean exists, String errorMessage) {
            this(path, exists, errorMessage, null);
        }

        public ResolvedPath(Path path, boolean exists, String errorMessage, List<Path> alternatives) {
            this.path         = path;
            this.exists       = exists;
            this.errorMessage = errorMessage;
            this.alternatives = alternatives;
        }

        public Path getPath() {
            return path;
        }

        public boolean exists() {
            return exists;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public List<Path> getAlternatives() {
            return alternatives;
        }

        /**
         * Prompts the user to select from alternatives using the TUI-aware
         * {@link com.eonmux.cadetcoder.ui.OutputRouter}.
         *
         * <p>This is the preferred entry point for interactive selection. It works correctly
         * both inside the immediate-mode TUI (where {@code System.in} is owned by JLine and
         * must not be read directly) and in plain CLI mode (where {@code OutputRouter}
         * transparently falls back to {@link System#console()}). The user's response is never
         * echoed to the command history.</p>
         *
         * @return Selected path or {@code null} if no valid selection
         */
        public Path selectFromAlternatives() {
            if (!hasAlternatives()) {
                return null;
            }
            String input = com.eonmux.cadetcoder.ui.OutputRouter.getInstance()
                                                                 .getUserInput("Please select a file (enter number): ");
            return parseSelection(input);
        }

        /**
         * Prompts the user to select from alternatives if available, reading from the supplied
         * {@link java.util.Scanner}.
         *
         * <p>Retained for direct/non-TUI callers (and tests). New code should prefer the no-arg
         * {@link #selectFromAlternatives()} which is TUI-aware.</p>
         *
         * @param scanner Scanner to read user input
         * @return Selected path or null if no valid selection
         */
        public Path selectFromAlternatives(java.util.Scanner scanner) {
            if (!hasAlternatives()) {
                return null;
            }

            UnifiedOutput.print("Please select a file (enter number): ");
            try {
                return parseSelection(scanner.nextLine());
            } catch (java.util.NoSuchElementException e) {
                // No input available
                return null;
            }
        }

        /**
         * Parses a user-entered selection (1-based index) into the corresponding alternative,
         * returning {@code null} for blank, non-numeric, or out-of-range input.
         */
        private Path parseSelection(String input) {
            if (input == null) {
                return null;
            }
            try {
                int selection = Integer.parseInt(input.trim());
                if (selection > 0 && selection <= alternatives.size()) {
                    return alternatives.get(selection - 1);
                }
            } catch (NumberFormatException e) {
                // Invalid input
            }
            return null;
        }

        public boolean hasAlternatives() {
            return alternatives != null && !alternatives.isEmpty();
        }
    }
}