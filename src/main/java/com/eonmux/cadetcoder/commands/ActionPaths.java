package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.OutputFormatter;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * Repairing the paths a model names in an action.
 *
 * <h2>The two halves</h2>
 *
 * <p>Every action that touches a file teaches this conversation where a file really is, and every
 * action that names a file it has not seen may need telling. So each dispatched action is read for
 * paths worth remembering, and each proposed action is read for paths that are plainly stand-ins and
 * substituted from what was remembered.</p>
 *
 * <h2>Why this is not the same rule as {@code util.PlaceholderPath}</h2>
 *
 * <p>That one answers a different question for a different caller: whether the DIRECTORY a person
 * typed at {@code ls} or {@code grep} is a stand-in, where a bare {@code src} counts and the answer
 * is a directory this project has. This one is about a FILE argument in a model's action, where a
 * bare {@code Foo.java} counts and the answer comes from what this conversation has already seen.
 * Both are deliberate, and neither should be widened into the other.</p>
 */
final class ActionPaths {

    private final LoggingCommandSupport log;
    private final ProjectFileSearch     search;
    private final FilenameSimilarity    names;

    /**
     * @param log where to record what was substituted, tracked, or could not be found
     */
    ActionPaths(LoggingCommandSupport log) {
        this.log    = log;
        this.search = new ProjectFileSearch(log);
        this.names  = new FilenameSimilarity(log);
    }

    /** The one command whose path argument is a LIST of paths rather than a single one. */
    private static final String TAKES_A_LIST_OF_PATHS = "multiread";

    /**
     * Replaces stand-in paths in an action's arguments with real ones.
     *
     * <h2>Why only the path argument</h2>
     *
     * <p>This walked every argument of every action, and "looks like a stand-in path" is a question
     * worth asking only of something that is supposed to BE a path. {@code grep}'s first argument is
     * a search pattern and {@code bash}'s is an entire command line, so
     * {@code bash "ls src/main/java/com/example/"} was rewritten to {@code bash "example"} and that
     * is what ran. For a shell command the rewrite also happened before the shell policy saw it, so
     * what was screened and executed was not what the model proposed -- the thing
     * {@code ActionRun.screenedShellCommand} deliberately refuses to do.</p>
     *
     * <p>{@link PathArgument} is the table that already answers which argument is a path, and it is
     * what the path checks in {@code ActionRun} consult; asking it here is what keeps the argument
     * that gets repaired and the argument that gets checked the same argument.</p>
     *
     * @param action  the action about to run; its arguments are rewritten in place
     * @param context the conversation, which knows what has been found so far
     */
    void substituteFoundPaths(ChatCommand.AIAction action, ChatContext context) {
        int first = PathArgument.indexFor(action.command, log);
        if (first == PathArgument.NONE) {
            return;
        }
        int last = TAKES_A_LIST_OF_PATHS.equals(action.command)
                   ? action.arguments.length - 1
                   : first;

        Map<String, String> foundFiles = context.getFoundFiles();
        for (int i = first; i <= last && i < action.arguments.length; i++) {
            substituteOne(action, i, foundFiles);
        }
    }

    /**
     * Repairs one argument, if it is a stand-in and something better is known.
     *
     * @param action     the action being repaired
     * @param index      which argument to look at
     * @param foundFiles what this conversation has found so far
     */
    private void substituteOne(ChatCommand.AIAction action, int index,
                               Map<String, String> foundFiles) {
        String argument = action.arguments[index];
        // An option among a list of paths is not one of the paths.
        if (argument == null || argument.startsWith("-") || !looksLikeAStandIn(argument)) {
            return;
        }
        String replacement = bestReplacementFor(argument, foundFiles);
        if (replacement != null && !replacement.equals(argument)) {
            OutputFormatter.printInfo("Substituting '" + argument + "' with '" + replacement + "'");
            action.arguments[index] = replacement;
        }
    }

    /**
     * Remembers the files an action just proved exist, so a later action can be pointed at them.
     *
     * @param action  the action that ran
     * @param context the conversation to record them in
     */
    void trackFoundFiles(ChatCommand.AIAction action, ChatContext context) {
        if (action.arguments.length == 0) {
            return;
        }
        Map<String, String> foundFiles = context.getFoundFiles();

        try {
            switch (action.command) {
                case "glob":
                    trackGlobMatch(action.arguments[0], foundFiles);
                    break;
                case "read":
                    trackIfPresent(action.arguments[0], foundFiles, "read");
                    break;
                case "grep":
                case "find":
                    // The pattern is first; the file, when there is one, is second.
                    if (action.arguments.length >= 2) {
                        trackIfPresent(action.arguments[1], foundFiles, "grep/find");
                    }
                    break;
                case "ls":
                    trackDirectoryContents(action.arguments[0], foundFiles);
                    break;
                case "edit":
                case "write":
                    // Named rather than checked: the file is about to exist even if it does not yet.
                    trackByName(action.arguments[0], foundFiles);
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            // Tracking is an optimisation; a command must not fail because its bookkeeping did.
            log.logWarning("File Tracking", "Error tracking files: " + e.getMessage());
        }
    }

    /** The file a glob pattern is looking for, if the project has one by that name. */
    private void trackGlobMatch(String pattern, Map<String, String> foundFiles) {
        String fileName = pattern.replaceAll("\\*\\*/", "").replaceAll("\\*", "");
        if (fileName.isEmpty()) {
            return;
        }
        String actualPath = search.locate(fileName);
        if (actualPath != null) {
            foundFiles.put(fileName, actualPath);
            log.logDebug("File Tracking", "Tracked file from glob: " + fileName + " -> " + actualPath);
        }
    }

    /** A path an action named, recorded only once it is known to be there. */
    private void trackIfPresent(String filePath, Map<String, String> foundFiles, String from) {
        String fileName = names.baseFilename(filePath);
        if (fileName == null || fileName.isEmpty()) {
            return;
        }
        if (Files.exists(Paths.get(filePath))) {
            foundFiles.put(fileName, filePath);
            log.logDebug("File Tracking",
                    "Tracked file from " + from + ": " + fileName + " -> " + filePath);
        }
    }

    /** A path an action is about to write, recorded whether or not it exists yet. */
    private void trackByName(String filePath, Map<String, String> foundFiles) {
        String fileName = names.baseFilename(filePath);
        if (fileName != null && !fileName.isEmpty()) {
            foundFiles.put(fileName, filePath);
            log.logDebug("File Tracking",
                    "Tracked file from edit/write: " + fileName + " -> " + filePath);
        }
    }

    /** Everything a listed directory holds, since the listing proved all of it exists. */
    private void trackDirectoryContents(String dirPath, Map<String, String> foundFiles)
            throws java.io.IOException {
        Path directory = Paths.get(dirPath == null || dirPath.isEmpty() ? "." : dirPath);
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path entry : stream) {
                if (Files.isRegularFile(entry)) {
                    String fileName = entry.getFileName().toString();
                    foundFiles.put(fileName, entry.toString());
                    log.logDebug("File Tracking",
                            "Tracked file from ls: " + fileName + " -> " + entry);
                }
            }
        }
    }

    /**
     * Whether a path is a stand-in rather than somewhere real.
     *
     * <p>These are the shapes a model produces when it is describing where a file would be rather
     * than saying where this one is: an example package, a {@code path/to/} lead-in, the word
     * placeholder, or a bare filename with no directory at all.</p>
     *
     * @param path the argument to judge
     * @return {@code true} when it should be replaced if a replacement can be found
     */
    boolean looksLikeAStandIn(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        return path.contains("/example/")
               || path.startsWith("path/to/")
               || path.contains("/path/to/")
               || path.startsWith("src/main/java/com/example/")
               || path.startsWith("found_path/")
               || (path.contains("found_path") && path.endsWith(".java"))
               || path.contains("placeholder")
               || path.contains("example.")
               // A filename with an extension and no directory: the model knows the name, not where.
               || (path.contains(".java") && !path.contains("/") && !path.contains("\\"))
               || path.contains("your/project/")
               || path.contains("/your/")
               || path.startsWith("project/")
               || path.contains("/project/")
               || path.contains("/sample/")
               || path.contains("/demo/");
    }

    /**
     * The most likely real path for a stand-in.
     *
     * <p>Ordered by how much is known: a path that turns out to exist is kept, then a
     * case-insensitive match for it, then what this conversation has already found, and only then a
     * guess from the project's shape.</p>
     *
     * @param placeholder what the model asked for
     * @param foundFiles  what has been found in this conversation so far
     * @return the replacement, or {@code placeholder} itself when nothing better is known
     */
    String bestReplacementFor(String placeholder, Map<String, String> foundFiles) {
        if (placeholder == null || placeholder.isEmpty()) {
            log.logWarning("Path Replacement", "Empty placeholder path provided");
            return null;
        }

        try {
            Path   asked    = Paths.get(placeholder);
            String fileName = asked.getFileName().toString();

            log.logDebug("Path Replacement",
                    "Looking for replacement for: " + placeholder + " (filename: " + fileName + ")");

            if (Files.exists(asked)) {
                log.logDebug("Path Replacement",
                        "Path already exists, no replacement needed: " + placeholder);
                return placeholder;
            }

            String sameButForCase = matchIgnoringCase(asked);
            if (sameButForCase != null) {
                log.logDebug("Path Replacement", "Found case-insensitive match: " + sameButForCase);
                return sameButForCase;
            }

            String tracked = fromTrackedFiles(fileName, foundFiles);
            if (tracked != null) {
                return tracked;
            }

            String guessed = search.likelyPath(fileName);
            if (guessed != null) {
                log.logDebug("Path Replacement", "Found heuristic path: " + guessed);
                return guessed;
            }

            log.logWarning("Path Replacement", "Could not find replacement for: " + placeholder);
            return placeholder;
        } catch (Exception e) {
            log.logWarning("Path Replacement",
                    "Error finding replacement for " + placeholder + ": " + e.getMessage());
            return placeholder;
        }
    }

    /**
     * The same path with the casing the filesystem actually uses.
     *
     * <p>Walked one segment at a time from the working directory, because a case-insensitive
     * comparison of the whole string cannot tell which segment was wrong.</p>
     *
     * @return the real path, or {@code null} when any segment has no match
     */
    private String matchIgnoringCase(Path asked) {
        try {
            Path here     = Paths.get(".");
            Path relative = here.relativize(asked.normalize().toAbsolutePath());
            if (relative.getNameCount() == 0) {
                return null;
            }

            Path found = here;
            for (String segment : relative.toString().split("[/\\\\]")) {
                Path match = childNamed(found, segment);
                if (match == null) {
                    return null;
                }
                found = match;
            }
            return found.toString();
        } catch (Exception e) {
            log.logDebug("Path Replacement", "Error in case-insensitive matching: " + e.getMessage());
            return null;
        }
    }

    /** The entry of {@code directory} whose name matches {@code segment} but for its case. */
    private static Path childNamed(Path directory, String segment) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path entry : stream) {
                if (entry.getFileName().toString().equalsIgnoreCase(segment)) {
                    return entry;
                }
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    /** What this conversation has already found, by exact name, then by tail, then by likeness. */
    private String fromTrackedFiles(String fileName, Map<String, String> foundFiles) {
        if (foundFiles == null || foundFiles.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, String> entry : foundFiles.entrySet()) {
            if (entry.getKey().equals(fileName)) {
                log.logDebug("Path Replacement", "Found exact match in tracked files: " + entry.getValue());
                return entry.getValue();
            }
        }
        for (Map.Entry<String, String> entry : foundFiles.entrySet()) {
            if (entry.getValue().endsWith(fileName)) {
                log.logDebug("Path Replacement", "Found path ending match in tracked files: " + entry.getValue());
                return entry.getValue();
            }
        }
        for (Map.Entry<String, String> entry : foundFiles.entrySet()) {
            if (names.areSimilar(entry.getKey(), fileName)) {
                log.logDebug("Path Replacement", "Found similar filename match in tracked files: " + entry.getValue());
                return entry.getValue();
            }
        }
        return null;
    }
}
