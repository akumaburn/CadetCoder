package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ExitCode;
import com.eonmux.cadetcoder.util.TextFiles;
import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.ai.AIManager;
import com.eonmux.cadetcoder.ai.PromptData;
import com.eonmux.cadetcoder.prompts.TemplatePromptBuilder;
import com.eonmux.cadetcoder.util.FilePathResolver;
import com.eonmux.cadetcoder.util.FilePathResolver.ResolvedPath;
import com.eonmux.cadetcoder.ui.InteractivePrompts;
import picocli.CommandLine.Command;

import java.nio.file.*;
import java.util.HashMap;
import java.util.Map;

@Command (name = "explain", description = "Explain code functionality and components")
public class ExplainCommand implements IterativeCommand, CommandRegistry.InterruptibleCommand {

    private CommandRegistry.InterruptionContext interruptionContext;

    @Override
    public void setInterruptionContext(CommandRegistry.InterruptionContext context) {
        this.interruptionContext = context;
    }
    @Override
    public int execute(String[] args) {
        // When dispatched non-interactively (e.g. from chat/agent), there is no human to
        // answer mid-action prompts and nesting an IterativeExecutor would spin up a second
        // LLM loop. Run a deterministic single-shot explanation instead. The iterative,
        // back-and-forth dialogue is reserved for interactive/CLI use.
        boolean isInteractive = InteractivePrompts.isOn();
        if (!isInteractive) {
            return executeSingleShot(args);
        }

        // Use iterative executor for better multi-step handling
        IterativeExecutor executor = new IterativeExecutor();
        return executor.execute(this, args);
    }

    /**
     * Runs a single, complete explanation without any iterative dialogue:
     * resolve the file path -> enforce the security policy -> read -> one LLM
     * call -> print the result. Used in non-interactive mode where there is
     * nobody to answer follow-up prompts.
     *
     * @return 0 on success, 1 on any failure (missing/ambiguous path, denied
     *         access, read error, or LLM error)
     */
    private int executeSingleShot(String[] args) {
        if (args.length < 1) {
            OutputFormatter.printError("No file path provided");
            return 1;
        }

        // Honor interruption requests before doing any work. Taken back, not failed: the
        // explanation did not happen because it was stopped, which is what ExitCode.INTERRUPTED
        // says and what every other command reports for the same event.
        if (CommandRegistry.InterruptibleCommand.stopWasAsked(interruptionContext)) {
            OutputFormatter.printWarning("Explanation cancelled by user");
            return ExitCode.INTERRUPTED;
        }

        String       filepath = args[0];
        ResolvedPath resolved = FilePathResolver.resolve(filepath, Paths.get("."), false);

        if (!resolved.exists()) {
            // Either no match or an ambiguous set of alternatives: with no human to
            // disambiguate, surface a clear diagnostic and stop.
            String message = resolved.getErrorMessage() != null
                    ? resolved.getErrorMessage()
                    : "File not found: " + filepath;
            OutputFormatter.printError(message);
            return 1;
        }

        String resolvedPath = resolved.getPath().toString();

        // Enforce the security policy before reading: never ship arbitrary
        // (traversal/outside-project/credential) file contents to the LLM.
        if (!new com.eonmux.cadetcoder.security.SecurityValidator().isFileAccessAllowed(resolvedPath)) {
            OutputFormatter.printError("Access denied: " + resolvedPath);
            return 1;
        }

        try {
            String fileContent = TextFiles.readText(Paths.get(resolvedPath));

            if (fileContent.trim().isEmpty()) {
                OutputFormatter.printSuccess("The file is empty - nothing to explain.");
                return 0;
            }

            String response = requestExplanation(fileContent, resolvedPath);

            OutputFormatter.printSuccess("AI Explanation:");
            OutputFormatter.printCodeBlock(response);
            return 0;
        } catch (Exception e) {
            OutputFormatter.printError("Error explaining file: " + e.getMessage());
            return 1;
        }
    }

    /**
     * Builds the explanation prompt from the shared template and performs a single
     * LLM completion. Centralizes the prompt construction so the single-shot and
     * iterative paths stay in lockstep.
     */
    private String requestExplanation(String fileContent, String filePath) {
        String prompt = TemplatePromptBuilder.explain()
                                             .with("code_content", fileContent)
                                             .with("explanation_request",
                                                     "Please explain this " + filePath + " file")
                                             .build();

        PromptData promptData = new PromptData("", prompt);
        return AIManager.getInstance().complete(promptData, new HashMap<>());
    }


    @Override
    public String getUsage() {
        return "explain <filepath>";
    }

    @Override
    public StepResult executeStep(String[] args, Map<String, Object> context, String llmResponse) {
        String step = (String) context.getOrDefault("step", "initial");

        // Honor interruption requests before processing the next step
        if (CommandRegistry.InterruptibleCommand.stopWasAsked(interruptionContext)) {
            return StepResult.interrupted("Explanation cancelled by user", context);
        }

        switch (step) {
            case "initial":
                // First step: check if file path is provided or needs to be searched
                if (args.length < 1) {
                    context.put("step", "search_file");
                    return new StepResult(false,
                            "No file path provided",
                            context,
                            "What file would you like me to explain? Please provide the file name or pattern to search for.");
                }

                String filepath = args[0];
                ResolvedPath resolved = FilePathResolver.resolve(filepath, Paths.get("."), false);

                if (!resolved.exists()) {
                    // File doesn't exist, need to search for it
                    context.put("searchPattern", filepath);
                    context.put("alternatives", resolved.getAlternatives());
                    context.put("step", "search_file");

                    if (!resolved.hasAlternatives()) {
                        return new StepResult(false, "File not found: " + filepath, context,
                                "The file '" +
                                filepath +
                                "' was not found. Should I search for files matching this pattern?");
                    }

                    StringBuilder alternatives = new StringBuilder("File not found: " + filepath + "\n");
                    alternatives.append("Did you mean one of these?\n");
                    java.util.List<Path> alts = resolved.getAlternatives();
                    for (int i = 0; i < Math.min(5, alts.size()); i++) {
                        alternatives.append(i + 1).append(". ").append(alts.get(i)).append("\n");
                    }
                    context.put("matchingFiles", alts);
                    context.put("step", "select_file");
                    return new StepResult(false, alternatives.toString(), context,
                            "Which file would you like me to explain? Please specify the number or full path.");
                }

                // File exists, proceed to read it
                context.put("filepath", resolved.getPath().toString());
                context.put("step", "read_file");
                return executeStep(args, context, null);

            case "search_file":
                // Handle file search based on LLM response
                if (llmResponse != null &&
                    (llmResponse.toLowerCase().contains("yes") || llmResponse.toLowerCase().contains("search"))) {
                    String pattern = (String) context.get("searchPattern");
                    if ((pattern == null || pattern.isBlank()) && llmResponse != null && !llmResponse.isBlank()) {
                        pattern = llmResponse.trim();
                    }
                    if (pattern == null || pattern.isBlank()) {
                        return StepResult.failure("No search pattern provided", context);
                    }
                    try {
                        // Use the shared FilePathResolver to search for files (parity with AnalyzeCommand)
                        ResolvedPath searchResult = FilePathResolver.resolve(pattern, Paths.get("."), false);

                        if (searchResult.exists()) {
                            // Resolver collapsed a single match to an exact path; use it directly.
                            context.put("filepath", searchResult.getPath().toString());
                            context.put("step", "read_file");
                            return executeStep(args, context, null);
                        }

                        if (!searchResult.hasAlternatives()) {
                            return StepResult.failure("No files found matching: " + pattern, context);
                        }

                        java.util.List<Path> matchingFiles = searchResult.getAlternatives();
                        if (matchingFiles.size() == 1) {
                            // Found exactly one file, use it
                            context.put("filepath", matchingFiles.get(0).toString());
                            context.put("step", "read_file");
                            return executeStep(args, context, null);
                        }

                        // Multiple files found, ask which one
                        StringBuilder fileList = new StringBuilder("Found multiple files:\n");
                        for (int i = 0; i < matchingFiles.size(); i++) {
                            fileList.append(i + 1).append(". ").append(matchingFiles.get(i)).append("\n");
                        }
                        context.put("matchingFiles", matchingFiles);
                        context.put("step", "select_file");
                        return new StepResult(false, fileList.toString(), context,
                                "Which file would you like me to explain? Please specify the number or full path.");
                    } catch (Exception e) {
                        return StepResult.failure("Error searching for files: " + e.getMessage(), context);
                    }
                } else {
                    return StepResult.failure("Explanation cancelled", context);
                }

            case "select_file":
                // Handle file selection from list
                if (llmResponse != null) {
                    var    matchingFiles = (java.util.List<Path>) context.get("matchingFiles");
                    String selected      = llmResponse.trim();

                    try {
                        // Check if it's a number
                        int index = Integer.parseInt(selected) - 1;
                        if (index >= 0 && index < matchingFiles.size()) {
                            context.put("filepath", matchingFiles.get(index).toString());
                            context.put("step", "read_file");
                            return executeStep(args, context, null);
                        }
                    } catch (NumberFormatException e) {
                        // Not a number, check if it's a path
                        for (Path path : matchingFiles) {
                            if (path.toString().equals(selected) || path.toString().endsWith(selected)) {
                                context.put("filepath", path.toString());
                                context.put("step", "read_file");
                                return executeStep(args, context, null);
                            }
                        }
                    }

                    return new StepResult(false, "Invalid selection: " + selected, context,
                            "Please select a valid file number or provide the full path.");
                }

                // No selection provided yet; re-prompt the user instead of failing
                StringBuilder pendingList = new StringBuilder("Found multiple files:\n");
                var pendingFiles = (java.util.List<Path>) context.get("matchingFiles");
                if (pendingFiles != null) {
                    for (int i = 0; i < pendingFiles.size(); i++) {
                        pendingList.append(i + 1).append(". ").append(pendingFiles.get(i)).append("\n");
                    }
                }
                return new StepResult(false, pendingList.toString(), context,
                        "Please select a valid file number or provide the full path.");

            case "read_file":
                // Read and explain the file
                String filepathToRead = (String) context.get("filepath");
                // Enforce the security policy before reading: never ship arbitrary
                // (traversal/outside-project/credential) file contents to the LLM.
                if (!new com.eonmux.cadetcoder.security.SecurityValidator().isFileAccessAllowed(filepathToRead)) {
                    return StepResult.failure("Access denied: " + filepathToRead, context);
                }
                try {
                    String fileContent = TextFiles.readText(Paths.get(filepathToRead));

                    // Check if file is empty
                    if (fileContent.trim().isEmpty()) {
                        return StepResult.success("The file is empty - nothing to explain.", context);
                    }

                    context.put("fileContent", fileContent);
                    context.put("step", "generate_explanation");
                    return executeStep(args, context, null);

                } catch (Exception e) {
                    return StepResult.failure("Error reading file: " + e.getMessage(), context);
                }

            case "generate_explanation":
                try {
                    String fileContent = (String) context.get("fileContent");
                    String filePath    = (String) context.get("filepath");

                    String response = requestExplanation(fileContent, filePath);

                    OutputFormatter.printSuccess("AI Explanation:");
                    OutputFormatter.printCodeBlock(response);

                    context.put("explanation", response);

                    // In non-interactive mode, skip follow-up questions
                    boolean isInteractive = InteractivePrompts.isOn();
                    if (!isInteractive) {
                        return StepResult.success("Explanation complete", context);
                    }

                    context.put("step", "follow_up");
                    return new StepResult(false, "", context,
                            "Would you like me to explain any specific part in more detail?");

                } catch (Exception e) {
                    return StepResult.failure("Error generating explanation: " + e.getMessage(), context);
                }

            case "follow_up":
                // Handle follow-up questions
                if (llmResponse != null && !llmResponse.trim().isEmpty() &&
                    !llmResponse.toLowerCase().contains("no") &&
                    !llmResponse.toLowerCase().contains("done")) {

                    String fileContent         = (String) context.get("fileContent");
                    String previousExplanation = (String) context.get("explanation");

                    try {
                        // Build follow-up prompt
                        String followUpPrompt = "Previous explanation:\n" + previousExplanation +
                                                "\n\nFollow-up question: " + llmResponse +
                                                "\n\nPlease provide a detailed explanation for this specific question about the code:\n" +
                                                fileContent;

                        PromptData promptData = new PromptData("", followUpPrompt);
                        String     response   = AIManager.getInstance().complete(promptData, new HashMap<>());

                        OutputFormatter.printSuccess("Follow-up Explanation:");
                        OutputFormatter.printCodeBlock(response);

                        return new StepResult(false, "", context,
                                "Do you have any other questions about this code?");

                    } catch (Exception e) {
                        return StepResult.failure(
                                "Error generating follow-up explanation: " + e.getMessage(),
                                context);
                    }
                }

                return StepResult.success("Explanation complete", context);
        }

        return StepResult.failure("Unknown step: " + step, context);
    }

    @Override
    public String getInitialPrompt(String[] args) {
        // If no file path provided, we need to ask the LLM
        if (args.length < 1) {
            return "The user wants to explain a file but didn't specify which one. What file should be explained?";
        }
        return null; // No initial prompt needed if file path is provided
    }

    @Override
    public boolean supportsIterativeExecution(String[] args) {
        // Always support iterative execution for better error handling
        return true;
    }
}