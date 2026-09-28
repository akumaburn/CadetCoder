package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.git.GitIntegration;
import com.eonmux.cadetcoder.git.GitIntegrationManager;
import com.eonmux.cadetcoder.security.ReadOnlyGuard;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

@Command (name = "push", description = "Push commits to the remote")
public class PushCommand extends LoggingCommandSupport implements CommandRegistry.Command, Callable<Integer> {

    @Option (names = {"-f", "--force"}, description = "Force push (use with caution)")
    boolean force = false;

    @Option (names = {"-u", "--set-upstream"}, description = "Set upstream branch")
    boolean setUpstream = false;

    @Option (names = {"-b", "--branch"}, description = "Branch to push to")
    String branch;

    @Override
    public Integer call() throws Exception {
        // Build logged arguments from only the options that were actually provided,
        // so unset options are omitted rather than logged as empty/"null" strings.
        List<String> loggedArgs = new ArrayList<>();
        if (branch != null) {
            loggedArgs.add("--branch");
            loggedArgs.add(branch);
        }
        if (force) {
            loggedArgs.add("--force");
        }
        if (setUpstream) {
            loggedArgs.add("--set-upstream");
        }
        startCommandLogging("push", loggedArgs.toArray(new String[0]));

        // Forward the picocli-populated option fields through args so execute() parses
        // its state solely from the current invocation (preventing singleton field leaks).
        String[] forwarded = loggedArgs.toArray(new String[0]);

        try {
            int result = execute(forwarded);
            completeCommandLogging(result);
            return result;
        } catch (Exception e) {
            logError("push", "Command execution failed", e);
            completeCommandLogging(1);
            throw e;
        }
    }

    /**
     * The tokens in {@code args} that are neither a flag this command knows nor a flag's value.
     *
     * <p>Separate from {@link #execute(String[])} so the guard can be checked without performing the
     * push. It could previously only be reached by running the command, which meant asserting that a
     * VALID invocation passes the guard required a real push to the tracked remote — from a unit
     * test, over the network, on every run of the suite.</p>
     *
     * @param args the invocation's arguments
     * @return the stray positionals, empty when the invocation is clean
     */
    static java.util.List<String> strayPositionals(String[] args) {
        java.util.List<String> stray = new java.util.ArrayList<>();
        if (args == null) {
            return stray;
        }
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg == null) {
                continue;
            }
            if (arg.equals("-f") || arg.equals("--force")
                    || arg.equals("-u") || arg.equals("--set-upstream")) {
                continue;
            }
            if ((arg.equals("-b") || arg.equals("--branch")) && i + 1 < args.length) {
                i++; // the branch name belongs to the flag
                continue;
            }
            stray.add(arg);
        }
        return stray;
    }

    /**
     * Runs one {@code push}, on an instance that has never run one before.
     *
     * <p>The registry builds a single {@code PushCommand} and hands it every {@code push} for the
     * life of the session, so a {@code --force} typed once is otherwise still set for every later
     * push. That used to be answered by putting all three fields back by hand before parsing -- a
     * second statement of the defaults the declarations already make, and one that says nothing
     * when a flag is added and nobody remembers it.</p>
     *
     * @param args the argument vector
     * @return the exit code
     */
    @Override
    public int execute(String[] args) {
        return new PushCommand().executeOnce(args);
    }

    private int executeOnce(String[] args) {
        if (ReadOnlyGuard.blocks("push")) {
            return 1;
        }

        // The interactive shell calls execute(args) directly, without picocli populating the
        // fields, so the -f/--force, -u/--set-upstream and -b/--branch tokens are parsed here or
        // they are silently dropped.
        java.util.List<String> unexpected = strayPositionals(args);
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg == null) {
                continue;
            }
            if (arg.equals("-f") || arg.equals("--force")) {
                force = true;
            } else if (arg.equals("-u") || arg.equals("--set-upstream")) {
                setUpstream = true;
            } else if ((arg.equals("-b") || arg.equals("--branch")) && i + 1 < args.length) {
                branch = args[++i];
            }
        }

        // This command takes no positional arguments and pushes to the tracked upstream with no
        // confirmation of any kind. Ignoring stray words silently meant `cadet push to origin`
        // performed a real push while discarding "to origin" -- including the possibility that the
        // user believed they had targeted a specific remote. Refuse instead.
        if (!unexpected.isEmpty()) {
            OutputFormatter.printError(
                    "push takes no positional arguments, but got: " + String.join(" ", unexpected));
            OutputFormatter.printInfo("Usage:");
            OutputFormatter.printInfo("  " + CommandUsage.render(getUsage()));
            OutputFormatter.printInfo("To ask the AI instead, put your request in quotes: "
                    + "cadet chat \"push " + String.join(" ", unexpected) + "\"");
            return 1;
        }

        try {
            GitIntegrationManager gitManager = GitIntegrationManager.getInstance();

            if (!gitManager.isAvailable()) {
                logErrorQuietly("Git availability", "Git is not available in this directory");
                OutputFormatter.printError("Git is not available in this directory");
                return 1;
            }
            
            logStep("Git integration", "Git is available for push operation");

            GitIntegration git = gitManager.getGitIntegration();

            if (force) {
                Integer refused = reasonNotToForce();
                if (refused != null) {
                    return refused;
                }
            }

            addContext("force", force);
            addContext("setUpstream", setUpstream);
            addContext("targetBranch", branch);

            // Said once every reason to refuse has been ruled out. Said before them, a push this
            // build cannot do -- forced, upstream-setting, or to a named branch -- announced itself
            // and then explained that it was never going to happen, which reads as a push that
            // failed part way rather than one that was refused outright.
            OutputFormatter.printInfo("Pushing to remote repository...");

            // Execute push using JGit
            long startTime = System.currentTimeMillis();
            logStep("Git push start", "Executing push to remote repository");
            
            java.util.List<String> refused = git.push(force, branch, setUpstream);

            long pushDuration = System.currentTimeMillis() - startTime;

            // A ref the remote would not take is the ordinary way a push fails, and it arrives as a
            // status rather than as an exception. Reported here as the failure it is: the remote has
            // none of these commits, and saying otherwise sends the user away believing their work
            // is safe somewhere it is not.
            if (!refused.isEmpty()) {
                logProtocolOperation("Git", "origin", "push", false, pushDuration);
                logStep("Push rejected", "The remote refused: " + String.join("; ", refused));
                OutputFormatter.printError("The remote refused the push:");
                for (String refusal : refused) {
                    OutputFormatter.printError("  " + refusal);
                }
                OutputFormatter.printInfo(
                        "Hint: pull and merge the remote's commits, then push again.");
                return 1;
            }

            // Log protocol operation
            logProtocolOperation("Git", "origin", "push", true, pushDuration);
            logStep("Push completed", "Successfully pushed to remote repository");

            OutputFormatter.printSuccess("Successfully pushed to remote repository!");

            // Show latest commit
            String latestCommit = git.getLatestCommitHash();
            if (latestCommit != null) {
                addContext("latestCommit", latestCommit);
                OutputFormatter.printInfo("Latest commit pushed: " + latestCommit.substring(0, 7));
            }

            return 0;
        } catch (Exception e) {
            logErrorQuietly("Git push", "Push operation failed: " + e.getMessage(), e);
            logProtocolOperation("Git", "origin", "push", false, 0);
            
            String msg = e.getMessage();
            OutputFormatter.printError("Failed to push: " + msg);
            if (msg != null && msg.contains("rejected")) {
                logStep("Push rejected", "Remote rejected the push, may need to pull first");
                OutputFormatter.printInfo(
                        "Hint: The push was rejected. You may need to pull changes first or use --force (with caution).");
            }
            return 1;
        }
    }


    /**
     * Asks the person before a force push, and says why not when the push is not to happen.
     *
     * <p>A force push discards commits on the remote that may exist nowhere else, so the person
     * confirms every one, whether they or a model typed {@code -f}. The question goes to the
     * person even when {@code security.commandApproval} is {@code auto}: a model that could answer
     * it could force a push on its own word. With nobody to ask, as in a script or a worker, the
     * push is refused.</p>
     *
     * @return the exit code to stop with, or {@code null} when the person said yes
     */
    private Integer reasonNotToForce() {
        logWarning("Force push", "Forced push requested");
        boolean confirmed = com.eonmux.cadetcoder.ai.CommandApproval.askThePerson(
                "Forcing overwrites the remote's history with this branch's, and discards any "
                + "commits on the remote that are not here.",
                "push --force" + (branch != null ? " --branch " + branch : ""),
                "Force this push?");
        if (confirmed) {
            logSecurityEvent("FORCE_CONFIRMED", "The person confirmed a force push", true);
            return null;
        }
        if (!com.eonmux.cadetcoder.ai.CommandApproval.aPersonCanBeAsked()) {
            logSecurityEvent("FORCE_REFUSED", "Nobody could be asked to confirm a force push", false);
            OutputFormatter.printError("Refusing to force the push: nobody can be asked to confirm "
                    + "it. Only the user can force a push, from a terminal.");
            return 1;
        }
        logSecurityEvent("FORCE_REFUSED", "The person declined a force push", false);
        OutputFormatter.printInfo("Push cancelled.");
        return com.eonmux.cadetcoder.ExitCode.INTERRUPTED;
    }

    @Override
    public String getUsage() {
        return "push [-b|--branch <branch>] [-u|--set-upstream] [-f|--force]\n"
               + "  -b, --branch <branch>  push this branch instead of the current one\n"
               + "  -u, --set-upstream     record the pushed branch as this branch's upstream\n"
               + "  -f, --force            overwrite the remote's history, once you confirm it";
    }
}