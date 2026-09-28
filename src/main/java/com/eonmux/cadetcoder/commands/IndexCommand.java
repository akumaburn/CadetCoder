package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.context.ContextEngine;
import com.eonmux.cadetcoder.error.ErrorHandler;
import picocli.CommandLine.Command;

import java.io.File;

@Command (name = "index", description = "Rebuild the searchable index of the project's files")
public class IndexCommand implements CommandRegistry.Command {

    @Override
    public int execute(String[] args) {
        try {
            // index-search-2: the catalog advertises "index [path]". Honor a single optional
            // path argument (validated below) and reject any extra arguments with a clear
            // message instead of silently ignoring them.
            String[] cleanArgs = stripBlanks(args);
            if (cleanArgs.length > 1) {
                OutputFormatter.printError(
                        "Too many arguments. Usage: " + CommandUsage.render(getUsage()));
                return 1;
            }

            if (cleanArgs.length == 1) {
                String pathArg  = cleanArgs[0];
                File   target   = new File(pathArg);
                if (!target.exists()) {
                    OutputFormatter.printError(
                            "Path does not exist: " + pathArg);
                    return 1;
                }
                // The index engine reindexes the whole project (incrementally), so a subtree
                // path cannot be scoped independently. Be explicit about that rather than
                // implying a partial reindex occurred.
                OutputFormatter.printInfo(
                        "Reindexing the full project (subtree scoping is not supported); "
                                + "changes under " + pathArg + " will be picked up.");
            }

            ContextEngine contextEngine = ContextEngine.getInstance();
            if (contextEngine.wasIndexRebuilt()) {
                OutputFormatter.printWarning(
                        "The existing index could not be read (it was written by a different "
                                + "version) and has been rebuilt from scratch.");
            }
            // One line for one action. This used to be three: the engine announced the reindex its
            // own constructor had run, then the one this command asked for, then this command said
            // the same thing a third time in different words.
            ContextEngine.ReindexSummary summary = contextEngine.reindex();
            OutputFormatter.printSuccess("Code index updated. " + summary.describe());
            return 0;
        } catch (Exception e) {
            // Delegate exception handling to ErrorHandler
            ErrorHandler.getInstance().handleException(e);
            return 1;
        }
    }

    /**
     * Removes null and blank entries from the raw argument array so accidental whitespace tokens
     * are not treated as a path argument.
     *
     * @param args the raw arguments (may be {@code null})
     * @return a new array containing only non-blank arguments
     */
    private String[] stripBlanks(String[] args) {
        if (args == null) {
            return new String[0];
        }
        return java.util.Arrays.stream(args)
                .filter(a -> a != null && !a.trim().isEmpty())
                .toArray(String[]::new);
    }


    @Override
    public String getUsage() {
        return "index [path]";
    }
}
