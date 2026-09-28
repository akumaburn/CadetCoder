package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.ui.ProgramOutput;
import com.eonmux.cadetcoder.ui.UnifiedOutput;
import com.eonmux.cadetcoder.util.FilePathResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.Callable;

@Command (name = "notebookread", description = "Read Jupyter notebook contents")
public class NotebookReadCommand extends LoggingCommandSupport implements CommandRegistry.Command, Callable<Integer> {

    private final ObjectMapper mapper = new ObjectMapper();
    @Parameters (index = "0", description = "Path to the Jupyter notebook file")
    private String notebookPath;
    @Option (names = {"-c", "--cell"}, description = "Specific cell ID to read")
    private String cellId;

    @Override
    public int execute(String[] args) {
        try {
            startCommandLogging("notebookread", args);
            logStep("Initializing notebook read command");
            
            // The command catalog advertises --cell=<id>; accept it as well as "--cell <id>".
            args = CommandOptions.expandInlineValues(args, java.util.Set.of("-c", "--cell"));

            if (args.length == 0) {
                logErrorQuietly("execute", "No notebook path provided");
                OutputFormatter.printError("No notebook path provided");
                completeCommandLogging(1);
                return 1;
            }

            String targetPath   = args[0];
            String targetCellId = null;

            // Parse options
            for (int i = 1; i < args.length; i++) {
                if ((args[i].equals("-c") || args[i].equals("--cell")) && i + 1 < args.length) {
                    targetCellId = args[++i];
                }
            }
            
            logStep("Notebook read configuration", String.format("Path: %s, Cell ID: %s", 
                targetPath, targetCellId != null ? targetCellId : "all"));

            int result = readNotebook(targetPath, targetCellId);
            completeCommandLogging(result);
            return result;
        } catch (Exception e) {
            logErrorQuietly("execute", "Notebook read execution failed", e);
            OutputFormatter.printError("Error reading notebook: " + e.getMessage());
            completeCommandLogging(1);
            return 1;
        }
    }

    private int readNotebook(String targetPath, String targetCellId) throws IOException {
        logStep("Starting notebook read", String.format("Target: %s", targetPath));
        
        // Use FilePathResolver to resolve the file path
        logStep("Resolving file path", String.format("Path: %s", targetPath));
        Path                          currentDir = Paths.get(System.getProperty("user.dir"));
        FilePathResolver.ResolvedPath resolved   = FilePathResolver.resolve(targetPath, currentDir, false);

        // Handle resolution result
        Path path = resolved.getPath();

        if (!resolved.exists()) {
            logWarning("File not found", String.format("Notebook file not found: %s", targetPath));
            // If alternatives are available, let user select
            if (resolved.hasAlternatives()) {
                OutputFormatter.printError(resolved.getErrorMessage());
                Path selected = resolved.selectFromAlternatives();
                if (selected != null) {
                    path = selected;
                    logStep("Alternative file selected", String.format("Selected: %s", path));
                } else {
                    logWarning("No file selected", "User cancelled file selection");
                    return 1;
                }
            } else {
                OutputFormatter.printError(resolved.getErrorMessage());
                return 1;
            }
        }

        if (!path.toString().endsWith(".ipynb")) {
            logWarning("File format warning", "File does not appear to be a Jupyter notebook (.ipynb)");
            OutputFormatter.printWarning("File does not appear to be a Jupyter notebook (.ipynb)");
        }

        // Read and parse notebook
        logStep("Reading notebook file", String.format("File: %s", path.getFileName()));
        long readStartTime = System.currentTimeMillis();
        String content = Files.readString(path);
        long readDuration = System.currentTimeMillis() - readStartTime;
        
        logFileOperation("read", path.toString(), true);
        logPerformance("Notebook file read", readDuration);
        
        JsonNode notebook = mapper.readTree(content);

        // What is about to be shown is what earns the right to edit it later; see ReadBeforeEdit.
        ReadBeforeEdit.sawContents(path);

        OutputFormatter.printHeader("Notebook: " + path.getFileName());

        // Display notebook metadata
        JsonNode metadata = notebook.get("metadata");
        if (metadata != null && metadata.has("kernelspec")) {
            JsonNode kernelspec  = metadata.get("kernelspec");
            String   language    = kernelspec.has("language") ? kernelspec.get("language").asText() : "unknown";
            String   displayName =
                    kernelspec.has("display_name") ? kernelspec.get("display_name").asText() : "unknown";
            logStep("Notebook metadata", String.format("Kernel: %s (%s)", displayName, language));
            OutputFormatter.printInfo("Kernel: " + displayName + " (" + language + ")");
        }

        // Display cells
        JsonNode cells = notebook.get("cells");
        if (cells == null || !cells.isArray()) {
            logWarning("No cells found", "Notebook contains no cells");
            OutputFormatter.printWarning("No cells found in notebook");
            return 0;
        }
        
        logDataProcessing("parse", "notebook cells", cells.size(), 0);

        int cellCount = 0;
        int displayedCells = 0;
        for (JsonNode cell : cells) {
            cellCount++;
            String id = cell.has("id") ? cell.get("id").asText() : "cell-" + cellCount;

            // If specific cell requested, skip others
            if (targetCellId != null && !id.equals(targetCellId)) {
                continue;
            }

            displayCell(cell, id, cellCount);
            displayedCells++;

            if (id.equals(targetCellId)) {
                logStep("Target cell found and displayed", String.format("Cell ID: %s", targetCellId));
                break; // Found the requested cell
            }
        }

        if (targetCellId != null && displayedCells == 0) {
            logErrorQuietly("readNotebook", String.format("Cell not found: %s", targetCellId));
            OutputFormatter.printError("Cell not found: " + targetCellId);
            return 1;
        }

        if (targetCellId == null) {
            logDataProcessing("display", "cells", displayedCells, 0);
            OutputFormatter.printInfo("Total cells: " + cellCount);
        } else {
            logStep("Specific cell read completed", String.format("Cell ID: %s", targetCellId));
        }

        return 0;
    }

    private void displayCell(JsonNode cell, String id, int index) {
        JsonNode cellTypeNode = cell.get("cell_type");
        String cellType = (cellTypeNode != null && !cellTypeNode.isNull()) ? cellTypeNode.asText() : "unknown";

        OutputFormatter.printSubheader(String.format("Cell %d [%s] - %s", index, id, cellType));

        // Display source
        JsonNode source = cell.get("source");
        if (source != null) {
            String sourceText = extractText(source);
            if (!sourceText.isEmpty()) {
                if (cellType.equals("markdown")) {
                    UnifiedOutput.println("Markdown:");
                } else {
                    UnifiedOutput.println("Code:");
                }
                ProgramOutput.println(sourceText);
            }
        }

        // Display outputs for code cells
        if (cellType.equals("code")) {
            JsonNode outputs = cell.get("outputs");
            if (outputs != null && outputs.isArray() && outputs.size() > 0) {
                UnifiedOutput.println("\nOutputs:");
                for (JsonNode output : outputs) {
                    displayOutput(output);
                }
            }

            // Display execution count
            JsonNode execCount = cell.get("execution_count");
            if (execCount != null && !execCount.isNull()) {
                UnifiedOutput.println("Execution count: " + execCount.asInt());
            }
        }

        UnifiedOutput.println(); // Empty line between cells
    }

    private String extractText(JsonNode node) {
        if (node.isTextual()) {
            return node.asText();
        } else if (node.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode item : node) {
                sb.append(item.asText());
            }
            return sb.toString();
        }
        return "";
    }

    private void displayOutput(JsonNode output) {
        JsonNode outputTypeNode = output.get("output_type");
        if (outputTypeNode == null || outputTypeNode.isNull()) {
            return;
        }
        String outputType = outputTypeNode.asText();

        switch (outputType) {
            case "stream":
                JsonNode text = output.get("text");
                if (text != null) {
                    ProgramOutput.print(extractText(text));
                }
                break;

            case "execute_result":
            case "display_data":
                JsonNode data = output.get("data");
                if (data != null) {
                    // Prefer plain text
                    if (data.has("text/plain")) {
                        ProgramOutput.println(extractText(data.get("text/plain")));
                    } else if (data.has("text/html")) {
                        UnifiedOutput.println("[HTML Output - content omitted]");
                    } else if (data.has("image/png")) {
                        UnifiedOutput.println("[Image Output - PNG]");
                    }
                }
                break;

            case "error":
                UnifiedOutput.println("Error:");
                if (output.has("ename")) {
                    UnifiedOutput.println("  " + output.get("ename").asText());
                }
                if (output.has("evalue")) {
                    UnifiedOutput.println("  " + output.get("evalue").asText());
                }
                break;
        }
    }


    @Override
    public String getUsage() {
        return "notebookread <notebook_path> [-c|--cell <cell_id>]";
    }

    @Override
    public Integer call() throws Exception {
        // Route the picocli path through execute() with reconstructed args so
        // both entry points share the command-logging lifecycle and argument
        // validation (e.g. the missing-path check), rather than diverging.
        java.util.List<String> argList = new java.util.ArrayList<>();
        if (notebookPath != null) {
            argList.add(notebookPath);
        }
        if (cellId != null) {
            argList.add("--cell");
            argList.add(cellId);
        }
        return execute(argList.toArray(new String[0]));
    }
}