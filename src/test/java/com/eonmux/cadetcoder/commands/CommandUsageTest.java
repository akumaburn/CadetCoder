package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.ui.OutputRouter;
import org.junit.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * A usage string has to be typeable where it is printed.
 */
public class CommandUsageTest {

    @Test
    public void theCommandLineSpellingNamesTheProgram() {
        // No interactive shell is running in a test, so this is the command-line surface.
        assertThat(CommandUsage.prefix()).isEqualTo("cadet ");
        assertThat(CommandUsage.render("read <file>")).isEqualTo("cadet read <file>");
    }

    @Test
    public void everyInvocationLineIsPrefixed() {
        String rendered = CommandUsage.render(
                "session [status]\n"
                + "session list [count]\n"
                + "session new");

        assertThat(rendered).isEqualTo(
                "cadet session [status]\n"
                + "cadet session list [count]\n"
                + "cadet session new");
    }

    @Test
    public void indentedInvocationLinesKeepTheirIndent() {
        assertThat(CommandUsage.render("theme list\n  theme set <name>"))
                .isEqualTo("cadet theme list\n  cadet theme set <name>");
    }

    @Test
    public void optionAndNoteLinesAreLeftAlone() {
        String rendered = CommandUsage.render(
                "grep <pattern> [options]\n"
                + "  -p, --path <dir>   Directory to search\n"
                + "  Quote a pattern containing spaces.");

        assertThat(rendered).contains("cadet grep <pattern>");
        assertThat(rendered).contains("  -p, --path <dir>   Directory to search");
        assertThat(rendered).doesNotContain("cadet -p");
        assertThat(rendered).doesNotContain("cadet   Quote");
    }

    @Test
    public void aNameIsOnlyMatchedAsAWholeWord() {
        // "grepping" is not an invocation of "grep".
        assertThat(CommandUsage.render("grep <pattern>\ngrepping is not a command"))
                .isEqualTo("cadet grep <pattern>\ngrepping is not a command");
    }

    /**
     * The other half of the point: at the shell prompt a command is a slash command, and a usage
     * line naming the program instead would be something the reader cannot type where it appeared.
     */
    @Test
    public void theShellSpellingIsASlashCommand() {
        OutputRouter router = OutputRouter.getInstance();
        InteractiveShell shell = mock(InteractiveShell.class);
        java.io.PrintStream out = System.out;
        java.io.PrintStream err = System.err;
        router.setShell(shell);
        router.startRouting();
        try {
            assertThat(router.isRouting()).isTrue();
            assertThat(CommandUsage.prefix()).isEqualTo("/");
            assertThat(CommandUsage.render("session list\nsession new"))
                    .isEqualTo("/session list\n/session new");
        } finally {
            router.stopRouting();
            router.setShell(null);
            System.setOut(out);
            System.setErr(err);
        }
        assertThat(CommandUsage.prefix()).isEqualTo("cadet ");
    }

    @Test
    public void nullAndEmptyAreHarmless() {
        assertThat(CommandUsage.render(null)).isEmpty();
        assertThat(CommandUsage.render("")).isEmpty();
    }

    /**
     * The whole point: no usage string may carry a program name of its own, or the renderer would
     * produce "cadet read" on one surface and "/cadet read" on the other.
     */
    @Test
    public void noRegisteredUsageCarriesItsOwnPrefix() {
        Map<String, CommandRegistry.Command> commands = new CommandRegistry().getCommands();
        assertThat(commands).isNotEmpty();

        for (Map.Entry<String, CommandRegistry.Command> entry : commands.entrySet()) {
            String usage = entry.getValue().getUsage();
            if (usage == null) {
                continue;
            }
            assertThat(usage)
                    .as("usage of '%s' must not spell the program name itself", entry.getKey())
                    .doesNotContain("cadet ");
            assertThat(usage)
                    .as("usage of '%s' must not spell the shell's slash itself", entry.getKey())
                    .doesNotContain("/" + entry.getKey());
        }
    }

    /**
     * The rendered form is what the user is told to type, so it has to start with the command they
     * asked about.
     */
    @Test
    public void everyRegisteredUsageStartsWithItsOwnName() {
        Map<String, CommandRegistry.Command> commands = new CommandRegistry().getCommands();

        for (Map.Entry<String, CommandRegistry.Command> entry : commands.entrySet()) {
            String usage = entry.getValue().getUsage();
            if (usage == null || usage.isBlank()) {
                continue;
            }
            assertThat(CommandUsage.render(usage))
                    .as("usage of '%s'", entry.getKey())
                    .startsWith("cadet " + entry.getKey());
        }
    }

    /**
     * One shape for all of them, whichever surface they are read on.
     *
     * <p>A usage block is read as a list, so the reader relies on the indent to tell an invocation
     * they can type from a note about one. The conventions are: an invocation form starts its line
     * at column 0, a note or option list is indented two, and an example under a note is indented
     * four. They had drifted -- {@code workers} was the only command indenting its sub-commands, so
     * they read as commentary; {@code template} was the only one putting prose at column 0, so its
     * headings read as commands; and {@code bash}'s example sat at column 0 under an indented note,
     * looking like a form of its own.</p>
     */
    @Test
    public void everyUsageLineIsIndentedByAnEvenAmount() {
        for (Map.Entry<String, String> entry : usages().entrySet()) {
            for (String line : entry.getValue().split("\n", -1)) {
                if (line.isBlank()) {
                    continue;
                }
                int indent = line.length() - line.stripLeading().length();
                assertThat(indent % 2)
                        .as("usage of '%s': odd indent on %s", entry.getKey(), line)
                        .isZero();
                if (line.stripLeading().startsWith(entry.getKey())) {
                    // An invocation: column 0 for a form of the command, 2 or 4 for an example
                    // sitting under a note. Deeper than that and it reads as prose.
                    assertThat(indent)
                            .as("usage of '%s': invocation indented past an example on %s",
                                entry.getKey(), line)
                            .isLessThanOrEqualTo(4);
                }
            }
        }
    }

    /** The first line names the command, so a reader knows what they are looking at. */
    @Test
    public void everyUsageOpensWithAnInvocationAtColumnZero() {
        for (Map.Entry<String, String> entry : usages().entrySet()) {
            assertThat(entry.getValue().split("\n", -1)[0])
                    .as("usage of '%s' must open with the command itself", entry.getKey())
                    .startsWith(entry.getKey());
        }
    }

    /**
     * Usage is printed into a terminal, and {@code help} indents it by two more. Anything much past
     * a hundred columns wraps into a ragged second row that is harder to read than the option it
     * describes -- which is what {@code notebookedit} did at 130 characters on one line.
     */
    @Test
    public void noUsageLineIsTooWideForATerminal() {
        for (Map.Entry<String, String> entry : usages().entrySet()) {
            for (String line : CommandUsage.render(entry.getValue()).split("\n", -1)) {
                assertThat(line.length())
                        .as("usage of '%s' is %d columns wide: %s",
                            entry.getKey(), line.length(), line)
                        .isLessThanOrEqualTo(100);
            }
        }
    }

    /** The bare usage of every registered command, keyed by name. */
    /**
     * Options a command accepts but deliberately does not advertise, with the reason.
     *
     * <p>Anything added here is a promise that the omission is intended, not forgotten.</p>
     */
    private static final Map<String, java.util.Set<String>> UNADVERTISED_OPTIONS = Map.of(
            // The JGit integration performs a plain push to the tracked upstream. These three are
            // recognised only so they can report "use the git CLI" instead of being ignored.
            "push", java.util.Set.of("-f", "--force", "-u", "--set-upstream", "-b", "--branch"));

    @Test
    public void everyOptionACommandAcceptsAppearsInItsUsage() {
        java.util.List<String> undocumented = new java.util.ArrayList<>();

        for (Map.Entry<String, CommandRegistry.Command> entry
                : new CommandRegistry().getCommands().entrySet()) {
            String name  = entry.getKey();
            String usage = entry.getValue().getUsage();
            if (usage == null || usage.isBlank()) {
                continue;
            }
            java.util.Set<String> exempt =
                    UNADVERTISED_OPTIONS.getOrDefault(name, java.util.Set.of());

            for (java.lang.reflect.Field field : entry.getValue().getClass().getDeclaredFields()) {
                picocli.CommandLine.Option option =
                        field.getAnnotation(picocli.CommandLine.Option.class);
                if (option == null) {
                    continue;
                }
                // One name is enough: an option is documented if the reader can find any of the
                // spellings that reach it.
                boolean mentioned = false;
                for (String optionName : option.names()) {
                    if (exempt.contains(optionName) || usage.contains(optionName)) {
                        mentioned = true;
                        break;
                    }
                }
                if (!mentioned) {
                    undocumented.add(name + " " + java.util.Arrays.toString(option.names()));
                }
            }
        }

        assertThat(undocumented)
                .as("An option a command accepts but never mentions can only be found by reading "
                    + "the source. Document it in getUsage(), or record the omission in "
                    + "UNADVERTISED_OPTIONS with a reason.")
                .isEmpty();
    }

    private static Map<String, String> usages() {
        Map<String, String> usages = new java.util.TreeMap<>();
        for (Map.Entry<String, CommandRegistry.Command> entry
                : new CommandRegistry().getCommands().entrySet()) {
            String usage = entry.getValue().getUsage();
            if (usage != null && !usage.isBlank()) {
                usages.put(entry.getKey(), usage);
            }
        }
        assertThat(usages).isNotEmpty();
        return usages;
    }
}
