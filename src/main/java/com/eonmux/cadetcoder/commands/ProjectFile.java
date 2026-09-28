package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.security.SecurityValidator;
import com.eonmux.cadetcoder.security.WritePathPolicy;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Turning what a caller typed into a file this tool is allowed to touch.
 *
 * <h2>Why the rule is asked for once rather than copied</h2>
 *
 * <p>Every command that opens a file asks the same two questions: where does this path point, and
 * may this run go there. {@code read} answers them at length and correctly, and a command that
 * answered them again in its own words would be a second rule that can drift from the first, which
 * is a way to reach what {@code read} refuses.</p>
 *
 * <p>{@link SecurityValidator#isFileAccessAllowed} is the rule. It covers path traversal, the
 * project boundary, system paths and the credential denylist together.</p>
 */
final class ProjectFile {

    private ProjectFile() {
    }

    /**
     * Where a path points, from wherever this run started.
     *
     * @param given the path as the caller wrote it
     * @return the absolute, normalised path, or {@code null} when nothing was named
     */
    static Path at(String given) {
        if (given == null || given.isBlank()) {
            return null;
        }
        Path named = Paths.get(given.trim());
        return named.isAbsolute()
               ? named.normalize()
               : Paths.get(System.getProperty("user.dir")).resolve(named).normalize();
    }

    /**
     * Why this run may not touch a file.
     *
     * @param file      the resolved path
     * @param validator the rule to ask
     * @return a sentence saying why, or {@code null} when it may
     */
    static String reasonNotToTouch(Path file, SecurityValidator validator) {
        if (file == null) {
            return "No file was named.";
        }
        if (!validator.isFileAccessAllowed(file.toString())) {
            return "Access to " + file + " is not allowed by the current security settings.";
        }
        return null;
    }

    /**
     * Why this run may not create, change or remove a file.
     *
     * <p>Stricter than {@link #reasonNotToTouch}: {@link WritePathPolicy} keeps writes inside the
     * working directory even when {@code security.allowOutsideProject} lets a run read beyond it.
     * {@code write} and {@code edit} already apply that rule, and a command that changes files
     * without it would be a way round them.</p>
     *
     * @param file      the resolved path
     * @param validator the rule to ask first
     * @return a sentence saying why, or {@code null} when it may
     */
    static String reasonNotToWrite(Path file, SecurityValidator validator) {
        String why = reasonNotToTouch(file, validator);
        if (why != null) {
            return why;
        }
        WritePathPolicy.Decision decision = WritePathPolicy.decide(file);
        return decision.isAllowed() ? null : WritePathPolicy.reasonFor(decision, file);
    }
}
