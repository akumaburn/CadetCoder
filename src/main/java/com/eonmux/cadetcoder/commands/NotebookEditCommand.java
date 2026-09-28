package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.session.SessionManager;
import com.eonmux.cadetcoder.util.FilePathResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import picocli.CommandLine.*;

import java.io.IOException;
import java.nio.file.*;
import com.eonmux.cadetcoder.ui.OutputRouter;
import com.eonmux.cadetcoder.security.ReadOnlyGuard;
import com.eonmux.cadetcoder.security.WritePathPolicy;

import java.util.Arrays;
import java.util.List;
import java.util.Scanner;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

@Command (name = "notebookedit", description = "Edit Jupyter notebook cells")
public class NotebookEditCommand extends LoggingCommandSupport implements CommandRegistry.Command, Callable<Integer> {

    /** Valid Jupyter cell types per the nbformat schema. */
    private static final List<String> VALID_CELL_TYPES = Arrays.asList("code", "markdown", "raw");

    private final ObjectMapper mapper = new ObjectMapper();
    @Parameters (index = "0", description = "Path to the Jupyter notebook file")
    private String notebookPath;
    @Parameters (index = "1", description = "Cell ID to edit")
    private String cellId;
    @Parameters (index = "2..*", description = "New source content for the cell")
    private String[] newSourceParts;
    @Option (names = {"-t", "--type"}, description = "Cell type (code or markdown)")
    private       String  cellType;
    @Option (names = {"-m", "--mode"}, description = "Edit mode (replace, insert, delete)")
    private String  editMode = "replace";
    @Option (names = {"-i", "--interactive"}, description = "Enter content interactively")
    private       boolean interactive;

    @Override
    public int execute(String[] args) {
        try {
            startCommandLogging("notebookedit", args);
            logStep("Initializing notebook edit command");
            
            // The command catalog advertises --type=<v>/--mode=<v>; accept those as well as the
            // space-separated form.
            args = CommandOptions.expandInlineValues(args,
                    java.util.Set.of("-t", "--type", "-m", "--mode"));

            if (args.length < 2) {
                OutputFormatter.printError("A notebook path and a cell id are both required.");
                OutputFormatter.printInfo("Usage: " + CommandUsage.render(getUsage()));
                completeCommandLogging(1);
                return 1;
            }

            String        targetPath     = args[0];
            String        targetCellId   = args[1];
            StringBuilder newSource      = new StringBuilder();
            String        mode           = "replace";
            String        type           = null;
            boolean       useInteractive = false;

            // Parse arguments
            for (int i = 2; i < args.length; i++) {
                if (args[i].equals("-t") || args[i].equals("--type")) {
                    if (i + 1 >= args.length) {
                        OutputFormatter.printError("Flag " + args[i] + " requires a value");
                        completeCommandLogging(1);
                        return 1;
                    }
                    type = args[++i];
                } else if (args[i].equals("-m") || args[i].equals("--mode")) {
                    if (i + 1 >= args.length) {
                        OutputFormatter.printError("Flag " + args[i] + " requires a value");
                        completeCommandLogging(1);
                        return 1;
                    }
                    mode = args[++i];
                } else if (args[i].equals("-i") || args[i].equals("--interactive")) {
                    useInteractive = true;
                } else {
                    if (newSource.length() > 0) {
                        newSource.append(" ");
                    }
                    newSource.append(args[i]);
                }
            }
            
            logStep("Notebook edit configuration", String.format("Path: %s, Cell ID: %s, Mode: %s, Type: %s, Interactive: %b", 
                targetPath, targetCellId, mode, type, useInteractive));

            int result = editNotebook(targetPath, targetCellId, newSource.toString(), mode, type, useInteractive);
            completeCommandLogging(result);
            return result;
        } catch (Exception e) {
            logErrorQuietly("execute", "Notebook edit execution failed", e);
            OutputFormatter.printError("Error editing notebook: " + e.getMessage());
            completeCommandLogging(1);
            return 1;
        }
    }

    private int editNotebook(String targetPath, String targetCellId, String newSource,
                             String mode, String type, boolean useInteractive) throws IOException {
        logStep("Starting notebook edit", String.format("Target: %s", targetPath));
        
        if (ReadOnlyGuard.isEnabled()) {
            logSecurityEvent("Read-only mode violation", "Attempted to edit notebook in read-only mode", false);
            ReadOnlyGuard.blocks("edit notebook");
            return 1;
        }

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

        // Require a Jupyter notebook extension before writing JSON to the
        // resolved file; without this guard an edit would overwrite an
        // arbitrary resolved file with notebook JSON.
        if (!path.toString().endsWith(".ipynb")) {
            logWarning("File format error", "Refusing to edit non-notebook file (.ipynb required)");
            OutputFormatter.printError("Cannot edit notebook: file is not a Jupyter notebook (.ipynb): " + path.getFileName());
            return 1;
        }

        // Applied to the resolved path, so an alternative chosen above is checked too. A notebook
        // is a file like any other, and editing one has to obey the same boundary as write and
        // edit rather than accepting whatever path it was handed.
        WritePathPolicy.Decision decision = WritePathPolicy.decide(path);
        if (!decision.isAllowed()) {
            logSecurityEvent("NOTEBOOK_WRITE_VALIDATION", decision + ": " + path, false);
            OutputFormatter.printError(WritePathPolicy.reasonFor(decision, path));
            return 1;
        }

        // A cell is edited by naming it, and a model that has not read the notebook is naming a
        // cell it has not seen; see ReadBeforeEdit.
        String unread = ReadBeforeEdit.reasonNotToChange(path, "notebookedit");
        if (unread != null) {
            OutputFormatter.printError(unread);
            return 1;
        }

        // Validate the requested cell type on every path that may persist it
        // (replace and insert) so invalid types are never written verbatim.
        if (type != null && !isValidCellType(type)) {
            logErrorQuietly("editNotebook", String.format("Invalid cell type: %s", type));
            OutputFormatter.printError("Invalid cell type: " + type + " (expected one of code, markdown, raw)");
            return 1;
        }

        // Interactive prompts would block forever inside the non-interactive
        // agentic loop (no console / TUI input line). Fail fast with a clear
        // message instead of hanging.
        if (useInteractive && !OutputRouter.getInstance().canPrompt()) {
            logWarning("Interactive unavailable", "Interactive mode requested without an interactive terminal");
            OutputFormatter.printError(
                    "Interactive mode is not available in non-interactive sessions; provide the cell source as an argument instead");
            return 1;
        }

        // Save state for undo
        logStep("Saving session state for undo");
        SessionManager.getInstance().saveState();

        // Read and parse notebook
        logStep("Reading notebook file", String.format("File: %s", path.getFileName()));
        long readStartTime = System.currentTimeMillis();
        String content = Files.readString(path);
        long readDuration = System.currentTimeMillis() - readStartTime;
        
        logFileOperation("read", path.toString(), true);
        logPerformance("Notebook file read", readDuration);
        
        JsonNode   root     = mapper.readTree(content);
        ObjectNode notebook = (ObjectNode) root;

        ArrayNode cells = (ArrayNode) notebook.get("cells");
        if (cells == null) {
            logErrorQuietly("editNotebook", "No cells found in notebook");
            OutputFormatter.printError("No cells found in notebook");
            return 1;
        }
        
        logDataProcessing("parse", "notebook cells", cells.size(), 0);

        // Handle different edit modes
        logStep("Executing edit mode", String.format("Mode: %s", mode));
        switch (mode.toLowerCase()) {
            case "replace":
                return replaceCell(notebook, cells, targetCellId, newSource, type, useInteractive, path);
            case "insert":
                return insertCell(notebook, cells, targetCellId, newSource, type, useInteractive, path);
            case "delete":
                return deleteCell(notebook, cells, targetCellId, path);
            default:
                logErrorQuietly("editNotebook", String.format("Unknown edit mode: %s", mode));
                OutputFormatter.printError("Unknown edit mode: " + mode);
                return 1;
        }
    }

    private int replaceCell(ObjectNode notebook, ArrayNode cells, String cellId,
                            String newSource, String type, boolean useInteractive, Path path) throws IOException {
        logStep("Replacing cell", String.format("Cell ID: %s", cellId));
        boolean found = false;

        for (int i = 0; i < cells.size(); i++) {
            ObjectNode cell      = (ObjectNode) cells.get(i);
            String     currentId = cell.has("id") ? cell.get("id").asText() : "cell-" + (i + 1);

            if (currentId.equals(cellId)) {
                found = true;
                logStep("Cell found", String.format("Cell %s at index %d", cellId, i));

                // Get interactive input if needed
                if (useInteractive) {
                    logStep("Getting interactive input");
                    newSource = getInteractiveInput();
                }

                // Update cell source following Jupyter conventions (no trailing
                // newline on the final source line, CRLF stripped, no spurious
                // blank line for empty input).
                ArrayNode sourceArray = buildSourceArray(newSource);
                cell.set("source", sourceArray);

                logDataProcessing("update", "cell source lines", sourceArray.size(), 0);

                // Update cell type if specified
                if (type != null) {
                    logStep("Updating cell type", String.format("New type: %s", type));
                    cell.put("cell_type", type.toLowerCase());
                    if (type.equalsIgnoreCase("code")) {
                        if (!cell.has("execution_count")) {
                            cell.putNull("execution_count");
                        }
                        if (!cell.has("outputs")) {
                            cell.set("outputs", mapper.createArrayNode());
                        }
                    } else {
                        // Converting away from a code cell: code-only fields are
                        // invalid on non-code cells, so drop any stale outputs and
                        // execution_count.
                        cell.remove("outputs");
                        cell.remove("execution_count");
                    }
                }

                break;
            }
        }

        if (!found) {
            logErrorQuietly("replaceCell", String.format("Cell not found: %s", cellId));
            OutputFormatter.printError("Cell not found: " + cellId);
            return 1;
        }

        // Write updated notebook
        logStep("Writing updated notebook");
        long writeStartTime = System.currentTimeMillis();
        writeNotebookAtomically(notebook, path);
        long writeDuration = System.currentTimeMillis() - writeStartTime;

        logFileOperation("write", path.toString(), true);
        logPerformance("Notebook file write", writeDuration);

        OutputFormatter.printSuccess("Cell " + cellId + " updated successfully");
        return 0;
    }

    private int insertCell(ObjectNode notebook, ArrayNode cells, String afterCellId,
                           String source, String type, boolean useInteractive, Path path) throws IOException {
        logStep("Inserting new cell", String.format("After cell ID: %s, Type: %s", afterCellId, type));
        
        if (type == null) {
            logErrorQuietly("insertCell", "Cell type is required for insert mode");
            OutputFormatter.printError("Cell type is required for insert mode");
            return 1;
        }

        // Get interactive input if needed
        if (useInteractive) {
            logStep("Getting interactive input for new cell");
            source = getInteractiveInput();
        }

        // Create new cell
        String newCellId = UUID.randomUUID().toString();
        logStep("Creating new cell", String.format("New cell ID: %s", newCellId));
        
        ObjectNode newCell = mapper.createObjectNode();
        newCell.put("cell_type", type.toLowerCase());
        newCell.put("id", newCellId);

        ArrayNode sourceArray = buildSourceArray(source);
        newCell.set("source", sourceArray);

        logDataProcessing("create", "cell source lines", sourceArray.size(), 0);

        if (type.equalsIgnoreCase("code")) {
            newCell.putNull("execution_count");
            newCell.set("outputs", mapper.createArrayNode());
            newCell.set("metadata", mapper.createObjectNode());
        } else {
            newCell.set("metadata", mapper.createObjectNode());
        }

        // Find position to insert
        if (afterCellId == null || afterCellId.equals("null")) {
            // Insert at beginning
            logStep("Inserting cell at beginning");
            cells.insert(0, newCell);
        } else {
            boolean found = false;
            for (int i = 0; i < cells.size(); i++) {
                JsonNode cell      = cells.get(i);
                String   currentId = cell.has("id") ? cell.get("id").asText() : "cell-" + (i + 1);

                if (currentId.equals(afterCellId)) {
                    logStep("Target cell found", String.format("Inserting after cell %s at index %d", afterCellId, i));
                    cells.insert(i + 1, newCell);
                    found = true;
                    break;
                }
            }

            if (!found) {
                logErrorQuietly("insertCell", String.format("Target cell not found: %s", afterCellId));
                OutputFormatter.printError("Cell not found: " + afterCellId);
                return 1;
            }
        }

        // Write updated notebook
        logStep("Writing updated notebook with new cell");
        long writeStartTime = System.currentTimeMillis();
        writeNotebookAtomically(notebook, path);
        long writeDuration = System.currentTimeMillis() - writeStartTime;

        logFileOperation("write", path.toString(), true);
        logPerformance("Notebook file write", writeDuration);

        OutputFormatter.printSuccess("New cell inserted successfully");
        return 0;
    }

    private int deleteCell(ObjectNode notebook, ArrayNode cells, String cellId, Path path) throws IOException {
        logStep("Deleting cell", String.format("Cell ID: %s", cellId));
        boolean found = false;

        for (int i = 0; i < cells.size(); i++) {
            JsonNode cell      = cells.get(i);
            String   currentId = cell.has("id") ? cell.get("id").asText() : "cell-" + (i + 1);

            if (currentId.equals(cellId)) {
                logStep("Cell found for deletion", String.format("Cell %s at index %d", cellId, i));
                cells.remove(i);
                found = true;
                break;
            }
        }

        if (!found) {
            logErrorQuietly("deleteCell", String.format("Cell not found: %s", cellId));
            OutputFormatter.printError("Cell not found: " + cellId);
            return 1;
        }

        // Write updated notebook
        logStep("Writing updated notebook after deletion");
        long writeStartTime = System.currentTimeMillis();
        writeNotebookAtomically(notebook, path);
        long writeDuration = System.currentTimeMillis() - writeStartTime;

        logFileOperation("write", path.toString(), true);
        logPerformance("Notebook file write", writeDuration);
        logDataProcessing("delete", "remaining cells", cells.size(), 0);

        OutputFormatter.printSuccess("Cell " + cellId + " deleted successfully");
        return 0;
    }

    /**
     * Builds a Jupyter {@code source} array from raw cell text.
     *
     * <p>Follows the Jupyter notebook format convention: each line except the
     * last carries a trailing newline, the final line has none, any per-line
     * carriage return (CRLF input) is stripped, and empty input yields an empty
     * array rather than a spurious blank line.
     *
     * @param source the raw cell source text (may be empty)
     * @return an {@link ArrayNode} of source lines
     */
    private ArrayNode buildSourceArray(String source) {
        ArrayNode sourceArray = mapper.createArrayNode();
        if (source == null || source.isEmpty()) {
            return sourceArray;
        }

        // Split on newline keeping a stable count; -1 limit preserves trailing
        // empty strings so an explicit trailing newline is not silently dropped.
        String[] lines = source.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            // Strip a trailing carriage return left by CRLF line endings.
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            // Trailing newline on every line except the last (Jupyter convention).
            if (i < lines.length - 1) {
                sourceArray.add(line + "\n");
            } else {
                // Drop a final empty element produced by a trailing newline so the
                // last real line keeps its "\n" and no spurious blank line is added.
                if (!line.isEmpty()) {
                    sourceArray.add(line);
                }
            }
        }
        return sourceArray;
    }

    /**
     * Whether the given cell type is a valid Jupyter cell type.
     *
     * @param type the candidate cell type (case-insensitive; may be {@code null})
     * @return {@code true} if non-null and one of {@code code}, {@code markdown}, {@code raw}
     */
    private boolean isValidCellType(String type) {
        return type != null && VALID_CELL_TYPES.contains(type.toLowerCase());
    }

    /**
     * Writes notebook JSON to {@code path} atomically.
     *
     * <p>Serializes to a sibling temporary file first, then moves it into place
     * so a crash mid-write cannot leave a truncated/corrupt notebook. Falls back
     * to a non-atomic replace move only if the platform rejects
     * {@link StandardCopyOption#ATOMIC_MOVE}.
     *
     * @param notebook the notebook JSON to persist
     * @param path     the destination notebook path
     * @throws IOException if serialization or the move fails
     */
    private void writeNotebookAtomically(ObjectNode notebook, Path path) throws IOException {
        String  updatedContent = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(notebook);
        Path    parent         = path.toAbsolutePath().getParent();
        Path    tempFile       = Files.createTempFile(parent, path.getFileName().toString(), ".tmp");
        try {
            Files.writeString(tempFile, updatedContent);
            try {
                Files.move(tempFile, path,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                logWarning("Atomic move unsupported", "Falling back to non-atomic replace move");
                Files.move(tempFile, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            // Clean up the temp file if the move did not consume it (e.g. failure).
            Files.deleteIfExists(tempFile);
        }
        // Having written it, the run knows what is in it; see ReadBeforeEdit.
        ReadBeforeEdit.sawContents(path);
    }

    private String getInteractiveInput() {
        OutputFormatter.printInfo("Enter cell content (type 'EOF' on a new line to finish):");
        StringBuilder content = new StringBuilder();

        // TUI-aware line source: under the TUI, read via the shell prompt (System.in is owned by
        // JLine); otherwise read System.in directly (terminal, pipe, or injected test input). The
        // System.in scanner is intentionally not closed (closing it would close standard input).
        OutputRouter     router = OutputRouter.getInstance();
        Supplier<String> lineReader;
        if (router.isRouting()) {
            lineReader = () -> router.getUserInput("");
        } else {
            Scanner scanner = new Scanner(System.in);
            lineReader = () -> scanner.hasNextLine() ? scanner.nextLine() : null;
        }

        while (true) {
            String line = lineReader.get();
            if (line == null || line.equals("EOF")) {
                break;
            }
            content.append(line).append("\n");
        }

        return content.toString();
    }


    @Override
    public String getUsage() {
        // Options on their own lines, like grep/ls/glob/websearch. On one line this was 130
        // characters, which wraps on any normal terminal into an unreadable second row.
        return "notebookedit <notebook_path> <cell_id> [new_source] [options]\n" +
               "  -t, --type <code|markdown>          Cell type (required for insert)\n" +
               "  -m, --mode <replace|insert|delete>  What to do (default: replace)\n" +
               "  -i, --interactive                   Read the new source interactively";
    }

    @Override
    public Integer call() throws Exception {
        // Route the picocli path through execute() with reconstructed args so
        // both entry points share the command-logging lifecycle and argument
        // validation (e.g. the insufficient-args check), rather than diverging.
        java.util.List<String> argList = new java.util.ArrayList<>();
        if (notebookPath != null) {
            argList.add(notebookPath);
        }
        if (cellId != null) {
            argList.add(cellId);
        }
        if (newSourceParts != null) {
            for (String part : newSourceParts) {
                argList.add(part);
            }
        }
        if (cellType != null) {
            argList.add("--type");
            argList.add(cellType);
        }
        // Only forward --mode when the user selected a non-default mode so the
        // reconstructed args do not inflate the positional count that execute()'s
        // insufficient-args check relies on.
        if (editMode != null && !editMode.equalsIgnoreCase("replace")) {
            argList.add("--mode");
            argList.add(editMode);
        }
        if (interactive) {
            argList.add("--interactive");
        }
        return execute(argList.toArray(new String[0]));
    }
}