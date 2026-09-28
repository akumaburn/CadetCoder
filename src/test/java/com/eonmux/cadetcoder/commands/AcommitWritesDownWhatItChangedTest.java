package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.context.ContextMatch;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code edit} commits on the user's behalf says what it did.
 *
 * <p><b>The defect</b>: {@code EditCommand.generateChangeSummary()} returned the constant
 * "AI-assisted changes applied to the file." -- for every edit, of every file, forever. It is the
 * only thing substituted into the user's configured {@code git.commitMessageTemplate}, so the
 * history this tool writes said the same nothing on every line of {@code git log}. A commit message
 * is the one part of a change that cannot be recovered by reading the code afterwards, and this one
 * was permanent. The sibling {@code multiedit} has always named what it did.</p>
 */
public class AcommitWritesDownWhatItChangedTest {

    private TestOutputCapture outputCapture;

    @Before
    public void setUp() {
        outputCapture = new TestOutputCapture();
        outputCapture.startCapture();
    }

    @After
    public void tearDown() {
        outputCapture.stopCapture();
    }

    @Test
    public void oneFileIsNamed() {
        assertThat(EditCommand.changeSummaryOf(List.of("src/main/java/Retry.java")))
                .isEqualTo("updated src/main/java/Retry.java");
    }

    @Test
    public void aFewFilesAreAllNamed() {
        assertThat(EditCommand.changeSummaryOf(List.of("A.java", "B.java", "C.java")))
                .isEqualTo("updated A.java, B.java, C.java");
    }

    @Test
    public void manyFilesAreCountedRatherThanListed() {
        // A commit subject running to thousands of characters is unreadable in every tool that
        // shows one, and a single reply can rewrite a great many files.
        String summary = EditCommand.changeSummaryOf(
                List.of("A.java", "B.java", "C.java", "D.java", "E.java"));

        assertThat(summary).startsWith("updated A.java, B.java, C.java");
        assertThat(summary).endsWith("and 2 more");
        assertThat(summary).doesNotContain("D.java");
    }

    @Test
    public void nothingChangedStillRendersIntoTheTemplate() {
        // Not reached from the auto-commit, which does not run when nothing was written, but the
        // template has to render into something if it ever is.
        assertThat(EditCommand.changeSummaryOf(List.of())).isEqualTo("no files changed");
        assertThat(EditCommand.changeSummaryOf(null)).isEqualTo("no files changed");
    }

    @Test
    public void aSearchResultShowsWhereItSkippedPartOfTheFile() {
        // Two fragments of one file are not adjacent, and the line numbers are the reader's only
        // signal of that -- so the gap is marked rather than left to be inferred.
        SearchReport.show(List.of(new ContextMatch("src/Retry.java", 1.0f, List.of(
                new ContextMatch.Line(10, "first fragment", true),
                new ContextMatch.Line(11, "still the first", false),
                new ContextMatch.Line(90, "a second fragment", true)), 4)));

        String output = outputCapture.getAllOutput();
        assertThat(output).contains("src/Retry.java");
        assertThat(output).contains("10: first fragment");
        assertThat(output).contains("90: a second fragment");
        assertThat(output).contains("...");
        assertThat(output).contains("4 further matching lines not shown");
        assertThat(output).contains("Found 2 matching lines in 1 file");
    }

    @Test
    public void oneMatchIsCountedInTheSingular() {
        SearchReport.show(List.of(new ContextMatch("src/One.java", 1.0f,
                List.of(new ContextMatch.Line(1, "only", true)), 1)));

        String output = outputCapture.getAllOutput();
        assertThat(output).contains("Found 1 matching line in 1 file");
        assertThat(output).contains("1 further matching line not shown");
    }
}
