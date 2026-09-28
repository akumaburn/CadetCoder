package com.eonmux.cadetcoder.shell;

import java.nio.charset.StandardCharsets;
import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.error.ErrorHandler;

import java.io.*;
import java.util.Arrays;

public class ScriptExecutor {
    private final String scriptFilePath;

    public ScriptExecutor(String scriptFilePath) {
        this.scriptFilePath = scriptFilePath;
    }

    /**
     * Executes every command in the script file.
     *
     * @return the worst (numerically largest) non-zero exit code observed across
     *         all executed commands, or 0 when every command succeeded. An unknown
     *         command counts as a failure (exit code 1).
     * @throws IOException if the script file cannot be read, or wrapping any fatal
     *                     runtime error encountered while executing a command (so
     *                     the caller can surface it rather than masking it as success)
     */
    public int executeScript() throws IOException {
        CommandRegistry registry  = new CommandRegistry();
        int             worstCode = 0;
        try (BufferedReader br = java.nio.file.Files.newBufferedReader(
                java.nio.file.Path.of(scriptFilePath), StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                // Skip empty lines and comments. The comment test is applied to the TRIMMED line:
                // testing the raw line meant an indented "  # note" was dispatched as a command.
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                // Tokenize quote-aware and whitespace-tolerant. A plain split(" ") produced an EMPTY
                // first token whenever a line contained two consecutive spaces ("read  a.java" became
                // argv ["", "a.java"], so the path was "" and the real path was dropped), and split
                // any quoted argument apart.
                String[] parts = com.eonmux.cadetcoder.commands.CommandLineTokenizer.tokenize(trimmed);
                if (parts.length == 0) {
                    continue;
                }
                // A leading '/' is accepted so script lines may be written the same way they are typed
                // at the interactive prompt.
                String                  commandName = parts[0].startsWith("/") && parts[0].length() > 1
                                                      ? parts[0].substring(1)
                                                      : parts[0];
                String[]                commandArgs = Arrays.copyOfRange(parts, 1, parts.length);
                CommandRegistry.Command command     = registry.getCommand(commandName);
                if (command != null) {
                    int result = command.execute(commandArgs);
                    if (result != 0) {
                        OutputFormatter.printWarning("Command '" + commandName + "' failed with exit code " + result);
                        worstCode = Math.max(worstCode, result);
                    }
                } else {
                    OutputFormatter.printError("Unknown command in script: " + commandName);
                    worstCode = Math.max(worstCode, 1);
                }
            }
        } catch (IOException e) {
            OutputFormatter.printError("Failed to execute script: " + e.getMessage());
            throw e;
        } catch (Exception e) {
            // Do not swallow a fatal runtime error: surface it so the caller maps it
            // to a non-zero exit code instead of reporting a false success.
            OutputFormatter.printError("Error during script execution: " + e.getMessage());
            ErrorHandler.getInstance().handleException(e);
            throw new IOException("Script execution aborted: " + e.getMessage(), e);
        }
        return worstCode;
    }
}
