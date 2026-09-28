package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.context.ProjectContext;
import com.eonmux.cadetcoder.ui.ProgramOutput;
import com.eonmux.cadetcoder.ui.UnifiedOutput;
import picocli.CommandLine.*;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Callable;

@Command (name = "context", description = "Show or rebuild the project notes (CADET.md)")
public class ContextCommand implements CommandRegistry.Command, Callable<Integer> {

    @Parameters (index = "0", description = "Action: show, create, reload, clear", defaultValue = "show")
    private String action;

    @Option (names = {"-v", "--verbose"}, description = "Show detailed context information")
    private boolean verbose;

    @Override
    public Integer call() throws Exception {
        String act = action != null ? action : "show";
        if (verbose) {
            return execute(new String[] {act, "-v"});
        }
        return execute(new String[] {act});
    }

    @Override
    public int execute(String[] args) {
        try {
            String  act         = "show";
            boolean verboseMode = false;
            boolean actionSet   = false;

            // Parse options and action in any order: the first non-option token is the
            // action, and -v/--verbose may appear before or after it.
            for (String arg : args) {
                if (arg.equals("-v") || arg.equals("--verbose")) {
                    verboseMode = true;
                } else if (!arg.startsWith("-") && !actionSet) {
                    act = arg.toLowerCase();
                    actionSet = true;
                }
            }

            switch (act) {
                case "show":
                    return showContext(verboseMode);
                case "create":
                    return createContext();
                case "reload":
                    return reloadContext();
                case "clear":
                    return clearContext();
                default:
                    OutputFormatter.printError("Unknown action: " + act);
                    OutputFormatter.printInfo("Valid actions: show, create, reload, clear");
                    return 1;
            }
        } catch (Exception e) {
            OutputFormatter.printError("Error managing context: " + e.getMessage());
            return 1;
        }
    }

    private int showContext(boolean verboseMode) {
        ProjectContext context = ProjectContext.getInstance();

        if (!context.hasProjectContext()) {
            OutputFormatter.printWarning("No project context found (CADET.md)");
            OutputFormatter.printInfo("Use '" + CommandUsage.prefix() + "context create' to create a default context file");
            return 0;
        }

        OutputFormatter.printHeader("Project Context");

        List<String> contextFiles = context.getContextFiles();
        if (!contextFiles.isEmpty()) {
            OutputFormatter.printInfo("Context files: " + String.join(", ", contextFiles));
        }

        if (verboseMode) {
            UnifiedOutput.println("\nContext content:");
            UnifiedOutput.println("================");
            ProgramOutput.println(context.getProjectContext());
        } else {
            String content = context.getProjectContext();
            int    lines   = content.split("\n").length;
            int    chars   = content.length();
            OutputFormatter.printInfo(String.format("Context size: %d lines, %d characters", lines, chars));

            // Show first few lines
            String[] contentLines = content.split("\n");
            UnifiedOutput.println("\nFirst 10 lines:");
            ProgramOutput.println(String.join("\n", java.util.Arrays.copyOfRange(
                    contentLines, 0, Math.min(10, contentLines.length))));
            if (contentLines.length > 10) {
                UnifiedOutput.println("... (" + (contentLines.length - 10) + " more lines)");
            }
        }

        return 0;
    }

    private int createContext() {
        try {
            boolean created = ProjectContext.createDefaultContextFile();

            // Reload to pick up the new file (or any pre-existing one).
            ProjectContext.getInstance().reload();

            if (created) {
                OutputFormatter.printInfo("You can now edit CADET.md to customize your project context");
            } else {
                OutputFormatter.printInfo("CADET.md already exists; edit it to customize your project context");
            }
            return 0;
        } catch (IOException e) {
            OutputFormatter.printError("Failed to create context file: " + e.getMessage());
            return 1;
        }
    }

    private int reloadContext() {
        ProjectContext.getInstance().reload();

        if (ProjectContext.getInstance().hasProjectContext()) {
            OutputFormatter.printSuccess("Project context reloaded successfully");
        } else {
            OutputFormatter.printWarning("No project context found after reload");
        }

        return 0;
    }

    private int clearContext() {
        ProjectContext context = ProjectContext.getInstance();

        if (!context.hasProjectContext()) {
            OutputFormatter.printInfo("No project context loaded; nothing to clear");
            return 0;
        }

        context.clear();
        OutputFormatter.printSuccess("Project context cleared; use '" + CommandUsage.prefix() + "context reload' to load it again");
        return 0;
    }


    @Override
    public String getUsage() {
        return "context [show|create|reload|clear] [-v|--verbose]";
    }
}