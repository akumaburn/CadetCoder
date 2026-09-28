package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.OutputFormatter;

import java.util.List;
import java.util.Set;

/**
 * The reference, printed.
 *
 * <h2>Why the list is grouped</h2>
 *
 * <p>There are more than forty commands. Sorted into one alphabetical column they are a reference
 * you can use only if you already know the name you want -- {@code glob} sits between {@code explain}
 * and {@code grep}, and someone looking for "how do I find a file" has to read all forty-two entries
 * to discover that any of the three is relevant. Grouped by what the command is for, the same list
 * answers that question by letting the reader skip six lines to the right heading.</p>
 *
 * <h2>Why the text is not here</h2>
 *
 * <p>What this prints is {@link HelpContent}, which the shell's F1 overlay draws as well. The two
 * used to hold separate text and had drifted apart in both directions; see that class.</p>
 */
@picocli.CommandLine.Command (name = "help", description = "List the commands, or show how one is used")
public class HelpCommand implements CommandRegistry.Command {

    private CommandRegistry registry;

    public HelpCommand(CommandRegistry registry) {
        this.registry = registry;
    }

    public HelpCommand() {
    }

    public void setCommandRegistry(CommandRegistry registry) {
        this.registry = registry;
    }

    @Override
    public int execute(String[] args) {
        // Guard against a missing registry (default constructor without setCommandRegistry)
        // so help fails with a user-friendly message instead of an NPE.
        if (registry == null) {
            OutputFormatter.printError("Help is unavailable: command registry not initialized.");
            return 1;
        }

        // 'help [command]': when a command name is given, show its detail; otherwise the full list.
        if (args != null && args.length > 0 && args[0] != null && !args[0].trim().isEmpty()) {
            return showCommandHelp(args[0].trim());
        }

        String prefix = CommandUsage.prefix();
        // At the shell this prints the keys and the mouse as well as the commands, so naming it
        // after the commands alone would describe a third of what follows.
        OutputFormatter.printHeader(HelpContent.atTheShell(prefix) ? "Help" : "Commands");

        for (HelpContent.Section section : HelpContent.sections(registry, prefix)) {
            OutputFormatter.println();
            OutputFormatter.printSubheader(section.heading());
            int width = section.width();
            for (HelpContent.Entry entry : section.entries()) {
                OutputFormatter.println(HelpContent.row(entry, width));
            }
            for (String note : section.notes()) {
                OutputFormatter.println();
                OutputFormatter.println("  " + note);
            }
        }

        OutputFormatter.println();
        for (String note : HelpContent.footer(prefix)) {
            OutputFormatter.printInfo(note);
        }
        return 0;
    }

    /** Prints help for a single command, or an error (exit 1) when it is not registered. */
    private int showCommandHelp(String name) {
        // A leading '/' is accepted so "help /grep" works as naturally as "/help grep".
        if (name.length() > 1 && name.charAt(0) == InputRouter.COMMAND_PREFIX) {
            name = name.substring(1);
        }
        // Dispatch is case-insensitive; lower-case the lookup key so 'help READ' resolves the
        // same command as 'help read'.
        String                  prefix  = CommandUsage.prefix();
        CommandRegistry.Command command = registry.getCommand(name.toLowerCase());
        if (command == null) {
            OutputFormatter.printError("Unknown command: " + name);
            List<String> suggestions = InputRouter.suggest(
                    name, registry.getCommands() != null ? registry.getCommands().keySet() : Set.of(), 3);
            if (!suggestions.isEmpty()) {
                OutputFormatter.printInfo("Did you mean: " + prefix
                                          + String.join(", " + prefix, suggestions) + "?");
            }
            OutputFormatter.printInfo("Run '" + prefix + "help' with no arguments to list "
                                      + "available commands.");
            return 1;
        }

        OutputFormatter.printHeader(prefix + name.toLowerCase());
        OutputFormatter.println(command.getDescription());
        OutputFormatter.println();
        OutputFormatter.printSubheader("Usage");
        // Printed plainly, not through printInfo: a usage block is several lines, and the info
        // marker goes on the first of them only, leaving the rest hanging at a different indent.
        for (String line : CommandUsage.render(command.getUsage()).split("\n", -1)) {
            OutputFormatter.println(line.isEmpty() ? "" : "  " + line);
        }
        return 0;
    }


    @Override
    public String getUsage() {
        return "help [command]";
    }
}
