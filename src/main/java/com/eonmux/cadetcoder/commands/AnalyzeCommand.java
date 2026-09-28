package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.util.TextFiles;
import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.ai.AIManager;
import com.eonmux.cadetcoder.ai.PromptData;
import com.eonmux.cadetcoder.error.ErrorHandler;
import com.eonmux.cadetcoder.logging.DebugLogger;
import com.eonmux.cadetcoder.prompts.TemplatePromptBuilder;
import com.eonmux.cadetcoder.util.FilePathResolver;
import com.eonmux.cadetcoder.util.FilePathResolver.ResolvedPath;
import com.eonmux.cadetcoder.ui.InteractivePrompts;
import picocli.CommandLine.Command;

import java.nio.file.*;
import java.util.*;

@Command (name = "analyze", description = "Analyze a file's structure and quality")
public class AnalyzeCommand implements IterativeCommand, CommandRegistry.InterruptibleCommand {

    /**
     * Upper bound on the number of characters of file content shipped to the LLM. Whole files are
     * read into memory and embedded in the prompt; without a cap a very large file would blow the
     * model's context window (and balloon token cost). When a file exceeds this size we send the
     * leading {@code MAX_CONTENT_CHARS} characters followed by an explicit truncation note so the
     * analysis is still useful and the truncation is transparent rather than silent.
     */
    static final int MAX_CONTENT_CHARS = 60_000;

    private CommandRegistry.InterruptionContext interruptionContext;

    @Override
    public void setInterruptionContext(CommandRegistry.InterruptionContext context) {
        this.interruptionContext = context;
    }

    @Override
    public int execute(String[] args) {
        // Use iterative executor for better multi-step handling
        IterativeExecutor executor = new IterativeExecutor();
        return executor.execute(this, args);
    }


    @Override
    public String getUsage() {
        return "analyze <filepath>";
    }

    @Override
    public StepResult executeStep(String[] args, Map<String, Object> context, String llmResponse) {
        String step = (String) context.getOrDefault("step", "initial");

        switch (step) {
            case "initial":
                // First step: check if file path is provided or needs to be searched
                if (args.length < 1) {
                    // Non-interactive single-shot contract: with no human to answer the follow-up,
                    // do not open a search/select dialog that can never be satisfied -> fail fast
                    // with the usage instead.
                    if (!isInteractive()) {
                        return StepResult.failure(
                                "No file path provided. Usage: " + CommandUsage.render(getUsage()), context);
                    }
                    context.put("step", "search_file");
                    return new StepResult(false,
                            "No file path provided",
                            context,
                            "What file would you like me to analyze? Please provide the file name or pattern to search for.");
                }

                String filepath = args[0];
                ResolvedPath resolved = FilePathResolver.resolve(filepath, Paths.get("."), false);

                if (!resolved.exists()) {
                    // Non-interactive single-shot contract: resolve -> read -> one complete() ->
                    // return. A missing path cannot trigger an interactive search/select dialog
                    // because there is nobody to answer it; report the failure directly.
                    if (!isInteractive()) {
                        return StepResult.failure("File not found: " + filepath, context);
                    }
                    // File doesn't exist, need to search for it
                    context.put("searchPattern", filepath);
                    context.put("alternatives", resolved.getAlternatives());
                    context.put("step", "search_file");

                    if (!resolved.hasAlternatives()) {
                        return new StepResult(false, "File not found: " + filepath, context,
                                "The file '" +
                                filepath +
                                "' was not found. Should I search for files matching this pattern?");
                    } else {
                        StringBuilder alternatives = new StringBuilder("File not found: " + filepath + "\n");
                        alternatives.append("Did you mean one of these?\n");
                        List<Path> alts = resolved.getAlternatives();
                        for (int i = 0; i < Math.min(5, alts.size()); i++) {
                            alternatives.append(i + 1).append(". ").append(alts.get(i)).append("\n");
                        }
                        context.put("step", "select_alternative");
                        return new StepResult(false, alternatives.toString(), context,
                                "Please select a file number or provide a different file path.");
                    }
                }

                // File exists, proceed to read it
                context.put("filepath", resolved.getPath().toString());
                context.put("step", "read_file");
                return executeStep(args, context, null);

            case "select_alternative":
                // Handle selection from alternatives provided by FilePathResolver
                if (llmResponse != null) {
                    List<Path> alternatives = asPathList(context.get("alternatives"));
                    if (alternatives != null && !alternatives.isEmpty()) {
                        String selected = llmResponse.trim();
                        try {
                            int index = Integer.parseInt(selected) - 1;
                            if (index >= 0 && index < alternatives.size()) {
                                context.put("filepath", alternatives.get(index).toString());
                                context.put("step", "read_file");
                                return executeStep(args, context, null);
                            }
                        } catch (NumberFormatException e) {
                            // Not a number, try as a new file path
                            ResolvedPath newResolved = FilePathResolver.resolve(selected, Paths.get("."), false);
                            if (newResolved.exists()) {
                                context.put("filepath", newResolved.getPath().toString());
                                context.put("step", "read_file");
                                return executeStep(args, context, null);
                            }
                        }
                    }
                    return new StepResult(false, "Invalid selection: " + llmResponse, context,
                            "Please select a valid file number or provide a different file path.");
                }
                return new StepResult(false, "Waiting for file selection", context,
                        "Please select a file number or provide a different file path.");

            case "search_file": {
                // Two distinct intents reach this step:
                //  (a) file-not-found path: "searchPattern" is set and the prompt was a yes/no
                //      confirmation ("Should I search ...?"). Proceed only on a normalized
                //      affirmative (y/yes/search) -> never on a loose substring scan that matched
                //      negatives like "no, don't search" or "yesterday".
                //  (b) no-args path: "searchPattern" is unset and the llmResponse IS the filename
                //      or pattern the user supplied; use it directly.
                String storedPattern = (String) context.get("searchPattern");
                boolean hasStoredPattern = storedPattern != null && !storedPattern.isBlank();

                String pattern;
                if (hasStoredPattern) {
                    if (!isAffirmative(llmResponse)) {
                        return StepResult.failure("Analysis cancelled", context);
                    }
                    pattern = storedPattern;
                } else {
                    // No-args flow: the response carries the filename/pattern itself.
                    pattern = llmResponse != null ? llmResponse.trim() : null;
                    if (pattern == null || pattern.isBlank() || isNegative(llmResponse)) {
                        return StepResult.failure("Analysis cancelled", context);
                    }
                }

                if (pattern == null || pattern.isBlank()) {
                    return StepResult.failure("No file path provided to search for", context);
                }
                try {
                    // Use FilePathResolver to search for files
                    ResolvedPath searchResult = FilePathResolver.resolve(pattern, Paths.get("."), false);

                    if (!searchResult.hasAlternatives()) {
                        return StepResult.failure("No files found matching: " + pattern, context);
                    }

                    List<Path> alternatives = searchResult.getAlternatives();
                    if (alternatives.size() == 1) {
                        // Found exactly one file, use it
                        context.put("filepath", alternatives.get(0).toString());
                        context.put("step", "read_file");
                        return executeStep(args, context, null);
                    }

                    // Multiple files found, ask which one
                    StringBuilder fileList = new StringBuilder("Found multiple files:\n");
                    for (int i = 0; i < alternatives.size(); i++) {
                        fileList.append(i + 1).append(". ").append(alternatives.get(i)).append("\n");
                    }
                    context.put("matchingFiles", alternatives);
                    context.put("step", "select_file");
                    return new StepResult(false, fileList.toString(), context,
                            "Which file would you like to analyze? Please specify the number or full path.");
                } catch (Exception e) {
                    ErrorHandler.getInstance().handleException(e);
                    return StepResult.failure("Error searching for files: " + e.getMessage(), context);
                }
            }

            case "select_file":
                // Handle file selection from list
                if (llmResponse != null) {
                    List<Path> matchingFiles = asPathList(context.get("matchingFiles"));
                    if (matchingFiles != null) {
                        String selected = llmResponse.trim();

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
                    }

                    return new StepResult(false, "Invalid selection: " + llmResponse, context,
                            "Please select a valid file number or provide the full path.");
                }
                return new StepResult(false, "Waiting for file selection", context,
                        "Which file would you like to analyze? Please specify the number or full path.");

            case "read_file":
                // Read and analyze the file
                String filePathToAnalyze = (String) context.get("filepath");
                if (filePathToAnalyze == null) {
                    return StepResult.failure("Error: No file path was set", context);
                }
                // Enforce the security policy before reading: never ship arbitrary
                // (traversal/outside-project/credential) file contents to the LLM.
                if (!new com.eonmux.cadetcoder.security.SecurityValidator().isFileAccessAllowed(filePathToAnalyze)) {
                    return StepResult.failure("Access denied: " + filePathToAnalyze, context);
                }
                try {
                    String rawContent = TextFiles.readText(Paths.get(filePathToAnalyze));

                    // Cap the content embedded in the prompt so a very large file cannot overflow
                    // the model's context window (or balloon token cost). Truncation is explicit:
                    // a trailing note tells the model the content was cut off.
                    String fileContent = capContent(rawContent);

                    // Use the template engine for the prompt
                    String prompt = TemplatePromptBuilder.analyze()
                                                         .with("code_content", fileContent)
                                                         .with("analysis_request",
                                                                 "Please analyze this " + filePathToAnalyze + " file")
                                                         .build();

                    // Create PromptData for AI processing with proper system prompt
                    String     systemPrompt =
                            "You are a code analysis expert. Analyze the provided code for structure, quality, potential issues, and provide constructive feedback.";
                    PromptData promptData   = new PromptData(systemPrompt, prompt);
                    
                    long aiStartTime = System.currentTimeMillis();
                    String     response     = AIManager.getInstance().complete(promptData, new HashMap<>());
                    long aiDuration = System.currentTimeMillis() - aiStartTime;
                    
                    // Log AI code analysis to debug log
                    DebugLogger debugLogger = DebugLogger.getInstance();
                    debugLogger.debug("Code Analysis", String.format("FILE: %s", filePathToAnalyze));
                    debugLogger.debug("Code Analysis", String.format("FILE_SIZE: %d characters", rawContent.length()));
                    if (rawContent.length() > MAX_CONTENT_CHARS) {
                        debugLogger.debug("Code Analysis", String.format(
                                "TRUNCATED: sent first %d of %d characters to the model",
                                MAX_CONTENT_CHARS, rawContent.length()));
                    }
                    debugLogger.debug("Code Analysis", String.format("DURATION: %dms", aiDuration));
                    debugLogger.debug("Code Analysis", String.format("RESPONSE_LENGTH: %d characters", response != null ? response.length() : 0));
                    debugLogger.debug("Code Analysis", "=== AI CODE ANALYSIS START ===");
                    debugLogger.debug("Code Analysis", response != null ? response : "<null>");
                    debugLogger.debug("Code Analysis", "=== AI CODE ANALYSIS END ===");

                    return StepResult.success("AI Analysis:\n" + response, context);
                } catch (Exception e) {
                    ErrorHandler.getInstance().handleException(e);
                    return StepResult.failure("Error reading file: " + e.getMessage(), context);
                }
        }

        return StepResult.failure("Unknown step: " + step, context);
    }

    @Override
    public String getInitialPrompt(String[] args) {
        // If no file path provided, ask the LLM which file to analyze -- but only in interactive
        // mode. Non-interactive runs follow the single-shot contract: there is no human (and we must
        // not have the model invent a target), so return no prompt and let executeStep("initial")
        // fail fast with the usage.
        if (args.length < 1 && isInteractive()) {
            return "The user wants to analyze a file but didn't specify which one. What file should be analyzed?";
        }
        return null; // No initial prompt needed if file path is provided (single-shot path)
    }

    @Override
    public boolean supportsIterativeExecution(String[] args) {
        // Always support iterative execution for better error handling
        return true;
    }

    /**
     * Whether the process is running interactively. Mirrors {@link IterativeExecutor}'s reading of
     * the {@code cadet.interactive} system property (default {@code true}) so the command's
     * single-shot vs. dialog behavior stays in lockstep with the executor.
     */
    static boolean isInteractive() {
        return InteractivePrompts.isOn();
    }

    /**
     * Normalized affirmative check for a yes/no confirmation. Accepts only an exact (trimmed,
     * case-insensitive) {@code y}, {@code yes}, or {@code search}; everything else -- including
     * negatives like "no, don't search" and unrelated text -- is treated as a decline. This
     * replaces an unbounded {@code contains("yes")}/{@code contains("search")} scan that matched
     * such negatives by accident.
     */
    static boolean isAffirmative(String response) {
        if (response == null) {
            return false;
        }
        String normalized = response.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("y") || normalized.equals("yes") || normalized.equals("search");
    }

    /**
     * Normalized negative check used on the no-args path, where the response carries a filename
     * rather than a yes/no answer. Only an exact {@code n}/{@code no}/{@code cancel} (or a leading
     * "no," reply) cancels; a bare filename does not.
     */
    static boolean isNegative(String response) {
        if (response == null) {
            return false;
        }
        String normalized = response.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("n") || normalized.equals("no") || normalized.equals("cancel")
               || normalized.startsWith("no,") || normalized.startsWith("no ");
    }

    /**
     * Safely narrows a context value to {@code List<Path>}. Guards {@code instanceof} before the
     * cast (the value is stored as a raw {@code Object} and could be absent or another type) and
     * verifies every element is a {@link Path}, returning {@code null} when the value is unusable so
     * callers fall through to their normal "invalid selection" handling instead of risking a
     * {@link ClassCastException}.
     */
    @SuppressWarnings("unchecked")
    static List<Path> asPathList(Object value) {
        if (!(value instanceof List<?>)) {
            return null;
        }
        List<?> list = (List<?>) value;
        for (Object element : list) {
            if (!(element instanceof Path)) {
                return null;
            }
        }
        return (List<Path>) value;
    }

    /**
     * Caps file content embedded in the LLM prompt at {@link #MAX_CONTENT_CHARS} characters. When
     * the content is longer it is truncated to the leading window and an explicit ASCII note is
     * appended so the truncation is transparent to both the model and anyone reading the prompt.
     */
    static String capContent(String content) {
        if (content == null || content.length() <= MAX_CONTENT_CHARS) {
            return content;
        }
        return content.substring(0, MAX_CONTENT_CHARS)
               + "\n\n... [content truncated: showing the first " + MAX_CONTENT_CHARS
               + " of " + content.length() + " characters] ...\n";
    }
}
