package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.ai.UberMode;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;

/**
 * Turns on driving a task to the end rather than to the first plausible stopping point.
 *
 * <h2>What it changes</h2>
 *
 * <p>Two things, both about the moment a run says it has finished. The model is told, up front, that
 * finishing means every part of the request, verified by whatever the project can be checked with,
 * with the surrounding code still agreeing -- and that stopping to report progress is not finishing.
 * And the claim itself is no longer the end of the run: it is sent back to be checked against the
 * request that started it, and against what the run actually changed, and only an answer that
 * survives every one of those checks in a row ends anything.</p>
 *
 * <h2>Why it is a setting and not a flag on a request</h2>
 *
 * <p>The thing being changed is what "done" means, and it has to mean the same thing for the whole
 * of a run -- including the turns after a compaction, and including whatever a run starts on its own
 * behalf. A per-request flag would apply to the first turn and be gone by the third, which is when
 * it matters. Kept in the configuration, it also survives the session, so someone who works this way
 * is not turning it back on every morning.</p>
 */
@picocli.CommandLine.Command (name = "ubermode",
        description = "Keep working until the task is actually finished, not until it looks done")
public class UberModeCommand implements CommandRegistry.Command {

    @Override
    public int execute(String[] args) {
        String asked = args == null || args.length == 0 ? "" : args[0].trim().toLowerCase();
        if (!asked.equals("status")) {
            Integer refused = ModelDispatch.refuseSetupChange("ubermode " + asked);
            if (refused != null) {
                return refused;
            }
        }
        switch (asked) {
            case "":
                return set(!UberMode.isOn());
            case "on":
            case "true":
            case "enable":
                return set(true);
            case "off":
            case "false":
            case "disable":
                return set(false);
            case "status":
                report();
                return 0;
            default:
                OutputFormatter.printError("Unknown option: " + args[0]);
                OutputFormatter.printInfo(getUsage());
                return 1;
        }
    }

    /**
     * Turns the mode on or off and says what that now means.
     *
     * @param on what it should become
     * @return the exit code
     */
    private int set(boolean on) {
        Configuration.AiConfig ai = ConfigManager.getInstance().getConfig().getAi();
        boolean                was = ai.isUberMode();
        ai.setUberMode(on);

        if (!ConfigManager.getInstance().saveConfig()) {
            // Reverted rather than left standing: a mode that is on for this session and off in the
            // file is the state that is hardest to reason about later, and the user has been told
            // nothing changed.
            ai.setUberMode(was);
            OutputFormatter.printError("Could not save the setting, so nothing was changed.");
            return 1;
        }
        report();
        return 0;
    }

    /** Says what the mode is doing, in terms of what will happen rather than of a flag's value. */
    private void report() {
        if (!UberMode.isOn()) {
            OutputFormatter.printInfo("Uber mode is off. A run ends when the model says the task is"
                                      + " done.");
            OutputFormatter.printInfo(CommandUsage.render("ubermode on") + " keeps a run going until"
                                      + " the task is actually finished.");
            return;
        }
        OutputFormatter.printSuccess("Uber mode is on.");
        OutputFormatter.printInfo("A run now has to finish every part of the request and verify it"
                                  + " with whatever this project can be checked with.");
        OutputFormatter.printInfo("A run ends when it has answered all " + UberMode.questionCount()
                                  + " closing questions in a row without doing any further work."
                                  + " Doing more work starts them again, so there is no number of"
                                  + " questions a run cannot exceed.");
        OutputFormatter.printInfo(CommandUsage.render("ubermode off") + " turns it off.");
    }

    @Override
    public String getUsage() {
        return "ubermode            turn it on if it is off, and off if it is on\n"
             + "ubermode on|off     set it explicitly\n"
             + "ubermode status     say what it is doing without changing it";
    }
}
