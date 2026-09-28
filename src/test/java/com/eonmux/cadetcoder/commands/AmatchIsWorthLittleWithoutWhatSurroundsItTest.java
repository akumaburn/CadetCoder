package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.test.TestOutputCapture;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import com.eonmux.cadetcoder.test.ProjectFolder;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Lines around a match, and where in a line the match sits.
 *
 * <h2>What was missing</h2>
 *
 * <p>{@code grep} returned matching lines and nothing else. A model that found the line it wanted
 * then spent a second step on {@code read} to see what the line was inside of, because one line of
 * a method body says nothing about the method. Every other grep has {@code -A}, {@code -B} and
 * {@code -C} for this reason.</p>
 *
 * <p>The column was already computed. {@code GrepMatch} records the start and end of every match on
 * a line while the matcher is still there, and {@link GrepReport} used the offsets to colour the
 * line and then dropped them. Nothing the caller received said where in the line the match was, so
 * an edit built from a search result had to find the text a second time.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>The shape of the output, because that is the whole interface. A context line is numbered with
 * a dash, a matching line with a colon, and a gap between groups is marked. These are GNU grep's
 * own conventions, so anything that already reads grep output reads this.</p>
 */
public class AmatchIsWorthLittleWithoutWhatSurroundsItTest {

    @Rule
    public TemporaryFolder project = new ProjectFolder();

    private TestOutputCapture output;

    @Before
    public void startCapture() {
        output = new TestOutputCapture();
        output.startCapture();
    }

    @After
    public void stopCapture() {
        output.stopCapture();
    }

    private void write(String name, String contents) throws IOException {
        Path file = project.getRoot().toPath().resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, contents);
    }

    /** Runs one search over the temporary project and returns what the user would see. */
    private String grep(String... options) {
        try (MockedStatic<ConfigManager> configured = mockStatic(ConfigManager.class)) {
            ConfigManager manager = mock(ConfigManager.class);
            Configuration config  = new Configuration();
            config.getUi().setColorEnabled(false);
            when(manager.getConfig()).thenReturn(config);
            configured.when(ConfigManager::getInstance).thenReturn(manager);

            String[] args = new String[options.length + 2];
            System.arraycopy(options, 0, args, 0, options.length);
            args[options.length]     = "-p";
            args[options.length + 1] = project.getRoot().getAbsolutePath();
            new GrepCommand().execute(args);
        }
        return output.getAllOutput();
    }

    /** Eight numbered lines with the word "needle" on line 4. */
    private void eightLines() throws IOException {
        write("subject.txt", "one\ntwo\nthree\nthe needle is here\nfive\nsix\nseven\neight\n");
    }

    @Test
    public void afterAskedForShowsTheLinesThatFollow() throws Exception {
        eightLines();

        String shown = grep("needle", "-A", "2");

        assertThat(shown).contains("    4: the needle is here");
        assertThat(shown).contains("    5- five");
        assertThat(shown).contains("    6- six");
        assertThat(shown).doesNotContain("seven");
    }

    @Test
    public void beforeAskedForShowsTheLinesThatPrecede() throws Exception {
        eightLines();

        String shown = grep("needle", "-B", "2");

        assertThat(shown).contains("    2- two");
        assertThat(shown).contains("    3- three");
        assertThat(shown).contains("    4: the needle is here");
        assertThat(shown).doesNotContain("one");
        assertThat(shown).doesNotContain("five");
    }

    @Test
    public void contextAskedForShowsBothSides() throws Exception {
        eightLines();

        String shown = grep("needle", "-C", "1");

        assertThat(shown).contains("    3- three");
        assertThat(shown).contains("    4: the needle is here");
        assertThat(shown).contains("    5- five");
        assertThat(shown).doesNotContain("two");
        assertThat(shown).doesNotContain("six");
    }

    @Test
    public void thereIsNoContextUnlessItIsAskedFor() throws Exception {
        eightLines();

        String shown = grep("needle");

        assertThat(shown).contains("    4: the needle is here");
        assertThat(shown).doesNotContain("three");
        assertThat(shown).doesNotContain("five");
    }

    @Test
    public void agapBetweenTwoGroupsIsMarked() throws Exception {
        write("subject.txt", "needle one\nb\nc\nd\ne\nf\nneedle two\n");

        String shown = grep("needle", "-C", "1");

        assertThat(shown).contains("--");
        assertThat(shown).contains("    1: needle one");
        assertThat(shown).contains("    2- b");
        assertThat(shown).contains("    6- f");
        assertThat(shown).contains("    7: needle two");
        assertThat(shown)
                .as("lines 3 to 5 are too far from either match to be shown")
                .doesNotContain("    3-")
                .doesNotContain("    4-")
                .doesNotContain("    5-");
    }

    @Test
    public void twoMatchesCloseTogetherRunIntoOneGroup() throws Exception {
        write("subject.txt", "a\nneedle one\nb\nneedle two\nc\n");

        String shown = grep("needle", "-C", "1");

        assertThat(shown).doesNotContain("--");
        assertThat(shown).contains("    3- b");
    }

    @Test
    public void nolineIsShownTwiceWhenTheGroupsOverlap() throws Exception {
        write("subject.txt", "a\nneedle one\nneedle two\nb\n");

        String shown = grep("needle", "-C", "2");
        int    onceOnly = shown.split("needle one", -1).length - 1;

        assertThat(onceOnly).isEqualTo(1);
    }

    @Test
    public void thecolumnOfTheFirstMatchIsReportedWhenAskedFor() throws Exception {
        write("subject.txt", "the needle is here\n");

        String shown = grep("needle", "--column");

        assertThat(shown)
                .as("column 5, counted from one, is where 'needle' starts")
                .contains("    1:5: the needle is here");
    }

    @Test
    public void thecolumnIsNotReportedUnlessItIsAskedFor() throws Exception {
        write("subject.txt", "the needle is here\n");

        assertThat(grep("needle")).contains("    1: the needle is here");
    }

    @Test
    public void acontextLineCarriesNoColumnBecauseItHasNoMatch() throws Exception {
        write("subject.txt", "before\nthe needle is here\n");

        String shown = grep("needle", "--column", "-B", "1");

        assertThat(shown).contains("    1- before");
        assertThat(shown).contains("    2:5: the needle is here");
    }

    /**
     * An inverted match is still a match for the purpose of showing what is around it.
     *
     * <p>Context was gated on whether the run would highlight within a line, which is false for
     * {@code -c}, for {@code -l} and also for {@code -v}. The first two are right -- neither shows a
     * line, so a neighbour kept for one would be read, held and thrown away. {@code -v} shows lines
     * like any other run, and it is the shape most in need of the surroundings: the interesting
     * thing about a line that does NOT match is usually the lines that do.</p>
     */
    @Test
    public void contextIsShownForAnInvertedMatchToo() throws Exception {
        write("subject.txt", "needle a\nneedle b\nplain c\nneedle d\nneedle e\n");

        String shown = grep("needle", "-v", "-C", "1");

        assertThat(shown).contains("    3: plain c");
        assertThat(shown)
                .as("-A, -B and -C were accepted and then silently discarded under -v")
                .contains("    2- needle b")
                .contains("    4- needle d");
        assertThat(shown).doesNotContain("    1-").doesNotContain("    5-");
    }

    @Test
    public void contextIsIgnoredByTheShapesThatShowNoLines() throws Exception {
        eightLines();

        assertThat(grep("needle", "-C", "2", "--count")).contains("subject.txt:1");
        assertThat(grep("needle", "-C", "2", "--files-with-matches")).contains("subject.txt");
    }

    @Test
    public void thesummaryCountsMatchesRatherThanLinesShown() throws Exception {
        eightLines();

        assertThat(grep("needle", "-C", "2"))
                .as("context lines are shown but are not matches")
                .contains("Found 1 match in 1 file");
    }
}
