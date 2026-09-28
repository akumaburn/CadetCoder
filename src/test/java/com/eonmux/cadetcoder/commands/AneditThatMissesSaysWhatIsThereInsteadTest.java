package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.test.TestOutputCapture;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import com.eonmux.cadetcoder.test.ProjectFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An edit whose OLD text is not in the file is told what the file says instead.
 *
 * <h2>The defect</h2>
 *
 * <p>A failed edit reported {@code String not found:} and the first fifty characters of the text it
 * had looked for -- which is the one thing the reader already knew. Nothing in it separated "the
 * indentation is one space out" from "that line is gone", so a model guessed again, and again. One
 * recorded run spent six consecutive requests re-guessing the same javadoc block, re-reading the
 * file between the guesses, and succeeded only by abandoning the block for a single line.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>That a miss quotes the file's own lines at the place the edit was aiming at; that a difference
 * of indentation is named as one rather than reported as absence; that text which is nowhere in the
 * file is said to be nowhere in the file; that a first line occurring several times says how many;
 * and that the reason reaches the model through the command and not only through the class that
 * states it.</p>
 */
public class AneditThatMissesSaysWhatIsThereInsteadTest {

    @Rule
    public TemporaryFolder folder = new ProjectFolder();

    private TestOutputCapture output;

    @Before
    public void startWithNothingRead() {
        ReadBeforeEdit.forgetEverything();
        output = new TestOutputCapture();
        output.startCapture();
    }

    @After
    public void releaseOutput() {
        output.stopCapture();
        ReadBeforeEdit.forgetEverything();
    }

    /** The whole point: the reader is handed the text to copy, not a description of it. */
    @Test
    public void amissQuotesTheFilesOwnLinesAtThatPlace() {
        String held = "class Thing {\n    /**\n     * The shipped value: 30.\n     */\n}\n";

        String why = UnmatchedEdit.reasonItDidNotMatch(held,
                "     /**\n      * The shipped value: 30.\n");

        assertThat(why).contains("    /**");
        assertThat(why).contains("     * The shipped value: 30.");
    }

    @Test
    public void adifferenceOfIndentationIsNamedAsOne() {
        String held = "class Thing {\n    int value = 30;\n}\n";

        String why = UnmatchedEdit.reasonItDidNotMatch(held, "        int value = 30;");

        assertThat(why).contains("spacing at the start or end");
        assertThat(why)
                .as("named, and then shown, because naming it still leaves the line to rebuild")
                .contains("    int value = 30;");
    }

    @Test
    public void adifferenceOfLineEndingsIsNamedAsOne() {
        String held = "alpha\r\nbeta\r\ngamma\r\n";

        String why = UnmatchedEdit.reasonItDidNotMatch(held, "alpha\nbeta");

        assertThat(why).contains("line endings");
    }

    /**
     * The commonest real cause, once a file has to be read before it can be changed: the text was
     * already changed by an earlier step, or belongs to another file.
     */
    @Test
    public void textThatIsNowhereInTheFileIsSaidToBeNowhere() {
        String held = "alpha\nbeta\ngamma\n";

        String why = UnmatchedEdit.reasonItDidNotMatch(held, "delta\nepsilon");

        assertThat(why).contains("not in this file");
        assertThat(why).doesNotContain("alpha");
    }

    @Test
    public void afirstLineThatOccursSeveralTimesSaysHowMany() {
        String held = "    return null;\nint a;\n    return null;\nint b;\n";

        String why = UnmatchedEdit.reasonItDidNotMatch(held, "return null;\nint c;");

        assertThat(why).contains("2 times");
    }

    @Test
    public void thereIsNothingToSayAboutAnEmptyOldText() {
        assertThat(UnmatchedEdit.reasonItDidNotMatch("alpha\n", "")).contains("empty");
        assertThat(UnmatchedEdit.reasonItDidNotMatch("alpha\n", "   \n  ")).contains("whitespace");
    }

    /**
     * The reason has to reach the model, which reads the command's output and not this class.
     *
     * <p>Run the way a model runs it, because that is the run this is for: a person at a terminal
     * is asked to confirm the edit first, and a model never is.</p>
     */
    @Test
    public void themodelIsToldTheReasonByTheCommandItself() throws IOException {
        Path file = folder.newFile("subject.java").toPath();
        Files.writeString(file, "class Thing {\n    int value = 30;\n}\n");
        ReadBeforeEdit.sawContents(file);

        int exitCode = ModelDispatch.run("multiedit", () -> new MultiEditCommand().execute(
                new String[] {
                        file.toString(),
                        "EDIT_START\nOLD:         int value = 30;\n"
                        + "NEW:         int value = 45;\nREPLACE_ALL: false\nEDIT_END"}));

        assertThat(exitCode).isNotZero();
        assertThat(output.getAllOutput()).contains("spacing at the start or end");
        assertThat(output.getAllOutput())
                .as("quoted as read shows it, so its indentation is not lost in the transcript's")
                .contains("     2\u2502    int value = 30;");
        assertThat(Files.readString(file))
                .as("nothing was changed")
                .isEqualTo("class Thing {\n    int value = 30;\n}\n");
    }
}
