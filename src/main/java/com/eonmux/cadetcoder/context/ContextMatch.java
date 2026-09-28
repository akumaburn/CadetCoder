package com.eonmux.cadetcoder.context;

import java.util.List;
import java.util.Objects;

/**
 * One file the index matched, and the lines in it that earned the match.
 *
 * <h2>Why a search result is not a string</h2>
 *
 * <p>A match used to be handed back as {@code "File: " + name + "\n" + the whole file}. Everything
 * downstream then had to take that shape apart again, and each place took it apart differently:
 * {@code search} printed the first thousand characters of it, which for source is the licence
 * header and the import block; {@code edit} recovered a target file by counting six characters in
 * past {@code "File: "}, which yields a bare name and not a path, so two files of that name are one
 * file to it. Nothing recovered which lines had actually matched, because by then that had been
 * thrown away -- the one thing a search is for.</p>
 *
 * <p>So the match carries what was found rather than a rendering of it: where the file is, how
 * strongly it matched, and the lines that matched with the numbers an editor would show. Each
 * consumer renders that for its own audience, and none of them has to parse anything.</p>
 *
 * <h2>Why the path is project-relative</h2>
 *
 * <p>The index stores an absolute path because that is what identifies a document. What a reader
 * needs is a path they can type, and what a model needs is one it can pass to {@code read}; both
 * are the relative one. The absolute path is kept out of results for the same reason it is kept out
 * of {@code grep}'s: it is the developer's home directory, repeated on every line.</p>
 *
 * @param path    where the file is, relative to the project when it lies inside it
 * @param score   Lucene's relevance score, used only for ordering
 * @param lines   the lines worth showing, in file order, matching and context alike
 * @param elided  how many further matching lines there were that {@code lines} does not carry
 */
public record ContextMatch(String path, float score, List<Line> lines, int elided) {

    /**
     * One line of a matched file, numbered as an editor numbers it.
     *
     * @param number  the line's 1-based number in the file
     * @param text    the line itself, without its terminator
     * @param matched whether this line contains a term from the query, as opposed to surrounding it
     */
    public record Line(int number, String text, boolean matched) { }

    /**
     * @param path   where the file is
     * @param score  the relevance score
     * @param lines  the lines to show
     * @param elided how many matching lines are not among them
     */
    public ContextMatch {
        Objects.requireNonNull(path, "path");
        lines = List.copyOf(Objects.requireNonNullElse(lines, List.of()));
    }

    /** @return the name of the file, without its directories */
    public String fileName() {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /** @return how many of the shown lines carry a term from the query */
    public long matchedLineCount() {
        return lines.stream().filter(Line::matched).count();
    }

    /**
     * This match as plain text for a prompt.
     *
     * <p>Line numbers are kept, because they are what lets a model ask for the right part of the
     * file afterwards -- and because {@code read} prints them too, so a line cited from a search
     * result and the same line read directly are recognisably the same line.</p>
     *
     * <p>The leading {@code File: } is retained: it is what {@code edit} recognises, and a shape
     * already in use is worth more than a tidier one. What follows it is now a path rather than a
     * bare name, which is what that caller needed all along.</p>
     *
     * @return the match rendered for the model channel
     */
    public String forPrompt() {
        StringBuilder text = new StringBuilder("File: ").append(path).append('\n');
        for (Line line : lines) {
            text.append(String.format("%6d: %s%n", line.number(), line.text()));
        }
        if (elided > 0) {
            text.append("        ... ").append(elided)
                .append(elided == 1 ? " further matching line" : " further matching lines")
                .append(" not shown\n");
        }
        return text.toString();
    }
}
