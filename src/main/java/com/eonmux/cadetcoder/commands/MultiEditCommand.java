package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.ai.*;
import com.eonmux.cadetcoder.ai.StandInAnswer;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.git.GitIntegrationManager;
import com.eonmux.cadetcoder.session.SessionManager;
import com.eonmux.cadetcoder.security.ReadOnlyGuard;
import com.eonmux.cadetcoder.security.WritePathPolicy;
import com.eonmux.cadetcoder.ui.InteractivePrompts;
import com.eonmux.cadetcoder.util.AtomicFileWrite;
import com.eonmux.cadetcoder.util.UnifiedDiff;
import picocli.CommandLine.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Command (name = "multiedit", description = "Apply several edits to one file, all or nothing")
public class MultiEditCommand extends LoggingCommandSupport implements IterativeCommand, java.util.concurrent.Callable<Integer> {

    @Parameters (index = "0", description = "The file to edit")
    String filePath;

    @Parameters (index = "1..*", description = "The edit request text or edit operations")
    String[] editRequestParts;

    @Option (names = {"-r", "--replace-all"}, description = "Replace all occurrences for each edit")
    boolean replaceAll = false;

    /** Maximum directory depth to traverse when searching for a file by pattern. */
    private static final int FILE_SEARCH_MAX_DEPTH = 10;

    @Override
    public StepResult executeStep(String[] args, Map<String, Object> context, String llmResponse) {
        String step = (String) context.getOrDefault("step", "initial");

        switch (step) {
            case "initial":
                if (args.length < 1) {
                    context.put("step", "get_file");
                    return new StepResult(false, "No file specified", context,
                            "Which file would you like to edit? Please provide the file path.");
                }

                String filePathArg = args[0];

                // Resolve and security-validate the path BEFORE touching the
                // filesystem so existence checks honor the shared policy
                // (credential denylist, dangerous paths, project containment).
                Path validatedPath = validatePath(Paths.get(filePathArg));
                if (validatedPath == null) {
                    return StepResult.failure(
                            "Error: Access denied for file path: " + filePathArg, context);
                }

                if (!Files.exists(validatedPath)) {
                    context.put("searchPattern", filePathArg);
                    context.put("args", args);
                    context.put("step", "search_file");
                    return new StepResult(false, "File not found: " + filePathArg, context,
                            "The file '" +
                            filePathArg +
                            "' was not found. Should I search for files matching this pattern?");
                }

                // File exists
                context.put("filePath", validatedPath.toString());
                context.put("args", args);

                if (args.length < 2) {
                    context.put("step", "get_edit_request");
                    return new StepResult(false, "File selected: " + validatedPath.getFileName(), context,
                            "What edits would you like to make to this file?");
                }

                // Have both file and edit request
                context.put("step", "prepare_edits");
                return executeStep(args, context, null);

            case "get_file":
                if (llmResponse != null && !llmResponse.trim().isEmpty()) {
                    File requestedFile = new File(llmResponse.trim());
                    if (!requestedFile.exists()) {
                        context.put("searchPattern", llmResponse.trim());
                        context.put("args", new String[] {llmResponse.trim()});
                        context.put("step", "search_file");
                        return new StepResult(false, "File not found", context,
                                "The file '" + llmResponse.trim() + "' was not found. Should I search for it?");
                    }
                    context.put("filePath", requestedFile.getAbsolutePath());
                    context.put("args", new String[] {requestedFile.getAbsolutePath()});
                    context.put("step", "get_edit_request");
                    return new StepResult(false, "File found: " + requestedFile.getName(), context,
                            "What edits would you like to make to this file?");
                }
                return StepResult.failure("No file provided, multiedit cancelled", context);

            case "search_file":
                if (llmResponse != null &&
                    (llmResponse.toLowerCase().contains("yes") || llmResponse.toLowerCase().contains("search"))) {
                    String pattern = (String) context.get("searchPattern");
                    try {
                        Path currentDir = Paths.get(".").toAbsolutePath();
                        // Closed: Files.walk holds an open directory stream per level, and this
                        // runs on a user-driven path that can be taken many times in one session.
                        java.util.List<Path> matchingFiles;
                        try (java.util.stream.Stream<Path> walk =
                                     Files.walk(currentDir, FILE_SEARCH_MAX_DEPTH)) {
                            matchingFiles = walk.filter(Files::isRegularFile)
                                                .filter(path -> path.getFileName().toString().contains(pattern))
                                                .limit(10)
                                                .collect(Collectors.toList());
                        }

                        if (matchingFiles.isEmpty()) {
                            return StepResult.failure("No files found matching: " + pattern, context);
                        }

                        if (matchingFiles.size() == 1) {
                            context.put("filePath", matchingFiles.get(0).toString());
                            String[] originalArgs = (String[]) context.get("args");
                            if (originalArgs.length < 2) {
                                context.put("step", "get_edit_request");
                                return new StepResult(false,
                                        "File found: " + matchingFiles.get(0).getFileName(),
                                        context,
                                        "What edits would you like to make to this file?");
                            } else {
                                context.put("step", "prepare_edits");
                                return executeStep(originalArgs, context, null);
                            }
                        }

                        StringBuilder fileList = new StringBuilder("Found multiple files:\n");
                        for (int i = 0; i < matchingFiles.size(); i++) {
                            fileList.append(i + 1).append(". ").append(matchingFiles.get(i)).append("\n");
                        }
                        context.put("matchingFiles", matchingFiles);
                        context.put("step", "select_file");
                        return new StepResult(false, fileList.toString(), context,
                                "Which file would you like to edit? Please specify the number or full path.");
                    } catch (Exception e) {
                        return StepResult.failure("Error searching for files: " + e.getMessage(), context);
                    }
                } else {
                    return StepResult.failure("Multiedit cancelled", context);
                }

            case "select_file":
                if (llmResponse != null) {
                    if (!(context.get("matchingFiles") instanceof java.util.List)) {
                        return StepResult.failure("Error: No matching files available for selection", context);
                    }
                    @SuppressWarnings("unchecked")
                    java.util.List<Path> matchingFiles = (java.util.List<Path>) context.get("matchingFiles");
                    String selected = llmResponse.trim();

                    try {
                        int index = Integer.parseInt(selected) - 1;
                        if (index >= 0 && index < matchingFiles.size()) {
                            context.put("filePath", matchingFiles.get(index).toString());
                            String[] originalArgs = (String[]) context.get("args");
                            if (originalArgs.length < 2) {
                                context.put("step", "get_edit_request");
                                return new StepResult(false,
                                        "File selected: " + matchingFiles.get(index).getFileName(),
                                        context,
                                        "What edits would you like to make to this file?");
                            } else {
                                context.put("step", "prepare_edits");
                                return executeStep(originalArgs, context, null);
                            }
                        }
                    } catch (NumberFormatException e) {
                        for (Path path : matchingFiles) {
                            if (path.toString().equals(selected) || path.toString().endsWith(selected)) {
                                context.put("filePath", path.toString());
                                String[] originalArgs = (String[]) context.get("args");
                                if (originalArgs.length < 2) {
                                    context.put("step", "get_edit_request");
                                    return new StepResult(false, "File selected: " + path.getFileName(), context,
                                            "What edits would you like to make to this file?");
                                } else {
                                    context.put("step", "prepare_edits");
                                    return executeStep(originalArgs, context, null);
                                }
                            }
                        }
                    }

                    return new StepResult(false, "Invalid selection: " + selected, context,
                            "Please select a valid file number or provide the full path.");
                }
                // No selection provided (null llmResponse): report a clear,
                // accurate failure rather than the misleading "Unknown step".
                return StepResult.failure("No selection provided, multiedit cancelled", context);

            case "get_edit_request":
                if (llmResponse != null && !llmResponse.trim().isEmpty()) {
                    String[] originalArgs = (String[]) context.get("args");
                    // Reconstruct args with the edit request
                    String[] newArgs = new String[] {originalArgs[0], llmResponse.trim()};
                    context.put("args", newArgs);
                    context.put("step", "prepare_edits");
                    return executeStep(newArgs, context, null);
                }
                return StepResult.failure("No edit request provided, multiedit cancelled", context);

            case "prepare_edits":
                try {
                    String   filePathStr = context.get("filePath") instanceof String
                            ? (String) context.get("filePath") : null;
                    String[] currentArgs = context.get("args") instanceof String[]
                            ? (String[]) context.get("args") : null;
                    if (filePathStr == null || currentArgs == null || currentArgs.length < 1) {
                        return StepResult.failure(
                                "Error: Internal state missing file path or edit arguments", context);
                    }

                    // Read file content
                    Path path = validatePath(Paths.get(filePathStr));
                    if (path == null) {
                        return StepResult.failure(
                                "Error: Access denied for file path: " + filePathStr,
                                context);
                    }
                    // A model that has not read the file writes the text to search for from
                    // memory, so the search misses and the turn is spent on an edit that changed
                    // nothing. Refused before anything is computed or confirmed; see ReadBeforeEdit.
                    String unread = ReadBeforeEdit.reasonNotToChange(path, "multiedit");
                    if (unread != null) {
                        OutputFormatter.printError(unread);
                        return StepResult.failure(unread, context);
                    }

                    String originalContent = Files.readString(path, StandardCharsets.UTF_8);
                    context.put("originalContent", originalContent);
                    context.put("path", path);

                    // Parse edit request
                    String[]            editRequestPartsLocal =
                            java.util.Arrays.copyOfRange(currentArgs, 1, currentArgs.length);
                    List<EditOperation> operations;

                    String combinedRequest = String.join(" ", editRequestPartsLocal);

                    if (containsStructuredEditBlocks(combinedRequest)) {
                        // Deterministic structured format: parse the EDIT_START/EDIT_END
                        // blocks directly without a hidden LLM round-trip.
                        operations = parseStructuredEditBlocks(combinedRequest);
                    } else if (combinedRequest.toUpperCase(java.util.Locale.ROOT).contains("EDIT_START")
                               || ModelDispatch.isModelDriven()) {
                        // Blocks that do not parse, or a model's request in words. Either went to a
                        // second model that was shown nothing of the conversation, and its reply --
                        // "you have not said which file" -- came back as the error. The model that
                        // wrote the request is told how to write it instead.
                        return StepResult.failure(malformedRequest(currentArgs[0]), context);
                    } else if (isNaturalLanguageRequest(editRequestPartsLocal)) {
                        // Use AI to determine operations
                        operations = getAIEditOperations(originalContent, combinedRequest);
                    } else {
                        // Direct positional old/new edit operations
                        operations = parseDirectEditOperations(editRequestPartsLocal);
                    }

                    if (operations.isEmpty()) {
                        // Treat "no operations parsed" as an error so the iterative
                        // loop does not read a no-op as successful completion.
                        return StepResult.failure(
                                "Error: No edit operations could be determined from your request",
                                context);
                    }

                    // Tried before anything is asked. An edit whose OLD: text is not in the file
                    // was shown for approval, approved, and only then found to miss, so the answer
                    // -- the user's, or the model's in auto mode -- was asked for nothing.
                    String edited;
                    try {
                        edited = appliedToCopy(originalContent, operations);
                    } catch (IllegalArgumentException misses) {
                        return StepResult.failure(
                                "Error: No edits were applied. " + misses.getMessage(), context);
                    }

                    context.put("operations", operations);
                    context.put("workingContent", originalContent);
                    context.put("step", "preview_edits");

                    // The whole change, as a diff of the file before and after. Each replacement was
                    // shown cut at fifty characters, so the approval -- a person's, or the model's
                    // in auto mode -- was given on part of it, and the model declined good edits
                    // because it could not see them.
                    String name    = path.getFileName().toString();
                    String preview = "I will apply " + operations.size() + " edit operation"
                                     + (operations.size() == 1 ? "" : "s") + " to " + path + ":\n\n"
                                     + UnifiedDiff.between("a/" + name, originalContent, "b/" + name,
                                                           edited, UnifiedDiff.DEFAULT_CONTEXT);

                    return new StepResult(false, preview, context,
                            "Do you want to apply these edits? (yes/no/modify)")
                            .addressedToUser();

                } catch (Exception e) {
                    return StepResult.failure("Error preparing edits: " + e.getMessage(), context);
                }

            case "preview_edits":
                // In auto mode the question went to the model and its answer is in llmResponse.
                if (!InteractivePrompts.isOn() && !StandInAnswer.answersNow()) {
                    SkippedConsent.announce();
                    context.put("step", "apply_edits");
                    return executeStep((String[]) context.get("args"), context, null);
                }

                switch (ChangeConsent.readFrom(llmResponse)) {
                    case CANCEL:
                        // Declined, not failed: the files are untouched because the user said so.
                        return StepResult.interrupted(StandInAnswer.answersNow()
                                ? "Edits declined: the model, answering for you because "
                                  + "security.commandApproval is auto, said no; its reason is above"
                                : "Edits cancelled by user", context);
                    case MODIFY:
                        context.put("step", "modify_edits");
                        return new StepResult(false, "Let's modify the edits", context,
                                "What would you like to change about the edits?").addressedToUser();
                    case APPLY:
                        context.put("step", "apply_edits");
                        return executeStep((String[]) context.get("args"), context, null);
                    default:
                        break;
                }
                return new StepResult(false, "Please confirm", context,
                        "Please respond with 'yes' to apply, 'no' to cancel, or 'modify' to change the edits.")
                        .addressedToUser();

            case "modify_edits":
                if (llmResponse != null && !llmResponse.trim().isEmpty()) {
                    String[] originalArgs    = (String[]) context.get("args");
                    String   originalRequest =
                            String.join(" ", java.util.Arrays.copyOfRange(originalArgs, 1, originalArgs.length));
                    String[] newArgs         =
                            new String[] {originalArgs[0], originalRequest + " (Modified: " + llmResponse + ")"};
                    context.put("args", newArgs);
                    context.put("step", "prepare_edits");
                    return executeStep(newArgs, context, null);
                }
                return StepResult.failure("No modifications provided, multiedit cancelled", context);

            case "apply_edits":
                try {
                    if (ReadOnlyGuard.blocks("edit file")) {
                        return StepResult.failure("Read-only mode is enabled", context);
                    }

                    if (context.get("operations") instanceof List &&
                        context.get("workingContent") instanceof String &&
                        context.get("originalContent") instanceof String &&
                        context.get("path") instanceof Path) {
                        @SuppressWarnings("unchecked")
                        List<EditOperation> operations = (List<EditOperation>) context.get("operations");
                        String workingContent = (String) context.get("workingContent");
                        String originalContent = (String) context.get("originalContent");
                        Path path = (Path) context.get("path");

                        OutputFormatter.printHeader("Applying " + operations.size() + " edit operations");

                        // Apply all edits
                        for (int i = 0; i < operations.size(); i++) {
                            EditOperation op = operations.get(i);
                            String beforeEdit = workingContent;

                            try {
                                workingContent = applyEdit(workingContent, op);

                                if (beforeEdit.equals(workingContent)) {
                                    OutputFormatter.printWarning("Edit " +
                                                                 (i + 1) +
                                                                 ": No matches found for: " +
                                                                 op.oldString);
                                } else {
                                    // Nothing has been written yet: this loop works on a string, and
                                    // a later edit that fails rolls the whole batch back. Announced
                                    // as "Applied successfully" here, the edits before the failure
                                    // were reported as done in the same transcript that then said no
                                    // edits were applied -- and the loop that reads that transcript
                                    // believed the first half.
                                    OutputFormatter.printInfo("Edit " + (i + 1) + ": matched");
                                }
                            } catch (Exception e) {
                                // The reason travels into the step's failure as well as onto the
                                // console. The console copy reaches a model through the captured
                                // output; the step copy is what the run loop puts in front of it as
                                // "Error:", and that one used to say only that a rollback had
                                // happened -- which is the one thing the reader could already see.
                                String why = "Edit " + (i + 1) + " failed: " + e.getMessage();
                                OutputFormatter.printError(why);
                                return StepResult.failure(
                                        "Error: Rolling back all changes - no edits applied. "
                                        + why, context);
                            }
                        }

                        // Check if any changes were made
                        if (originalContent.equals(workingContent)) {
                            return StepResult.failure(
                                    "Error: No changes were made to the file. No matches found.",
                                    context);
                        }

                        // The file was read in prepare_edits, before the confirmation this step is
                        // the answer to. Anything written to it while that was being asked is not
                        // in workingContent, and writing workingContent would erase it.
                        String movedOn = UnchangedSince.reasonNotToWrite(path, originalContent);
                        if (movedOn != null) {
                            OutputFormatter.printError(movedOn);
                            return StepResult.failure(movedOn, context);
                        }

                        // Save current session state for undo
                        SessionManager.getInstance().saveState();

                        // Write the changes atomically: stage into a sibling temp
                        // file then move it into place. This avoids leaving a
                        // dangling ".backup" sibling behind while still being
                        // crash-safe (the original is untouched until the move).
                        AtomicFileWrite.writeString(path, workingContent);
                        // Having written it, the run knows what is in it; see ReadBeforeEdit.
                        ReadBeforeEdit.sawContents(path);
                        OutputFormatter.printSuccess("All edits applied successfully to " + path.getFileName());

                        // Count changes
                        int changeCount = countChanges(originalContent, workingContent);
                        OutputFormatter.printInfo("Total lines changed: " + changeCount);

                        // Auto-commit if enabled
                        GitIntegrationManager gitManager = GitIntegrationManager.getInstance();
                        if (gitManager.isAvailable() &&
                            ConfigManager.getInstance().getConfig().getGit().isAutoCommitEnabled()) {
                            String changeSummary =
                                    "Applied " + operations.size() + " edit operations to " + path.getFileName();
                            // Everything, not what happens to be staged: see EditCommand's
                            // auto-commit for why an index nobody filled records nothing.
                            gitManager.getGitIntegration().commitEverything(
                                    com.eonmux.cadetcoder.git.CommitMessage.render(
                                            ConfigManager.getInstance().getConfig().getGit()
                                                         .getCommitMessageTemplate(),
                                            changeSummary));
                        }
                    }

                    return StepResult.success("Edits completed successfully", context);

                } catch (Exception e) {
                    return StepResult.failure("Error applying edits: " + e.getMessage(), context);
                }
        }

        return StepResult.failure("Unknown step: " + step, context);
    }

    @Override
    public String getInitialPrompt(String[] args) {
        if (args.length < 1) {
            return "The user wants to perform multiple edits but didn't specify which file. What file should be edited?";
        } else if (args.length < 2) {
            return "The user wants to edit " +
                   args[0] +
                   " but didn't specify what changes to make. What edits should be performed?";
        }
        return null;
    }

    @Override
    public boolean supportsIterativeExecution(String[] args) {
        // Always support iterative execution for better interaction
        return true;
    }

    /**
     * Applies the edits to a copy of the file's text.
     *
     * @param content    the file's text
     * @param operations the edits, in order
     * @return the text with every edit applied
     * @throws IllegalArgumentException saying which edit cannot be applied and why, or that the
     *                                  edits change nothing
     */
    private String appliedToCopy(String content, List<EditOperation> operations) {
        String working = content;
        for (int i = 0; i < operations.size(); i++) {
            try {
                working = applyEdit(working, operations.get(i));
            } catch (Exception e) {
                throw new IllegalArgumentException("Edit " + (i + 1) + " failed: " + e.getMessage(), e);
            }
        }
        if (working.equals(content)) {
            throw new IllegalArgumentException("The edits change nothing in the file.");
        }
        return working;
    }

    /**
     * What is wrong with edit blocks that do not parse, and how to write them.
     *
     * <p>The arguments arrive joined with spaces, so blocks written on one line, or given as
     * separate tokens, lose the line breaks the grammar requires.</p>
     *
     * @param file the file the edit was for
     * @return the message
     */
    static String malformedRequest(String file) {
        return "Error: the edit blocks could not be read. EDIT_START, OLD:, NEW:, REPLACE_ALL: and "
               + "EDIT_END must each start their own line (REPLACE_ALL: may be left out, which means "
               + "false), and a text of more than one word or line "
               + "needs ARGS_BEGIN/ARGS_END around the arguments so its line breaks are kept:\n"
               + "ARGS_BEGIN\n"
               + (file == null || file.isBlank() ? "<file>" : file) + "\n"
               + "EDIT_START\n"
               + "OLD: <the exact text in the file, with its whitespace>\n"
               + "NEW: <the text to put in its place>\n"
               + "REPLACE_ALL: false\n"
               + "EDIT_END\n"
               + "ARGS_END\n"
               + "Nothing was changed.";
    }

    /**
     * Determines if the input is a natural language request that needs AI processing
     */
    private boolean isNaturalLanguageRequest(String[] parts) {
        // If odd number of arguments and it's not just a single argument, likely natural language
        if (parts.length % 2 != 0 && parts.length > 1) {
            return true;
        }

        // If single argument, it's definitely natural language
        if (parts.length == 1) {
            return true;
        }

        // Check for common natural language indicators (multi-word phrases)
        String   combined   = String.join(" ", parts).toLowerCase();
        String[] indicators = {"please", "can you", "could you", "would you", "i want", "i need", "help me"};

        for (String indicator : indicators) {
            if (combined.contains(indicator)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Uses AI to determine edit operations from natural language request
     */
    private List<EditOperation> getAIEditOperations(String fileContent, String request) throws Exception {
        List<EditOperation> operations = new ArrayList<>();

        // Build AI prompt
        PromptBuilder promptBuilder = new PromptBuilder("multiedit",
                PromptConstants.EDIT_BLOCK_MAIN_SYSTEM,
                PromptConstants.SYSTEM_REMINDER);
        promptBuilder.addCodeFile(filePath, fileContent);

        String systemPrompt = promptBuilder.buildSystemPrompt() +
                              "\n\nYou must respond with a list of edit operations in the following format:\n" +
                              "EDIT_START\n" +
                              "OLD: <exact string to replace, including all whitespace>\n" +
                              "NEW: <replacement string>\n" +
                              "REPLACE_ALL: <true or false>\n" +
                              "EDIT_END\n" +
                              "\nYou can provide multiple EDIT_START/EDIT_END blocks for multiple edits.\n" +
                              "IMPORTANT: The OLD string must match EXACTLY, including all spaces, tabs, and newlines.\n" +
                              "For multi-line replacements, include all lines in the OLD and NEW sections.";

        String userPrompt = "Apply the following changes to the file: " + request;

        PromptData promptData = new PromptData(systemPrompt, userPrompt);
        String     aiResponse = AIManager.getInstance().complete(promptData, new java.util.HashMap<>());

        // Parse AI response using the same deterministic block grammar that
        // callers may supply directly.
        operations = parseStructuredEditBlocks(aiResponse);

        if (operations.isEmpty()) {
            OutputFormatter.printWarning("AI did not provide valid edit operations. Response:");
            OutputFormatter.printCodeBlock(aiResponse);
        }

        return operations;
    }

    /**
     * Compiled grammar for a single EDIT_START/OLD/NEW/REPLACE_ALL/EDIT_END block. The
     * {@code REPLACE_ALL:} line may be left out, which means {@code false}.
     */
    private static final Pattern EDIT_BLOCK_PATTERN = Pattern.compile(
            "EDIT_START[ \\t]*\\r?\\n" +
            "OLD:[ \\t]?([\\s\\S]*?)\\r?\\n" +
            "NEW:[ \\t]?([\\s\\S]*?)" +
            "(?:\\r?\\nREPLACE_ALL:[ \\t]*(true|false)[ \\t]*)?\\r?\\n" +
            "EDIT_END",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    /**
     * Returns {@code true} when the supplied text contains at least one
     * complete structured edit block, allowing a deterministic parse with no
     * LLM round-trip.
     */
    private boolean containsStructuredEditBlocks(String text) {
        return text != null && EDIT_BLOCK_PATTERN.matcher(text).find();
    }

    /**
     * Parses zero or more EDIT_START/EDIT_END blocks from the supplied text.
     * The OLD/NEW strings preserve exact whitespace; only a single trailing
     * newline introduced by the block layout is stripped. REPLACE_ALL controls
     * the per-edit replace-all behavior.
     */
    private List<EditOperation> parseStructuredEditBlocks(String text) {
        List<EditOperation> operations = new ArrayList<>();
        if (text == null) {
            return operations;
        }

        Matcher matcher = EDIT_BLOCK_PATTERN.matcher(text);
        while (matcher.find()) {
            String  oldString      = matcher.group(1);
            String  newString      = matcher.group(2);
            boolean replaceAllFlag = matcher.group(3) != null && Boolean.parseBoolean(matcher.group(3));

            // Don't trim the strings to preserve exact whitespace
            // But do remove the very last newline if present
            if (oldString.endsWith("\n")) {
                oldString = oldString.substring(0, oldString.length() - 1);
            }
            if (newString.endsWith("\n")) {
                newString = newString.substring(0, newString.length() - 1);
            }

            operations.add(new EditOperation(oldString, newString, replaceAllFlag));
        }

        return operations;
    }

    /**
     * Parses direct edit operations from command line arguments
     */
    private List<EditOperation> parseDirectEditOperations(String[] parts) {
        List<EditOperation> operations = new ArrayList<>();

        // Expect pairs of old/new strings; warn if a trailing unpaired argument is dropped
        if (parts.length % 2 != 0) {
            OutputFormatter.printWarning(
                    "Ignoring unpaired final edit argument: " + parts[parts.length - 1]);
        }

        for (int i = 0; i < parts.length - 1; i += 2) {
            String oldString = parts[i];
            String newString = parts[i + 1];
            operations.add(new EditOperation(oldString, newString, this.replaceAll));
        }

        return operations;
    }

    /**
     * Applies a single edit operation to the content
     */
    private String applyEdit(String content, EditOperation op) throws Exception {
        // An empty OLD would match at index 0 (or between every character with replace-all),
        // inserting/duplicating NEW and corrupting the file. Reject it outright.
        if (op.oldString.isEmpty()) {
            throw new IllegalArgumentException("Old text to find cannot be empty");
        }
        if (op.oldString.equals(op.newString)) {
            throw new IllegalArgumentException("Old and new strings cannot be the same");
        }

        if (op.replaceAll) {
            // Replace all occurrences
            String result = content.replace(op.oldString, op.newString);
            if (result.equals(content)) {
                // The reader already knows what it asked for; what it needs is what is there
                // instead. See UnmatchedEdit for the six-requests-in-a-row this replaces.
                throw new IllegalArgumentException(
                        UnmatchedEdit.reasonItDidNotMatch(content, op.oldString));
            }
            return result;
        } else {
            // Replace first occurrence only
            int index = content.indexOf(op.oldString);
            if (index == -1) {
                throw new IllegalArgumentException(
                        UnmatchedEdit.reasonItDidNotMatch(content, op.oldString));
            }

            return content.substring(0, index) +
                   op.newString +
                   content.substring(index + op.oldString.length());
        }
    }

    /**
     * Counts the number of changed lines between two strings
     */
    private int countChanges(String original, String modified) {
        String[] originalLines = original.split("\\r?\\n");
        String[] modifiedLines = modified.split("\\r?\\n");

        int changes  = 0;
        int maxLines = Math.max(originalLines.length, modifiedLines.length);

        for (int i = 0; i < maxLines; i++) {
            String origLine = i < originalLines.length ? originalLines[i] : "";
            String modLine  = i < modifiedLines.length ? modifiedLines[i] : "";

            if (!origLine.equals(modLine)) {
                changes++;
            }
        }

        return changes;
    }

    @Override
    public Integer call() throws Exception {
        String[] args;
        if (filePath != null) {
            List<String> argList = new ArrayList<>();
            argList.add(filePath);
            if (editRequestParts != null) {
                Collections.addAll(argList, editRequestParts);
            }
            // Put the picocli-bound flag back into argv so execute() sees one authoritative source
            // of truth for it, exactly as AgentCommand.call() does for its own options. Without this,
            // execute()'s parse (which must reset the field, since this command is a reused
            // singleton) would silently discard a --replace-all that picocli had already bound.
            if (replaceAll) {
                argList.add("-r");
            }
            args = argList.toArray(new String[0]);
        } else {
            args = replaceAll ? new String[] {"-r"} : new String[0];
        }
        return execute(args);
    }

    @Override
    public int execute(String[] args) {
        try {
            startCommandLogging("multiedit", args);
            logStep("Initializing multi-edit command");

            // -r/--replace-all is advertised in getUsage() but was only ever set by picocli's
            // Callable path, so on the registry path (shell, script, agentic dispatch) it was inert
            // AND was passed through as if it were part of the edit request. Consume it here.
            args = extractReplaceAllFlag(args);

            logStep("Starting iterative multi-edit execution");
            logStep("Multi-edit configuration", String.format("Replace all: %b", replaceAll));

            // Use iterative executor for better multi-step handling
            IterativeExecutor executor = new IterativeExecutor();
            int result = executor.execute(this, args);
            
            completeCommandLogging(result);
            return result;
        } catch (Exception e) {
            logError("execute", "Multi-edit execution failed", e);
            completeCommandLogging(1);
            return 1;
        }
    }

    /**
     * Consumes {@code -r}/{@code --replace-all} from {@code argv}, setting {@link #replaceAll}.
     *
     * <p>{@code replaceAll} is reset to {@code false} first so this reused singleton does not carry a
     * previous invocation's flag into a later one.</p>
     *
     * @param argv the raw argument vector (may be {@code null})
     * @return the remaining arguments, never {@code null}
     */
    private String[] extractReplaceAllFlag(String[] argv) {
        this.replaceAll = false;
        if (argv == null) {
            return new String[0];
        }
        List<String> remaining = new ArrayList<>(argv.length);
        for (String token : argv) {
            if ("-r".equals(token) || "--replace-all".equals(token)) {
                this.replaceAll = true;
            } else {
                remaining.add(token);
            }
        }
        return remaining.toArray(new String[0]);
    }


    @Override
    public String getUsage() {
        // One form per line. All three used to be joined with " OR " into a single line well over
        // 300 characters, which wrapped into a block no one could pick an option out of.
        return "multiedit <file> \"<edit-request>\"\n"
             + "multiedit <file> \"<old1>\" \"<new1>\" [\"<old2>\" \"<new2>\"] ... [-r|--replace-all]\n"
             + "multiedit <file> \"EDIT_START\\nOLD: <old>\\nNEW: <new>\\nREPLACE_ALL: <true|false>\\nEDIT_END\"\n"
             + "  Repeat EDIT_START..EDIT_END blocks for several deterministic edits.";
    }

    private Path validatePath(Path filePath) {
        if (filePath == null) {
            return null;
        }

        try {
            WritePathPolicy.Decision decision = WritePathPolicy.decide(filePath);
            if (!decision.isAllowed()) {
                OutputFormatter.printError(WritePathPolicy.reasonFor(decision, filePath));
                return null;
            }

            // The normalized form is returned so later steps operate on a path with no ".."
            // segments left in it.
            return WritePathPolicy.normalize(filePath,
                                             Paths.get(System.getProperty("user.dir")).toAbsolutePath());
        } catch (Exception e) {
            OutputFormatter.printError("Invalid file path: " + filePath);
            return null;
        }
    }

    /**
     * Represents a single edit operation
     */
    private static class EditOperation {
        String  oldString;
        String  newString;
        boolean replaceAll;

        EditOperation(String oldString, String newString, boolean replaceAll) {
            this.oldString  = oldString;
            this.newString  = newString;
            this.replaceAll = replaceAll;
        }
    }
}