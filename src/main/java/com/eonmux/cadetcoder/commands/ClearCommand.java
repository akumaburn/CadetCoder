package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.ui.OutputRouter;

/**
 * Clears the interactive shell's console, as Ctrl+L does.
 *
 * <h2>What it leaves alone</h2>
 *
 * <p>Only the screen is cleared. The session log is written by the shell as lines arrive and the
 * console has no hold on it. The scrollback saved with the session keeps what was cleared, so a
 * resumed session still shows it; see {@link ShellTranscript#clearView()}. The conversation the
 * model is given is a record of its own and does not change either.</p>
 *
 * <h2>Why the model is refused</h2>
 *
 * <p>The console is the person's view of what the model did, and a model able to clear it could
 * take its own work out of view of the person watching.</p>
 */
@picocli.CommandLine.Command (name = "clear", description = "Clear the console (the session log is kept)")
public class ClearCommand implements CommandRegistry.Command {

    @Override
    public int execute(String[] args) {
        if (ModelDispatch.isModelDriven()) {
            OutputFormatter.printError("The console belongs to the person at the terminal; "
                                       + "nothing was cleared.");
            return 1;
        }
        if (!OutputRouter.getInstance().requestConsoleClear()) {
            OutputFormatter.printInfo("There is no console to clear outside the interactive shell.");
        }
        return 0;
    }

    @Override
    public String getUsage() {
        return "clear";
    }
}
