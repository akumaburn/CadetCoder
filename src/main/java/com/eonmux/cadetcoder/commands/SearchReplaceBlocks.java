package com.eonmux.cadetcoder.commands;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@code SEARCH}/{@code REPLACE} block format, read and applied.
 *
 * <h2>Why a malformed block writes nothing at all</h2>
 *
 * <p>Both halves of this refuse rather than guess. A body whose markers are present but whose
 * blocks do not close is unparseable, and a {@code SEARCH} that is not in the file describes an
 * edit to something that is not there -- in both cases the model was describing a file other than
 * the one on disk. Applying what could be matched would leave a file half-edited in a way nothing
 * downstream can detect, so the answer is {@code null} and the file is left alone.</p>
 *
 * <h2>Why the divider is seven characters and not five</h2>
 *
 * <p>A Markdown setext heading is underlined with {@code =====}. Inside {@code SEARCH} content that
 * is ordinary text, and treating it as the divider splits one block into two halves that mean
 * nothing. Seven is the length of the canonical divider, and long enough that a heading underline
 * of that width is not something that appears by accident.</p>
 */
final class SearchReplaceBlocks {

    /** How many marker characters a fence line needs before it counts as one. */
    private static final int FENCE_LENGTH = 5;

    /** How many {@code =} a line needs before it counts as the divider. */
    private static final int DIVIDER_LENGTH = 7;

    private SearchReplaceBlocks() {}

    /**
     * Whether a fenced block body carries {@code SEARCH}/{@code REPLACE} markers -- the format the
     * edit system prompt instructs the model to emit.
     *
     * <p>Their presence is what switches an edit from a full-file overwrite to a targeted
     * replacement against the existing content.</p>
     *
     * @param body the fenced block's contents
     * @return whether it is written in this format
     */
    static boolean present(String body) {
        return body != null && body.contains("<<<<<<<")
               && body.contains("=======") && body.contains(">>>>>>>");
    }

    /**
     * The {@code [search, replace]} pairs in a fenced block body, in order.
     *
     * <p>Each block is {@code <<<<<<< SEARCH \n <search lines> \n ======= \n <replace lines> \n
     * >>>>>>> REPLACE}.</p>
     *
     * @param body the fenced block's contents
     * @return the pairs, or {@code null} when markers are present but a block does not close
     */
    static List<String[]> parse(String body) {
        List<String[]> blocks = new ArrayList<>();
        // Split on CRLF or LF so a CRLF response body still matches an LF-on-disk file.
        String[]       lines  = body.split("\r?\n", -1);
        int            i      = 0;

        while (i < lines.length) {
            if (!isFence(lines[i], '<', "SEARCH")) {
                i++;
                continue;
            }
            i++; // consume the SEARCH marker line

            StringBuilder search = new StringBuilder();
            i = readUntil(lines, i, search, line -> isDivider(line));
            if (i < 0) {
                return null;
            }
            StringBuilder replace = new StringBuilder();
            i = readUntil(lines, i, replace, line -> isFence(line, '>', "REPLACE"));
            if (i < 0) {
                return null;
            }
            blocks.add(new String[] {withoutTrailingNewline(search.toString()),
                                     withoutTrailingNewline(replace.toString())});
        }
        return blocks.isEmpty() ? null : blocks;
    }

    /**
     * Reads lines into {@code into} until {@code terminator} says to stop.
     *
     * @param lines      every line of the body
     * @param from       where to start reading
     * @param into       where the lines go
     * @param terminator what ends this half of a block
     * @return the index after the terminator, or {@code -1} when the body ended without one
     */
    private static int readUntil(String[] lines, int from, StringBuilder into,
                                 java.util.function.Predicate<String> terminator) {
        for (int i = from; i < lines.length; i++) {
            if (terminator.test(lines[i])) {
                return i + 1;
            }
            into.append(lines[i]).append('\n');
        }
        return -1;
    }

    /**
     * The file with every block applied, in order.
     *
     * <p>An empty {@code SEARCH} replaces the whole file, which is how a new file and a full rewrite
     * are expressed; a non-empty one replaces its first occurrence.</p>
     *
     * @param existing what is on disk now
     * @param blocks   the pairs to apply
     * @param error    where the reason goes when one of them does not match
     * @return the new content, or {@code null} when a block did not match and nothing was written
     */
    static String applyTo(String existing, List<String[]> blocks, StringBuilder error) {
        String working = existing;
        for (int b = 0; b < blocks.size(); b++) {
            String search  = blocks.get(b)[0];
            String replace = blocks.get(b)[1];
            if (search.isEmpty()) {
                working = replace;
                continue;
            }
            int at = working.indexOf(search);
            if (at < 0) {
                error.append("SEARCH block #").append(b + 1).append(" did not match the file");
                return null;
            }
            working = working.substring(0, at) + replace + working.substring(at + search.length());
        }
        return working;
    }

    /** A trimmed line of at least {@value #FENCE_LENGTH} of {@code marker} then {@code keyword}. */
    private static boolean isFence(String line, char marker, String keyword) {
        String trimmed = line.trim();
        int    count   = 0;
        while (count < trimmed.length() && trimmed.charAt(count) == marker) {
            count++;
        }
        return count >= FENCE_LENGTH && trimmed.substring(count).trim().equalsIgnoreCase(keyword);
    }

    /** A trimmed line of at least {@value #DIVIDER_LENGTH} {@code '='} and nothing else. */
    private static boolean isDivider(String line) {
        String trimmed = line.trim();
        if (trimmed.length() < DIVIDER_LENGTH) {
            return false;
        }
        for (int k = 0; k < trimmed.length(); k++) {
            if (trimmed.charAt(k) != '=') {
                return false;
            }
        }
        return true;
    }

    private static String withoutTrailingNewline(String value) {
        return value.endsWith("\n") ? value.substring(0, value.length() - 1) : value;
    }
}
