package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.build.BuildInfo;
import com.eonmux.cadetcoder.build.Release;
import com.eonmux.cadetcoder.build.SelfUpdate;
import com.eonmux.cadetcoder.build.UpdateCheck;

/**
 * Checks the origin repository for a newer build, and installs it.
 *
 * <h2>What checking answers, and what it is short of</h2>
 *
 * <p>The comparison is the release's commit and published time against the ones stamped into this
 * build: newer work, published after this build existed, is an update; anything short of that is
 * not, and the interesting case is the one it refuses to guess about. A build that does not record
 * what it came from, and a repository that answers nothing, both end the check at {@code UNKNOWN}
 * with the reason said out loud -- a wrong "you are up to date" is worse than an honest "I cannot
 * tell".</p>
 *
 * <h2>Why the model may look but not install</h2>
 *
 * <p>{@code update check} answers a question about the world, which a model may ask as well as the
 * person at the terminal. {@code update now} replaces the program every participant in this session
 * is running inside, which is a decision about the machine and belongs to the person who owns it.
 * A model that replaced its own jar mid-session would be discarding its own context and the user's
 * along with it.</p>
 */
@picocli.CommandLine.Command (name = "update",
        description = "Check the origin repository for a newer build, and install it")
public class UpdateCommand implements CommandRegistry.Command {

    @Override
    public int execute(String[] args) {
        String asked = args == null || args.length == 0 ? "now" : args[0].trim().toLowerCase();
        if (asked.equals("-h") || asked.equals("--help")) {
            OutputFormatter.println(CommandUsage.render(getUsage()));
            return 0;
        }
        switch (asked) {
            case "check":
                return check();
            case "now":
            case "install":
                Integer refused = ModelDispatch.refuseSetupChange("update " + asked);
                if (refused != null) {
                    return refused;
                }
                return now();
            default:
                OutputFormatter.printError("Unknown option: " + args[0]);
                OutputFormatter.printInfo("Usage: " + CommandUsage.render(getUsage()));
                return 1;
        }
    }

    /** The full comparison, said out loud, whether it is welcome news or not. */
    private int check() {
        OutputFormatter.printHeader("Update check");
        OutputFormatter.println("  This build   " + BuildInfo.current().describe());

        UpdateCheck.Result result = UpdateCheck.checkNow();
        if (result.release() != null) {
            OutputFormatter.println("  Latest       " + result.release().tag() + " (build "
                                    + result.release().commitShort() + ", published "
                                    + result.release().publishedAt() + ")");
            if (result.release().htmlUrl() != null) {
                OutputFormatter.println("  Release page " + result.release().htmlUrl());
            }
        }
        switch (result.state()) {
            case UP_TO_DATE:
                OutputFormatter.printSuccess("This build is up to date.");
                return 0;
            case NEWER_AVAILABLE:
                OutputFormatter.printWarning("A newer build is available. Run "
                                             + CommandUsage.render("update now") + " to install it.");
                return 0;
            case UNKNOWN:
                OutputFormatter.printWarning("Cannot tell whether a newer build exists: "
                                             + result.reason());
                return 1;
        }
        return 1; // Not reached; the switch covers every state.
    }

    /**
     * Checks, and installs what the check found.
     *
     * @return the exit code: {@code 0} when this build is current or was just updated, {@code 1}
     *         when nothing could be installed
     */
    private int now() {
        UpdateCheck.Result result = UpdateCheck.checkNow();
        switch (result.state()) {
            case UP_TO_DATE:
                OutputFormatter.printSuccess("This build (" + BuildInfo.current().shortCommit()
                                             + ") is up to date; nothing to install.");
                return 0;
            case UNKNOWN:
                OutputFormatter.printError("Cannot check for updates: " + result.reason());
                return 1;
            case NEWER_AVAILABLE:
                break;
        }

        if (!SelfUpdate.canUpdateTheRunningProgram()) {
            OutputFormatter.printError("This instance is not running from a jar, so there is no"
                                       + " jar to replace. Install " + result.release().tag()
                                       + " by building it or downloading it from the release page.");
            return 1;
        }
        return SelfUpdate.install(result.release(), SelfUpdate.currentJar());
    }

    @Override
    public String getUsage() {
        return "update [check | now]";
    }
}
