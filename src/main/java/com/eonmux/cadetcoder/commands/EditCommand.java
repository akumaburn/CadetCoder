package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.ai.*;
import com.eonmux.cadetcoder.ai.StandInAnswer;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.context.ContextEngine;
import com.eonmux.cadetcoder.context.ContextFiles;
import com.eonmux.cadetcoder.git.GitIntegrationManager;
import com.eonmux.cadetcoder.session.SessionManager;
import com.eonmux.cadetcoder.util.FilePathResolver;
import com.eonmux.cadetcoder.util.TextFiles;
import com.eonmux.cadetcoder.security.ReadOnlyGuard;
import com.eonmux.cadetcoder.security.WritePathPolicy;
import com.eonmux.cadetcoder.ui.InteractivePrompts;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import com.eonmux.cadetcoder.util.AtomicFileWrite;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@Command (name = "edit", description = "Edit files with AI assistance")
public class EditCommand extends LoggingCommandSupport implements IterativeCommand, CommandRegistry.InterruptibleCommand, java.util.concurrent.Callable<Integer> {

    @Parameters (index = "0..*", description = "The edit request text")
    String[] editRequestParts;
    
    private CommandRegistry.InterruptionContext interruptionContext;

    @Override
    public void setInterruptionContext(CommandRegistry.InterruptionContext context) {
        this.interruptionContext = context;
    }

    /**
     * Puts the whole project files this edit should see in front of the model.
     *
     * <h2>Why they are loaded whole</h2>
     *
     * <p>The rest of an edit's context is snippets the index returned for the request, and that is a
     * search over what is inside files rather than over what they are called -- so a file the user
     * named is not certain to be among them, and an edit to a file the model was never shown is a
     * guess. The same is true of a file the project has decided always matters, such as the document
     * its coding conventions live in: no search over the sentence the user typed will return it.
     * These are added as primary code files, ahead of the snippets, so that when the prompt is
     * trimmed to the token budget it is the search's guesses that go first.</p>
     *
     * <p>One file used to be loaded this way and it was named in the source: {@code README.md}.
     * Every other file in the project was left to the search.</p>
     *
     * @param editRequest   what was asked for, in the user's own words
     * @param promptBuilder the prompt being assembled
     * @param projectRoot   the directory names resolve against
     */
    void loadContextFiles(String editRequest, PromptBuilder promptBuilder, Path projectRoot) {
        Path root = projectRoot.toAbsolutePath().normalize();
        for (Path file : ContextFiles.forRequest(editRequest, root)) {
            String name = root.relativize(file).toString();
            logStep("Loading a file for context", name);
            try {
                String content = TextFiles.readText(file);
                promptBuilder.addCodeFile(name, content);
                // The model that writes this edit's SEARCH/REPLACE blocks is being handed the file
                // in the same request, so it has read it in the only sense that matters; see
                // ReadBeforeEdit.
                ReadBeforeEdit.sawContents(file);
                logFileOperation("read", name, true,
                                 String.format("Loaded %d characters", content.length()));
            } catch (IOException e) {
                logFileOperation("read", name, false, e.getMessage());
                OutputFormatter.printWarning("Failed to load " + name + ": " + e.getMessage());
            }
        }
    }

    @Override
    public StepResult executeStep(String[] args, Map<String, Object> context, String llmResponse) {
        String step = (String) context.getOrDefault("step", "initial");

        switch (step) {
            case "initial":
                String[] requestParts = (args != null && args.length > 0) ?
                                        args :
                                        (editRequestParts != null ? editRequestParts : new String[0]);
                if (requestParts.length == 0) {
                    context.put("step", "get_edit_request");
                    return new StepResult(false, "No edit request provided", context,
                            "What would you like me to edit? Please describe the changes you want to make.");
                }

                String editRequest = String.join(" ", requestParts);
                context.put("editRequest", editRequest);
                context.put("step", "prepare_edit");
                return executeStep(args, context, null);

            case "get_edit_request":
                if (llmResponse != null && !llmResponse.trim().isEmpty()) {
                    context.put("editRequest", llmResponse.trim());
                    context.put("step", "prepare_edit");
                    return executeStep(args, context, null);
                }
                // In non-interactive mode with no response, fail
                boolean isInteractive = InteractivePrompts.isOn();
                if (!isInteractive) {
                    return StepResult.failure("Error: No edit request provided, edit cancelled", context);
                }
                return StepResult.failure("No edit request provided, edit cancelled", context);

            case "prepare_edit":
                String editReq = (String) context.get("editRequest");
                logStep("Preparing edit request", editReq);
                addContext("editRequest", editReq);
                OutputFormatter.printHeader("Preparing edit: " + editReq);

                try {
                    // Save current session state for undo functionality
                    logStep("Saving session state for undo functionality");
                    SessionManager.getInstance().saveState();

                    // Initialize PromptBuilder with the 'edit' command type
                    logStep("Initializing prompt builder for edit command");
                    PromptBuilder promptBuilder = new PromptBuilder("edit",
                            PromptConstants.EDIT_BLOCK_MAIN_SYSTEM,
                            PromptConstants.SYSTEM_REMINDER);

                    // The files this edit should see whole: the ones it names, and the ones
                    // the project always wants (context.priorityFiles)
                    loadContextFiles(editReq, promptBuilder, Paths.get(System.getProperty("user.dir")));

                    // Retrieve relevant snippets from context using ContextEngine
                    logStep("Searching for relevant code snippets");
                    long searchStartTime = System.currentTimeMillis();
                    List<String> relevantSnippets = ContextEngine.getInstance().searchRelevantSnippets(editReq);
                    long searchDuration = System.currentTimeMillis() - searchStartTime;
                    logDataProcessing("Context search", "snippets", relevantSnippets.size(), searchDuration);

                    // Retrieve source tree as additional context
                    String sourceTree = getSourceTree();

                    // Combine context snippets and source tree for prompt context
                    String fileContent;
                    if (!relevantSnippets.isEmpty()) {
                        fileContent = "\n\nPOTENTIALLY RELEVANT SNIPPETS:\n" +
                                      String.join("\n\n", relevantSnippets) +
                                      "\n\nSOURCE TREE:\n" +
                                      sourceTree;
                    } else {
                        fileContent = "SOURCE TREE:\n" + sourceTree;
                    }

                    // Determine target files if available from context
                    List<String> targetFiles = new ArrayList<>();
                    for (String snippet : relevantSnippets) {
                        if (snippet.startsWith("File: ")) {
                            int newlineIndex = snippet.indexOf("\n");
                            if (newlineIndex > 0) {
                                String file = snippet.substring(6, newlineIndex).trim();
                                if (!targetFiles.contains(file)) {
                                    targetFiles.add(file);
                                }
                            }
                        }
                    }

                    context.put("targetFiles", targetFiles);
                    context.put("promptBuilder", promptBuilder);
                    context.put("fileContent", fileContent);

                    if (targetFiles.isEmpty()) {
                        // In non-interactive mode, continue anyway (AI will specify files)
                        boolean isInteractiveMode2 = InteractivePrompts.isOn();
                        if (!isInteractiveMode2) {
                            context.put("step", "generate_changes");
                            return executeStep(args, context, null);
                        }

                        context.put("step", "identify_files");
                        return new StepResult(false, "No specific files identified for editing", context,
                                "Which file(s) should I edit for this request: " + editReq + "?");
                    } else {
                        context.put("step", "generate_changes");
                        return executeStep(args, context, null);
                    }

                } catch (Exception e) {
                    return StepResult.failure("Error preparing edit: " + e.getMessage(), context);
                }

            case "identify_files":
                if (llmResponse != null && !llmResponse.trim().isEmpty()) {
                    List<String> targetFiles = parseFileNames(llmResponse);
                    if (targetFiles.isEmpty()) {
                        return new StepResult(false,
                                "No valid files identified",
                                context,
                                "Please specify the file names more clearly. For example: 'Edit Main.java' or 'Edit src/Main.java'");
                    }
                    context.put("targetFiles", targetFiles);
                    context.put("step", "generate_changes");
                    return executeStep(args, context, null);
                }
                return StepResult.failure("No files specified, edit cancelled", context);

            case "generate_changes":
                try {
                    String        editRequestStr = (String) context.get("editRequest");
                    PromptBuilder promptBuilder  = (PromptBuilder) context.get("promptBuilder");
                    String        fileContent    = (String) context.get("fileContent");

                    // Build the user prompt with the edit request and context
                    String userPrompt = editRequestStr + "\n\nContext:\n" + fileContent;

                    // Check for interruption before AI call
                    if (shouldInterrupt()) {
                        return StepResult.interrupted("Edit operation interrupted by user", context);
                    }
                    
                    // Create PromptData with system and user prompts
                    PromptData promptData = new PromptData(promptBuilder.buildSystemPrompt(), userPrompt);

                    // Send prompt to AI and get response
                    long aiStartTime = System.currentTimeMillis();
                    String aiResponse = AIManager.getInstance().complete(promptData, new HashMap<>());
                    long aiDuration = System.currentTimeMillis() - aiStartTime;
                    
                    // Log full AI response to debug log
                    logDebug("AI Response", String.format("REQUEST: %s", editRequestStr));
                    logDebug("AI Response", String.format("MODEL: edit"));
                    logDebug("AI Response", String.format("DURATION: %dms", aiDuration));
                    logDebug("AI Response", String.format("RESPONSE_LENGTH: %d characters", aiResponse != null ? aiResponse.length() : 0));
                    logDebug("AI Response", "=== AI EDIT RESPONSE START ===");
                    logDebug("AI Response", aiResponse != null ? aiResponse : "<null>");
                    logDebug("AI Response", "=== AI EDIT RESPONSE END ===");

                    context.put("aiResponse", aiResponse);
                    context.put("step", "preview_changes");

                    // Extract changes for preview
                    String preview = extractChangesPreview(aiResponse != null ? aiResponse : "");
                    return new StepResult(false, "Generated changes:\n" + (aiResponse != null ? preview : "<No response received>"), context,
                            "Do you want to apply these changes? (yes/no/modify)")
                            .addressedToUser();

                } catch (Exception e) {
                    return StepResult.failure("Error generating changes: " + e.getMessage(), context);
                }

            case "preview_changes":
                // In auto mode the question went to the model and its answer is in llmResponse.
                if (!InteractivePrompts.isOn() && !StandInAnswer.answersNow()) {
                    SkippedConsent.announce();
                    context.put("step", "apply_changes");
                    return executeStep(args, context, null);
                }

                switch (ChangeConsent.readFrom(llmResponse)) {
                    case CANCEL:
                        // Declined, not failed: the file is untouched because the user said so.
                        return StepResult.interrupted(StandInAnswer.answersNow()
                                ? "Edit declined: the model, answering for you because "
                                  + "security.commandApproval is auto, said no; its reason is above"
                                : "Edit cancelled by user", context);
                    case MODIFY:
                        context.put("step", "modify_edit");
                        return new StepResult(false, "Let's modify the edit", context,
                                "What would you like to change about the proposed edits?").addressedToUser();
                    case APPLY:
                        context.put("step", "apply_changes");
                        return executeStep(args, context, null);
                    default:
                        break;
                }
                return new StepResult(false, "Please confirm", context,
                        "Please respond with 'yes' to apply, 'no' to cancel, or 'modify' to change the edits.")
                        .addressedToUser();

            case "modify_edit":
                if (llmResponse != null && !llmResponse.trim().isEmpty()) {
                    String originalRequest = (String) context.get("editRequest");
                    context.put("editRequest", originalRequest + " (Modified: " + llmResponse + ")");
                    context.put("step", "generate_changes");
                    return executeStep(args, context, null);
                }
                return StepResult.success("No modifications provided", context);

            case "apply_changes":
                try {
                    if (ReadOnlyGuard.blocks("edit file")) {
                        return StepResult.failure("Read-only mode is enabled", context);
                    }

                    String       aiResponse  = (String) context.get("aiResponse");
                    List<String> targetFiles = (List<String>) context.get("targetFiles");

                    // Apply changes based on AI response, tracking how many files
                    // were actually written so we can report an accurate result.
                    List<String> changedFiles = new ArrayList<>();
                    if (aiResponse == null) {
                        OutputFormatter.printWarning("No AI response received; skipping apply.");
                        return StepResult.success("Edit skipped: no AI response received", context);
                    } else if (aiResponse.contains("```")) {
                        changedFiles = applyMultipleChanges(aiResponse);
                    } else {
                        // No fenced code block: the response is plain prose, not a
                        // structured patch. Writing it verbatim would destroy the
                        // target file's original content, so skip writing entirely
                        // and surface a clear failure instead.
                        OutputFormatter.printWarning(
                                "AI response contained no fenced code block; skipping edit to avoid " +
                                "overwriting files with prose.");
                        return StepResult.failure(
                                "Edit skipped: AI response had no fenced code block to apply", context);
                    }

                    // If nothing was actually written, do not report success: the
                    // exit code must reflect that no edit was performed.
                    if (changedFiles.isEmpty()) {
                        OutputFormatter.printError("No changes were applied.");
                        return StepResult.failure("Edit failed: no changes were applied", context);
                    }

                    OutputFormatter.printSuccess("Changes applied successfully.");

                    // Check for auto-commit
                    GitIntegrationManager gitManager = GitIntegrationManager.getInstance();
                    if (gitManager.isAvailable() &&
                        ConfigManager.getInstance().getConfig().getGit().isAutoCommitEnabled()) {
                        // Everything, not what happens to be staged: nobody chose an index here,
                        // and committing one nobody filled recorded an empty change while the edit
                        // this is meant to preserve stayed in the working tree.
                        gitManager.getGitIntegration().commitEverything(
                                com.eonmux.cadetcoder.git.CommitMessage.render(
                                        ConfigManager.getInstance().getConfig().getGit()
                                                     .getCommitMessageTemplate(),
                                        changeSummaryOf(changedFiles)));
                    }

                    return StepResult.success("Edit completed successfully", context);

                } catch (Exception e) {
                    return StepResult.failure("Failed to apply changes: " + e.getMessage(), context);
                }
        }

        return StepResult.failure("Unknown step: " + step, context);
    }

    @Override
    public String getInitialPrompt(String[] args) {
        // In non-interactive mode, don't use initial prompt
        boolean isInteractive = InteractivePrompts.isOn();
        if (!isInteractive) {
            return null;
        }

        String[] requestParts = (args != null && args.length > 0) ?
                                args :
                                (editRequestParts != null ? editRequestParts : new String[0]);
        if (requestParts.length == 0) {
            return "The user wants to edit something but didn't specify what. What should we edit?";
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

    private List<String> parseFileNames(String response) {
        List<String> files = new ArrayList<>();
        // Simple pattern to extract file names
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("([\\w\\-/\\.]+\\.[a-zA-Z]+)");
        java.util.regex.Matcher matcher = pattern.matcher(response);
        while (matcher.find()) {
            files.add(matcher.group(1));
        }
        return files;
    }

    private String extractChangesPreview(String aiResponse) {
        return ChangePreview.of(aiResponse);
    }

    private String getSourceTree() {
        StringBuilder                                   tree      = new StringBuilder();
        java.nio.file.Path                              rootPath  =
                java.nio.file.Paths.get(System.getProperty("user.dir"));
        final int                                       MAX_DEPTH = 3;
        final int                                       MAX_FILES = 100;
        final java.util.concurrent.atomic.AtomicInteger fileCount = new java.util.concurrent.atomic.AtomicInteger(0);

        try {
            java.nio.file.Files.walkFileTree(rootPath,
                    java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class),
                    MAX_DEPTH,
                    new java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                        @Override
                        public java.nio.file.FileVisitResult preVisitDirectory(java.nio.file.Path dir,
                                                                               java.nio.file.attribute.BasicFileAttributes attrs) {
                            // The start directory is never pruned, so a project that lives in a
                            // hidden directory still has a source tree. Asking Files.isHidden
                            // before that exemption pruned the root itself and produced an empty
                            // tree, which the model then read as an empty project.
                            if (com.eonmux.cadetcoder.util.ProjectTreeWalk.isPruned(rootPath, dir)) {
                                return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                            }
                            if (!dir.equals(rootPath)) {
                                tree.append("- ").append(rootPath.relativize(dir)).append("/\n");
                            }
                            return java.nio.file.FileVisitResult.CONTINUE;
                        }

                        @Override
                        public java.nio.file.FileVisitResult visitFile(java.nio.file.Path file,
                                                                       java.nio.file.attribute.BasicFileAttributes attrs) {
                            if (fileCount.incrementAndGet() > MAX_FILES) {
                                return java.nio.file.FileVisitResult.TERMINATE;
                            }

                            try {
                                if (!java.nio.file.Files.isHidden(file)) {
                                    String relativePath = rootPath.relativize(file).toString();
                                    tree.append("- ").append(relativePath).append("\n");
                                }
                            } catch (java.io.IOException e) {
                                // Handle exception, but continue
                            }
                            return java.nio.file.FileVisitResult.CONTINUE;
                        }
                    });

            if (fileCount.get() > MAX_FILES) {
                tree.append("\n... (truncated - too many files)\n");
            }
        } catch (java.io.IOException e) {
            return "Error retrieving source tree: " + e.getMessage();
        }
        return tree.toString();
    }

    /**
     * Applies every file block in the response, and says which files it wrote.
     *
     * <p>The names rather than a count, because the auto-commit message is written from this: a
     * commit that says only that something was edited is a commit nobody can read back later, and
     * this is the one place that still knows what was touched.</p>
     *
     * @param aiResponse the model's reply, carrying one fenced block per file
     * @return the files actually written, in the order they were applied
     */
    private List<String> applyMultipleChanges(String aiResponse) {
        // Pattern to match file blocks with any extension
        java.util.regex.Pattern filePattern = java.util.regex.Pattern.compile(
                "(?:File:\\s*)?([^\\n]+\\.[a-zA-Z0-9]+)\\s*\\n```(?:[a-zA-Z]+)?\\n([\\s\\S]*?)\\n```",
                java.util.regex.Pattern.MULTILINE
                                                                             );

        java.util.regex.Matcher matcher      = filePattern.matcher(aiResponse);
        List<String>            changedFiles = new ArrayList<>();

        while (matcher.find()) {
            String filename = matcher.group(1).trim();
            String content  = matcher.group(2);

            try {
                // Use FilePathResolver to resolve the file path
                java.nio.file.Path            currentDir = java.nio.file.Paths.get(System.getProperty("user.dir"));
                FilePathResolver.ResolvedPath resolved   = FilePathResolver.resolve(filename, currentDir, true);

                // Handle resolution result
                java.nio.file.Path filePath = resolved.getPath();

                if (!resolved.exists() && resolved.hasAlternatives()) {
                    // If alternatives are available, let user select
                    OutputFormatter.printError(resolved.getErrorMessage());
                    java.nio.file.Path selected = resolved.selectFromAlternatives();
                    if (selected != null) {
                        filePath = selected;
                    } else {
                        continue;
                    }
                }

                // Additional validation
                filePath = validatePath(filePath);
                if (filePath == null) {
                    continue;
                }

                boolean fileExists = java.nio.file.Files.exists(filePath);

                // Resolve the bytes to write. The edit system prompt (PromptConstants
                // SYSTEM_REMINDER) instructs the model to return *SEARCH/REPLACE* blocks;
                // when those markers are present we apply them against the existing file so
                // only the targeted lines change. Writing the fenced block verbatim (the
                // previous behavior) corrupted files by persisting the SEARCH/REPLACE marker
                // lines and discarding every line the block did not contain. When no markers
                // are present the fenced body is treated as the full file content (legacy).
                String contentToWrite;
                if (SearchReplaceBlocks.present(content)) {
                    java.util.List<String[]> blocks = SearchReplaceBlocks.parse(content);
                    if (blocks == null) {
                        OutputFormatter.printError("Malformed SEARCH/REPLACE block for " + filename +
                                "; skipping to avoid corrupting the file.");
                        continue;
                    }
                    String existing = fileExists
                            ? java.nio.file.Files.readString(filePath, java.nio.charset.StandardCharsets.UTF_8)
                            : "";
                    StringBuilder applyError = new StringBuilder();
                    contentToWrite = SearchReplaceBlocks.applyTo(existing, blocks, applyError);
                    if (contentToWrite == null) {
                        OutputFormatter.printError("Could not apply edit to " + filename + ": " +
                                applyError + "; file left unchanged.");
                        continue;
                    }
                } else if (content.contains("<<<<<<<") || content.contains(">>>>>>>")) {
                    // The body carries SEARCH/REPLACE scaffolding but did not qualify as a
                    // complete marker set (e.g. an inner ``` fence truncated the block before
                    // its >>>>>>> terminator). Writing it verbatim would overwrite the file
                    // with marker text, so refuse rather than corrupt the file.
                    OutputFormatter.printError("Edit response for " + filename + " looks like a " +
                            "truncated/malformed SEARCH/REPLACE block; skipping to avoid corrupting the file.");
                    continue;
                } else {
                    contentToWrite = content;
                }

                // Ensure parent directory exists
                java.nio.file.Path parent = filePath.getParent();
                if (parent != null && !java.nio.file.Files.exists(parent)) {
                    java.nio.file.Files.createDirectories(parent);
                }

                // Written through a staging file, so a failure part-way leaves the original whole.
                // No ".backup" sibling is kept: undo works through git, which already holds what the
                // file said, while the copies were untracked litter that a hard reset left in place
                // and the auto-commit's `git add .` would commit as source.
                AtomicFileWrite.writeString(filePath, contentToWrite);
                // Having written it, the run knows what is in it; see ReadBeforeEdit.
                ReadBeforeEdit.sawContents(filePath);
                OutputFormatter.printSuccess("Applied changes to " + filename);
                changedFiles.add(filename);
            } catch (java.io.IOException e) {
                OutputFormatter.printError("Error applying changes to " + filename + ": " + e.getMessage());
            }
        }

        if (changedFiles.isEmpty()) {
            OutputFormatter.printWarning("No file changes detected in AI response.");
        } else {
            // Track changes in session
            SessionManager.getInstance().saveState();
        }

        return changedFiles;
    }

    private boolean applyChanges(String filepath, String aiResponse) {
        try {
            // Use FilePathResolver to resolve the file path
            java.nio.file.Path            currentDir = java.nio.file.Paths.get(System.getProperty("user.dir"));
            FilePathResolver.ResolvedPath resolved   = FilePathResolver.resolve(filepath, currentDir, true);

            // Handle resolution result
            java.nio.file.Path filePath = resolved.getPath();

            if (!resolved.exists() && resolved.hasAlternatives()) {
                // If alternatives are available, let user select
                OutputFormatter.printError(resolved.getErrorMessage());
                java.nio.file.Path selected = resolved.selectFromAlternatives();
                if (selected != null) {
                    filePath = selected;
                } else {
                    return false;
                }
            }

            // Additional validation
            filePath = validatePath(filePath);
            if (filePath == null) {
                return false;
            }

            // Ensure parent directory exists
            java.nio.file.Path parent = filePath.getParent();
            if (parent != null && !java.nio.file.Files.exists(parent)) {
                java.nio.file.Files.createDirectories(parent);
            }

            // Staged and moved into place, and no ".backup" sibling kept; see the other write site.
            byte[] contentBytes = aiResponse.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            AtomicFileWrite.write(filePath, contentBytes);
            
            // Log file edit operation to debug log
            logDebug("File Edit", String.format("FILE: %s", filepath));
            logDebug("File Edit", String.format("ABSOLUTE_PATH: %s", filePath.toAbsolutePath()));
            logDebug("File Edit", String.format("CONTENT_LENGTH: %d bytes", contentBytes.length));
            logDebug("File Edit", String.format("CONTENT_LINES: %d", aiResponse.split("\n").length));
            logDebug("File Edit", "=== FILE CONTENT START ===");
            logDebug("File Edit", aiResponse);
            logDebug("File Edit", "=== FILE CONTENT END ===");
            
            OutputFormatter.printSuccess("Applied changes to " + filepath);

            // Track the change in undo manager
            SessionManager.getInstance().saveState();
            return true;
        } catch (java.io.IOException e) {
            // Log file edit error to debug log
            logDebug("File Edit", String.format("FILE: %s", filepath));
            logDebug("File Edit", "STATUS: ERROR");
            logDebug("File Edit", String.format("ERROR: %s", e.getMessage()));
            logDebug("File Edit", "CONTENT: <failed to write>");

            OutputFormatter.printError("Error applying changes to " + filepath + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * What the auto-commit says was done.
     *
     * <h2>Why this names the files</h2>
     *
     * <p>It used to return the constant "AI-assisted changes applied to the file." -- for every
     * edit, of every file, forever, and it is the only thing substituted into the user's configured
     * {@code git.commitMessageTemplate}. So the history this tool writes on the user's behalf said
     * the same nothing on every line of {@code git log}, which is worse than no message: a commit
     * message is the one part of a change that cannot be recovered by reading the code. The sibling
     * {@code multiedit} has always named what it did; this was the outlier.</p>
     *
     * <p>Bounded, because a single reply can rewrite a great many files and a commit subject that
     * runs to thousands of characters is unreadable in every tool that shows one.</p>
     *
     * @param changedFiles the files that were actually written
     * @return a summary naming them, or how many there were when they will not fit
     */
    static String changeSummaryOf(List<String> changedFiles) {
        if (changedFiles == null || changedFiles.isEmpty()) {
            // Not reached from the auto-commit, which does not run when nothing was written, but
            // the template still has to render into something if it ever is.
            return "no files changed";
        }
        if (changedFiles.size() == 1) {
            return "updated " + changedFiles.get(0);
        }
        if (changedFiles.size() <= SUMMARISED_FILES) {
            return "updated " + String.join(", ", changedFiles);
        }
        List<String> named = changedFiles.subList(0, SUMMARISED_FILES);
        return "updated " + String.join(", ", named)
               + " and " + (changedFiles.size() - SUMMARISED_FILES) + " more";
    }

    /** How many files a commit message names before it starts counting them instead. */
    private static final int SUMMARISED_FILES = 3;

    private java.nio.file.Path validatePath(java.nio.file.Path filePath) {
        if (filePath == null) {
            return null;
        }

        try {
            WritePathPolicy.Decision decision = WritePathPolicy.decide(filePath);
            if (!decision.isAllowed()) {
                OutputFormatter.printError(WritePathPolicy.reasonFor(decision, filePath));
                return null;
            }

            return filePath;
        } catch (Exception e) {
            OutputFormatter.printError("Invalid file path: " + filePath);
            return null;
        }
    }

    @Override
    public Integer call() throws Exception {
        return execute(editRequestParts);
    }

    @Override
    public int execute(String[] args) {
        try {
            startCommandLogging("edit", args);
            logStep("Initializing iterative edit execution");
            
            // Use iterative executor for better multi-step handling
            IterativeExecutor executor = new IterativeExecutor();
            
            logStep("Starting iterative execution for file editing");
            int result = executor.execute(this, args);
            
            completeCommandLogging(result);
            return result;
        } catch (Exception e) {
            logError("execute", "Edit command execution failed", e);
            completeCommandLogging(1);
            return 1;
        }
    }


    @Override
    public String getUsage() {
        return "edit \"<edit request>\"";
    }
}
