package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ai.StandInAnswer;
import com.eonmux.cadetcoder.util.TextFiles;
import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.ai.AIManager;
import com.eonmux.cadetcoder.ai.PromptData;
import com.eonmux.cadetcoder.prompts.TemplatePromptBuilder;
import com.eonmux.cadetcoder.security.ReadOnlyGuard;
import com.eonmux.cadetcoder.security.WritePathPolicy;
import com.eonmux.cadetcoder.ui.InteractivePrompts;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

@Command (name = "refactor", description = "Refactor code with AI assistance")
public class RefactorCommand implements IterativeCommand, CommandRegistry.InterruptibleCommand {

    @Parameters (index = "0", description = "The file path to refactor")
    private String filePath;

    @Parameters (index = "1..*", description = "Optional refactoring instructions")
    private String[] refactorInstructions;

    private CommandRegistry.InterruptionContext interruptionContext;

    @Override
    public void setInterruptionContext(CommandRegistry.InterruptionContext context) {
        this.interruptionContext = context;
    }

    @Override
    public int execute(String[] args) {
        // Refactoring rewrites the file in place, so it is a write like any other.
        if (ReadOnlyGuard.blocks("refactor")) {
            return 1;
        }

        // Use iterative executor for better multi-step handling
        IterativeExecutor executor = new IterativeExecutor();
        return executor.execute(this, args);
    }


    @Override
    public String getUsage() {
        return "refactor <filepath> [instructions]";
    }

    @Override
    public StepResult executeStep(String[] args, Map<String, Object> context, String llmResponse) {
        String step = (String) context.getOrDefault("step", "initial");

        switch (step) {
            case "initial":
                if (args == null || args.length < 1) {
                    context.put("step", "get_file");
                    return new StepResult(false, "No file specified for refactoring", context,
                            "Which file would you like me to refactor? Please provide the file path.");
                }

                String filePath = args[0];
                File file = new File(filePath);

                if (!file.exists()) {
                    context.put("searchPattern", filePath);
                    context.put("step", "search_file");
                    return new StepResult(false, "File not found: " + filePath, context,
                            "The file '" +
                            filePath +
                            "' was not found. Should I search for files matching this pattern?");
                }

                // File exists, proceed to read it
                context.put("filePath", file.getAbsolutePath());
                context.put("refactorInstructions",
                        args.length > 1 ? java.util.Arrays.copyOfRange(args, 1, args.length) : new String[0]);
                context.put("step", "read_file");
                return executeStep(args, context, null);

            case "get_file":
                if (llmResponse != null && !llmResponse.trim().isEmpty()) {
                    File responseFile = new File(llmResponse.trim());
                    if (!responseFile.exists()) {
                        context.put("searchPattern", llmResponse.trim());
                        context.put("step", "search_file");
                        return new StepResult(false, "File not found", context,
                                "The file '" + llmResponse.trim() + "' was not found. Should I search for it?");
                    }
                    context.put("filePath", responseFile.getAbsolutePath());
                    context.put("refactorInstructions", new String[0]);
                    context.put("step", "get_instructions");
                    return new StepResult(false, "File found: " + responseFile.getName(), context,
                            "What refactoring would you like me to perform on this file?");
                }
                return StepResult.failure(
                        "No file provided, refactoring cancelled. " +
                        "Re-run 'refactor' with an exact, existing file path to retry.",
                        context);

            case "search_file":
                if (llmResponse != null &&
                    (llmResponse.toLowerCase().contains("yes") || llmResponse.toLowerCase().contains("search"))) {
                    String pattern = (String) context.get("searchPattern");
                    try {
                        List<File> matchingFiles = searchForFiles(pattern);

                        if (matchingFiles.isEmpty()) {
                            return StepResult.failure(
                                    "No files found matching: " + pattern +
                                    ". Re-run 'refactor' with an exact, existing file path to retry.",
                                    context);
                        }

                        if (matchingFiles.size() == 1) {
                            context.put("filePath", matchingFiles.get(0).getAbsolutePath());
                            String[] instructions = (String[]) context.get("refactorInstructions");
                            if (instructions == null || instructions.length == 0) {
                                context.put("step", "get_instructions");
                                return new StepResult(false, "File found: " + matchingFiles.get(0).getName(), context,
                                        "What refactoring would you like me to perform on this file?");
                            } else {
                                context.put("step", "read_file");
                                return executeStep(args, context, null);
                            }
                        }

                        // Multiple files found
                        StringBuilder fileList = new StringBuilder("Found multiple files:\n");
                        for (int i = 0; i < matchingFiles.size(); i++) {
                            fileList.append(i + 1).append(". ").append(matchingFiles.get(i).getPath()).append("\n");
                        }
                        context.put("matchingFiles", matchingFiles);
                        context.put("step", "select_file");
                        return new StepResult(false, fileList.toString(), context,
                                "Which file would you like to refactor? Please specify the number or full path.");
                    } catch (Exception e) {
                        return StepResult.failure("Error searching for files: " + e.getMessage(), context);
                    }
                } else {
                    return StepResult.failure(
                            "File not found and search declined for: " + context.get("searchPattern") +
                            ". Re-run 'refactor' with an exact, existing file path to retry.",
                            context);
                }

            case "select_file":
                if (llmResponse != null) {
                    List<File> matchingFiles = (List<File>) context.get("matchingFiles");
                    if (matchingFiles == null) {
                        return StepResult.success("No matching files in context", context);
                    }
                    String     selected      = llmResponse.trim();

                    try {
                        int index = Integer.parseInt(selected) - 1;
                        if (index >= 0 && index < matchingFiles.size()) {
                            context.put("filePath", matchingFiles.get(index).getAbsolutePath());
                            String[] instructions = (String[]) context.get("refactorInstructions");
                            if (instructions == null || instructions.length == 0) {
                                context.put("step", "get_instructions");
                                return new StepResult(false,
                                        "File selected: " + matchingFiles.get(index).getName(),
                                        context,
                                        "What refactoring would you like me to perform on this file?");
                            } else {
                                context.put("step", "read_file");
                                return executeStep(args, context, null);
                            }
                        }
                    } catch (NumberFormatException e) {
                        // Not a number, check if it's a path
                        for (File matchFile : matchingFiles) {
                            if (matchFile.getPath().equals(selected) || matchFile.getPath().endsWith(selected)) {
                                context.put("filePath", matchFile.getAbsolutePath());
                                String[] instructions = (String[]) context.get("refactorInstructions");
                                if (instructions == null || instructions.length == 0) {
                                    context.put("step", "get_instructions");
                                    return new StepResult(false, "File selected: " + matchFile.getName(), context,
                                            "What refactoring would you like me to perform on this file?");
                                } else {
                                    context.put("step", "read_file");
                                    return executeStep(args, context, null);
                                }
                            }
                        }
                    }

                    return new StepResult(false, "Invalid selection: " + selected, context,
                            "Please select a valid file number or provide the full path.");
                }
                // No selection provided yet; treat as a failure (cancelled) rather than
                // falling through to the terminal "Unknown step" success.
                return StepResult.failure(
                        "No file selection provided, refactoring cancelled. " +
                        "Re-run 'refactor' with an exact, existing file path to retry.",
                        context);

            case "get_instructions":
                if (llmResponse != null && !llmResponse.trim().isEmpty()) {
                    context.put("refactorInstructions", new String[] {llmResponse.trim()});
                    context.put("step", "read_file");
                    return executeStep(args, context, null);
                }
                return StepResult.failure("No instructions provided, refactoring cancelled", context);

            case "read_file":
                String filePathToRefactor = (String) context.get("filePath");
                if (filePathToRefactor == null) {
                    return StepResult.failure("File path not found in context", context);
                }
                // Enforce the security policy before reading: never ship arbitrary
                // (traversal/outside-project/credential) file contents to the LLM.
                if (!new com.eonmux.cadetcoder.security.SecurityValidator().isFileAccessAllowed(filePathToRefactor)) {
                    return StepResult.failure("Access denied: " + filePathToRefactor, context);
                }
                try {
                    String fileContent = TextFiles.readText(Paths.get(filePathToRefactor));
                    context.put("fileContent", fileContent);
                    context.put("step", "generate_refactoring");
                    return executeStep(args, context, null);
                } catch (Exception e) {
                    return StepResult.failure("Error reading file: " + e.getMessage(), context);
                }

            case "generate_refactoring":
                try {
                    String   content      = (String) context.get("fileContent");
                    String[] instructions = (String[]) context.get("refactorInstructions");
                    String   path         = (String) context.get("filePath");

                    String request = "Refactor the following code.";
                    if (instructions != null && instructions.length > 0) {
                        request += " Instructions: " + String.join(" ", instructions);
                    }

                    // Use the template engine for the prompt
                    String prompt = TemplatePromptBuilder.refactor()
                                                         .with("code_content", content)
                                                         .with("refactor_request", request)
                                                         .with("language", path.endsWith(".java") ? "java" : "text")
                                                         .build();

                    // Create PromptData for AI processing
                    PromptData promptData = new PromptData("", prompt);
                    String     aiResponse = AIManager.getInstance().complete(promptData, new HashMap<>());

                    context.put("aiResponse", aiResponse);
                    context.put("step", "preview_refactoring");

                    // Extract preview
                    String preview = extractRefactoringPreview(aiResponse);
                    return new StepResult(false, "Generated refactoring:\n" + preview, context,
                            "Do you want to apply this refactoring? (yes/no/modify)")
                            .addressedToUser();

                } catch (Exception e) {
                    return StepResult.failure("Error generating refactoring: " + e.getMessage(), context);
                }

            case "preview_refactoring":
                // In non-interactive mode, automatically apply the refactoring. In auto mode during a
                // model's work, the question went to the model and its answer is in llmResponse.
                boolean isInteractive = InteractivePrompts.isOn();
                if (!isInteractive && !StandInAnswer.answersNow()) {
                    context.put("step", "apply_refactoring");
                    return executeStep(args, context, null);
                }

                switch (ChangeConsent.readFrom(llmResponse)) {
                    case CANCEL:
                        // Declined, not failed: nothing was rewritten because the user said so.
                        return StepResult.interrupted(StandInAnswer.answersNow()
                                ? "Refactoring declined: the model, answering for you because "
                                  + "security.commandApproval is auto, said no; its reason is above"
                                : "Refactoring cancelled by user", context);
                    case MODIFY:
                        context.put("step", "modify_refactoring");
                        return new StepResult(false, "Let's modify the refactoring", context,
                                "What would you like to change about the refactoring?").addressedToUser();
                    case APPLY:
                        context.put("step", "apply_refactoring");
                        return executeStep(args, context, null);
                    default:
                        break;
                }
                return new StepResult(false, "Please confirm", context,
                        "Please respond with 'yes' to apply, 'no' to cancel, or 'modify' to change the refactoring.")
                        .addressedToUser();

            case "modify_refactoring":
                if (llmResponse != null && !llmResponse.trim().isEmpty()) {
                    String[] oldInstructions = (String[]) context.get("refactorInstructions");
                    // refactorInstructions can be absent (e.g. refactor invoked with no instructions),
                    // so guard against null before joining to avoid an NPE here.
                    String   existing        = (oldInstructions == null || oldInstructions.length == 0)
                            ? ""
                            : String.join(" ", oldInstructions) + " ";
                    String   newInstructions = existing + "(Modified: " + llmResponse + ")";
                    context.put("refactorInstructions", new String[] {newInstructions});
                    context.put("step", "generate_refactoring");
                    return executeStep(args, context, null);
                }
                return StepResult.success("No modifications provided", context);

            case "apply_refactoring":
                try {
                    String aiResponse = (String) context.get("aiResponse");

                    if (aiResponse == null || aiResponse.trim().isEmpty()) {
                        OutputFormatter.printWarning("No AI response available to apply refactoring");
                        return StepResult.success("No AI response available for refactoring", context);
                    }

                    if (aiResponse.contains("```")) {
                        // Restrict the write to the originally resolved target file (the path
                        // validated when the file was read), not any AI-supplied header path.
                        String targetPath = (String) context.get("filePath");
                        if (targetPath == null) {
                            return StepResult.failure("Refactoring target file is unknown", context);
                        }
                        int filesWritten = applyMultipleChanges(aiResponse, targetPath);
                        if (filesWritten == 0) {
                            OutputFormatter.printWarning(
                                    "AI response contained a code block but no file was written");
                            return StepResult.failure("Refactoring was not applied: no files were modified", context);
                        }
                        return StepResult.success("Refactoring applied successfully", context);
                    } else {
                        OutputFormatter.printWarning("AI response did not contain a code block for refactoring");
                        return StepResult.success("AI response did not contain a code block", context);
                    }
                } catch (Exception e) {
                    com.eonmux.cadetcoder.logging.DebugLogger.getInstance()
                            .error("Refactor", "Failed to apply refactoring", e);
                    return StepResult.failure("Error applying refactoring: " + e.getMessage(), context);
                }
        }

        return StepResult.failure("Unknown step: " + step, context);
    }

    @Override
    public String getInitialPrompt(String[] args) {
        if (args == null || args.length == 0) {
            return "The user wants to refactor code but didn't specify which file. What file should be refactored?";
        }
        return null;
    }

    @Override
    public boolean supportsIterativeExecution(String[] args) {
        // Always support iterative execution for better interaction
        return true;
    }

    private List<File> searchForFiles(String pattern) {
        List<File> results    = new ArrayList<>();
        File       currentDir = new File(".");
        searchFilesRecursive(currentDir, pattern.toLowerCase(), results, 0);
        return results;
    }

    private void searchFilesRecursive(File dir, String pattern, List<File> results, int depth) {
        if (depth > 5 || results.size() >= 10) {
            return; // Limit depth and results
        }

        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.getName().toLowerCase().contains(pattern)) {
                    results.add(file);
                }
                if (file.isDirectory()
                    && !com.eonmux.cadetcoder.util.ProjectTreeWalk.isPrunedName(file.getName())) {
                    searchFilesRecursive(file, pattern, results, depth + 1);
                }
            }
        }
    }

    private String extractRefactoringPreview(String aiResponse) {
        return ChangePreview.of(aiResponse);
    }

    /**
     * Applies the refactored code blocks from the AI response.
     *
     * <p>Refactoring is restricted to the single originally resolved target file
     * (the path validated when the file was read). The AI controls both the
     * declared header path and the block contents, so any header that does not
     * resolve to the target file is rejected to prevent a crafted response from
     * writing to an arbitrary location. Headerless blocks default to the target.
     * Before writing, the target is re-validated against the security policy and,
     * unless {@code allowOutsideProject} is enabled, is explicitly confirmed to be
     * within the project.
     *
     * @param aiResponse the raw AI response containing fenced code blocks
     * @param targetPath the originally resolved file being refactored (must be non-null)
     * @return the number of files written (0 or 1)
     */
    private int applyMultipleChanges(String aiResponse, String targetPath) {
        if (targetPath == null) {
            OutputFormatter.printError("Refactoring target file is unknown; nothing was written");
            return 0;
        }

        String[]      lines              = aiResponse.split("\\r?\\n");
        String        currentFile        = null;
        StringBuilder fileContentBuilder = new StringBuilder();
        boolean       inFileBlock        = false;
        boolean       written            = false;
        for (String line : lines) {
            if (!inFileBlock && line.trim().startsWith("```")) {
                inFileBlock = true;
                fileContentBuilder.setLength(0);
            } else if (!inFileBlock && looksLikeFilePath(line)) {
                currentFile = line.trim();
            } else if (inFileBlock && line.trim().startsWith("```")) {
                inFileBlock = false;
                // A declared header must name the file being refactored; a headerless
                // block defaults to that same target. Any other header is rejected so
                // the AI cannot redirect the write to a different file.
                if (currentFile != null && !samePath(currentFile, targetPath)) {
                    OutputFormatter.printWarning(
                            "Skipping refactoring block for unexpected file '" + currentFile +
                            "'; only '" + targetPath + "' may be modified by this command");
                    currentFile = null;
                    continue;
                }
                if (fileContentBuilder.length() > 0 && !written) {
                    written = writeTarget(targetPath, fileContentBuilder.toString());
                }
                currentFile = null;
            } else if (inFileBlock) {
                fileContentBuilder.append(line).append("\n");
            }
        }
        if (inFileBlock && fileContentBuilder.length() > 0) {
            OutputFormatter.printWarning("Incomplete code block in AI response: file " +
                                         (currentFile != null ? currentFile : targetPath) +
                                         " was not fully defined");
        }
        return written ? 1 : 0;
    }

    /**
     * Writes refactored content to the target file after enforcing the shared write policy.
     *
     * <p>The path is re-checked here rather than trusted from earlier in the turn: the model
     * controls both the contents and the file name in a refactor, and the name it produced is not
     * necessarily the one the user asked about. {@link WritePathPolicy} is the same rule
     * {@code write}, {@code edit}, {@code multiedit} and {@code notebookedit} apply, so containment
     * holds whatever {@code security.allowOutsideProject} is set to -- previously the containment
     * half of this check was skipped entirely under the shipped default.</p>
     *
     * @return true if the file was written, false if rejected or the write failed
     */
    private boolean writeTarget(String targetFile, String content) {
        java.nio.file.Path target = Paths.get(targetFile);
        WritePathPolicy.Decision decision = WritePathPolicy.decide(target);
        if (!decision.isAllowed()) {
            OutputFormatter.printError("Refactoring target rejected: "
                                       + WritePathPolicy.reasonFor(decision, target));
            return false;
        }
        try {
            Files.write(Paths.get(targetFile),
                    content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            OutputFormatter.printSuccess("Applied refactoring to " + targetFile);
            return true;
        } catch (java.io.IOException e) {
            OutputFormatter.printError("Error applying refactoring to " + targetFile + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Whether two path strings resolve to the same file. Both are normalized to
     * absolute form so an AI-supplied header expressed differently (relative form,
     * redundant separators) still matches the originally resolved target.
     */
    private boolean samePath(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.equals(b)) {
            return true;
        }
        try {
            return Paths.get(a).toAbsolutePath().normalize()
                    .equals(Paths.get(b).toAbsolutePath().normalize());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Recognizes a line that names a target file/path for any extension (not just .java).
     * A header line is a single, non-blank token that is not a code fence and either carries
     * a dotted extension or contains a path separator.
     */
    private boolean looksLikeFilePath(String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("```") || trimmed.contains(" ")) {
            return false;
        }
        int lastSlash = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
        String name   = lastSlash >= 0 ? trimmed.substring(lastSlash + 1) : trimmed;
        // Must look like a filename with an extension (a dot that is not leading/trailing),
        // or be an explicit path (containing a separator).
        int dot = name.lastIndexOf('.');
        boolean hasExtension = dot > 0 && dot < name.length() - 1;
        return hasExtension || lastSlash >= 0;
    }
}
