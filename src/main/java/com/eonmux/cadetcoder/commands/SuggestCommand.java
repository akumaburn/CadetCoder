package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.ai.AIManager;
import com.eonmux.cadetcoder.ai.PromptData;
import com.eonmux.cadetcoder.prompts.TemplatePromptBuilder;
import com.eonmux.cadetcoder.ui.InteractivePrompts;
import picocli.CommandLine.Command;

import java.io.File;
import java.nio.file.*;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Command (name = "suggest", description = "Suggest tests, refactors or improvements for a file")
public class SuggestCommand implements IterativeCommand, CommandRegistry.InterruptibleCommand {

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
        return "suggest <improvements|tests|refactoring|documentation|performance> [filepath]";
    }

    @Override
    public StepResult executeStep(String[] args, Map<String, Object> context, String llmResponse) {
        String step = (String) context.getOrDefault("step", "initial");

        switch (step) {
            case "initial":
                if (args.length < 1) {
                    context.put("step", "get_suggestion_type");
                    return new StepResult(false,
                            "No suggestion type specified",
                            context,
                            "What type of suggestions would you like? (improvements/tests/refactoring/documentation/performance)");
                }

                String suggestionType = args[0].trim().toLowerCase();

                // The contract is "suggest <type> [filepath]", with <type> being the
                // first argument. Validate it up front so a caller that mistakenly
                // passes a file path as arg-0 (e.g. following an outdated
                // "suggest <filepath>" signature) fails with a clear, actionable
                // message instead of silently using the path as the suggestion type.
                if (!isValidSuggestionType(suggestionType)) {
                    String reason = looksLikePath(args[0])
                            ? "'" + args[0] + "' looks like a file path, but the first argument must be a "
                              + "suggestion type."
                            : "Invalid suggestion type: '" + args[0] + "'.";
                    return StepResult.failure(
                            reason + " Usage: " + CommandUsage.render(getUsage())
                            + " (valid types: improvements, tests, refactoring, documentation, performance)",
                            context);
                }

                context.put("suggestionType", suggestionType);

                if (args.length < 2) {
                    context.put("step", "get_file");
                    return new StepResult(false, "Suggestion type: " + suggestionType, context,
                            "Which file would you like suggestions for? Please provide the file path.");
                }

                String filePath = args[1];
                File file = new File(filePath);

                if (!file.exists()) {
                    context.put("searchPattern", filePath);
                    context.put("step", "search_file");
                    return new StepResult(false, "File not found: " + filePath, context,
                            "The file '" +
                            filePath +
                            "' was not found. Should I search for files matching this pattern?");
                }

                context.put("filePath", file.getAbsolutePath());
                context.put("step", "read_file");
                return executeStep(args, context, null);

            case "get_suggestion_type":
                if (llmResponse != null && !llmResponse.trim().isEmpty()) {
                    String type = llmResponse.trim().toLowerCase();
                    // Validate suggestion type
                    if (!isValidSuggestionType(type)) {
                        return new StepResult(false, "Invalid suggestion type", context,
                                "Please choose from: improvements, tests, refactoring, documentation, or performance");
                    }
                    context.put("suggestionType", type);
                    context.put("step", "get_file");
                    return new StepResult(false, "Suggestion type: " + type, context,
                            "Which file would you like " + type + " suggestions for?");
                }
                return StepResult.failure("No suggestion type provided, cancelled", context);

            case "get_file":
                if (llmResponse != null && !llmResponse.trim().isEmpty()) {
                    File requestedFile = new File(llmResponse.trim());
                    if (!requestedFile.exists()) {
                        context.put("searchPattern", llmResponse.trim());
                        context.put("step", "search_file");
                        return new StepResult(false, "File not found", context,
                                "The file '" + llmResponse.trim() + "' was not found. Should I search for it?");
                    }
                    context.put("filePath", requestedFile.getAbsolutePath());
                    context.put("step", "read_file");
                    return executeStep(args, context, null);
                }
                return StepResult.failure("No file provided, suggestions cancelled", context);

            case "search_file":
                if (llmResponse != null &&
                    (isAffirmative(llmResponse) || llmResponse.trim().toLowerCase().contains("search"))) {
                    String pattern = (String) context.get("searchPattern");
                    // Match against the filename component of the pattern, not the raw
                    // pattern: getFileName() returns only the last path component, so a
                    // path-like pattern (e.g. "src/main/java/Foo.java") could never match.
                    Path patternPath = Paths.get(pattern);
                    String patternName = patternPath.getFileName() != null
                            ? patternPath.getFileName().toString()
                            : pattern;
                    Path searchRoot = Paths.get(".").toAbsolutePath();
                    try (var stream = Files.walk(searchRoot)) {
                        var matchingFiles = stream.filter(Files::isRegularFile)
                                                 .filter(path -> !isExcludedSearchPath(searchRoot, path))
                                                 .filter(path -> path.getFileName().toString().contains(patternName))
                                                 .limit(10)
                                                 .collect(Collectors.toList());

                        if (matchingFiles.isEmpty()) {
                            return StepResult.failure("No files found matching: " + pattern, context);
                        }

                        if (matchingFiles.size() == 1) {
                            context.put("filePath", matchingFiles.get(0).toString());
                            context.put("step", "read_file");
                            return executeStep(args, context, null);
                        }

                        StringBuilder fileList = new StringBuilder("Found multiple files:\n");
                        for (int i = 0; i < matchingFiles.size(); i++) {
                            fileList.append(i + 1).append(". ").append(matchingFiles.get(i)).append("\n");
                        }
                        context.put("matchingFiles", matchingFiles);
                        context.put("step", "select_file");
                        return new StepResult(false, fileList.toString(), context,
                                "Which file would you like suggestions for? Please specify the number or full path.");
                    } catch (Exception e) {
                        return StepResult.failure("Error searching for files: " + e.getMessage(), context);
                    }
                } else {
                    return StepResult.failure("Suggestions cancelled", context);
                }

            case "select_file":
                if (llmResponse != null) {
                    Object matchingFilesObj = context.get("matchingFiles");
                    if (!(matchingFilesObj instanceof java.util.List)) {
                        return StepResult.failure("Error: no matching files available for selection", context);
                    }
                    @SuppressWarnings("unchecked")
                    java.util.List<Path> matchingFiles = (java.util.List<Path>) matchingFilesObj;
                    String               selected      = llmResponse.trim();

                    try {
                        int index = Integer.parseInt(selected) - 1;
                        if (index >= 0 && index < matchingFiles.size()) {
                            context.put("filePath", matchingFiles.get(index).toString());
                            context.put("step", "read_file");
                            return executeStep(args, context, null);
                        }
                    } catch (NumberFormatException e) {
                        for (Path path : matchingFiles) {
                            if (path.toString().equals(selected) || path.toString().endsWith(selected)) {
                                context.put("filePath", path.toString());
                                context.put("step", "read_file");
                                return executeStep(args, context, null);
                            }
                        }
                    }

                    return new StepResult(false, "Invalid selection: " + selected, context,
                            "Please select a valid file number or provide the full path.");
                }
                return StepResult.failure("No file selection provided, suggestions cancelled", context);

            case "read_file":
                String filePathToRead = (String) context.get("filePath");
                if (filePathToRead == null) {
                    return StepResult.failure("Error: no file path specified", context);
                }
                // Enforce the security policy before reading: never ship arbitrary
                // (traversal/outside-project/credential) file contents to the LLM.
                if (!new com.eonmux.cadetcoder.security.SecurityValidator().isFileAccessAllowed(filePathToRead)) {
                    return StepResult.failure("Access denied: " + filePathToRead, context);
                }
                try {
                    String content = Files.readString(Paths.get(filePathToRead));
                    if (content.trim().isEmpty()) {
                        return StepResult.success("The file is empty - no suggestions possible.", context);
                    }

                    context.put("fileContent", content);
                    context.put("step", "generate_suggestions");
                    return executeStep(args, context, null);
                } catch (Exception e) {
                    return StepResult.failure("Error reading file: " + e.getMessage(), context);
                }

            case "generate_suggestions":
                try {
                    String suggestType = (String) context.get("suggestionType");
                    String content     = (String) context.get("fileContent");
                    String path        = (String) context.get("filePath");

                    OutputFormatter.printHeader("Generating " + suggestType + " suggestions for " + path);

                    // Use the template engine for the prompt
                    String prompt = TemplatePromptBuilder.suggest()
                                                         .with("code_content", content)
                                                         .with("suggestion_request",
                                                                 "Please provide " +
                                                                 suggestType +
                                                                 " suggestions for this code")
                                                         .build();

                    // Create PromptData for AI processing
                    PromptData promptData = new PromptData("", prompt);
                    String     response   = AIManager.getInstance().complete(promptData, new HashMap<>());

                    OutputFormatter.printInfo("AI Suggestions:");
                    OutputFormatter.printCodeBlock(response);

                    context.put("suggestions", response);

                    // In non-interactive mode, don't ask for more suggestions
                    boolean isInteractive = InteractivePrompts.isOn();
                    if (!isInteractive) {
                        return StepResult.success("Suggestions complete", context);
                    }

                    context.put("step", "ask_for_more");
                    return new StepResult(false, "", context,
                            "Would you like suggestions for a different aspect of this code? (yes/no)");

                } catch (Exception e) {
                    return StepResult.failure("Error generating suggestions: " + e.getMessage(), context);
                }

            case "ask_for_more":
                if (llmResponse != null && isAffirmative(llmResponse)) {
                    context.put("step", "get_additional_type");
                    return new StepResult(false,
                            "",
                            context,
                            "What other type of suggestions would you like? (improvements/tests/refactoring/documentation/performance)");
                }
                return StepResult.success("Suggestions complete", context);

            case "get_additional_type":
                if (llmResponse != null && !llmResponse.trim().isEmpty()) {
                    String type = llmResponse.trim().toLowerCase();
                    if (!isValidSuggestionType(type)) {
                        return new StepResult(false, "Invalid suggestion type", context,
                                "Please choose from: improvements, tests, refactoring, documentation, or performance");
                    }
                    context.put("suggestionType", type);
                    context.put("step", "generate_suggestions");
                    return executeStep(args, context, null);
                }
                return StepResult.success("No additional suggestions requested", context);
        }

        return StepResult.failure("Unknown step: " + step, context);
    }

    @Override
    public String getInitialPrompt(String[] args) {
        if (args.length < 1) {
            return "The user wants code suggestions but didn't specify what type. What kind of suggestions should we provide?";
        }
        // An invalid/path-looking first argument must be rejected by executeStep BEFORE any AI
        // call; returning null here suppresses the iterative executor's initial LLM prompt so the
        // type-validation failure is surfaced instead of silently asking the model for a file.
        if (!isValidSuggestionType(args[0].trim().toLowerCase())) {
            return null;
        }
        if (args.length < 2) {
            return "The user wants " +
                   args[0] +
                   " suggestions but didn't specify which file. What file should we analyze?";
        }
        return null;
    }

    /**
     * No executor-level announcement: this command prints its own opening header, and two frames for
     * one run is one too many.
     */
    @Override
    public String getRunTitle(String[] args) {
        return null;
    }

    @Override
    public boolean supportsIterativeExecution(String[] args) {
        // Always support iterative execution for better interaction
        return true;
    }

    /**
     * Detect an affirmative response without matching unintended substrings
     * (e.g. "yesterday"). Matches common yes/y variants as whole words.
     */
    private boolean isAffirmative(String response) {
        if (response == null) {
            return false;
        }
        String lower = response.trim().toLowerCase();
        return lower.equals("yes") || lower.equals("y") ||
               lower.equals("yeah") || lower.equals("yep") ||
               lower.startsWith("yes ") || lower.startsWith("y ");
    }

    private boolean isValidSuggestionType(String type) {
        return type.equals("improvements") || type.equals("tests") ||
               type.equals("refactoring") || type.equals("documentation") ||
               type.equals("performance");
    }

    /**
     * Heuristic to detect an argument that looks like a file path rather than a
     * suggestion type. Used only to make the validation error message more
     * helpful when a caller follows an outdated "suggest &lt;filepath&gt;" signature.
     */
    private boolean looksLikePath(String arg) {
        if (arg == null) {
            return false;
        }
        String trimmed = arg.trim();
        return trimmed.contains("/") || trimmed.contains("\\") ||
               trimmed.contains(".") || new File(trimmed).exists();
    }

    /**
     * Whether a candidate lies inside a directory a project walk skips.
     *
     * <p>The rule is {@code ProjectTreeWalk}'s, not this command's. Kept here it knew about
     * {@code target} alone, so a bundle in {@code dist/} or a vendored copy in {@code out/} was
     * offered to the model as the user's own code while {@code grep} and {@code ls} skipped it.</p>
     */
    private boolean isExcludedSearchPath(Path root, Path candidate) {
        Path relative = root.relativize(candidate);
        for (int i = 0; i < relative.getNameCount() - 1; i++) {
            if (com.eonmux.cadetcoder.util.ProjectTreeWalk.isPrunedName(
                    relative.getName(i).toString())) {
                return true;
            }
        }
        return false;
    }
}