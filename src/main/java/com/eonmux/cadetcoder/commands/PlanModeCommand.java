package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.logging.DebugLogger;
import com.eonmux.cadetcoder.session.SessionManager;
import com.eonmux.cadetcoder.ui.OutputRouter;
import com.eonmux.cadetcoder.ui.UnifiedOutput;
import picocli.CommandLine.*;

import java.io.InputStream;
import java.util.Scanner;
import java.util.concurrent.Callable;

@Command (name = "plan", description = "Plan a change before any of it is made")
public class PlanModeCommand implements CommandRegistry.Command, Callable<Integer> {

    // Single shared monitor guarding all access to the process-global plan state below.
    //
    // INTENT (planmode-1): plan mode is currently a self-contained capture/record loop. The
    // static {@link #isInPlanMode()} / {@link #getCurrentPlan()} getters exist so that other
    // components (e.g. an interactive shell prompt or the agent dispatcher) can later gate
    // behaviour on whether a plan is being assembled without this command owning that wiring.
    // No cross-file consumer is wired today; the state is intentionally observable but inert.
    //
    // IMMUTABILITY (planmode-7): the plan buffer is process-global mutable static state, which
    // runs against the project's immutable style. It is kept static deliberately so the record
    // survives across the separate command invocations that make up a plan session (enter, add,
    // exit), but every read and write is serialized through {@link #PLAN_LOCK} and reads return
    // an immutable snapshot ({@code String}), so no caller can observe or mutate a half-built
    // plan. The mutable surface is confined to this class and guarded; it is never handed out.
    private static final Object        PLAN_LOCK   = new Object();
    private static       boolean       inPlanMode  = false;
    private static final StringBuilder currentPlan = new StringBuilder();
    @Parameters (index = "0..*", description = "Initial plan description")
    private              String[]      planParts;
    @Option (names = {"-e", "--exit"}, description = "Exit plan mode")
    private boolean exitPlanMode;
    // For testing - allows injection of custom input stream
    private InputStream inputStream = System.in;
    // Reused command registry for executing the agent on plan exit. Injected by
    // CommandRegistry via reflection (setCommandRegistry) when this command is discovered, so we
    // do not pay for a fresh reflection-based command discovery just to run the agent.
    private CommandRegistry commandRegistry;

    public static boolean isInPlanMode() {
        synchronized (PLAN_LOCK) {
            return inPlanMode;
        }
    }

    public static String getCurrentPlan() {
        synchronized (PLAN_LOCK) {
            return currentPlan.toString();
        }
    }

    /**
     * Set input stream for testing purposes
     */
    public void setInputStream(InputStream inputStream) {
        this.inputStream = inputStream;
    }

    /**
     * Injects the shared {@link CommandRegistry} so plan execution can dispatch the agent through
     * the already-discovered command set instead of constructing a new registry (which would
     * re-run full reflection-based command discovery). Invoked reflectively by
     * {@link CommandRegistry} during command registration; may be {@code null} when this command
     * is used standalone (e.g. in tests), in which case a fresh registry is used as a fallback.
     */
    public void setCommandRegistry(CommandRegistry commandRegistry) {
        this.commandRegistry = commandRegistry;
    }

    /**
     * Reads a line of interactive input. Under the TUI it uses the TUI-aware
     * {@link OutputRouter} (blocking on the shell's prompt line, so plan mode works inside the
     * immediate-mode shell where {@code System.in} is owned by JLine); otherwise it reads from
     * the supplied console scanner, which wraps the configurable input stream and lets tests
     * inject input.
     *
     * <p>Returns {@code null} to signal end-of-input. This happens when the scanner is exhausted
     * (no next line) and, critically (planmode-4), when the console fallback yields an empty or
     * blank line. {@link OutputRouter#getUserInput(String)} returns {@code ""} (not {@code null})
     * when no real console is attached, and an exhausted {@link Scanner} on an empty stream would
     * otherwise let the caller's read loop append blanks and spin forever. Collapsing that empty
     * fallback to {@code null} here gives the loop a single, unambiguous EOF guard so it always
     * terminates instead of busy-looping on the same empty input.
     */
    private String readInteractiveLine(String prompt, Scanner consoleScanner) {
        if (OutputRouter.getInstance().isRouting()) {
            String routed = OutputRouter.getInstance().getUserInput(prompt);
            // The router's no-console fallback returns "" rather than null; treat that (and any
            // null) as EOF so the plan loop cannot spin on a stream that never blocks.
            return isBlank(routed) ? null : routed;
        }
        UnifiedOutput.print(prompt);
        if (consoleScanner == null || !consoleScanner.hasNextLine()) {
            return null;
        }
        String line = consoleScanner.nextLine();
        return isBlank(line) ? null : line;
    }

    /**
     * @return {@code true} when the value is {@code null} or contains only whitespace. Used as the
     * end-of-input / blank-line guard for the plan-input loop (planmode-4).
     */
    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    @Override
    public int execute(String[] args) {
        try {
            // Check if exiting plan mode
            boolean exit = false;
            for (String arg : args) {
                if (arg.equals("-e") || arg.equals("--exit")) {
                    exit = true;
                    break;
                }
            }

            if (exit) {
                return exitPlanMode(null);
            }

            // Enter plan mode
            return enterPlanMode(args);
        } catch (Exception e) {
            OutputFormatter.printError("Error in plan mode: " + e.getMessage());
            return 1;
        }
    }

    /**
     * Exits plan mode and optionally executes the recorded plan.
     *
     * @param existingScanner the console scanner already reading {@code inputStream} (passed in when
     *                        invoked from the interactive {@link #enterPlanMode(String[])} loop so the
     *                        single Scanner that owns the stream is reused and previously buffered input
     *                        is not lost); {@code null} when invoked directly, in which case a fresh
     *                        Scanner is allocated for the y/n prompt.
     */
    private int exitPlanMode(Scanner existingScanner) throws Exception {
        String planSnapshot;
        synchronized (PLAN_LOCK) {
            if (!inPlanMode) {
                OutputFormatter.printWarning("Not currently in plan mode");
                return 0;
            }

            inPlanMode = false;

            if (currentPlan.length() == 0) {
                OutputFormatter.printInfo("Exited plan mode with no plan");
                return 0;
            }
            planSnapshot = currentPlan.toString();
        }

        OutputFormatter.printHeader("Exiting Plan Mode");
        OutputFormatter.printInfo("Your plan has been recorded:");
        UnifiedOutput.println(planSnapshot);

        // Recorded where a session can actually give it back. It used to go into
        // SessionState.currentCommand, which is written here and read nowhere -- so "the plan is
        // persisted" was true of the bytes and false of everything that matters. The conversation
        // is the part of a session that is restored AND handed to the model, so a plan put there
        // survives a resume as something both the user and the model can still see.
        SessionManager.getInstance().addUserRequest("Plan for this session:\n" + planSnapshot);
        SessionManager.getInstance().saveSession();

        // Ask user if they want to execute the plan. Reuse the caller's Scanner when one was passed in
        // (interactive 'plan --exit' path) so the single Scanner owning inputStream keeps any buffered
        // bytes (e.g. the user's y/n answer); otherwise allocate a fresh one for direct invocations.
        Scanner scanner = existingScanner != null
                          ? existingScanner
                          : (OutputRouter.getInstance().isRouting() ? null : new Scanner(inputStream));
        String  rawResponse = readInteractiveLine("Would you like to execute this plan now? (y/n): ", scanner);
        String  response    = rawResponse == null ? "" : rawResponse.trim().toLowerCase();

        if (response.equals("y") || response.equals("yes")) {
            // Execute plan using agent
            OutputFormatter.printInfo("Executing plan with AI agent...");
            try {
                // Reuse the injected registry when available so we do not re-run the full
                // reflection-based command discovery just to dispatch the agent (planmode-5).
                // Only fall back to a fresh registry when none was injected (e.g. standalone use).
                CommandRegistry registry = commandRegistry != null ? commandRegistry : new CommandRegistry();
                registry.executeCommand("agent", new String[] {planSnapshot});
            } catch (Exception e) {
                // The agent command may be unavailable (e.g. not registered in a test/headless
                // environment) or it may have failed for a real reason (auth/AI error). Surface
                // the underlying cause instead of discarding it, and log full detail. The plan is
                // still persisted below so the user can re-run it, hence we do not fail the exit.
                String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                OutputFormatter.printWarning("Plan execution did not complete: " + detail);
                DebugLogger.getInstance().error("plan", "Agent execution failed during plan exit", e);
            }
        }

        OutputFormatter.printSuccess("Plan saved for future execution");
        synchronized (PLAN_LOCK) {
            currentPlan.setLength(0);
        }
        return 0;
    }

    private int enterPlanMode(String[] args) throws Exception {
        synchronized (PLAN_LOCK) {
            if (inPlanMode) {
                OutputFormatter.printWarning("Already in plan mode");
                return 0;
            }

            inPlanMode = true;
            currentPlan.setLength(0);

            // Add initial plan if provided (recorded under the lock so a concurrent reader
            // cannot observe plan mode active with a still-empty plan buffer).
            if (args.length > 0) {
                currentPlan.append(String.join(" ", args)).append("\n");
            }
        }

        OutputFormatter.printHeader("Entering Plan Mode");
        OutputFormatter.printInfo("You are now in plan mode. Describe your plan in detail.");
        OutputFormatter.printInfo("Type 'done' or 'exit' when finished, or use '" + CommandUsage.prefix()
                + "plan --exit' to exit plan mode.");

        if (args.length > 0) {
            OutputFormatter.printSuccess("Initial plan recorded");
        }

        // Interactive plan input
        Scanner scanner = OutputRouter.getInstance().isRouting() ? null : new Scanner(inputStream);
        while (isInPlanMode()) {
            // Every way out of the loop goes through exitPlanMode, because that is the only thing
            // that does anything with the plan: records it into the session, shows it back, and
            // asks whether to act on it. "done", "exit" and the end of input each used to clear
            // inPlanMode themselves and break -- and exitPlanMode refuses outright when plan mode
            // is already off. So the plan sat in a buffer that "plan --exit" would no longer touch
            // and the next "plan" would overwrite: the two words the tool tells the user to type
            // when they have finished were the two that threw the work away.
            String rawLine = readInteractiveLine("plan> ", scanner);
            if (rawLine == null) {
                exitPlanMode(scanner);
                break;
            }
            String line = rawLine.trim();

            if (saysTheyAreFinished(line)) {
                exitPlanMode(scanner);
                break;
            }

            // Add to plan
            synchronized (PLAN_LOCK) {
                currentPlan.append(line).append("\n");
            }
        }

        // No summary here. The loop above cannot be left without going through exitPlanMode, which
        // has already shown the plan and said what became of it; a second rendering underneath it
        // showed the same thing twice and said it was still waiting to be executed.
        return 0;
    }


    /**
     * Whether a line typed at the {@code plan>} prompt means "that is the plan".
     *
     * <h2>Why the prefix has to be allowed for</h2>
     *
     * <p>Entering plan mode prints "use '<i>prefix</i>plan --exit' to exit plan mode", and the
     * prefix is {@code /} in the shell and {@code cadet } on the command line -- while the check
     * here matched the bare spelling with no prefix at all. The exact line the tool had just told
     * the user to type was therefore appended to their plan as though it were a step of it.</p>
     *
     * <p>Matched whole rather than by prefix: "plan the migration in two phases" is a plan, and
     * "we are done when the tests pass" is a step, so a line that merely BEGINS with one of these
     * words is not a way out.</p>
     *
     * @param line what was typed, already trimmed of surrounding space or not
     * @return {@code true} when the plan is finished and should be acted on
     */
    static boolean saysTheyAreFinished(String line) {
        if (line == null) {
            return false;
        }
        String said = line.trim();
        if (said.equalsIgnoreCase("done") || said.equalsIgnoreCase("exit")) {
            return true;
        }
        String withoutPrefix = said.startsWith("/") ? said.substring(1)
                             : said.regionMatches(true, 0, "cadet ", 0, "cadet ".length())
                               ? said.substring("cadet ".length()).trim()
                               : said;
        return withoutPrefix.equalsIgnoreCase("plan --exit")
               || withoutPrefix.equalsIgnoreCase("plan -e");
    }

    @Override
    public String getUsage() {
        return "plan [initial_description]\n"
             + "plan -e|--exit              leave plan mode and act on the plan";
    }

    @Override
    public Integer call() throws Exception {
        String[] args = planParts != null ? planParts : new String[0];
        if (exitPlanMode) {
            return exitPlanMode(null);
        }
        return enterPlanMode(args);
    }
}