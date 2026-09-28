package com.eonmux.cadetcoder.security;

import org.junit.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A shell command line is taken apart before it is judged.
 *
 * <h2>The defect</h2>
 *
 * <p>The screens {@code bash} passes through refused any line containing one of
 * {@code ; & | < > $ ` { } [ ]}. That is a rule about characters, and the work people ask for is
 * made of them: {@code awk '{print $1}'} was refused for the {@code $} inside its own script,
 * {@code grep -r todo . | wc -l} for the pipe, {@code ls target/*.xml} for nothing at all once the
 * brackets were counted. The refusal carried no reason -- the message ended in a colon and an empty
 * string -- so neither the user nor the model could tell what to write instead.</p>
 *
 * <p>What the screens need is the programs a line runs and the files it touches. This is the parse
 * that recovers them.</p>
 */
public class AcommandLineIsReadRatherThanScannedTest {

    private static List<String> programsOf(String line) {
        return ShellCommandLine.parse(line).allSegments().stream()
                .map(segment -> segment.tokens().isEmpty() ? "" : segment.tokens().get(0))
                .toList();
    }

    @Test
    public void everyCommandAnOperatorSeparatesIsItsOwnCommand() {
        assertThat(programsOf("echo hello; rm -rf /tmp")).containsExactly("echo", "rm");
        assertThat(programsOf("mvn test && git status")).containsExactly("mvn", "git");
        assertThat(programsOf("grep -r todo src | wc -l")).containsExactly("grep", "wc");
        assertThat(programsOf("cat missing || echo gone")).containsExactly("cat", "echo");
    }

    @Test
    public void aQuotedMetacharacterIsText() {
        ShellCommandLine line =
                ShellCommandLine.parse("awk 'match($0, /tests=\"[0-9]+\"/) {n++} END {print n}' r.xml");

        assertThat(line.segments()).hasSize(1);
        assertThat(line.segments().get(0).tokens())
                .as("the script is one argument, whatever it is made of")
                .containsExactly("awk", "match($0, /tests=\"[0-9]+\"/) {n++} END {print n}", "r.xml");
        assertThat(line.segments().get(0).isHidden(1))
                .as("nothing expands inside single quotes")
                .isFalse();
    }

    @Test
    public void aCommandSubstitutionIsACommandOfItsOwn() {
        assertThat(programsOf("echo $(git rev-parse HEAD)")).contains("echo", "git");
        assertThat(programsOf("echo `git status`")).contains("echo", "git");
        assertThat(programsOf("echo \"today is $(date)\"")).contains("echo", "date");
    }

    @Test
    public void aTokenBuiltByExpansionIsReportedAsUnreadableRatherThanGuessedAt() {
        ShellCommandLine.Segment segment = ShellCommandLine.parse("$TOOL --version").segments().get(0);

        assertThat(segment.isHidden(0)).isTrue();
        assertThat(segment.isHidden(1)).isFalse();
    }

    @Test
    public void redirectionNamesAFileAndIsNotAnArgument() {
        ShellCommandLine.Segment written =
                ShellCommandLine.parse("mvn -q test > build.log").segments().get(0);

        assertThat(written.tokens()).containsExactly("mvn", "-q", "test");
        assertThat(written.redirects()).containsExactly(new ShellCommandLine.Redirect("build.log", true));

        ShellCommandLine.Segment read =
                ShellCommandLine.parse("sort < names.txt").segments().get(0);

        assertThat(read.redirects()).containsExactly(new ShellCommandLine.Redirect("names.txt", false));
    }

    @Test
    public void aDescriptorPointedAtAnotherDescriptorNamesNoFile() {
        ShellCommandLine.Segment segment =
                ShellCommandLine.parse("git status 2>&1").segments().get(0);

        assertThat(segment.tokens()).containsExactly("git", "status");
        assertThat(segment.redirects()).isEmpty();
    }

    @Test
    public void aDescriptorWrittenInFrontOfTheOperatorBelongsToTheRedirection() {
        ShellCommandLine.Segment segment =
                ShellCommandLine.parse("mvn test 2> errors.log").segments().get(0);

        assertThat(segment.tokens()).containsExactly("mvn", "test");
        assertThat(segment.redirects()).containsExactly(
                new ShellCommandLine.Redirect("errors.log", true));
    }

    @Test
    public void appendingIsWriting() {
        assertThat(ShellCommandLine.parse("echo done >> log").segments().get(0).redirects())
                .containsExactly(new ShellCommandLine.Redirect("log", true));
    }

    @Test
    public void aLoneAmpersandIsBackgroundAndTwoAreNot() {
        assertThat(ShellCommandLine.parse("npm start &").isBackground()).isTrue();
        assertThat(ShellCommandLine.parse("npm test && npm run lint").isBackground()).isFalse();
    }

    @Test
    public void whatAShellIsToldToRunIsReadableFromTheLine() {
        ShellCommandLine.Segment segment =
                ShellCommandLine.parse("bash -c \"rm -rf /\"").segments().get(0);

        assertThat(segment.tokens()).containsExactly("bash", "-c", "rm -rf /");
    }

    @Test
    public void anUnterminatedQuoteTakesTheRestOfTheLineRatherThanFailing() {
        // Model-authored lines arrive malformed now and then, and refusing to parse one would end
        // the turn instead of screening it.
        ShellCommandLine.Segment segment =
                ShellCommandLine.parse("echo 'unfinished business").segments().get(0);

        assertThat(segment.tokens()).containsExactly("echo", "unfinished business");
    }

    @Test
    public void anEmptyLineNamesNothing() {
        assertThat(ShellCommandLine.parse(null).segments()).isEmpty();
        assertThat(ShellCommandLine.parse("   ").segments()).isEmpty();
    }
}
