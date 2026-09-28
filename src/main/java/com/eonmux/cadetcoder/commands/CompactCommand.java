package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.ai.ContextWindow;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;

/**
 * Shows and changes when a long run folds its own history to stay inside the model's input window.
 *
 * <h2>Why there is no "compact now"</h2>
 *
 * <p>The transcript compaction acts on is a local of a single {@code IterativeExecutor.execute}
 * call: it is built for one run and dies with it. Between commands there is nothing to compact, so a
 * manual trigger would have no subject — it would report success having done nothing. Compaction is
 * therefore automatic and threshold-driven, and this command is how the thresholds are set and
 * inspected.</p>
 */
@picocli.CommandLine.Command (name = "compact", description = "Show or change when a long run folds its own history")
public class CompactCommand implements CommandRegistry.Command {

    @Override
    public int execute(String[] args) {
        String action = args == null || args.length == 0 ? "status" : args[0].trim().toLowerCase();
        if (!action.equals("status")) {
            Integer refused = ModelDispatch.refuseSetupChange("compact " + action);
            if (refused != null) {
                return refused;
            }
        }
        switch (action) {
            case "status":
                return showStatus();
            case "on":
                return setEnabled(true);
            case "off":
                return setEnabled(false);
            case "trigger":
                return setShare(args, "trigger");
            case "target":
                return setShare(args, "target");
            case "keep-head":
                return setCount(args, "keep-head");
            case "keep-tail":
                return setCount(args, "keep-tail");
            default:
                OutputFormatter.printError("Unknown compact action: " + action);
                OutputFormatter.printInfo(CommandUsage.render(getUsage()));
                return 1;
        }
    }

    private int showStatus() {
        Configuration.CompactionConfig settings = settings();
        int window = ContextWindow.tokens();
        int budget = TranscriptCompactor.effectiveBudgetTokens();

        OutputFormatter.printHeader("Context compaction");
        OutputFormatter.printInfo(
                "State:           " + (settings.isEnabled() ? "on" : "off") + "\n"
                + "Input window:    " + String.format("%,d", window) + " tokens\n"
                + "Prompt budget:   " + String.format("%,d", budget)
                + " tokens  (the window, less the generation limit and a margin)\n"
                + "Folds at:        " + percent(settings.getTrigger()) + " of budget  ("
                + String.format("%,d", (int) (settings.getTrigger() * budget)) + " tokens)\n"
                + "Folds down to:   " + percent(settings.getTarget()) + " of budget  ("
                + String.format("%,d", (int) (settings.getTarget() * budget)) + " tokens)\n"
                + "Kept verbatim:   " + settings.getKeepHeadEntries() + " entries at the start, "
                + settings.getKeepTailEntries() + " at the end");
        OutputFormatter.println();
        OutputFormatter.printInfo(
                "Folding rewrites the start of the prompt, which discards the provider-side cache a\n"
                + "long run has built up, so it is deliberately rare. The head is kept verbatim\n"
                + "because caching matches a leading prefix.");
        return 0;
    }

    private int setEnabled(boolean enabled) {
        settings().setEnabled(enabled);
        return save("compaction is now " + (enabled ? "on" : "off"));
    }

    private int setShare(String[] args, String name) {
        if (args.length < 2) {
            OutputFormatter.printError("compact " + name + " needs a value between 0 and 1");
            return 1;
        }
        double value;
        try {
            value = Double.parseDouble(args[1].trim());
        } catch (NumberFormatException e) {
            OutputFormatter.printError("Not a number: " + args[1]);
            return 1;
        }
        if (value <= 0 || value >= 1) {
            OutputFormatter.printError("A share must be between 0 and 1, exclusive.");
            return 1;
        }
        Configuration.CompactionConfig settings = settings();
        if ("trigger".equals(name)) {
            if (value <= settings.getTarget()) {
                OutputFormatter.printError("The trigger must be above the target ("
                                           + percent(settings.getTarget())
                                           + "), or a run would fold on every turn.");
                return 1;
            }
            settings.setTrigger(value);
        } else {
            if (value >= settings.getTrigger()) {
                OutputFormatter.printError("The target must be below the trigger ("
                                           + percent(settings.getTrigger())
                                           + "), or a run would fold on every turn.");
                return 1;
            }
            settings.setTarget(value);
        }
        return save("compaction " + name + " is now " + percent(value));
    }

    private int setCount(String[] args, String name) {
        if (args.length < 2) {
            OutputFormatter.printError("compact " + name + " needs a number of entries");
            return 1;
        }
        int value;
        try {
            value = Integer.parseInt(args[1].trim());
        } catch (NumberFormatException e) {
            OutputFormatter.printError("Not a number: " + args[1]);
            return 1;
        }
        if (value < 0) {
            OutputFormatter.printError("A count cannot be negative.");
            return 1;
        }
        int effective = value;
        if ("keep-head".equals(name)) {
            // A head of zero is a legitimate trade: it gives up the cache-eligible prefix for room.
            settings().setKeepHeadEntries(value);
        } else {
            // A tail of zero is not: it would fold away the step being answered, leaving the model
            // nothing to continue from. Clamp, and say so rather than reporting the number asked for.
            effective = Math.max(1, value);
            settings().setKeepTailEntries(effective);
        }
        String note = effective == value ? "" : " (raised from " + value + "; the most recent step"
                                                + " is always kept)";
        String unit = effective == 1 ? " entry" : " entries";
        return save("compaction " + name + " is now " + effective + unit + note);
    }

    private int save(String what) {
        if (!ConfigManager.getInstance().saveConfig()) {
            OutputFormatter.printError("Could not save the configuration; the change applies to this run only.");
            return 1;
        }
        OutputFormatter.printSuccess("Saved: " + what + ".");
        return 0;
    }

    private static Configuration.CompactionConfig settings() {
        return ConfigManager.getInstance().getConfig().getCompaction();
    }

    private static String percent(double share) {
        return Math.round(share * 100) + "%";
    }


    @Override
    public String getUsage() {
        return "compact [status | on | off | trigger <0-1> | target <0-1> "
               + "| keep-head <n> | keep-tail <n>]";
    }
}
