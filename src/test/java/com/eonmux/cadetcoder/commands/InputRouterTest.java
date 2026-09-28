package com.eonmux.cadetcoder.commands;

import org.junit.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the command-vs-chat routing rule.
 *
 * <p>The bug being prevented: the first whitespace-separated word used to be dispatched whenever it
 * happened to name a registered command, so ordinary sentences were executed as tool calls
 * ({@code write a test for Foo} created a file named {@code a}; {@code commit the changes} made a
 * real git commit) while a sentence starting with any other word went to the model.</p>
 */
public class InputRouterTest {

    private static final Set<String> KNOWN =
            Set.of("read", "write", "commit", "push", "help", "grep", "ls", "chat");

    // ------------------------------------------------------------------ conversation mode

    @Test
    public void bareSentenceStartingWithACommandWordIsAChatMessage() {
        InputRouter.Routed routed =
                InputRouter.route("write a test for Foo", KNOWN, InputRouter.Mode.CONVERSATION);

        assertThat(routed.isChat()).isTrue();
        assertThat(routed.getText()).isEqualTo("write a test for Foo");
        assertThat(routed.getName()).isNull();
    }

    @Test
    public void aBareSentenceReportsTheCommandItShadowsSoTheUserCanBeTold() {
        InputRouter.Routed routed =
                InputRouter.route("commit the changes", KNOWN, InputRouter.Mode.CONVERSATION);

        assertThat(routed.isChat()).isTrue();
        assertThat(routed.getShadowedCommand()).isEqualTo("commit");
    }

    @Test
    public void aBareSentenceThatShadowsNothingReportsNoShadowedCommand() {
        InputRouter.Routed routed =
                InputRouter.route("what does this project do?", KNOWN, InputRouter.Mode.CONVERSATION);

        assertThat(routed.isChat()).isTrue();
        assertThat(routed.getShadowedCommand()).isNull();
    }

    @Test
    public void slashPrefixIsAlwaysACommand() {
        InputRouter.Routed routed =
                InputRouter.route("/read pom.xml", KNOWN, InputRouter.Mode.CONVERSATION);

        assertThat(routed.isCommand()).isTrue();
        assertThat(routed.getName()).isEqualTo("read");
        assertThat(routed.getArgs()).containsExactly("pom.xml");
        assertThat(routed.isExplicit()).isTrue();
        assertThat(routed.isKnown()).isTrue();
    }

    @Test
    public void anUnknownSlashCommandIsStillACommandSoTheCallerCanReportIt() {
        InputRouter.Routed routed =
                InputRouter.route("/raed pom.xml", KNOWN, InputRouter.Mode.CONVERSATION);

        assertThat(routed.isCommand()).isTrue();
        assertThat(routed.getName()).isEqualTo("raed");
        assertThat(routed.isKnown())
                .as("an unregistered name must not be silently forwarded to the model")
                .isFalse();
    }

    @Test
    public void doubleSlashEscapesToAChatMessageWithOneSlashRemoved() {
        InputRouter.Routed routed =
                InputRouter.route("//read is a command word", KNOWN, InputRouter.Mode.CONVERSATION);

        assertThat(routed.isChat()).isTrue();
        assertThat(routed.getText()).isEqualTo("/read is a command word");
    }

    @Test
    public void quotedArgumentsSurviveAsOneArgument() {
        InputRouter.Routed routed = InputRouter.route(
                "/grep \"class Foo\" --path=src", KNOWN, InputRouter.Mode.CONVERSATION);

        assertThat(routed.getArgs()).containsExactly("class Foo", "--path=src");
    }

    @Test
    public void commandNamesAreCaseInsensitive() {
        InputRouter.Routed routed =
                InputRouter.route("/READ pom.xml", KNOWN, InputRouter.Mode.CONVERSATION);

        assertThat(routed.getName()).isEqualTo("read");
        assertThat(routed.isKnown()).isTrue();
    }

    @Test
    public void blankAndBareSlashInputProduceNothingToDo() {
        assertThat(InputRouter.route("", KNOWN, InputRouter.Mode.CONVERSATION).isEmpty()).isTrue();
        assertThat(InputRouter.route("   ", KNOWN, InputRouter.Mode.CONVERSATION).isEmpty()).isTrue();
        assertThat(InputRouter.route((String) null, KNOWN, InputRouter.Mode.CONVERSATION).isEmpty()).isTrue();
        assertThat(InputRouter.route((String[]) null, KNOWN, InputRouter.Mode.ARGV).isEmpty()).isTrue();
        assertThat(InputRouter.route("/", KNOWN, InputRouter.Mode.CONVERSATION).isEmpty()).isTrue();
    }

    // ------------------------------------------------------------------ argv mode

    @Test
    public void argvModeKeepsTheBareCommandLineContract() {
        InputRouter.Routed routed = InputRouter.route(
                new String[] {"read", "pom.xml"}, KNOWN, InputRouter.Mode.ARGV);

        assertThat(routed.isCommand()).isTrue();
        assertThat(routed.getName()).isEqualTo("read");
        assertThat(routed.getArgs()).containsExactly("pom.xml");
        assertThat(routed.isExplicit())
                .as("dispatched, but not because the user wrote a slash")
                .isFalse();
    }

    @Test
    public void argvModeRoutesAnUnknownFirstWordToChatWithTheWholeLine() {
        InputRouter.Routed routed = InputRouter.route(
                new String[] {"explain-this-repo", "please"}, KNOWN, InputRouter.Mode.ARGV);

        assertThat(routed.isChat()).isTrue();
        assertThat(routed.getText()).isEqualTo("explain-this-repo please");
    }

    @Test
    public void argvModeAcceptsTheSlashFormToo() {
        InputRouter.Routed routed = InputRouter.route(
                new String[] {"/ls", "-a"}, KNOWN, InputRouter.Mode.ARGV);

        assertThat(routed.getName()).isEqualTo("ls");
        assertThat(routed.getArgs()).containsExactly("-a");
        assertThat(routed.isExplicit()).isTrue();
    }

    @Test
    public void argvModePreservesAShellQuotedArgumentAsOneElement() {
        InputRouter.Routed routed = InputRouter.route(
                new String[] {"grep", "class Foo"}, KNOWN, InputRouter.Mode.ARGV);

        assertThat(routed.getArgs())
                .as("argv is already tokenized by the shell and must not be re-split")
                .containsExactly("class Foo");
    }

    @Test
    public void conversationModeNeverDispatchesABareCommandWord() {
        InputRouter.Routed routed = InputRouter.route(
                new String[] {"read", "pom.xml"}, KNOWN, InputRouter.Mode.CONVERSATION);

        assertThat(routed.isChat()).isTrue();
        assertThat(routed.getShadowedCommand()).isEqualTo("read");
    }

    // ------------------------------------------------------------------ suggestions

    @Test
    public void suggestFindsTheObviousTypo() {
        List<String> suggestions = InputRouter.suggest("raed", KNOWN, 3);

        assertThat(suggestions).contains("read");
    }

    @Test
    public void suggestFindsAPrefixMatch() {
        List<String> suggestions = InputRouter.suggest("wri", KNOWN, 3);

        assertThat(suggestions).contains("write");
    }

    @Test
    public void suggestRespectsTheLimitAndHandlesNonsense() {
        assertThat(InputRouter.suggest("zzzzzzzzzzzz", KNOWN, 3)).isEmpty();
        assertThat(InputRouter.suggest("r", KNOWN, 1)).hasSizeLessThanOrEqualTo(1);
        assertThat(InputRouter.suggest(null, KNOWN, 3)).isEmpty();
        assertThat(InputRouter.suggest("read", null, 3)).isEmpty();
    }

    // ------------------------------------------------------------------ robustness

    @Test
    public void aNullOrEmptyCommandSetMakesEverythingBareIntoChat() {
        assertThat(InputRouter.route("read pom.xml", null, InputRouter.Mode.ARGV).isChat()).isTrue();
        assertThat(InputRouter.route("read pom.xml", Set.of(), InputRouter.Mode.ARGV).isChat()).isTrue();
    }

    @Test
    public void returnedArgumentsAreDefensiveCopies() {
        InputRouter.Routed routed =
                InputRouter.route("/read a.java", KNOWN, InputRouter.Mode.CONVERSATION);

        String[] first = routed.getArgs();
        first[0] = "mutated";

        assertThat(routed.getArgs()).containsExactly("a.java");
    }
}
