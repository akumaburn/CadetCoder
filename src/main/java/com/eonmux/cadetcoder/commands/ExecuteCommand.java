package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ExitCode;
import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.error.ErrorHandler;
import com.eonmux.cadetcoder.shell.ScriptExecutor;
import com.eonmux.cadetcoder.util.FilePathResolver;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.nio.file.Paths;

@Command (name = "execute", description = "Run a file of commands, one per line")
public class ExecuteCommand implements CommandRegistry.Command, java.util.concurrent.Callable<Integer> {

    @Parameters (index = "0", description = "Path to script file")
    private String scriptFilePath;

    @Override
    public Integer call() throws Exception {
        return execute(new String[] {scriptFilePath});
    }

    @Override
    public int execute(String[] args) {
        try {
            if (args.length == 0) {
                OutputFormatter.printError("Script file path is required");
                return 1;
            }

            // A local, not the field. The registry builds one ExecuteCommand and hands it every
            // run, so a field written here is the argument the NEXT run starts from; the field is
            // picocli's binding for call() and nothing else.
            String requestedPath = args[0];

            // Use FilePathResolver to resolve the file path
            Path                          currentDir = Paths.get(System.getProperty("user.dir"));
            FilePathResolver.ResolvedPath resolved   = FilePathResolver.resolve(requestedPath, currentDir, false);

            // Handle resolution result
            Path path = resolved.getPath();

            if (!resolved.exists()) {
                // If alternatives are available, let user select
                if (resolved.hasAlternatives()) {
                    OutputFormatter.printError(resolved.getErrorMessage());
                    Path selected = resolved.selectFromAlternatives();
                    if (selected != null) {
                        path = selected;
                    } else {
                        // Nobody chose one of the alternatives, so nothing was run. That is the
                        // user declining rather than the command failing.
                        OutputFormatter.printWarning("Invalid selection. Operation cancelled.");
                        return ExitCode.INTERRUPTED;
                    }
                } else {
                    OutputFormatter.printError(resolved.getErrorMessage());
                    return 1;
                }
            }

            ScriptExecutor executor = new ScriptExecutor(path.toString());
            int            scriptExitCode = executor.executeScript();
            if (scriptExitCode == 0) {
                OutputFormatter.printSuccess("Script executed successfully.");
            } else {
                OutputFormatter.printError("Script completed with failures (exit code " + scriptExitCode + ")");
            }
            return scriptExitCode;
        } catch (Exception e) {
            // Delegate exception handling to ErrorHandler
            ErrorHandler.getInstance().handleException(e);
            return 1;
        }
    }


    @Override
    public String getUsage() {
        return "execute <script_file>";
    }
}
