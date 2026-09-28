package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.ai.AIManager;
import com.eonmux.cadetcoder.ai.PromptData;
import com.eonmux.cadetcoder.ai.StandInAnswer;
import com.eonmux.cadetcoder.git.GitIntegration;
import com.eonmux.cadetcoder.git.GitIntegrationManager;
import com.eonmux.cadetcoder.logging.DebugLogger;
import com.eonmux.cadetcoder.ui.ProgramOutput;
import com.eonmux.cadetcoder.security.ReadOnlyGuard;
import com.eonmux.cadetcoder.ui.InteractivePrompts;
import picocli.CommandLine.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

@Command (name = "commit", description = "Commit changes to git")
public class CommitCommand implements IterativeCommand, CommandRegistry.InterruptibleCommand, Callable<Integer> {

    /** Filenames/extensions that commonly hold secrets and warrant a pre-stage warning. */
    private static final Pattern SENSITIVE_FILE_PATTERN = Pattern.compile(
            "(?i).*(\\.env(\\..*)?|\\.pem|\\.key|\\.p12|\\.pfx|\\.keystore|id_rsa|id_dsa|id_ecdsa|id_ed25519|"
            + "credentials|secrets?)([^/\\\\]*)$");

    /** Lightweight heuristics for secret-looking content. Values are never printed, only that a match exists. */
    private static final Pattern SECRET_CONTENT_PATTERN = Pattern.compile(
            "(?i)(api[_-]?key|secret[_-]?key|access[_-]?token|aws_secret_access_key|private[_-]?key|"
            + "BEGIN [A-Z ]*PRIVATE KEY|password\\s*[=:])");

    /** Files at or above this size (bytes) are flagged as large before a stage-all. */
    private static final long LARGE_FILE_WARN_BYTES = 5L * 1024 * 1024;

    /** Cap how many bytes of any single file the secret heuristic reads. */
    private static final int MAX_SCAN_BYTES_PER_FILE = 256 * 1024;

    @Parameters (index = "0..*", description = "Commit message")
    String[] messageParts;

    private CommandRegistry.InterruptionContext interruptionContext;

    @Override
    public void setInterruptionContext(CommandRegistry.InterruptionContext context) {
        this.interruptionContext = context;
    }

    @Option (names = {"-a", "--all"}, description = "Automatically stage all modified files")
    boolean stageAll = false;

    @Option (names = {"-n", "--no-verify"},
             description = "Request bypassing the soft pre-commit lint gate (e.g. TODO scan)")
    boolean noVerify = false;

    @Override
    public StepResult executeStep(String[] args, Map<String, Object> context, String llmResponse) {
        String step = (String) context.getOrDefault("step", "initial");

        try {
            GitIntegrationManager gitManager = GitIntegrationManager.getInstance();

            if (!gitManager.isAvailable()) {
                return StepResult.failure("Error: Git is not available in this directory", context);
            }

            GitIntegration git = gitManager.getGitIntegration();
            context.put("git", git);

            switch (step) {
                case "initial":
                    // Check for staged changes or modified files
                    String status = "Ready to commit";
                    context.put("status", status);

                    // Parse arguments
                    boolean hasStageAllFlag = this.stageAll; // Check the field first
                    boolean hasNoVerifyFlag = this.noVerify; // Check the field first
                    List<String> messageParts = new ArrayList<>();

                    for (int i = 0; i < args.length; i++) {
                        if (args[i].equals("-a") || args[i].equals("--all")) {
                            hasStageAllFlag = true;
                        } else if (args[i].equals("-n") || args[i].equals("--no-verify")) {
                            hasNoVerifyFlag = true;
                        } else {
                            messageParts.add(args[i]);
                        }
                    }

                    context.put("stageAll", hasStageAllFlag);
                    context.put("noVerify", hasNoVerifyFlag);

                    if (messageParts.isEmpty()) {
                        // In non-interactive mode, fail immediately
                        boolean isInteractive = InteractivePrompts.isOn();
                        if (!isInteractive) {
                            return StepResult.failure("Error: Please provide a commit message", context);
                        }
                        context.put("step", "show_status");
                        return executeStep(args, context, null);
                    }

                    String commitMessage = String.join(" ", messageParts);
                    context.put("commitMessage", commitMessage);

                    // In non-interactive mode, skip confirmation
                    boolean isInteractive = InteractivePrompts.isOn();
                    if (StandInAnswer.answersNow()) {
                        // Auto mode during a model's work: the confirmation is put, and the model
                        // answers it in a request of its own.
                        context.put("step", "confirm_commit");
                    } else if (!isInteractive) {
                        // commit-5: make the autonomous (no-confirmation) path explicit instead of
                        // silently bypassing the confirmation gate.
                        DebugLogger.getInstance().info(
                                "CommitCommand",
                                "Non-interactive mode: committing without confirmation prompt");
                        OutputFormatter.printInfo(
                                "Non-interactive mode: proceeding with commit without confirmation.");
                        context.put("step", "execute_commit_direct");
                    } else {
                        context.put("step", "confirm_commit");
                    }
                    return executeStep(args, context, null);

                case "show_status":
                    String gitStatus = (String) context.get("status");
                    OutputFormatter.printHeader("Git Status:");
                    ProgramOutput.println(gitStatus);

                    // Always offer to suggest a commit message since we can't easily check git status
                    context.put("step", "suggest_message");
                    return new StepResult(false, "", context,
                            "Would you like me to suggest a commit message based on your changes? (yes/no)");

                case "suggest_message":
                    if (llmResponse != null && llmResponse.toLowerCase().contains("yes")) {
                        try {
                            // Since we can't get diff easily, provide a generic prompt
                            String systemPrompt =
                                    "You are a helpful assistant that suggests clear, concise git commit messages. " +
                                    "Follow conventional commit format when appropriate (feat:, fix:, docs:, etc.).";
                            String userPrompt =
                                    "The user is about to commit code changes. Suggest a clear, generic commit message that they can customize. " +
                                    "Examples: 'feat: add new feature', 'fix: resolve bug in component', 'docs: update README'";

                            PromptData promptData       = new PromptData(systemPrompt, userPrompt);
                            String     suggestedMessage =
                                    AIManager.getInstance().complete(promptData, new HashMap<>());

                            context.put("suggestedMessage", suggestedMessage);
                            context.put("step", "review_suggestion");
                            return new StepResult(false, "Suggested commit message:\n" + suggestedMessage, context,
                                    "Do you want to use this commit message? (yes/no/edit)");
                        } catch (Exception e) {
                            DebugLogger.getInstance().error(
                                    "CommitCommand", "AI commit message suggestion failed", e);
                            OutputFormatter.printWarning("Could not generate suggestion: " + e.getMessage());
                            context.put("step", "get_message");
                            return new StepResult(false, "", context, "Please provide a commit message:");
                        }
                    } else {
                        context.put("step", "get_message");
                        return new StepResult(false, "", context, "Please provide a commit message:");
                    }

                case "review_suggestion":
                    if (llmResponse != null) {
                        String response = llmResponse.toLowerCase();
                        if (response.contains("yes")) {
                            String suggestedMessage = (String) context.get("suggestedMessage");
                            context.put("commitMessage", suggestedMessage);
                            context.put("step", "check_staging");
                            return executeStep(args, context, null);
                        } else if (response.contains("edit")) {
                            context.put("step", "edit_suggestion");
                            return new StepResult(false, "", context,
                                    "Please provide your edited version of the commit message:");
                        } else {
                            context.put("step", "get_message");
                            return new StepResult(false, "", context, "Please provide a commit message:");
                        }
                    }
                    return StepResult.failure(
                            "No response provided for commit-message review, cancelled", context);

                case "edit_suggestion":
                    if (llmResponse != null && !llmResponse.trim().isEmpty()) {
                        context.put("commitMessage", llmResponse.trim());
                        context.put("step", "check_staging");
                        return executeStep(args, context, null);
                    }
                    return StepResult.failure("No commit message provided, cancelled", context);

                case "get_message":
                    if (llmResponse != null && !llmResponse.trim().isEmpty()) {
                        context.put("commitMessage", llmResponse.trim());
                        context.put("step", "check_staging");
                        return executeStep(args, context, null);
                    }
                    return StepResult.failure("No commit message provided, cancelled", context);

                case "check_staging":
                    boolean hasStageAll = (boolean) context.getOrDefault("stageAll", false);

                    // In non-interactive mode, skip staging prompt
                    boolean isInteractiveCheck = InteractivePrompts.isOn();
                    if (!isInteractiveCheck) {
                        context.put("step", "execute_commit_direct");
                        return executeStep(args, context, null);
                    }

                    if (!hasStageAll) {
                        context.put("step", "ask_stage_all");
                        return new StepResult(false, "Checking for unstaged changes", context,
                                "Would you like to stage all modified files before committing? (yes/no)");
                    }

                    context.put("step", "confirm_commit");
                    return executeStep(args, context, null);

                case "ask_stage_all":
                    if (llmResponse != null && llmResponse.toLowerCase().contains("yes")) {
                        context.put("stageAll", true);
                    }
                    context.put("step", "confirm_commit");
                    return executeStep(args, context, null);

                case "confirm_commit":
                    String message = (String) context.get("commitMessage");
                    boolean shouldStageAll = (boolean) context.getOrDefault("stageAll", false);

                    // Show what will be committed
                    OutputFormatter.printHeader("Commit Preview:");
                    OutputFormatter.printInfo("Message: " + message);
                    if (shouldStageAll) {
                        OutputFormatter.printInfo("Will stage all modified files");
                    }

                    context.put("step", "execute_commit");
                    return new StepResult(false, "", context,
                            "Do you want to proceed with this commit? (yes/no)");

                case "execute_commit_direct":
                    // Non-interactive mode answers its own confirmation and then takes the ordinary
                    // commit path. Delegated rather than fallen through: every other case in this
                    // switch hands on by re-entering with the next step, and a fallthrough here read
                    // as a missing break to both javac and the next reader. ($FALL-THROUGH$, the
                    // marker that would have said otherwise, is an Eclipse convention javac ignores.)
                    context.put("step", "execute_commit");
                    return executeStep(args, context, "yes");

                case "execute_commit":
                    if (llmResponse != null && llmResponse.toLowerCase().contains("yes")) {
                        GitIntegration gitForCommit = (GitIntegration) context.get("git");
                        String         commitMsg    = (String) context.get("commitMessage");
                        boolean        doStageAll   = (boolean) context.getOrDefault("stageAll", false);
                        boolean        doNoVerify   = (boolean) context.getOrDefault("noVerify", false);

                        if (gitForCommit == null) {
                            return StepResult.failure("Error: Git context lost", context);
                        }
                        if (commitMsg == null || commitMsg.trim().isEmpty()) {
                            return StepResult.failure("Error: No commit message available", context);
                        }

                        // Stage files if requested
                        if (doStageAll) {
                            // commit-2: surface what stage-all will touch and warn (but never block) on
                            // secret-looking or oversized files before staging everything.
                            surfacePreStageWarnings(gitForCommit);
                            OutputFormatter.printInfo("Staging all modified files...");
                            gitForCommit.stageAllChanges();
                        }

                        // commit-1: a soft pre-commit lint (TODO scan) in GitIntegration can abort an
                        // explicitly requested commit. Surface the --no-verify intent so it is explicit
                        // and (once the owner wires it through) can bypass the soft gate.
                        if (doNoVerify) {
                            DebugLogger.getInstance().info(
                                    "CommitCommand",
                                    "Commit requested with --no-verify; soft pre-commit lint should be skipped");
                            OutputFormatter.printInfo(
                                    "--no-verify requested: skipping soft pre-commit lint where supported.");
                        }

                        // Commit the changes. doNoVerify is now wired through to GitIntegration so
                        // --no-verify actually skips the (already non-blocking) soft pre-commit lint.
                        OutputFormatter.printInfo("Attempting to create commit: " + commitMsg);
                        try {
                            // A commit that records nothing is not a commit that happened. Reported
                            // as the failure it is, so a run does not continue believing its work
                            // was preserved by an entry that preserves nothing.
                            if (!gitForCommit.commit(commitMsg, doNoVerify)) {
                                return StepResult.failure(
                                        "Nothing was staged, so nothing was committed", context);
                            }
                        } catch (RuntimeException commitEx) {
                            return handleCommitFailure(commitEx, doNoVerify, context);
                        }

                        OutputFormatter.printSuccess("Changes committed successfully!");

                        // Show latest commit. A failure reading the hash here must not
                        // invalidate the commit that was already created above.
                        try {
                            String latestCommit = gitForCommit.getLatestCommitHash();
                            if (latestCommit != null && latestCommit.length() >= 7) {
                                OutputFormatter.printInfo("Latest commit: " + latestCommit.substring(0, 7));
                            }
                        } catch (Exception hashEx) {
                            DebugLogger.getInstance().error(
                                    "CommitCommand", "Could not read latest commit hash", hashEx);
                        }

                        return StepResult.success("Commit completed successfully", context);
                    } else {
                        return StepResult.failure("Commit cancelled", context);
                    }
            }

        } catch (Exception e) {
            return StepResult.failure("Failed to commit: " + e.getMessage(), context);
        }

        return StepResult.failure("Unknown step: " + step, context);
    }

    /**
     * Maps a commit failure to a user-friendly message. When the soft pre-commit lint aborts the
     * commit and --no-verify was not requested, point the user at the bypass flag instead of leaking
     * a raw exception. Never swallows the error: it is always surfaced as a failure StepResult.
     *
     * @param commitEx the exception raised by {@link GitIntegration#commit(String)}
     * @param doNoVerify whether the user already requested a verify bypass
     * @param context the iterative context to propagate
     * @return a terminal failure StepResult describing the problem
     */
    private StepResult handleCommitFailure(RuntimeException commitEx, boolean doNoVerify,
                                           Map<String, Object> context) {
        String reason = commitEx.getMessage() == null ? commitEx.toString() : commitEx.getMessage();
        DebugLogger.getInstance().error("CommitCommand", "Commit failed: " + reason, commitEx);

        boolean preCommitGate = reason != null && reason.toLowerCase().contains("pre-commit");
        if (preCommitGate && !doNoVerify) {
            OutputFormatter.printWarning(
                    "Commit blocked by a pre-commit validation. Re-run with --no-verify to bypass the "
                    + "soft lint if this is intentional.");
            return StepResult.failure(
                    "Failed to commit: " + reason + " (use --no-verify to bypass the soft pre-commit lint)",
                    context);
        }
        return StepResult.failure("Failed to commit: " + reason, context);
    }

    /**
     * Warns, before a stage-all, about what it is about to stage.
     *
     * <p>The files are the ones git reports as new or changed, so ignored build output is not among
     * them. The tree used to be walked instead, and a commit warned of secrets in
     * {@code build/classes} files that git never staged.</p>
     *
     * @param git the repository about to be staged
     */
    private void surfacePreStageWarnings(GitIntegration git) {
        OutputFormatter.printHeader("Pre-stage check (stage-all):");
        OutputFormatter.printInfo("All new and changed files in the working tree will be staged.");

        List<Path> candidates;
        try {
            candidates = git.pathsStageAllWouldAdd();
        } catch (Exception statusEx) {
            DebugLogger.getInstance().warn(
                    "CommitCommand", "Pre-stage check could not read the status: " + statusEx.getMessage());
            OutputFormatter.printWarning("The files about to be staged could not be listed, so they "
                                         + "were not checked for secrets; review the commit.");
            return;
        }
        Path here = Path.of("").toAbsolutePath();
        for (Path candidate : candidates) {
            warnIfSensitive(here, candidate);
        }
    }

    /**
     * Emits warnings (never values) for a single candidate file based on name, size, and a capped
     * content heuristic.
     */
    private void warnIfSensitive(Path root, Path candidate) {
        String relative = root.relativize(candidate).toString();
        try {
            if (SENSITIVE_FILE_PATTERN.matcher(candidate.getFileName().toString()).matches()) {
                OutputFormatter.printWarning(
                        "Sensitive-looking file will be staged: " + relative + " (verify it holds no secrets)");
            }

            long size = Files.size(candidate);
            if (size >= LARGE_FILE_WARN_BYTES) {
                OutputFormatter.printWarning(
                        "Large file will be staged: " + relative + " (" + (size / (1024 * 1024)) + " MB)");
            }

            if (containsSecretHeuristic(candidate)) {
                OutputFormatter.printWarning(
                        "Possible secret detected in: " + relative + " (review before committing)");
            }
        } catch (Exception fileEx) {
            DebugLogger.getInstance().warn(
                    "CommitCommand", "Could not inspect file for pre-stage check: " + relative);
        }
    }

    /**
     * Reads up to a capped number of bytes and reports only whether a secret-looking token exists.
     * The matched value is never returned or logged.
     */
    private boolean containsSecretHeuristic(Path candidate) {
        try {
            long size = Files.size(candidate);
            if (size == 0 || size > MAX_SCAN_BYTES_PER_FILE * 4L) {
                return false; // skip empty and clearly-binary/huge files
            }
            byte[] raw = Files.readAllBytes(candidate);
            int    len = Math.min(raw.length, MAX_SCAN_BYTES_PER_FILE);
            String text = new String(raw, 0, len, StandardCharsets.UTF_8);
            return SECRET_CONTENT_PATTERN.matcher(text).find();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String getInitialPrompt(String[] args) {
        // In non-interactive mode, don't use initial prompt
        boolean isInteractive = InteractivePrompts.isOn();
        if (!isInteractive) {
            return null;
        }

        if (args.length == 0) {
            return "The user wants to commit changes but didn't provide a commit message. What should the commit message be?";
        }
        return null;
    }

    @Override
    public boolean supportsIterativeExecution(String[] args) {
        // Always support iterative execution for better interaction
        return true;
    }

    @Override
    public Integer call() throws Exception {
        List<String> args = new ArrayList<>();
        if (messageParts != null) {
            Collections.addAll(args, messageParts);
        }
        if (noVerify) {
            args.add(0, "--no-verify");
        }
        if (stageAll) {
            args.add(0, "-a");
        }
        return execute(args.toArray(new String[0]));
    }

    @Override
    public int execute(String[] args) {
        if (ReadOnlyGuard.blocks("commit")) {
            return 1;
        }

        // Use iterative executor for better multi-step handling
        IterativeExecutor executor = new IterativeExecutor();
        return executor.execute(this, args);
    }


    @Override
    public String getUsage() {
        // commit-4: advertise every flag the parser actually accepts.
        return "commit [-a|--all] [-n|--no-verify] \"<commit message>\"";
    }
}