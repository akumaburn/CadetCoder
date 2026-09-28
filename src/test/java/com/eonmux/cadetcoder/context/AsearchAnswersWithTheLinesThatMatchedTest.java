package com.eonmux.cadetcoder.context;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.junit.After;
import org.junit.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A search result is the part of the file the search was about.
 *
 * <p><b>The defect</b>: a match was the whole indexed file, cut to its first thousand characters by
 * whoever rendered it. For source that is the package declaration, the imports and the class
 * comment, so every result looked alike and none of them showed the thing that had been searched
 * for. The command printed ten of those; {@code agent} put three of them into every prompt, whole
 * and unbounded, and {@code edit} put ten.</p>
 *
 * <p>These tests hold the replacement to the property that was missing: what comes back is the
 * lines carrying the query's terms, numbered as the file numbers them.</p>
 */
public class AsearchAnswersWithTheLinesThatMatchedTest {

    private final Analyzer analyzer = new StandardAnalyzer();

    @After
    public void closeAnalyzer() {
        analyzer.close();
    }

    private static String lineNumbers(RelevantLines.Selection selection) {
        return selection.lines().stream()
                        .map(line -> String.valueOf(line.number()))
                        .collect(Collectors.joining(","));
    }

    @Test
    public void theMatchingLineIsReturnedRatherThanTheStartOfTheFile() {
        StringBuilder file = new StringBuilder("package com.example;\n\nimport java.util.List;\n\n");
        for (int i = 0; i < 60; i++) {
            file.append("    // filler line ").append(i).append('\n');
        }
        file.append("    long backoff = computeRetryBackoff(attempt);\n");

        RelevantLines.Selection selection =
                RelevantLines.from(file.toString(), Set.of("computeretrybackoff"), analyzer);

        assertThat(selection.lines()).isNotEmpty();
        String text = selection.lines().stream().map(ContextMatch.Line::text)
                               .collect(Collectors.joining("\n"));
        assertThat(text).contains("computeRetryBackoff");
        // The thing the old behaviour always returned, and the thing a reader never wanted.
        assertThat(text).doesNotContain("import java.util.List;");
    }

    @Test
    public void aMatchingLineIsNumberedAsTheFileNumbersIt() {
        String file = "one\ntwo\nthree needle here\nfour\n";

        RelevantLines.Selection selection = RelevantLines.from(file, Set.of("needle"), analyzer);

        ContextMatch.Line matched = selection.lines().stream()
                                             .filter(ContextMatch.Line::matched)
                                             .findFirst().orElseThrow();
        assertThat(matched.number()).isEqualTo(3);
        assertThat(matched.text()).isEqualTo("three needle here");
    }

    @Test
    public void theLinesEitherSideComeWithItSoTheFragmentCanBeRead() {
        String file = "a\nb\nc\nneedle\ne\nf\ng\n";

        RelevantLines.Selection selection = RelevantLines.from(file, Set.of("needle"), analyzer);

        assertThat(lineNumbers(selection)).isEqualTo("2,3,4,5,6");
    }

    @Test
    public void aLineCarryingMoreOfTheQueryIsPreferredToOneCarryingLess() {
        StringBuilder file = new StringBuilder();
        for (int i = 1; i <= 200; i++) {
            file.append("filler ").append(i).append('\n');
        }
        // Only one of these carries both terms; it must survive the per-file bound.
        file.insert(0, "retry only here\n");
        file.append("the retry backoff together\n");

        RelevantLines.Selection selection =
                RelevantLines.from(file.toString(), Set.of("retry", "backoff"), analyzer);

        String text = selection.lines().stream().map(ContextMatch.Line::text)
                               .collect(Collectors.joining("\n"));
        assertThat(text).contains("the retry backoff together");
    }

    @Test
    public void oneFileCannotContributeMoreThanItsShareOfALongResult() {
        StringBuilder file = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            file.append("needle on line ").append(i).append('\n');
        }

        RelevantLines.Selection selection =
                RelevantLines.from(file.toString(), Set.of("needle"), analyzer);

        assertThat(selection.lines()).hasSizeLessThanOrEqualTo(RelevantLines.MAX_LINES_PER_FILE);
        // What was left out is reported rather than silently dropped.
        assertThat(selection.elided()).isGreaterThan(0);
    }

    @Test
    public void aMinifiedLineIsBoundedRatherThanPastedWhole() {
        String huge = "x".repeat(RelevantLines.MAX_LINE_CHARS + 5_000);
        String file = "needle " + huge + "\n";

        RelevantLines.Selection selection = RelevantLines.from(file, Set.of("needle"), analyzer);

        String text = selection.lines().get(0).text();
        assertThat(text.length()).isLessThan(file.length());
        assertThat(text).contains("more characters on this line");
    }

    @Test
    public void aFileWhoseLinesCannotBeLocatedStillShowsSomething() {
        // A match with no term this scan can see -- from the filename field, say. Dropping the file
        // would lose a result Lucene ranked, so its opening is shown instead.
        String file = "first\nsecond\nthird\n";

        RelevantLines.Selection selection = RelevantLines.from(file, Set.of(), analyzer);

        assertThat(selection.lines()).isNotEmpty();
        assertThat(selection.lines().get(0).number()).isEqualTo(1);
        assertThat(selection.lines()).allMatch(line -> !line.matched());
    }

    @Test
    public void aTermInsideALongerWordIsNotAMatch() {
        // What Lucene indexed is whole tokens, so a substring scan would disagree with the hit.
        String file = "int noNeedleHere = 1;\nString needle = \"x\";\n";

        RelevantLines.Selection selection = RelevantLines.from(file, Set.of("needle"), analyzer);

        List<Integer> matched = selection.lines().stream()
                                         .filter(ContextMatch.Line::matched)
                                         .map(ContextMatch.Line::number)
                                         .collect(Collectors.toList());
        assertThat(matched).containsExactly(2);
    }

    @Test
    public void emptyContentIsNotAResult() {
        assertThat(RelevantLines.from("", Set.of("needle"), analyzer).lines()).isEmpty();
        assertThat(RelevantLines.from(null, Set.of("needle"), analyzer).lines()).isEmpty();
    }

    @Test
    public void whatGoesToTheModelCarriesThePathAndTheLineNumbers() {
        ContextMatch match = new ContextMatch(
                "src/main/java/Retry.java", 2.5f,
                List.of(new ContextMatch.Line(41, "long backoff = base * attempt;", true)), 3);

        String prompt = match.forPrompt();

        assertThat(prompt).contains("File: src/main/java/Retry.java");
        assertThat(prompt).contains("41: long backoff = base * attempt;");
        assertThat(prompt).contains("3 further matching lines not shown");
        assertThat(match.fileName()).isEqualTo("Retry.java");
        assertThat(match.matchedLineCount()).isEqualTo(1);
    }
}
