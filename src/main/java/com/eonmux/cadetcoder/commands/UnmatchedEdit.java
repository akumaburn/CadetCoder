package com.eonmux.cadetcoder.commands;

import java.util.ArrayList;
import java.util.List;

/**
 * Why an edit's OLD text is not in the file, said in enough detail to be acted on.
 *
 * <h2>The defect this exists for</h2>
 *
 * <p>An edit that did not match used to report {@code String not found:} and the first fifty
 * characters of the text it had looked for. The model reading that already knows what it looked
 * for. What it does not know is what is actually there, and nothing in the message distinguished
 * "the indentation is one space out" from "that line is gone". So it guessed again, and again: one
 * recorded run spent six consecutive requests re-guessing the same javadoc block, re-reading the
 * file in between, and got it right only by giving up on the block and editing a single line.</p>
 *
 * <h2>What the answer carries instead</h2>
 *
 * <p>A sentence naming how the file differs from what was asked for, and then the file's own lines
 * at the place the edit was aiming at, verbatim. A model given the exact text can copy it; a model
 * given a count of characters can only guess again.</p>
 *
 * <h2>Why the file's text is quoted rather than the difference described</h2>
 *
 * <p>Describing it ("the indentation differs") still leaves the model to reconstruct the line, and
 * reconstructing the line from memory is the thing that failed. The quoted lines are the answer,
 * not a hint towards it.</p>
 */
final class UnmatchedEdit {

    /** Most lines of the file quoted back, however many the edit asked for. */
    private static final int MOST_LINES_QUOTED = 40;

    /** Longest quoted block, in characters, whatever the line count. */
    private static final int MOST_CHARACTERS_QUOTED = 4000;

    private UnmatchedEdit() {
    }

    /**
     * Says why {@code wanted} is not in {@code content}, and what is there instead.
     *
     * @param content what the file holds
     * @param wanted  the OLD text the edit looked for
     * @return one message, ready to be shown and to be read by a model
     */
    static String reasonItDidNotMatch(String content, String wanted) {
        if (content == null || wanted == null || wanted.isEmpty()) {
            return "The text to replace was empty, so there was nothing to look for.";
        }

        List<String> asked = linesOf(wanted);
        String       first = firstMeaningfulLine(asked);
        if (first == null) {
            return "The text to replace is nothing but whitespace, which is never searched for.";
        }

        List<String>  held  = linesOf(content);
        List<Integer> where = whereThatLineIs(held, first);
        String        why   = howTheFileDiffers(content, wanted);

        if (where.isEmpty()) {
            return why + " No line of the file matches even the first line of it, ignoring "
                   + "indentation, so that text is not in this file: it may have been changed "
                   + "already, or it may be in a different file.";
        }

        StringBuilder said = new StringBuilder(why);
        if (where.size() > 1) {
            said.append(" Its first line occurs ").append(where.size())
                .append(" times in the file; the first of them is quoted below.");
        }
        said.append("\nThe file's own lines there, as read shows them: everything after the bar is ")
            .append("the line exactly, to copy into OLD:\n")
            .append(quoted(held, where.get(0), asked.size()));
        return said.toString();
    }

    /**
     * The one sentence that names the difference.
     *
     * <p>Ordered by how cheaply the reader can act on it. Line endings and spacing are single,
     * mechanical corrections; anything else is a line that genuinely says something different, and
     * is left to the quoted text to show.</p>
     *
     * @param content what the file holds
     * @param wanted  the OLD text the edit looked for
     * @return a sentence, ending in a full stop
     */
    private static String howTheFileDiffers(String content, String wanted) {
        if (withUnixEndings(content).contains(withUnixEndings(wanted))) {
            return "The text is in the file, but the line endings differ: one side uses CRLF and "
                   + "the other LF.";
        }
        if (withEachLineStripped(content).contains(withEachLineStripped(wanted))) {
            return "The text is in the file, but the spacing at the start or end of its lines is "
                   + "not what OLD says it is.";
        }
        return "The text to replace is not in the file as it was written.";
    }

    /**
     * The file's lines at one place, bounded, numbered as {@code read} numbers them.
     *
     * <p>This message reaches the reader inside a failure the transcript indents, so a line quoted
     * bare lost the one thing it was quoted for: where its own indentation starts.</p>
     *
     * @param held  every line of the file
     * @param from  the line the quotation starts at, counting from zero
     * @param count how many lines the edit asked for
     * @return the lines, each on its own, ending in a newline
     */
    private static String quoted(List<String> held, int from, int count) {
        StringBuilder block = new StringBuilder();
        int           last  = Math.min(held.size(), from + Math.min(count, MOST_LINES_QUOTED));
        for (int line = from; line < last; line++) {
            if (block.length() + held.get(line).length() > MOST_CHARACTERS_QUOTED) {
                block.append("... the rest is left out; read the file for it\n");
                break;
            }
            block.append(ReadCommand.numberedLine(line + 1, held.get(line)));
        }
        return block.toString();
    }

    /**
     * Where a line appears in the file, compared without its indentation.
     *
     * <p>Indentation is exactly what the reader most often has wrong, so matching on it here would
     * report "not in this file" for the case this method exists to locate.</p>
     *
     * @param held    every line of the file
     * @param wanted  the line to look for, already stripped
     * @return the indices of the lines that match, in order
     */
    private static List<Integer> whereThatLineIs(List<String> held, String wanted) {
        List<Integer> found = new ArrayList<>();
        for (int line = 0; line < held.size(); line++) {
            if (held.get(line).strip().equals(wanted)) {
                found.add(line);
            }
        }
        return found;
    }

    /**
     * @param lines the lines of the text to replace
     * @return the first line with something on it, stripped, or {@code null} when there is none
     */
    private static String firstMeaningfulLine(List<String> lines) {
        for (String line : lines) {
            if (!line.isBlank()) {
                return line.strip();
            }
        }
        return null;
    }

    /** @return the text split into lines, keeping empty ones */
    private static List<String> linesOf(String text) {
        return List.of(text.split("\n", -1));
    }

    /** @return the text with every CRLF turned into a bare newline */
    private static String withUnixEndings(String text) {
        return text.replace("\r\n", "\n");
    }

    /** @return the text with the whitespace at both ends of every line removed */
    private static String withEachLineStripped(String text) {
        StringBuilder stripped = new StringBuilder();
        for (String line : withUnixEndings(text).split("\n", -1)) {
            stripped.append(line.strip()).append('\n');
        }
        return stripped.toString();
    }
}
