package com.eonmux.cadetcoder.context;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Which lines of a matched file the search was actually about.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Lucene says which files match and how strongly; it does not say where in them. Nothing asked
 * it, so a result was the whole file, and the whole file was then cut to its first thousand
 * characters on the way out. For source that is the package declaration, the imports and the
 * class comment -- the part of a file that is the same in every file -- so a search for
 * "where the retry backoff is computed" returned ten import blocks, and the agent that was told to
 * use {@code search} when it did not know the exact string got nothing it could act on.</p>
 *
 * <h2>Why the index's own analyzer decides what a term is</h2>
 *
 * <p>A line matches when it carries a term the query carries, and "a term" has to mean here what it
 * meant when the file was indexed, or the lines shown are not the lines that matched. So the
 * content is tokenised with the same {@link Analyzer} the index was built with rather than scanned
 * for substrings: a substring scan disagrees with Lucene at both ends -- it finds
 * {@code retryBackoff} inside {@code noRetryBackoffHere}, and it misses everything the analyzer
 * folds, such as case.</p>
 *
 * <p>Tokenising also gives each term's character offset, which is what makes a line number
 * obtainable at all. The content is tokenised once and offsets are resolved against a table of
 * line starts, so the cost is one pass over a file rather than one pass per line.</p>
 *
 * <h2>Why context lines are included</h2>
 *
 * <p>A matching line alone frequently cannot be read: {@code return backoff;} says nothing without
 * the signature above it. A couple of lines either side is the least that makes a fragment
 * self-explanatory, and is what every familiar tool shows.</p>
 */
final class RelevantLines {

    /** Lines kept either side of a matching line, so a fragment can be read on its own. */
    static final int CONTEXT_LINES = 2;

    /**
     * The most lines one file contributes.
     *
     * <p>A bound is needed because these results go into a prompt as well as onto a screen, and a
     * file can match on hundreds of lines. Twenty-four is about a screenful: enough for several
     * separate fragments with their context, and small enough that the ten files
     * {@code context.maxFiles} allows still leave room for the request itself.</p>
     */
    static final int MAX_LINES_PER_FILE = 24;

    /**
     * Lines shown for a file that matched without any of its lines appearing to.
     *
     * <p>Possible when the match came from the filename field, or from a query shape carrying no
     * term this scan can see. Showing the opening of the file is a poor answer but an honest one;
     * showing nothing would drop a file Lucene ranked.</p>
     */
    static final int FALLBACK_LINES = 6;

    /**
     * The most characters one line contributes.
     *
     * <p>A line is normally its own bound -- source is written to be read -- but a minified bundle,
     * a lock file or a data dump is one line of hundreds of thousands of characters, and the index
     * holds whatever was in the project. Bounding the line is what bounding the result comes to
     * once the result is lines: {@code search} used to cut each file to its first thousand
     * characters, which bounded nothing that mattered and discarded everything that did.</p>
     */
    static final int MAX_LINE_CHARS = 500;

    private RelevantLines() {
    }

    /** A line as it should be shown: itself, unless it is long enough to be machine-written. */
    private static String bound(String line) {
        if (line.length() <= MAX_LINE_CHARS) {
            return line;
        }
        return line.substring(0, MAX_LINE_CHARS)
               + " … [" + (line.length() - MAX_LINE_CHARS) + " more characters on this line]";
    }

    /**
     * The lines of {@code content} worth showing for a query carrying {@code terms}.
     *
     * @param content  the file's text
     * @param terms    the query's terms, as the index's analyzer produced them
     * @param analyzer the analyzer the index was built with
     * @return the lines to show in file order, and how many matching lines had to be left out
     */
    static Selection from(String content, Set<String> terms, Analyzer analyzer) {
        if (content == null || content.isEmpty()) {
            return new Selection(List.of(), 0);
        }
        String[] lines  = content.split("\n", -1);
        int[]    starts = lineStartOffsets(lines);

        // line number (1-based) -> the distinct query terms found on it. Sorted so the fragments
        // come out in file order without a second sort, and so context runs merge by walking it.
        TreeMap<Integer, Set<String>> hits = new TreeMap<>();
        if (terms != null && !terms.isEmpty()) {
            for (int[] hit : termOffsets(content, terms, analyzer)) {
                int line = lineOf(starts, hit[0]);
                hits.computeIfAbsent(line, l -> new LinkedHashSet<>()).add(String.valueOf(hit[1]));
            }
        }

        if (hits.isEmpty()) {
            return new Selection(opening(lines), 0);
        }

        // The strongest lines first, so that what survives the bound is what matched best; ties go
        // to the earlier line, because a file is read downwards and an arbitrary tiebreak would
        // make the same search answer differently on different runs.
        List<Integer> ranked = new ArrayList<>(hits.keySet());
        ranked.sort(Comparator.<Integer, Integer>comparing(line -> -hits.get(line).size())
                              .thenComparing(Comparator.naturalOrder()));

        Set<Integer> shown = new TreeSet<>();
        int          taken = 0;
        for (Integer seed : ranked) {
            Set<Integer> fragment = fragmentAround(seed, lines.length, shown);
            if (taken > 0 && taken + fragment.size() > MAX_LINES_PER_FILE) {
                continue;
            }
            shown.addAll(fragment);
            taken += fragment.size();
            if (taken >= MAX_LINES_PER_FILE) {
                break;
            }
        }

        List<ContextMatch.Line> selected = new ArrayList<>();
        for (Integer number : shown) {
            selected.add(new ContextMatch.Line(number, bound(lines[number - 1]), hits.containsKey(number)));
        }
        int elided = (int) hits.keySet().stream().filter(line -> !shown.contains(line)).count();
        return new Selection(selected, elided);
    }

    /** The lines a fragment around {@code seed} would add, those already shown excluded. */
    private static Set<Integer> fragmentAround(int seed, int lineCount, Set<Integer> alreadyShown) {
        Set<Integer> fragment = new TreeSet<>();
        int          first    = Math.max(1, seed - CONTEXT_LINES);
        int          last     = Math.min(lineCount, seed + CONTEXT_LINES);
        for (int line = first; line <= last; line++) {
            if (!alreadyShown.contains(line)) {
                fragment.add(line);
            }
        }
        return fragment;
    }

    /** The opening of a file, for a match none of whose lines can be located. */
    private static List<ContextMatch.Line> opening(String[] lines) {
        List<ContextMatch.Line> selected = new ArrayList<>();
        for (int number = 1; number <= Math.min(FALLBACK_LINES, lines.length); number++) {
            selected.add(new ContextMatch.Line(number, bound(lines[number - 1]), false));
        }
        return selected;
    }

    /**
     * Every position in {@code content} where one of {@code terms} occurs.
     *
     * @return one {@code {startOffset, termIndex}} pair per occurrence; termIndex only distinguishes
     *         terms from each other, so that a line's distinct-term count can be taken
     */
    private static List<int[]> termOffsets(String content, Set<String> terms, Analyzer analyzer) {
        List<String>  ordered = new ArrayList<>(terms);
        List<int[]>   found   = new ArrayList<>();
        try (TokenStream stream = analyzer.tokenStream("content", content)) {
            CharTermAttribute term   = stream.addAttribute(CharTermAttribute.class);
            OffsetAttribute   offset = stream.addAttribute(OffsetAttribute.class);
            stream.reset();
            while (stream.incrementToken()) {
                int index = ordered.indexOf(term.toString());
                if (index >= 0) {
                    found.add(new int[] {offset.startOffset(), index});
                }
            }
            stream.end();
        } catch (IOException e) {
            // Analysis reads from a string, so this cannot arise from I/O; a file whose lines
            // cannot be located is still a file worth reporting, so the opening is shown instead.
            return List.of();
        }
        return found;
    }

    /** The character offset at which each line begins, indexed from zero. */
    private static int[] lineStartOffsets(String[] lines) {
        int[] starts = new int[lines.length];
        int   offset = 0;
        for (int i = 0; i < lines.length; i++) {
            starts[i] = offset;
            offset += lines[i].length() + 1; // the "\n" that split() removed
        }
        return starts;
    }

    /** The 1-based line holding a character offset. */
    private static int lineOf(int[] starts, int offset) {
        int found = Arrays.binarySearch(starts, offset);
        // Not on a line start: binarySearch reports where it would go, so the line before that.
        return found >= 0 ? found + 1 : Math.max(1, -found - 1);
    }

    /**
     * The lines chosen for one file.
     *
     * @param lines  the lines to show, in file order
     * @param elided how many matching lines are not among them
     */
    record Selection(List<ContextMatch.Line> lines, int elided) { }
}
