package com.eonmux.cadetcoder.ai;

import com.eonmux.cadetcoder.config.ConfigManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code context.maxLinesPerFile} decides how much of a file a prompt carries.
 *
 * <h2>The defect</h2>
 *
 * <p>The setting existed, had a default of 500, could be set in {@code config.json} and by
 * {@code cadet config context maxLinesPerFile}, and no production code read it. A file went into a
 * prompt whole however long it was, and the only thing that ever removed any of it was the
 * token-budget trim, which cuts the end of the assembled prompt at a character count -- so a very
 * long file did not lose its own tail, it pushed everything after it out of the prompt instead, and
 * nothing said so.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>That the allowance is honoured where a file enters a prompt, that it is counted in lines, that
 * the cut is announced with the file's name and the number of lines left out -- a model that cannot
 * tell it was shown part of a file will write an edit against a class it believes it has read in
 * full -- and that a non-positive allowance means the whole file.</p>
 */
public class AFileIsShownToTheModelOnlyAsFarAsContextAllowsTest {

    private int originalAllowance;

    @Before
    public void rememberTheConfiguredAllowance() {
        originalAllowance = ConfigManager.getInstance().getConfig().getContext().getMaxLinesPerFile();
    }

    @After
    public void restoreTheConfiguredAllowance() {
        ConfigManager.getInstance().getConfig().getContext().setMaxLinesPerFile(originalAllowance);
    }

    private static void allow(int lines) {
        ConfigManager.getInstance().getConfig().getContext().setMaxLinesPerFile(lines);
    }

    /** A file of {@code count} lines, each naming its own number. */
    private static String numberedLines(int count) {
        StringBuilder content = new StringBuilder();
        for (int line = 1; line <= count; line++) {
            content.append("line ").append(line).append('\n');
        }
        return content.toString();
    }

    private static String promptShowing(String filename, String content) {
        return new PromptBuilder("edit", "system", "reminder")
                .addCodeFile(filename, content)
                .buildUserPrompt("make the change");
    }

    @Test
    public void aFileShorterThanTheAllowanceIsShownWhole() {
        allow(50);

        String prompt = promptShowing("Small.java", numberedLines(20));

        assertThat(prompt).contains("line 1\n").contains("line 20");
        assertThat(prompt).doesNotContain("further lines");
    }

    @Test
    public void aFileAsLongAsTheAllowanceIsShownWhole() {
        allow(20);

        String prompt = promptShowing("Exact.java", numberedLines(20));

        assertThat(prompt).contains("line 20");
        assertThat(prompt).doesNotContain("further lines");
    }

    @Test
    public void aFileLongerThanTheAllowanceIsCutAtTheAllowance() {
        allow(10);

        String prompt = promptShowing("Big.java", numberedLines(25));

        assertThat(prompt).contains("line 10");
        assertThat(prompt)
                .as("the eleventh line is past the allowance and must not be in the prompt")
                .doesNotContain("line 11");
    }

    @Test
    public void theCutSaysHowManyLinesAreMissingAndWhichFileTheyBelongTo() {
        allow(10);

        String prompt = promptShowing("Big.java", numberedLines(25));

        assertThat(prompt)
                .as("a model that cannot tell it was shown part of a file will edit as if it saw it all")
                .contains("[15 further lines of Big.java are not shown.]");
    }

    @Test
    public void theAllowanceIsCountedInLinesAndNotInCharacters() {
        allow(3);
        String longLines = "a".repeat(5000) + "\n" + "b".repeat(5000) + "\n";

        String prompt = promptShowing("Wide.java", longLines);

        assertThat(prompt)
                .as("two very long lines are two lines")
                .doesNotContain("further lines");
    }

    @Test
    public void anAllowanceOfNoneAtAllMeansTheWholeFile() {
        allow(0);

        String prompt = promptShowing("Whole.java", numberedLines(400));

        assertThat(prompt).contains("line 400");
        assertThat(prompt).doesNotContain("further lines");
    }

    @Test
    public void everyFileGetsTheAllowanceRatherThanSharingOne() {
        allow(5);

        String prompt = new PromptBuilder("edit", "system", "reminder")
                .addCodeFile("First.java", numberedLines(9))
                .addCodeFile("Second.java", numberedLines(8))
                .buildUserPrompt("make the change");

        assertThat(prompt).contains("[4 further lines of First.java are not shown.]");
        assertThat(prompt).contains("[3 further lines of Second.java are not shown.]");
    }

    @Test
    public void aFileWhoseLastLineHasNoNewlineIsCountedAsThatLineAndNoMore() {
        allow(3);

        String prompt = promptShowing("Terse.java", "one\ntwo\nthree");

        assertThat(prompt).contains("three");
        assertThat(prompt)
                .as("three lines and no trailing newline is three lines, not four")
                .doesNotContain("further lines");
    }
}
