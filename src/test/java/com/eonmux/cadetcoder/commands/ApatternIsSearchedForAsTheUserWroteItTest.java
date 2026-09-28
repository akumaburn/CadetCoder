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
 * The expression that reaches the matcher is the expression the caller typed.
 *
 * <h2>What was being rewritten</h2>
 *
 * <p>Three separate places edited the pattern on its way in, each of them silently.</p>
 *
 * <p>{@code -w} and {@code -x} wrapped the expression by concatenation, so {@code \b} + the
 * pattern + {@code \b} turned {@code cat|dog} into {@code \bcat|dog\b}. Alternation binds more
 * loosely than a boundary assertion, so that is "a word-initial cat" OR "a word-final dog", and
 * {@code catalog} matched a whole-word search for {@code cat|dog}.</p>
 *
 * <p>{@link GrepPattern} stripped a leading and trailing delimiter whenever it found one, which is
 * a rule about the shape of the string rather than about what the caller meant by it: {@code /tmp/}
 * became {@code tmp} and matched every mention of a temporary file, {@code "[^"]*"} became
 * {@code [^"]*} and matched every line in the project, and an unconditional
 * {@code replace("\\\"", "\"")} rewrote a backslash followed by a quote into a bare quote wherever
 * it appeared.</p>
 *
 * <p>A pattern part that was only whitespace was dropped before the parts were joined, so the run
 * of spaces in {@code grep foo " " bar} came back as one.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>Both directions. Unwrapping still happens where the wrapper cannot be anything else -- that is
 * what it is for, and a model quotes a pattern the way whatever it was trained on quoted one -- and
 * is refused where the same characters could be part of a deliberate expression. When the two
 * readings cannot be told apart, the pattern is searched for as written, because a search that
 * finds nothing is recoverable and a search that quietly answers a different question is not.</p>
 */
public class ApatternIsSearchedForAsTheUserWroteItTest {

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
    private String grep(String... arguments) {
        try (MockedStatic<ConfigManager> configured = mockStatic(ConfigManager.class)) {
            ConfigManager manager = mock(ConfigManager.class);
            Configuration config  = new Configuration();
            config.getUi().setColorEnabled(false);
            when(manager.getConfig()).thenReturn(config);
            configured.when(ConfigManager::getInstance).thenReturn(manager);

            String[] args = new String[arguments.length + 2];
            System.arraycopy(arguments, 0, args, 0, arguments.length);
            args[arguments.length]     = "-p";
            args[arguments.length + 1] = project.getRoot().getAbsolutePath();
            new GrepCommand().execute(args);
        }
        return output.getAllOutput();
    }

    @Test
    public void wholeWordContainsTheAlternationItIsWrappedAround() throws Exception {
        write("subject.txt", "catalog software\nthe cat sat\nhotdog stand\nthe dog ran\n");

        String shown = grep("cat|dog", "-w");

        assertThat(shown)
                .as("\\bcat|dog\\b anchors one branch on the left and the other on the right")
                .doesNotContain("catalog")
                .doesNotContain("hotdog");
        assertThat(shown).contains("the cat sat").contains("the dog ran");
    }

    @Test
    public void wholeLineContainsTheAlternationItIsWrappedAround() throws Exception {
        write("subject.txt", "cat\nthe cat\ndog\nthe dog\n");

        String shown = grep("cat|dog", "-x");

        assertThat(shown)
                .as("^cat|dog$ is 'starts with cat' OR 'ends with dog', which is neither")
                .doesNotContain("the cat")
                .doesNotContain("the dog");
        assertThat(shown).contains("    1: cat").contains("    3: dog");
    }

    /**
     * The wrapper adds no group, so {@code \1} still means the caller's own first group.
     *
     * <p>Grouping the expression is only safe if it is non-capturing: a plain {@code (...)} would
     * become group one and renumber every back-reference in the pattern behind it, which is a
     * quieter way of breaking the same search.</p>
     */
    @Test
    public void abackReferenceStillCountsFromTheCallersOwnFirstGroup() throws Exception {
        write("subject.txt", "aa\nab\n");

        String shown = grep("(a)\\1", "-w");

        assertThat(shown).contains("    1: aa");
        assertThat(shown).doesNotContain("    2: ab");
    }

    @Test
    public void apathWrittenWithItsTrailingSlashIsSearchedForWholesale() throws Exception {
        write("subject.txt", "cache under /tmp/ today\ntmp is a word too\n");

        String shown = grep("/tmp/");

        assertThat(shown)
                .as("stripping the slashes searched for 'tmp' and found the line that has no path")
                .doesNotContain("tmp is a word too");
        assertThat(shown).contains("cache under /tmp/ today");
    }

    @Test
    public void aquotedStringPatternIsNotMistakenForAquotedPattern() throws Exception {
        write("subject.txt", "he said \"hello\" once\nnothing quoted here\n");

        String shown = grep("\"[^\"]*\"");

        assertThat(shown)
                .as("unwrapped to [^\"]* this matches the empty string, so every line matches")
                .doesNotContain("nothing quoted here");
        assertThat(shown).contains("he said \"hello\" once");
    }

    /**
     * A backslash before a quote is a regex escape, not shell quoting left over from a model.
     *
     * <p>The unconditional unescape rewrote a backslash followed by a quote into a bare quote
     * anywhere in the pattern, so an expression looking for a Windows-style path next to a quote
     * became an expression looking for the quote alone.</p>
     */
    @Test
    public void abackslashBeforeAquoteSurvivesIntoTheExpression() throws Exception {
        write("subject.txt", "C:\\\"quoted\" path\nsay \"hi\" plainly\n");

        String shown = grep("\\\\\"");

        assertThat(shown).contains("C:\\\"quoted\" path");
        assertThat(shown)
                .as("rewritten to a bare quote, the pattern matched any quoted text at all")
                .doesNotContain("say \"hi\" plainly");
    }

    @Test
    public void arunOfSpacesBetweenTwoWordsIsPartOfThePattern() throws Exception {
        write("subject.txt", "foo   bar\nfoo bar\n");

        String shown = grep("foo", " ", "bar");

        assertThat(shown)
                .as("the whitespace-only part was dropped, leaving a single space between the words")
                .contains("    1: foo   bar");
        assertThat(shown).doesNotContain("    2: foo bar");
    }

    /** A phrase in quotes is what the unwrapping exists for, and still is. */
    @Test
    public void aquotedPhraseIsStillUnwrapped() throws Exception {
        write("subject.txt", "the needle is here\n");

        assertThat(grep("\"needle is\"")).contains("the needle is here");
    }

    /** Backticks are how a pattern arrives from anything markdown-shaped. */
    @Test
    public void abacktickedPatternIsStillUnwrapped() throws Exception {
        write("subject.txt", "the needle is here\n");

        assertThat(grep("`needle`")).contains("the needle is here");
    }

    /** {@code /pattern/} is how JavaScript and Perl write one, when the body reads as a regex. */
    @Test
    public void aslashDelimitedRegexIsStillUnwrapped() throws Exception {
        write("subject.txt", "the neeedle is here\n");

        assertThat(grep("/ne+dle/")).contains("the neeedle is here");
    }

    /** Shell quoting that a model escaped on its way in is still undone with the quotes. */
    @Test
    public void aquoteEscapedInsideAquotedPatternIsStillUnescaped() throws Exception {
        write("subject.txt", "he said \"hi\" once\n");

        assertThat(grep("\"said \\\"hi\\\"\"")).contains("he said \"hi\" once");
    }
}
