package com.eonmux.cadetcoder;

import com.eonmux.cadetcoder.commands.InputRouter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A command name counts as a command only where a command name belongs.
 *
 * <h2>What this stops</h2>
 *
 * <p>picocli goes on looking for a subcommand among positional arguments, so any request whose
 * words happened to include a registered command name ran that command instead of reaching the
 * model. {@code cadet please ls} listed the directory. {@code cadet how do I use ls} listed the
 * directory. {@code cadet help undo} began discarding uncommitted changes, and was stopped only by
 * a non-interactive guard further in. {@code cadet can you undo the last commit} matched
 * {@code undo} and failed on argument count rather than on intent.</p>
 *
 * <p>The parser is taken from {@link Main#newCommandLine()} rather than rebuilt here, so these
 * assert the configuration that actually runs.</p>
 */
class CommandRoutingSafetyTest {

    /** How the command line routes one argument vector. */
    private static InputRouter.Routed routed(CommandRegistry registry, String... argv) {
        return InputRouter.route(argv, registry.getCommands().keySet(), InputRouter.Mode.ARGV);
    }

    /** @return the subcommand picocli resolved, or {@code null} when the input stayed free text */
    private static String resolvedSubcommand(String... args) {
        CommandLine.ParseResult result = Main.newCommandLine().parseArgs(args);
        CommandLine.ParseResult sub = result.subcommand();
        return sub == null ? null : sub.commandSpec().name();
    }

    @Test
    @DisplayName("A command given as the first word still runs")
    void aLeadingCommandNameIsStillACommand() {
        CommandRegistry registry = new CommandRegistry();

        assertThat(routed(registry, "ls", "src").getName()).isEqualTo("ls");
        assertThat(routed(registry, "ls", "src").getArgs()).containsExactly("src");
        assertThat(routed(registry, "grep", "pattern").getName()).isEqualTo("grep");
    }

    @Test
    @DisplayName("Global options before the command are consumed, leaving the command to dispatch")
    void optionsMayPrecedeTheCommand() {
        CommandLine.ParseResult parsed =
                Main.newCommandLine().parseArgs("--no-color", "ls", "src");

        assertThat(parsed.hasMatchedOption("--no-color")).isTrue();
        assertThat(parsed.matchedPositionalValue(0, new String[0]))
                .as("the command and its arguments survive as free arguments to dispatch")
                .containsExactly("ls", "src");
    }

    @Test
    @DisplayName("A command name inside a sentence is text, not a command")
    void aCommandNameInFreeTextDoesNotRun() {
        assertThat(resolvedSubcommand("please", "ls")).isNull();
        assertThat(resolvedSubcommand("how", "do", "I", "use", "ls")).isNull();
        assertThat(resolvedSubcommand("what", "does", "theme", "do")).isNull();
    }

    @Test
    @DisplayName("Asking about a destructive command does not begin performing it")
    void askingAboutADestructiveCommandIsSafe() {
        // The one that matters most: this used to reach `undo` and start a hard reset.
        assertThat(resolvedSubcommand("can", "you", "undo", "the", "last", "commit")).isNull();
        assertThat(resolvedSubcommand("should", "I", "push", "this")).isNull();
        assertThat(resolvedSubcommand("please", "undo")).isNull();
    }

    @Test
    @DisplayName("'help <name>' reaches help, whatever the name is")
    void helpIsNotShadowedByTheCommandItDescribes() {
        // `help` is not a picocli subcommand, so every name that IS one used to win the match and
        // execute -- which made `help` mean "run" for half the command surface.
        for (String name : new String[]{"undo", "push", "ls", "grep", "theme", "bash", "write"}) {
            assertThat(resolvedSubcommand("help", name))
                    .as("help " + name + " must not dispatch " + name)
                    .isNull();
        }
    }

    @Test
    @DisplayName("Unrecognised options in free text stay text rather than aborting the parse")
    void unknownOptionsInARequestAreNotFatal() {
        // The pre-existing rule this must not undo: a request, or a command's own flag, may carry
        // dashes the top-level parser knows nothing about.
        assertThat(resolvedSubcommand("explain", "--why", "does", "this", "fail")).isNull();
        assertThat(Main.newCommandLine().parseArgs("tell", "me", "about", "--limit=3")
                       .matchedArgs()).isNotNull();
    }

    @Test
    @DisplayName("There is ONE dispatch path: no command is a picocli subcommand")
    void thereIsNoSecondDispatchPath() {
        // 22 of 42 commands used to be registered here as well as in CommandRegistry, which is what
        // made --help show half the tool, `help <name>` mean "run <name>" for that half, and a
        // command name inside a sentence executable. Every command parses its own arguments, so the
        // registration bought nothing and cost a second set of rules to keep in step.
        assertThat(Main.newCommandLine().getSubcommands())
                .as("commands are dispatched by CommandRegistry, not by picocli")
                .isEmpty();
    }

    @Test
    @DisplayName("A command resolves the same with or without the leading slash")
    void slashIsOptionalOnTheCommandLine() {
        CommandRegistry registry = new CommandRegistry();

        assertThat(routed(registry, "/ls", "src").getName()).isEqualTo("ls");
        assertThat(routed(registry, "ls", "src").getName()).isEqualTo("ls");
    }

    @Test
    @DisplayName("Free text is handed to the model rather than resolved as a command")
    void freeTextGoesToTheModel() {
        CommandRegistry registry = new CommandRegistry();

        assertThat(routed(registry, "please", "ls").isChat()).isTrue();
    }
}
