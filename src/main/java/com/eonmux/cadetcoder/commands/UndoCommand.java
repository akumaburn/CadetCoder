package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ExitCode;
import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.git.GitIntegration;
import com.eonmux.cadetcoder.git.GitIntegrationManager;
import com.eonmux.cadetcoder.ui.OutputRouter;
import com.eonmux.cadetcoder.security.ReadOnlyGuard;
import com.eonmux.cadetcoder.ui.InteractivePrompts;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

@Command (name = "undo", description = "Undo the last commit or discard all uncommitted changes")
public class UndoCommand implements CommandRegistry.Command, Callable<Integer> {

    @Option (names = {"-c", "--commit"},
             description = "Undo the last commit (hard reset to the previous commit)")
    boolean undoCommit = false;

    @Option (names = {"-e", "--edit"},
             description = "Discard ALL uncommitted changes (hard reset to HEAD)")
    boolean undoEdit = false;

    @Option (names = {"-f", "--force"},
             description = "Skip the confirmation prompt; required in non-interactive contexts")
    boolean force = false;

    @Override
    public Integer call() throws Exception {
        // Forward the picocli-populated option fields through args so execute() can reset
        // its own state without losing flags parsed on the picocli Callable path.
        java.util.List<String> forwarded = new java.util.ArrayList<>();
        if (undoCommit) {
            forwarded.add("--commit");
        }
        if (undoEdit) {
            forwarded.add("--edit");
        }
        if (force) {
            forwarded.add("--force");
        }
        return execute(forwarded.toArray(new String[0]));
    }

    /**
     * The tokens in {@code args} that are not flags this command knows.
     *
     * <p>Separate from {@link #execute(String[])} so the guard can be checked without discarding the
     * working tree, which is what reaching it through the command itself would do.</p>
     *
     * @param args the invocation's arguments
     * @return the stray positionals, empty when the invocation is clean
     */
    static java.util.List<String> strayPositionals(String[] args) {
        java.util.List<String> stray = new java.util.ArrayList<>();
        if (args == null) {
            return stray;
        }
        for (String arg : args) {
            if (arg == null) {
                continue;
            }
            if (arg.equals("-c") || arg.equals("--commit")
                    || arg.equals("-e") || arg.equals("--edit")
                    || arg.equals("-f") || arg.equals("--force")) {
                continue;
            }
            stray.add(arg);
        }
        return stray;
    }

    /**
     * Runs one {@code undo}, on an instance that has never run one before.
     *
     * <p>The registry builds a single {@code UndoCommand} and hands it every {@code undo} for the
     * life of the session. What one invocation typed must not still be set for the next: a prior
     * {@code undo --commit} would otherwise make a later plain {@code undo} hard-reset HEAD~1, and
     * a prior {@code --force} would take the confirmation away from it. That used to be answered by
     * putting all three fields back by hand before parsing -- a second statement of the defaults
     * the declarations already make, on the command where forgetting one is most expensive.</p>
     *
     * @param args the argument vector
     * @return the exit code
     */
    @Override
    public int execute(String[] args) {
        return new UndoCommand().executeOnce(args);
    }

    private int executeOnce(String[] args) {
        // Before anything is parsed: both forms of undo are hard resets, and a hard reset is the
        // most destructive write this tool can make.
        if (ReadOnlyGuard.blocks("undo")) {
            return 1;
        }

        // Parse flags from the current invocation's args.
        java.util.List<String> unexpected = strayPositionals(args);
        for (String arg : args) {
            if (arg == null) {
                continue;
            }
            if (arg.equals("-c") || arg.equals("--commit")) {
                undoCommit = true;
            } else if (arg.equals("-e") || arg.equals("--edit")) {
                undoEdit = true;
            } else if (arg.equals("-f") || arg.equals("--force")) {
                force = true;
            }
        }

        // Both forms of undo are hard resets, so a model's --force would discard the user's work
        // without asking them. It is dropped, and the reset is confirmed or refused as usual.
        if (force && !ModelDispatch.personsForce(force)) {
            force = false;
            com.eonmux.cadetcoder.logging.SessionLogger.getInstance().logSecurityEvent(
                    "FORCE_IGNORED", "Ignoring --force on an undo a model asked for", false);
            OutputFormatter.printWarning("Ignoring --force from within a model run: only the user "
                    + "can skip the confirmation of a hard reset.");
        }

        // This command takes NO positional arguments, and with no flag it discards the entire
        // working tree. Silently ignoring stray words while doing something that destructive is the
        // worst possible combination: `cadet undo the last thing I did` threw away every uncommitted
        // change and never mentioned the five words it ignored. Refuse instead, and say what to do.
        if (!unexpected.isEmpty()) {
            OutputFormatter.printError(
                    "undo takes no positional arguments, but got: " + String.join(" ", unexpected));
            OutputFormatter.printInfo("Usage:");
            OutputFormatter.printInfo("  " + CommandUsage.render(getUsage()));
            OutputFormatter.printInfo("To ask the AI instead, put your request in quotes: "
                    + "cadet chat \"undo " + String.join(" ", unexpected) + "\"");
            return 1;
        }

        try {
            GitIntegrationManager gitManager = GitIntegrationManager.getInstance();

            if (!gitManager.isAvailable()) {
                OutputFormatter.printError("Git is not available in this directory");
                return 1;
            }

            GitIntegration git = gitManager.getGitIntegration();

            // If no specific flag, default to undoing the working tree (discard uncommitted changes).
            boolean discardWorkingTree = undoEdit || !undoCommit;

            if (undoCommit) {
                int commitResult = undoLastCommit(git);
                if (commitResult != 0) {
                    return commitResult;
                }
            }

            if (discardWorkingTree) {
                int editResult = discardUncommittedChanges(git);
                if (editResult != 0) {
                    return editResult;
                }
            }

            return 0;
        } catch (Exception e) {
            OutputFormatter.printError("Failed to undo: " + e.getMessage());
            return 1;
        }
    }

    /**
     * Hard-resets HEAD to the previous commit (HEAD~1) after a destructive-action guard.
     *
     * @return 0 on success, non-zero (and a printed error) when the reset was refused or failed
     */
    private int undoLastCommit(GitIntegration git) throws Exception {
        OutputFormatter.printInfo("Undoing last commit...");

        // Verify commit history exists before attempting to reset past HEAD.
        // The latest commit hash is used only as a presence check; the actual
        // reset targets HEAD~1 (the commit before the latest one).
        String latestCommit = git.getLatestCommitHash();
        if (latestCommit == null) {
            OutputFormatter.printError("No previous commit found.");
            return 1;
        }

        // push-undo-2: this is a destructive hard reset that loses uncommitted changes; require an
        // explicit confirmation (interactive) or an explicit --force (non-interactive) first.
        OutputFormatter.printWarning("This will perform a hard reset and lose uncommitted changes!");
        Integer notProceeding = reasonNotToProceed(
                "Undo the last commit (hard reset to the previous commit)?");
        if (notProceeding != null) {
            return notProceeding;
        }

        // push-undo-5: with a single commit there is no HEAD~1 parent, so the reset cannot
        // resolve the ref. Detect that case and report it clearly instead of masking the
        // failure behind the generic outer handler.
        try {
            git.undo("HEAD~1");
        } catch (Exception resetEx) {
            OutputFormatter.printError(
                    "Cannot undo the last commit: the repository has only one commit, so there is "
                    + "no previous commit to reset to.");
            return 1;
        }
        OutputFormatter.printSuccess("Reset to previous commit.");
        return 0;
    }

    /**
     * Discards ALL uncommitted changes via a hard reset to HEAD after a destructive-action guard.
     *
     * @return 0 on success or when there was nothing to undo, non-zero when the reset failed
     */
    private int discardUncommittedChanges(GitIntegration git) {
        OutputFormatter.printInfo("Discarding uncommitted changes...");
        OutputFormatter.printWarning("This will discard all uncommitted changes!");

        // push-undo-2: discarding the whole working tree is destructive; require explicit
        // confirmation (interactive) or --force (non-interactive) before the hard reset.
        Integer notProceeding =
                reasonNotToProceed("Discard ALL uncommitted changes (hard reset to HEAD)?");
        if (notProceeding != null) {
            return notProceeding;
        }

        try {
            // Reset to HEAD to discard changes
            git.undo("HEAD");
            OutputFormatter.printSuccess("Uncommitted changes discarded.");
            return 0;
        } catch (Exception e) {
            String msg = e.getMessage();
            boolean isNoChanges = msg != null && msg.toLowerCase().contains("nothing");
            if (isNoChanges) {
                OutputFormatter.printWarning("No changes to undo or operation failed: " + msg);
                return 0;
            }
            OutputFormatter.printError("No changes to undo or operation failed: " + msg);
            return 1;
        }
    }

    /**
     * Destructive-action guard mirroring the CommitCommand confirmation flow.
     *
     * <p>{@code --force} bypasses the prompt in any context. In a non-interactive context
     * (system property {@code cadet.interactive=false}) a hard reset is refused unless
     * {@code --force} was supplied, so a destructive reset never runs unattended. In an
     * interactive context the user is prompted to confirm.
     *
     * <h2>Why the two refusals are not the same answer</h2>
     *
     * <p>Told no by a person, nothing is wrong: they were asked and they declined, which is the same
     * ending as stopping a command part-way. Told no by the absence of {@code --force}, the
     * invocation itself is at fault and its author has to change it. Reported with one code they were
     * indistinguishable, and the only one a script can act on is the second.</p>
     *
     * @param prompt the confirmation question to show in interactive mode
     * @return {@code null} if the destructive action may proceed, otherwise the code to report
     */
    private Integer reasonNotToProceed(String prompt) {
        if (force) {
            return null;
        }

        boolean isInteractive = InteractivePrompts.isOn();
        if (!isInteractive) {
            // A model is not told to add --force, because its --force is ignored.
            OutputFormatter.printError(
                    "Refusing to perform a destructive hard reset in a non-interactive context. "
                    + (ModelDispatch.isModelDriven()
                       ? "Only the user can confirm it."
                       : "Re-run with --force to proceed."));
            return ExitCode.FAILED;
        }

        if (!OutputRouter.getInstance().getConfirmation(prompt)) {
            OutputFormatter.printInfo("Undo cancelled.");
            return ExitCode.INTERRUPTED;
        }
        return null;
    }


    @Override
    public String getUsage() {
        // The default is stated explicitly: with no flag this DISCARDS all uncommitted changes,
        // which the old one-line usage did not say anywhere.
        return "undo [-c|--commit] [-e|--edit] [-f|--force]\n"
             + "  -e, --edit    (DEFAULT) discard ALL uncommitted working-tree changes\n"
             + "  -c, --commit  undo the last commit\n"
             + "  -f, --force   skip the confirmation prompt (required when non-interactive)";
    }
}
