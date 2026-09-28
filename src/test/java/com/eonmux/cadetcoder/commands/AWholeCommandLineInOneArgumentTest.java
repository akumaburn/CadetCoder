package com.eonmux.cadetcoder.commands;

import org.junit.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One shell-quoted argument holding a whole command line.
 *
 * <h2>The defect</h2>
 *
 * <p>{@code cadet "/ls ."} answered <em>Unknown command: ls .</em> The leading slash had already been
 * read as "this is a command", and then the entire line -- name, space and argument -- was looked up
 * as a command <em>name</em>. The tool understood what was meant, said so in its own error message
 * by suggesting {@code cadet ls}, and still refused to do it. The interactive shell had never had
 * the problem, because the prompt routes through the shared tokenizer; the command line had a second
 * router of its own that did not.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>An explicit line is split by the tokenizer, quotes and all, so it means the same thing typed at
 * the prompt and passed in one argument. A bare line is not: without the slash there is nothing to
 * separate a command from a sentence that opens with a command word, and a guess would turn
 * "read the design doc and summarise it" into a file called "the".</p>
 */
public class AWholeCommandLineInOneArgumentTest {

    private static final Set<String> KNOWN = Set.of("ls", "read", "grep", "write", "chat");

    private static InputRouter.Routed route(String... argv) {
        return InputRouter.route(argv, KNOWN, InputRouter.Mode.ARGV);
    }

    @Test
    public void anExplicitLineInOneArgumentIsSplitIntoNameAndArguments() {
        InputRouter.Routed routed = route("/ls .");

        assertThat(routed.isCommand()).isTrue();
        assertThat(routed.getName()).isEqualTo("ls");
        assertThat(routed.getArgs()).containsExactly(".");
    }

    @Test
    public void aQuotedArgumentInsideThatLineSurvivesAsOneArgument() {
        InputRouter.Routed routed = route("/grep \"class Foo\" -p src");

        assertThat(routed.getName()).isEqualTo("grep");
        assertThat(routed.getArgs()).containsExactly("class Foo", "-p", "src");
    }

    @Test
    public void argumentsAfterTheQuotedLineFollowTheOnesInsideIt() {
        InputRouter.Routed routed = route("/read pom.xml", "-l", "20");

        assertThat(routed.getName()).isEqualTo("read");
        assertThat(routed.getArgs()).containsExactly("pom.xml", "-l", "20");
    }

    @Test
    public void anExplicitLineNamingNothingIsStillReportedAsACommand() {
        InputRouter.Routed routed = route("/raed pom.xml");

        assertThat(routed.isCommand())
                .as("an explicit name must fail as an unknown command, not become a billed chat turn")
                .isTrue();
        assertThat(routed.isExplicit()).isTrue();
        assertThat(routed.isKnown()).isFalse();
        assertThat(routed.getName()).isEqualTo("raed");
    }

    @Test
    public void aBareLineInOneArgumentIsStillAMessageForTheModel() {
        InputRouter.Routed routed = route("ls .");

        assertThat(routed.isChat()).isTrue();
        assertThat(routed.getText()).isEqualTo("ls .");
    }

    @Test
    public void aSentenceThatOpensWithACommandWordIsNotSplitIntoOne() {
        InputRouter.Routed routed = route("read the design doc and summarise it");

        assertThat(routed.isChat()).isTrue();
    }

    @Test
    public void separateArgumentsAreUntouched() {
        InputRouter.Routed routed = route("grep", "class Foo");

        assertThat(routed.getName()).isEqualTo("grep");
        assertThat(routed.getArgs())
                .as("a shell-quoted argument must not be re-split")
                .containsExactly("class Foo");
    }

    @Test
    public void aLineOfNothingButASlashAsksForNothing() {
        assertThat(route("/   ").isEmpty()).isTrue();
    }

    @Test
    public void anEscapedLineIsAMessageBeginningWithASlash() {
        InputRouter.Routed routed = route("//ls .");

        assertThat(routed.isChat()).isTrue();
        assertThat(routed.getText()).isEqualTo("/ls .");
    }
}
