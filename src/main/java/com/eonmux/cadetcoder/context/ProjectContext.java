package com.eonmux.cadetcoder.context;

import com.eonmux.cadetcoder.OutputFormatter;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages project context from CADET.md files
 */
public class ProjectContext {
    private static final String         CADET_MD = "CADET.md";

    /** How many directories above the working directory a CADET.md is looked for. */
    private static final int            PARENT_SEARCH_DEPTH = 3;

    private static       ProjectContext instance;

    private       String       projectContext = "";
    private final List<String> contextFiles   = new ArrayList<>();

    private ProjectContext() {
        loadProjectContext();
    }

    /**
     * Loads the project context from the nearest CADET.md, starting at the working directory.
     */
    private void loadProjectContext() {
        Path workingDir = Paths.get(System.getProperty("user.dir"));

        if (loadFrom(workingDir, workingDir)) {
            return;
        }
        Path candidate = workingDir;
        for (int level = 0; level < PARENT_SEARCH_DEPTH; level++) {
            candidate = candidate.getParent();
            if (candidate == null || loadFrom(candidate, workingDir)) {
                return;
            }
        }
    }

    /**
     * Reads the CADET.md in one directory, if there is one.
     *
     * <p>One method for every directory searched, so a parent's CADET.md is the project context
     * rather than a lesser case of it. Held apart, the parent branch lost something at each step: a
     * read failure was passed over in silence while the same failure a directory below printed a
     * warning, and the file's own directives were resolved against the working directory instead of
     * the directory that declares them, so a project opened from a subdirectory silently included
     * nothing.</p>
     *
     * @param directory  the directory to look in
     * @param workingDir the directory that paths are reported relative to
     * @return {@code true} when the context was loaded and the search is over
     */
    private boolean loadFrom(Path directory, Path workingDir) {
        Path cadetFile = directory.resolve(CADET_MD);
        if (!Files.exists(cadetFile)) {
            return false;
        }
        String shown = displayPath(workingDir, cadetFile);
        try {
            projectContext = Files.readString(cadetFile);
        } catch (IOException e) {
            OutputFormatter.printWarning("Failed to read " + shown + ": " + e.getMessage());
            return false;
        }
        contextFiles.add(shown);
        OutputFormatter.printSuccess("Loaded project context from " + shown);
        parseContextFile(projectContext, directory, workingDir);
        return true;
    }

    /**
     * Names a file the way it is most useful to read: relative to the working directory when it
     * lies inside it, and in full when it does not.
     *
     * <p>A relative path only says anything about a file underneath the directory it is relative
     * to. The parent-directory case used to be recorded as the working directory's own name joined
     * to CADET.md, which named the file that had just been found missing rather than the one that
     * was read, and {@code context show} listed it as the source.</p>
     */
    private static String displayPath(Path workingDir, Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        Path base     = workingDir.toAbsolutePath().normalize();
        return absolute.startsWith(base) ? base.relativize(absolute).toString() : absolute.toString();
    }

    /**
     * Records the files a context file asks for with {@code @include} or {@code @file}.
     *
     * @param content    the context file's text
     * @param baseDir    the directory the context file was read from, which relative directives are
     *                   resolved against
     * @param workingDir the directory that paths are reported relative to
     */
    private void parseContextFile(String content, Path baseDir, Path workingDir) {
        for (String rawLine : content.split("\n")) {
            String line = rawLine.trim();

            if (!line.startsWith("@include ") && !line.startsWith("@file ")) {
                continue;
            }
            String fileName = line.substring(line.indexOf(' ') + 1).trim();
            if (fileName.isEmpty()) {
                continue;
            }
            Path filePath = Paths.get(fileName);
            if (!filePath.isAbsolute()) {
                filePath = baseDir.resolve(fileName);
            }

            if (Files.exists(filePath)) {
                contextFiles.add(displayPath(workingDir, filePath));
            } else {
                // Reported rather than dropped: the directive is the user asking for a file to be
                // part of what the model is told, and an omission made in silence is
                // indistinguishable from the file having been included.
                OutputFormatter.printWarning(
                        CADET_MD + " includes " + fileName + ", which does not exist");
            }
        }
    }

    public static synchronized ProjectContext getInstance() {
        if (instance == null) {
            instance = new ProjectContext();
        }
        return instance;
    }

    /**
     * Create a default CADET.md file.
     *
     * @return {@code true} if a new file was written, {@code false} if a CADET.md already existed
     * and was therefore left untouched.
     */
    public static boolean createDefaultContextFile() throws IOException {
        Path cadetFile = Paths.get(System.getProperty("user.dir")).resolve(CADET_MD);

        if (Files.exists(cadetFile)) {
            OutputFormatter.printWarning(CADET_MD + " already exists");
            return false;
        }

        String defaultContent = """
                                # Project Context for CadetCoder
                                
                                ## Project Overview
                                [Describe your project here]
                                
                                ## Key Architecture Decisions
                                - [List important architectural choices]
                                
                                ## Coding Standards
                                - [Define coding standards and conventions]
                                
                                ## Important Files
                                @include src/main/java/Main.java
                                @include README.md
                                
                                ## Development Guidelines
                                - [Add development guidelines]
                                
                                ## Testing Strategy
                                - [Describe testing approach]
                                
                                ## Notes for AI Assistant
                                - [Add any specific instructions for the AI]
                                """;

        Files.writeString(cadetFile, defaultContent);
        OutputFormatter.printSuccess("Created " + CADET_MD + " with default template");
        return true;
    }

    /**
     * Get the project context
     */
    public String getProjectContext() {
        return projectContext;
    }

    /**
     * Get list of context files
     */
    public List<String> getContextFiles() {
        return new ArrayList<>(contextFiles);
    }

    /**
     * Check if project context is available
     */
    public boolean hasProjectContext() {
        return !projectContext.isEmpty();
    }

    /**
     * Reload project context.
     *
     * <p>Synchronized because it mutates the shared in-memory state of this singleton; callers on
     * different threads (e.g. the CLI vs. prompt building) must not observe a half-cleared context.
     */
    public synchronized void reload() {
        projectContext = "";
        contextFiles.clear();
        loadProjectContext();
    }

    /**
     * Forget the currently loaded project context without re-reading any file from disk.
     *
     * <p>After this call {@link #hasProjectContext()} returns {@code false} and
     * {@link #getProjectContext()} returns an empty string until the next {@link #reload()}.
     * Synchronized for the same reason as {@link #reload()}: it mutates shared singleton state.
     */
    public synchronized void clear() {
        projectContext = "";
        contextFiles.clear();
    }
}