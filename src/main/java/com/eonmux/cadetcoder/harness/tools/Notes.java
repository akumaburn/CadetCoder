package com.eonmux.cadetcoder.harness.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * The agent's own working notes, which are the only thing it writes that nothing checks.
 *
 * <h2>Why notes are not beliefs</h2>
 *
 * <p>A belief carries evidence, can be refuted and is kept apart from the ones that have been. A
 * note is whatever the agent wants to remember and answers to nobody, which makes it the right place
 * for a plan of attack and the wrong place for a claim about the world. They are a file rather than
 * a field because the point of them is to survive the compaction that throws the conversation away.</p>
 */
public final class Notes {

    /** How much of the end an agent gets when it does not say. */
    public static final int STANDARD_TAIL = 6_000;

    private final Path path;

    /** @param path where the notes are kept */
    public Notes(Path path) {
        this.path = path;
    }

    /** Where the notes are kept. */
    public Path path() {
        return path;
    }

    /**
     * Adds a line.
     *
     * @param text what to remember
     * @throws IllegalArgumentException if the note says nothing
     * @throws ToolException            if the notes cannot be written
     */
    public void append(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("a note has to say something");
        }
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, text.strip() + System.lineSeparator(), StandardCharsets.UTF_8,
                              StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException failure) {
            throw new ToolException("the notes at " + path + " cannot be written to", failure);
        }
    }

    /**
     * The end of the notes.
     *
     * @param characters how much of the end to read
     * @return that much of the end, or everything when there is less
     * @throws ToolException if the notes cannot be read
     */
    public String tail(int characters) {
        String all = all();
        int    from = Math.max(0, all.length() - Math.max(0, characters));
        return from == 0 ? all : all.substring(from);
    }

    /** Everything written so far. */
    public String all() {
        if (!Files.exists(path)) {
            return "";
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new ToolException("the notes at " + path + " cannot be read", failure);
        }
    }

    /** How much has been written, in characters. */
    public int size() {
        return all().length();
    }
}
