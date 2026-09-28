package com.eonmux.cadetcoder.harness.cadet;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.harness.budget.Budget;
import com.eonmux.cadetcoder.harness.commit.CommitPolicy;
import com.eonmux.cadetcoder.harness.env.Environment;
import com.eonmux.cadetcoder.harness.loop.Harness;
import com.eonmux.cadetcoder.harness.loop.HarnessLimits;
import com.eonmux.cadetcoder.harness.loop.Reasoner;
import com.eonmux.cadetcoder.harness.loop.RunResult;
import com.eonmux.cadetcoder.harness.loop.RunStop;
import com.eonmux.cadetcoder.harness.loop.RunWatch;
import com.eonmux.cadetcoder.harness.tools.ToolSession;

import java.util.List;

/**
 * Everything a run needs that does not change from one run to the next, and the one call that starts
 * one.
 *
 * <h2>What is bound to what</h2>
 *
 * <p>The world is {@link CommandEnvironment} -- this project's files, acted on through this tool's
 * own command registry, so an action the agent commits is a command a person could have typed. The
 * record is a {@link RunRecord} inside the project but outside what the agent observes. The thinking
 * is whatever reasoners were supplied. The gate is {@link CommitPolicy#standard()}, which is the one
 * decision here that is deliberately not the caller's: it is the bound on what the agent may do to
 * somebody's repository, and an agent that could widen it would have no gate at all.</p>
 *
 * <h2>Why the run is built per request rather than held</h2>
 *
 * <p>A ledger, a budget and a commit gate are all about one episode. Reusing them across runs would
 * carry the first run's spend and the first run's hash chain into the second, so the second could not
 * be certified and would appear to have started with its allowance already part spent. What is held
 * here is only what is genuinely the same every time: where commands come from, who thinks, who is
 * watching, and how the run can be taken back.</p>
 */
public final class HarnessRun {

    private final CommandRegistry commands;
    private final List<Reasoner>  reasoners;
    private final RunWatch        watch;
    private final RunStop         stop;

    /**
     * @param commands  what the agent acts through
     * @param reasoners who does the thinking, weakest first
     * @param watch     whoever is watching it happen
     * @param stop      whether whoever started it still wants it
     */
    public HarnessRun(CommandRegistry commands, List<Reasoner> reasoners, RunWatch watch,
                     RunStop stop) {
        if (commands == null || watch == null || stop == null) {
            throw new IllegalArgumentException("a run needs commands to act through, a watch and a "
                                               + "way to be called off");
        }
        if (reasoners == null || reasoners.isEmpty()) {
            throw new IllegalArgumentException("a run needs at least one reasoner to think with");
        }
        this.commands  = commands;
        this.reasoners = List.copyOf(reasoners);
        this.watch     = watch;
        this.stop      = stop;
    }

    /**
     * A run driven by whichever model this tool is connected to -- and, once it stops getting
     * anywhere, by whatever {@code ai.escalateTo} names -- watched from the terminal, that stops
     * when the thread driving it is interrupted.
     *
     * @param commands what the agent acts through
     * @param verbose  whether the terminal shows the agent's reasoning and every answer in full
     * @return the run
     */
    public static HarnessRun standard(CommandRegistry commands, boolean verbose) {
        return new HarnessRun(commands, Reasoners.configured(),
                             verbose ? TerminalWatch.verbose() : TerminalWatch.quiet(),
                             RunStop.whenThreadInterrupted());
    }

    /**
     * Runs one task to whatever end it reaches.
     *
     * @param request what was asked for
     * @return what it came to, and where the evidence for that is
     */
    public RunOutcome on(RunRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("there is no run without something to run");
        }
        RunRecord  record  = RunRecord.under(request.project());
        RunSummary summary = RunSummary.beginning(request);
        record.describe(summary);
        Environment world   = new CommandEnvironment(commands, request.project(), request.task(),
                                                     request.goalCheck());
        ToolSession session = ToolSession.open(world, record.directory(),
                                               new Budget(request.limits()),
                                               CommitPolicy.standard());
        RunResult result = new Harness(session, reasoners, HarnessLimits.standard(), watch, stop,
                                       new CadetSignals(request.task()))
                .run();
        record.describe(summary.completed(result));
        return new RunOutcome(result, record);
    }
}
