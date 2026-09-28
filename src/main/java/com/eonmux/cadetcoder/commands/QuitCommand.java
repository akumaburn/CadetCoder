package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.ui.OutputRouter;
import com.eonmux.cadetcoder.jobs.BackgroundJob;
import com.eonmux.cadetcoder.jobs.JobRegistry;
import com.eonmux.cadetcoder.ui.InteractivePrompts;

import java.util.List;

@picocli.CommandLine.Command (name = "quit", description = "Exit")
public class QuitCommand implements CommandRegistry.Command {

    /**
     * Sentinel exit code returned (instead of calling System.exit) when quit is invoked from a
     * non-interactive/agentic context. Callers that drive an interactive session should treat this
     * value as an "exit requested" signal and shut the session down on their own terms; agentic
     * dispatch can simply treat it as a successful no-op so a single 'quit' tool call cannot tear
     * down the host JVM.
     *
     * <p>The value is chosen to avoid colliding with the conventional 0 (success) / 1 (error) exit
     * codes used by the other commands.
     */
    public static final int EXIT_REQUESTED = 64;

    /**
     * Says what leaving is about to stop.
     *
     * <p>A background job is a process, not a thread, so it survives the JVM unless something ends
     * it -- and {@code JobRegistry} installs a hook that does. Ending them is right: a session that
     * walked away from a watch build would leave it running with nothing left that knows about it,
     * and the next session would start another. Doing it silently is not: a server somebody started
     * on purpose is about to go away, and this is their one chance to say so, or to note what they
     * will have to start again by hand.</p>
     */
    private static void reportJobsAboutToBeStopped() {
        List<BackgroundJob> running = JobRegistry.running();
        if (running.isEmpty()) {
            return;
        }
        OutputFormatter.printWarning(
                "Stopping " + running.size()
                + (running.size() == 1 ? " background job:" : " background jobs:"));
        for (BackgroundJob job : running) {
            OutputFormatter.printInfo("  " + job.id() + "  " + job.command());
        }
    }

    @Override
    public int execute(String[] args) {
        if (ModelDispatch.isModelDriven()) {
            OutputFormatter.printInfo("The model asked to stop; ending this run rather than the session.");
            return EXIT_REQUESTED;
        }
        // Ending the session is the user's call, so the only question that matters is whether the
        // user asked. The host being non-interactive means nobody typed anything, so nothing is
        // torn down: the sentinel goes back and the caller decides what to do with it. (A model
        // emitting this as an action is answered above, before anything is printed -- the
        // interactivity flag cannot see that case, because an agent loop runs INSIDE the interactive
        // shell where that flag reads true, and a model choosing `quit` used to close the user's
        // shell.)
        //
        // Asked BEFORE the announcement. Announced first, a non-interactive host was told "Exiting
        // CadetCoder." by a command that then returned a sentinel nothing acts on, and went on
        // running.
        if (!isInteractive()) {
            return EXIT_REQUESTED;
        }

        reportJobsAboutToBeStopped();

        OutputFormatter.printSuccess("Exiting CadetCoder.");

        // Check if we're running in interactive mode (TamboUI TUI)
        OutputRouter router = OutputRouter.getInstance();
        if (router.isRouting()) {
            // We're in TUI mode - exit gracefully by signaling the shell to close.
            // This lets runShell() run its cleanup (restore streams, save session).
            try {
                // Give the output message a moment to display
                Thread.sleep(100);
                // Signal the interactive shell's event loop to stop
                if (!router.requestExit()) {
                    // Fallback if we can't reach the shell: stop routing and exit
                    router.stopRouting();
                    System.exit(0);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // Sleep was interrupted; still ensure we exit rather than returning silently.
                router.stopRouting();
                System.exit(0);
            }
        } else {
            // We're in console mode - safe to exit directly
            System.exit(0);
        }

        return 0;
    }

    /**
     * Whether the interactive shell is running, and so whether quit has a UI to shut down in
     * order rather than a console to leave directly. Not the confirmation preference: a user who
     * turned prompts off is still at a terminal.
     */
    private boolean isInteractive() {
        return InteractivePrompts.someoneIsThere();
    }

    @Override
    public String getUsage() {
        return "quit";
    }
}
