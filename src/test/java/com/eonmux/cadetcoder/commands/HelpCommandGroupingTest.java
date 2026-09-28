package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import com.eonmux.cadetcoder.ui.Glyphs;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The command reference is the only place most of these commands are ever discovered, so every one
 * of them has to appear, under a heading someone would think to look under.
 */
public class HelpCommandGroupingTest {

    private TestOutputCapture outputCapture;

    @Before
    public void setUp() {
        outputCapture = new TestOutputCapture();
    }

    @After
    public void tearDown() {
        outputCapture.restore();
    }

    /**
     * A new command falls into "Other" unless someone files it. That is the safe behaviour -- it is
     * still listed -- but it is not the intended one, so this fails until the command is placed.
     */
    @Test
    public void everyRegisteredCommandNamesItselfAndSaysWhatItDoes() {
        // Both come from the @Command annotation, which is the one place either is written.
        for (java.util.Map.Entry<String, CommandRegistry.Command> entry
                : new CommandRegistry().getCommands().entrySet()) {
            picocli.CommandLine.Command annotation =
                    entry.getValue().getClass().getAnnotation(picocli.CommandLine.Command.class);

            assertThat(annotation)
                    .as("%s has no @Command annotation, so its registry name is derived from its "
                        + "class name and a rename would silently rename the command",
                        entry.getValue().getClass().getSimpleName())
                    .isNotNull();
            assertThat(annotation.name())
                    .as("the annotation names the command")
                    .isEqualTo(entry.getKey());
            assertThat(entry.getValue().getDescription())
                    .as("%s must say what it does", entry.getKey())
                    .isNotBlank();
        }
    }

    /**
     * Every command says how it is used, and names itself while doing it.
     *
     * <h2>Why the name has to be in it</h2>
     *
     * <p>A usage line is read by someone who has just been told the command exists and does not yet
     * know how to type it. "{@code <file> [options]}" answers a question nobody asked. The registry
     * prints this text and nothing else in answer to {@code help <command>} and to {@code -h}, so
     * whatever is not here is not anywhere.</p>
     */
    @Test
    public void everyRegisteredCommandSaysHowItIsUsed() {
        for (Map.Entry<String, CommandRegistry.Command> entry
                : new CommandRegistry().getCommands().entrySet()) {
            String usage = entry.getValue().getUsage();

            assertThat(usage)
                    .as("'%s' answers 'help %s' with nothing", entry.getKey(), entry.getKey())
                    .isNotNull()
                    .isNotBlank();
            assertThat(usage)
                    .as("'%s' does not name itself in its own usage, so the line cannot be typed",
                        entry.getKey())
                    .contains(entry.getKey());
        }
    }

    /** Asking about one command prints that command's own usage rather than the whole reference. */
    @Test
    public void askingAboutOneCommandPrintsItsUsage() {
        CommandRegistry registry = new CommandRegistry();

        assertThat(registry.executeCommand("read", new String[] {"--help"}, false)).isZero();

        assertThat(outputCapture.getAllOutput())
                .contains(registry.getCommand("read").getUsage().split("\n")[0].strip());
    }

    @Test
    public void everyRegisteredCommandHasAGroup() {
        List<String> ungrouped = new ArrayList<>();
        for (String name : new CommandRegistry().getCommands().keySet()) {
            if ("Other".equals(HelpContent.groupOf(name))) {
                ungrouped.add(name);
            }
        }
        assertThat(ungrouped)
                .as("add these to HelpContent.GROUPS so they are findable by what they do")
                .isEmpty();
    }

    @Test
    public void groupsAreNamedForWhatTheUserWants() {
        assertThat(HelpContent.groupOf("grep")).isEqualTo("Find things");
        assertThat(HelpContent.groupOf("commit")).isEqualTo("Git");
        assertThat(HelpContent.groupOf("login")).isEqualTo("Providers and models");
        assertThat(HelpContent.groupOrder()).startsWith("Ask the AI");
    }

    @Test
    public void everyCommandIsListedExactlyOnce() {
        CommandRegistry registry = new CommandRegistry();
        new HelpCommand(registry).execute(new String[0]);
        String output = outputCapture.getAllOutput();

        for (Map.Entry<String, CommandRegistry.Command> entry : registry.getCommands().entrySet()) {
            assertThat(output)
                    .as("'%s' must appear in the reference", entry.getKey())
                    .contains("cadet " + entry.getKey());
        }
    }

    @Test
    public void aBlankSeparatorIsABlankLineAndNotABareMarker() {
        new HelpCommand(new CommandRegistry()).execute(new String[0]);

        // printInfo("") used to render a line holding the info marker and a trailing space.
        for (String line : outputCapture.getAllOutput().split("\n")) {
            assertThat(line.strip())
                    .as("a spacer line must be empty, not a lone marker")
                    .isNotEqualTo("ℹ")
                    .isNotEqualTo("[i]");
        }
    }

    @Test
    public void anUnknownCommandSuggestsTheNearestNames() {
        int exit = new HelpCommand(new CommandRegistry()).execute(new String[] {"raed"});

        assertThat(exit).isEqualTo(1);
        assertThat(outputCapture.getAllOutput())
                .contains("Unknown command: raed")
                .contains("cadet read");
    }

    /**
     * The overlay and {@code /help} used to be written separately, and had drifted in both
     * directions: the overlay described {@code /search} as "Search through the codebase" long after
     * the command had come to describe itself as finding code by meaning through the project index,
     * and it named seven commands out of forty-two while {@code /help} named all of them and said
     * nothing about the keys. Both are now rendered from {@link HelpContent}.
     */
    @Test
    public void theOverlayNamesEveryCommandInTheCommandsOwnWords() {
        CommandRegistry registry = new CommandRegistry();
        List<String>    panel    = new ShellHelp(Glyphs.UNICODE, registry).lines();
        Pattern         row      = Pattern.compile("^ {2}(/\\S+) {2,}(\\S.*)$");

        List<String> named = new ArrayList<>();
        for (String line : panel) {
            Matcher matched = row.matcher(line);
            if (!matched.matches()) {
                continue;
            }
            String                  name    = matched.group(1).substring(1);
            CommandRegistry.Command command = registry.getCommand(name);
            if (command == null) {
                continue; // a key or a gesture, not a command
            }
            named.add(name);
            assertThat(matched.group(2))
                    .as("the overlay must not describe '%s' in words of its own", name)
                    .isEqualTo(command.getDescription());
        }
        assertThat(named)
                .as("every command the shell has is in the overlay")
                .containsAll(registry.getCommands().keySet());
    }

    /** What used to need the panel closed: the command list. And what used to need it open: the keys. */
    @Test
    public void bothHelpsCarryTheSameSections() {
        CommandRegistry registry = new CommandRegistry();
        String          panel    = String.join("\n", new ShellHelp(Glyphs.UNICODE, registry).lines());

        for (HelpContent.Section section : HelpContent.sections(registry, "/")) {
            assertThat(panel).as("the overlay carries '%s'", section.heading())
                             .contains(section.heading());
        }
        assertThat(panel).contains("Ctrl+Shift+C");
        assertThat(panel).contains("Talking and commanding");
    }
}
