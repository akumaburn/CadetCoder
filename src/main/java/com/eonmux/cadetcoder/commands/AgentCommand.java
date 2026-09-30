package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.ai.AIManager;
import com.eonmux.cadetcoder.ai.PromptData;
import com.eonmux.cadetcoder.ai.UberMode;
import com.eonmux.cadetcoder.harness.budget.BudgetLimits;
import com.eonmux.cadetcoder.harness.cadet.HarnessRun;
import com.eonmux.cadetcoder.harness.cadet.RunCutShort;
import com.eonmux.cadetcoder.harness.cadet.RunOutcome;
import com.eonmux.cadetcoder.harness.cadet.RunRequest;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.context.ContextEngine;
import com.eonmux.cadetcoder.jobs.BackgroundJob;
import com.eonmux.cadetcoder.jobs.JobNotice;
import com.eonmux.cadetcoder.resume.ResumeScope;
import com.eonmux.cadetcoder.session.ResumePoint;
import com.eonmux.cadetcoder.timers.TimerNotice;
import picocli.CommandLine.*;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.Callable;

@Command (name = "agent", description = "Launch an AI agent for complex multi-step tasks")
public class AgentCommand extends LoggingCommandSupport implements IterativeCommand, CommandRegistry.InterruptibleCommand, Callable<Integer> {

    @Parameters (index = "0..*", description = "The task description for the agent")
    private String[] taskParts;

    @Option (names = {"-t", "--timeout"},
            description = "Stop after this many seconds (default: no time limit)")
    private Integer timeout = AgentOptions.UNLIMITED;

    @Option (names = {"-m", "--max-steps"},
            description = "Stop after this many steps (default: no step limit)")
    private Integer maxSteps = AgentOptions.UNLIMITED;

    @Option (names = {"-v", "--verbose"}, description = "Show detailed agent reasoning")
    private boolean verbose;

    @Option (names = {"-c", "--check"},
            description = "A command that leaves with 0 when the task is done")
    private String check;

    @Option (names = {"--classic"},
            description = "Run under the classic loop instead of the harness")
    private boolean classic;

    private CommandRegistry commandRegistry;

    /** Per-thread nesting depth of running agents, and the maximum permitted (1 = no nesting). */
    private static final ThreadLocal<Integer> AGENT_DEPTH     = ThreadLocal.withInitial(() -> 0);
    private static final int                   MAX_AGENT_DEPTH = 1;
    private CommandRegistry.InterruptionContext interruptionContext;

    /** The context key for the directory of the harness run's record. */
    private static final String RUN_RECORD = "runRecord";

    /** What this run is told about the interrupted run it carries on, or empty for a new run. */
    private String resumeNote = "";

    /** The interrupted run this run carries on, or {@code null} for a new run. */
    private ResumePoint.Agent resumed;

    public void setCommandRegistry(CommandRegistry registry) {
        this.commandRegistry = registry;
    }

    /**
     * Runs the whole task under the agent harness and reports what it came to.
     *
     * <h2>Why this is one step and not a loop of them</h2>
     *
     * <p>{@link IterativeExecutor} drives a step at a time because the previous loop needed a turn
     * of the crank per model reply. The harness owns its own loop, its ledger and its commit gate,
     * and a driver that took one deliberation at a time would have to hold that state between calls
     * -- in the step context, where a resumed session or a second invocation would find half of it.
     * So the run happens here, on this thread, and what comes back is the ending.</p>
     *
     * <h2>Why the thread matters</h2>
     *
     * <p>{@code CommandRegistry} runs an interruptible command on its own thread and interrupts that
     * thread when the user presses Ctrl-C. The harness is given {@code RunStop.whenThreadInterrupted}
     * , so a run called off here stops at its next turn with its ledger whole rather than being
     * abandoned mid-commit.</p>
     */
    private StepResult underTheHarness(Map<String, Object> context, String task) {
        // Read with a default rather than a cast: a context is a map, and the one thing a run must
        // not do with an allowance it cannot find is invent one. Absent means nobody set a bound.
        BudgetLimits allowance = context.get("budget") instanceof BudgetLimits asked
                                 ? asked : BudgetLimits.unlimited();
        RunRequest request = RunRequest.here(task)
                                       .checkedBy((String) context.get("goalCheck"))
                                       .spending(allowance);
        RunOutcome outcome;
        try {
            outcome = underTheHarness(request, Boolean.TRUE.equals(context.get("verboseMode")));
        } catch (RunCutShort cut) {
            // The record holds what the run did before it was cut short, which a resume needs.
            context.put(RUN_RECORD, cut.record().directory().toString());
            throw cut.failure();
        }

        context.put("step", "harness_finished");
        context.put(RUN_RECORD, outcome.record().directory().toString());
        // A run somebody called off is reported as taken back rather than as failed, so that the
        // whole command answers 130 whichever loop was driving it.
        if (outcome.exitCode() == RunOutcome.CALLED_OFF) {
            return StepResult.interrupted(reported(outcome), context);
        }
        return new StepResult(true, reported(outcome), context, null,
                              outcome.exitCode() != RunOutcome.FINISHED);
    }

    /**
     * What the run came to, and what to type to read the record it left.
     *
     * <p>The record is the point of running under the harness, and a path printed on its own is not
     * an invitation to open it. The line is spelled for the surface the reader is on, so it can be
     * used where it is read.</p>
     */
    private static String reported(RunOutcome outcome) {
        return outcome.render() + System.lineSeparator()
               + CommandUsage.render("runs show " + outcome.record().name()) + " opens it.";
    }

    /**
     * Puts one request to the harness.
     *
     * <p>Separated so a test can see what a run was asked for without a backend behind it, by the
     * same convention as {@link #getAIManager()}. A run is built per request rather than kept:
     * a reused one would carry the first run's ledger, budget and commit gate into the second.</p>
     */
    protected RunOutcome underTheHarness(RunRequest request, boolean verbose) {
        return HarnessRun.standard(registry(), verbose).on(request);
    }

    /**
     * The commands this run acts through.
     *
     * <p>Built on demand because not every caller sets one: the shell hands its own registry over so
     * an agent sees the session's commands, while a worker constructs an {@code AgentCommand}
     * directly and never calls {@link #setCommandRegistry}. A run with no commands at all can do
     * nothing, so one is made rather than the run failing on a null.</p>
     */
    private CommandRegistry registry() {
        if (commandRegistry == null) {
            commandRegistry = new CommandRegistry();
        }
        return commandRegistry;
    }

    @Override
    public void setInterruptionContext(CommandRegistry.InterruptionContext context) {
        this.interruptionContext = context;
    }

    @Override
    public boolean shouldInterrupt() {
        return CommandRegistry.InterruptibleCommand.stopWasAsked(interruptionContext);
    }

    @Override
    public StepResult executeStep(String[] args, Map<String, Object> context, String llmResponse) {
        String step = (String) context.getOrDefault("step", "initial");

        switch (step) {
            case "initial":            return readInvocation(args, context);
            case "get_task":           return takeTaskFromReply(args, context, llmResponse);
            case "confirm_task":       return askToProceed(args, context);
            case "confirm_execution":  return startIfConfirmed(args, context, llmResponse);
            case "execute_agent_step": return classicSteps(context);
            case "handle_error":       return retryIfAsked(args, context, llmResponse);
            default:                   return StepResult.success("Unknown step: " + step, context);
        }
    }

    /**
     * Reads the whole invocation once, by the one thing that reads it.
     *
     * <p>What the run is governed by is this context, so the budgets have to be established here: a
     * second reader that saw a vector the options had already been taken out of found none of them,
     * and put the defaults in their place.</p>
     */
    private StepResult readInvocation(String[] args, Map<String, Object> context) {
        AgentOptions invocation = AgentOptions.parse(args);
        if (invocation.task().length == 0) {
            context.put("step", "get_task");
            return new StepResult(false, "No task description provided", context,
                                  "What task would you like the AI agent to complete?");
        }

        // A resumed run was confirmed when it was first started, and the resume asked for it again.
        boolean resuming = !resumeNote.isEmpty();
        context.put("task", invocation.taskDescription() + resumeNote);
        context.put("timeoutSec", invocation.timeoutSeconds());
        context.put("maxStepCount", invocation.maxSteps());
        context.put("verboseMode", invocation.verbose());
        context.put("preconfirmed", invocation.preconfirmed() || resuming);
        if (resumed != null) {
            context.put("uberChecksPassed", resumed.uberChecksPassed());
        }
        context.put("classic", invocation.classic());
        context.put("goalCheck", invocation.goalCheck());
        context.put("budget", invocation.budget());
        context.put("step", "confirm_task");
        return executeStep(args, context, null);
    }

    /** The task, when it had to be asked for. A run with no task at all is cancelled, not guessed. */
    private StepResult takeTaskFromReply(String[] args, Map<String, Object> context, String reply) {
        if (reply == null || reply.trim().isEmpty()) {
            return StepResult.failure("No task provided, agent cancelled", context);
        }
        context.put("task", reply.trim());
        context.put("timeoutSec", AgentOptions.UNLIMITED);
        context.put("maxStepCount", AgentOptions.UNLIMITED);
        context.put("verboseMode", false);
        context.put("classic", false);
        context.put("goalCheck", null);
        context.put("budget", BudgetLimits.unlimited());
        context.put("step", "confirm_task");
        return executeStep(args, context, null);
    }

    /**
     * Asks whether to go ahead, unless the caller already answered.
     *
     * <p>A caller's authority is answered with rather than asked into a void: without this a worker
     * reached a yes/no it could not show anyone, and the safe default for an unanswerable
     * confirmation is "no" -- so every worker cancelled itself before doing any work.</p>
     */
    private StepResult askToProceed(String[] args, Map<String, Object> context) {
        String task     = (String) context.get("task");
        int    maxSteps = (int) context.get("maxStepCount");
        context.put("step", "confirm_execution");
        if (Boolean.TRUE.equals(context.get("preconfirmed"))) {
            return executeStep(args, context, "yes");
        }
        return new StepResult(false, "Task configured", context,
                              "I'll help you with: \"" + task + "\"\n"
                              + "The agent will run " + describeStepBudget(maxSteps)
                              + " to complete this task.\n"
                              + "Do you want to proceed? (yes/no)");
    }

    /** Starts the run, under the harness unless {@code --classic} asked for the classic loop. */
    private StepResult startIfConfirmed(String[] args, Map<String, Object> context, String reply) {
        String answer = reply == null ? "" : reply.trim().toLowerCase();
        if (!answer.equals("y") && !answer.equals("yes")) {
            // Nobody authorised the run, so it never started. That is not a failed task.
            return StepResult.interrupted("Agent execution cancelled", context);
        }
        String task     = (String) context.get("task");
        int    maxSteps = (int) context.get("maxStepCount");

        OutputFormatter.printHeader("AI Agent Starting");
        OutputFormatter.printInfo("Task: " + task);
        OutputFormatter.printInfo("Step budget: " + describeStepBudget(maxSteps));

        if (!Boolean.TRUE.equals(context.get("classic"))) {
            return underTheHarness(context, task);
        }
        context.put("agentState", new AgentState(task, maxSteps, this::nowMillis));
        context.put("step", "execute_agent_step");
        return executeStep(args, context, null);
    }

    /** Takes the step again when the person at the terminal said to, and stops when they did not. */
    private StepResult retryIfAsked(String[] args, Map<String, Object> context, String reply) {
        if (reply != null && reply.toLowerCase().contains("yes")) {
            context.put("step", "execute_agent_step");
            return executeStep(args, context, null);
        }
        return StepResult.failure("Agent execution stopped due to error", context);
    }

    /**
     * Runs the classic loop to its end: one model reply, one action, again.
     *
     * <h2>Why this is a loop and not a recursion</h2>
     *
     * <p>Each step used to finish by calling {@code executeStep} again, so a run's stack depth was
     * its step count. That survived only while the loop had a built-in ceiling of ten steps. Budgets
     * are opt-in now, and a run long enough to be worth starting overflowed the stack -- reported as
     * an "Agent execution error" with the work half done.</p>
     *
     * <p>{@link IterativeExecutor} cannot drive these steps itself: it ends any run whose step comes
     * back without a next prompt, and there is nobody to prompt between one action and the next.</p>
     */
    private StepResult classicSteps(Map<String, Object> context) {
        while (true) {
            try {
                StepResult finished = oneClassicStep(context);
                if (finished != null) {
                    return finished;
                }
            } catch (Exception e) {
                OutputFormatter.printError("Agent execution error: " + e.getMessage());
                return StepResult.failure("Agent failed: " + e.getMessage(), context);
            }
        }
    }

    /**
     * One turn of the classic loop: think, propose, run.
     *
     * @param context the run's context
     * @return what the run came to, or {@code null} to take another turn
     */
    private StepResult oneClassicStep(Map<String, Object> context) {
        AgentState state = (AgentState) context.get("agentState");

        StepResult stop = reasonToStop(state, context);
        if (stop != null) {
            return stop;
        }
        int timeBudgetSec = (int) context.getOrDefault("timeoutSec", AgentOptions.UNLIMITED);

        state.incrementStep();
        // Opens the step's navigable section, and marks the boundary between one step and the next
        // so a long run can be scanned. It used to read "Thinking", which repeated the header bar's
        // live status word for word.
        OutputFormatter.printIteration("Step " + state.getCurrentStep());

        String systemPrompt = AgentPrompts.system(state, timeBudgetSec,
                                                  message -> logWarning("buildSystemPrompt", message));
        String userPrompt   = AgentPrompts.user(state, context, getContextEngine());

        if ((boolean) context.get("verboseMode")) {
            OutputFormatter.printInfo("Agent reasoning...");
        }
        // Asked again here because building the prompt searches the index, which is not instant: an
        // interruption during it would otherwise still cost a whole model request.
        if (shouldInterrupt()) {
            // Building the prompt withdrew any timer firing and any job ending that had come due,
            // and this prompt is not going to be sent, so both are owed again rather than spent on
            // nobody.
            TimerNotice.undelivered();
            JobNotice.undelivered();
            return interrupted(context);
        }

        String reply = askTheModel(systemPrompt, userPrompt);
        if (reply == null) {
            TimerNotice.undelivered();
            JobNotice.undelivered();
            context.put("step", "handle_error");
            return new StepResult(false, "AI response failed", context,
                                  "The AI failed to respond. Should we retry this step? (yes/no)");
        }
        TimerNotice.delivered();
        JobNotice.delivered();

        AgentAction action = AgentActions.from(reply, message -> logWarning("parseAction", message));
        if (action == null) {
            askForTheFormatAgain(context, reply);
            return null;
        }
        // A response parsed cleanly, so every correction the prompt carried has now been read: the
        // parse-failure format reminder, the loop guard's guidance, and any challenge the reply was
        // answering. All three go here rather than on the paths that happen to follow, because a
        // correction left in the context is repeated in every prompt for the rest of the run --
        // and the guard sets its guidance again, below, for a proposal that is refused again.
        context.remove("agentParseFailed");
        context.remove("loopGuidance");
        context.remove("uberChallenge");
        context.remove(JOBS_QUESTION);

        if (action.command.equals("complete")) {
            if (questionedCompletion(state, context, action.result)) {
                return null;
            }
            state.markComplete();
            OutputFormatter.printSuccess("Agent task completed: " + action.result);
            return StepResult.success("Agent completed successfully", context);
        }
        return runProposedAction(state, context, action);
    }

    /**
     * Whether this claim of completion is sent back to be checked rather than believed.
     *
     * <p>The claim is not marked complete and nothing is announced as finished: the run simply takes
     * another turn, carrying the challenge as the correction its next prompt is built with. A model
     * that has nothing to add answers each question in turn, and once it has answered all of them
     * without acting on any, it is believed.</p>
     *
     * <p>The question cannot be asked where the claim is made: the claim ends a step, and a step's
     * prompt has already been built. So what is recorded here is a correction for the NEXT prompt,
     * carried in the run's context beside the parse-failure and loop-guard corrections and cleared
     * the moment a reply parses -- and a count beside it, which {@link #runProposedAction} sets
     * back to zero, so that a run which does more work is questioned from the beginning again.</p>
     *
     * @param state   the run so far, for the task as it was originally given
     * @param context the run's context, which carries the count and the correction
     * @param claim   what the model said when it declared the work finished
     * @return {@code true} if the run goes on, {@code false} to accept the claim
     */
    boolean questionedCompletion(AgentState state, Map<String, Object> context, String claim) {
        if (askedAboutJobsLeftRunning(state, context)) {
            return true;
        }
        int passed = context.get("uberChecksPassed") instanceof Integer count ? count : 0;
        if (!UberMode.shouldQuestion(passed) || !thereIsAstepLeftToAnswerIn(state, context)) {
            return false;
        }
        int round = passed + 1;
        context.put("uberChecksPassed", round);
        context.put("uberChallenge",
                    UberMode.challenge(round, state == null ? null : state.getTask(), claim,
                                       FINISH_THE_AGENT_WAY));
        OutputFormatter.printInfo("Checking the claim that this is finished (" + round + " of "
                                  + UberMode.questionCount() + "). "
                                  + CommandUsage.prefix() + "ubermode off stops this.");
        logStep("Completion questioned", "Question " + round + " of " + UberMode.questionCount());
        return true;
    }

    /** The context key the question about jobs left running is carried to the next prompt in. */
    static final String JOBS_QUESTION = "jobsQuestion";

    /**
     * Whether this claim is sent back because jobs the run started are still running.
     *
     * <p>Asked whether or not uber mode is on, and before its questions: a run that ends here is
     * never told when its jobs end. See {@link JobsLeftRunning}.</p>
     *
     * @param state   the run so far
     * @param context the run's context, which carries the jobs already asked about
     * @return {@code true} if the run goes on
     */
    private boolean askedAboutJobsLeftRunning(AgentState state, Map<String, Object> context) {
        List<String> askedAbout = new ArrayList<>();
        if (context.get(JobsLeftRunning.ASKED_KEY) instanceof List<?> ids) {
            for (Object id : ids) {
                if (id instanceof String text) {
                    askedAbout.add(text);
                }
            }
        }
        List<BackgroundJob> running = JobsLeftRunning.toAskAbout(askedAbout);
        if (running.isEmpty() || !thereIsAstepLeftToAnswerIn(state, context)) {
            return false;
        }
        context.put(JobsLeftRunning.ASKED_KEY, JobsLeftRunning.nowAskedAbout(askedAbout, running));
        context.put(JOBS_QUESTION, JobsLeftRunning.question(running, FINISH_THE_AGENT_WAY));
        OutputFormatter.printInfo("Jobs this run started are still running; asking whether the"
                                  + " answer needs them.");
        logStep("Completion questioned", running.size() + " jobs still running");
        return true;
    }

    /**
     * Whether the run could still take the step in which an answer would be read.
     *
     * <h2>Why a question nobody can answer is worse than no question</h2>
     *
     * <p>A run's budgets are checked at the START of a step and a claim of completion is made at the
     * END of one. A model that said it was finished on its last allowed step was therefore sent back
     * to check itself and the next step never came: the run ended on "reached maximum steps without
     * completing the task", and the completion it had actually claimed, with the summary it had
     * written, was discarded. Turning the mode on made the run end worse than leaving it off.</p>
     *
     * @param state   the run so far
     * @param context the run's context, which carries the time budget
     * @return whether another step would be allowed to happen
     */
    private static boolean thereIsAstepLeftToAnswerIn(AgentState state, Map<String, Object> context) {
        if (state == null) {
            return true;
        }
        if (state.maxSteps > AgentOptions.UNLIMITED && state.getCurrentStep() >= state.maxSteps) {
            return false;
        }
        int timeBudgetSec = context.get("timeoutSec") instanceof Integer seconds
                            ? seconds
                            : AgentOptions.UNLIMITED;
        return !state.isTimedOut(timeBudgetSec);
    }

    /** How the agent loop is told to go on, and how to end, when a claim is sent back. */
    private static final String FINISH_THE_AGENT_WAY =
            "Respond with exactly ONE ACTION_START/ACTION_END block if anything remains. If "
            + "everything really is done and verified, reply 'TASK COMPLETE: <summary>' again and "
            + "the run will end.";

    /**
     * Remembers a reply that proposed nothing, so the next prompt re-states the required action
     * format rather than silently re-prompting with no feedback.
     *
     * @param context the run's context
     * @param reply   what the model said instead
     */
    private void askForTheFormatAgain(Map<String, Object> context, String reply) {
        logWarning("parseAction", "Could not parse agent action from AI response: " + reply);
        OutputFormatter.printWarning("Could not parse agent action");
        context.put("agentParseFailed", Boolean.TRUE);
    }

    /**
     * Why this run should stop before taking another step, or {@code null} to take one.
     *
     * @param state   the run so far
     * @param context the run's context
     * @return what the run came to, or {@code null}
     */
    private StepResult reasonToStop(AgentState state, Map<String, Object> context) {
        if (shouldInterrupt()) {
            return interrupted(context);
        }
        // Already finished: this is a re-entry guard, not the announcement. The step that marked the
        // task complete announced it; announcing again here printed the same "Agent task completed"
        // line twice at the end of every agent run.
        if (state.isComplete()) {
            return StepResult.success("Agent completed successfully", context);
        }
        // Both budgets are opt-in: enforced only when the caller asked for one.
        if (state.maxSteps > AgentOptions.UNLIMITED && state.getCurrentStep() >= state.maxSteps) {
            OutputFormatter.printWarning("Agent reached the requested limit of " + state.maxSteps
                                         + " steps without completing the task");
            return StepResult.success("Agent reached maximum steps", context);
        }
        int timeBudgetSec = (int) context.getOrDefault("timeoutSec", AgentOptions.UNLIMITED);
        if (state.isTimedOut(timeBudgetSec)) {
            OutputFormatter.printWarning("Agent reached its time budget (" + timeBudgetSec
                                         + "s) without completing the task");
            return StepResult.success("Agent timed out", context);
        }
        return null;
    }

    /**
     * A run the user took back: a clean termination, but not a finished task.
     *
     * <p>Reported as {@link com.eonmux.cadetcoder.ExitCode#INTERRUPTED} rather than as success. The
     * termination being clean is about the agent's state -- nothing is half-written and the ledger is
     * whole -- and says nothing about whether the work was done. Answering 0 told every script that
     * ran {@code cadet agent} that the task it asked for had been completed, when what had actually
     * happened was a person stopping it part-way; the same key press, noticed by {@code bash} or
     * {@code workers} instead, has always answered 130.</p>
     */
    private StepResult interrupted(Map<String, Object> context) {
        OutputFormatter.printWarning("Agent execution interrupted by user request");
        return StepResult.interrupted("Agent interrupted by user", context);
    }

    /**
     * The model's reply to this step.
     *
     * @param systemPrompt what the agent is
     * @param userPrompt   where the run stands
     * @return the reply, or {@code null} when the request failed (already reported)
     */
    private String askTheModel(String systemPrompt, String userPrompt) {
        try {
            logAIStart("agent-ai", userPrompt, new HashMap<>());
            long started = System.currentTimeMillis();

            String reply = getAIManager().complete(new PromptData(systemPrompt, userPrompt),
                                                   new HashMap<>());

            logAIComplete("agent-ai", reply, System.currentTimeMillis() - started);
            return reply;
        } catch (Exception e) {
            OutputFormatter.printError("AI response failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Runs the action the model proposed, unless the loop guard refuses it.
     *
     * @param state   the run so far
     * @param context the run's context
     * @param action  what the model proposed
     * @return what the run came to, or {@code null} to take another turn
     */
    private StepResult runProposedAction(AgentState state, Map<String, Object> context,
                                         AgentAction action) {
        // Canonicalize the verb so the model's shell-execution synonyms (execute/exec/run) dispatch
        // to the real shell runner "bash" rather than the script-file "execute" command, which would
        // resolve the shell string as a (missing) file path.
        String dispatchCommand = CommandAliases.canonicalize(action.command);

        ActionLoopGuard.Decision decision = state.getLoopGuard().check(dispatchCommand, action.args);
        if (decision.isBlocked()) {
            return refuseRepetition(state, context, decision);
        }
        // The run is about to do something, so uber mode's closing questions start again from the
        // first one; see questionedCompletion.
        context.put("uberChecksPassed", 0);
        runAndRecord(state, dispatchCommand, action);
        return null;
    }

    /**
     * Refuses an action that cannot make progress, and tells the model why -- instead of letting it
     * spend the rest of its step budget re-running one command.
     *
     * <p>With no step ceiling, lack of progress is what ends the run: a model that keeps proposing
     * non-productive work after being told what repeated is stuck.</p>
     *
     * @param state    the run so far
     * @param context  the run's context
     * @param decision what the loop guard made of the proposal
     * @return the failure that ends the run, or {@code null} to skip this action and take another turn
     */
    private StepResult refuseRepetition(AgentState state, Map<String, Object> context,
                                        ActionLoopGuard.Decision decision) {
        logWarning("Loop detection", decision.getReason());
        state.getLoopGuard().recordBlocked();

        if (state.getLoopGuard().isStuck()) {
            String message = "Stopping: " + state.getLoopGuard().consecutiveBlocks()
                             + " proposed actions in a row made no progress ("
                             + decision.getReason() + ")";
            logWarning("Loop detection", message);
            OutputFormatter.printError(message);
            return StepResult.failure(message, context);
        }

        OutputFormatter.printWarning("Skipping repeated action: " + decision.getReason());
        context.put("loopGuidance", decision.getGuidance());
        return null;
    }

    /**
     * Runs one command, shows what it did, and records it for the next prompt.
     *
     * @param state           the run so far
     * @param dispatchCommand the canonical command to run
     * @param action          what the model proposed
     */
    private void runAndRecord(AgentState state, String dispatchCommand, AgentAction action) {
        String arguments = String.join(" ", action.args);
        logStep("Executing agent command", String.format("%s %s", dispatchCommand, arguments));

        // Announced BEFORE it runs, as a sub-header so the shell opens a nested section for it --
        // the same shape a chat action gets. This used to be printed only under --verbose, which
        // left a plain run silent for however long the command took, and it was placed after the
        // loop guard so a skipped action was announced as if it had run.
        OutputFormatter.printSubheader(
                com.eonmux.cadetcoder.ui.CommandOutputVisibility.describe(dispatchCommand, arguments));

        long started = System.currentTimeMillis();

        // The command's output is captured so it can be fed back to the model on the next step: the
        // agent must be able to reason about results, not just exit codes.
        StringBuilder captured = new StringBuilder();
        int           exitCode = executeCommand(dispatchCommand, action.args, captured);

        long duration = System.currentTimeMillis() - started;
        logPerformance("Agent command execution", duration);

        state.addExecutedAction(action, exitCode, captured.toString());
        state.getLoopGuard().observe(dispatchCommand, action.args, exitCode == 0, captured.toString());

        announceOutcome(state, dispatchCommand, action, exitCode, duration, captured.toString());
    }

    /**
     * The ordered record of what ran, printed in BOTH visibility modes: with output shown it is the
     * only line that states the outcome and the position in the run.
     *
     * <p>See {@link ChatCommand}: the command is named in the announcement above, so the record
     * restates it only when the output stands between the two lines. The exit code rides along on
     * the record rather than on a second line -- the record already says FAILED, so a separate
     * "Command failed with exit code" line restated it and only added the number.</p>
     */
    private void announceOutcome(AgentState state, String dispatchCommand, AgentAction action,
                                 int exitCode, long duration, String captured) {
        String record = com.eonmux.cadetcoder.ui.CommandOutputVisibility.isVisible()
                ? com.eonmux.cadetcoder.ui.CommandOutputVisibility.summarize(
                        state.getCurrentStep(), dispatchCommand, String.join(" ", action.args),
                        exitCode == 0, captured)
                : com.eonmux.cadetcoder.ui.CommandOutputVisibility.summarizeOutcome(
                        state.getCurrentStep(), exitCode == 0, captured);
        if (exitCode == 0) {
            // A status, marked as one, and hidden until the result is opened; see
            // ChatCommand#executeAction for both halves of that.
            com.eonmux.cadetcoder.ui.CollapsedOutput.hiding(() -> OutputFormatter.printSuccess(record));
            logStep("Agent command completed successfully",
                    String.format("Command '%s' completed in %dms", action.command, duration));
        } else {
            OutputFormatter.printWarning(record + "  exit " + exitCode);
            logWarning("Agent command failed",
                       String.format("Command '%s' failed with exit code: %d", action.command, exitCode));
        }
    }

    @Override
    public String getInitialPrompt(String[] args) {
        if (args.length == 0) {
            return "The user wants to launch an AI agent but didn't specify what task to complete. What should the agent do?";
        }
        return null;
    }

    @Override
    public boolean supportsIterativeExecution(String[] args) {
        // Always support iterative execution
        return true;
    }


    /**
     * Execute a registry command while capturing its stdout/stderr so the agent can reason about the
     * result on the next step. The captured text (ANSI-stripped) is appended to {@code outSink} and
     * also echoed back to the real streams so the user still sees it live. {@code chat} does the
     * same in {@code ActionRun.dispatch}.
     */
    private int executeCommand(String command, String[] args, StringBuilder outSink) {
        // Captured per THREAD, not by swapping the process-wide System.out: several of these run at
        // once under WorkerPool, and a global swap left one worker restoring another worker's buffer
        // as "the original", after which nothing printed by anyone reached the terminal again.
        //
        // ModelDispatch is what makes this the model's doing rather than the user's: it marks the
        // thread so `quit` ends the run instead of the session, and refuses a nested "agent" or
        // "chat" that would spawn loops until the stack or the budget ran out. Its refusal is
        // printed inside the capture, so the model reads it on the next step.
        com.eonmux.cadetcoder.ui.CapturedRun.Result run = com.eonmux.cadetcoder.ui.CapturedRun.of(
                () -> ModelDispatch.run(command, () -> registry().executeCommand(command, args)));
        String output = run.output();

        // Echo only when the user asked to see command output (see CommandOutputVisibility). The
        // captured text still reaches the model and the logs regardless. Printing it after the
        // capture has ended sends it wherever this thread's output belongs -- the terminal, or the
        // enclosing worker's transcript.
        if (com.eonmux.cadetcoder.ui.CommandOutputVisibility.isVisible() && !output.isEmpty()) {
            com.eonmux.cadetcoder.ui.ProgramOutput.print(output);
        }

        if (outSink != null && !output.isEmpty()) {
            outSink.append(output);
        }
        return run.exitCode();
    }

    @Override
    public Integer call() throws Exception {
        List<String> args = new ArrayList<>();
        if (taskParts != null) {
            Collections.addAll(args, taskParts);
        }
        if (timeout != null && timeout > AgentOptions.UNLIMITED) {
            args.add("-t");
            args.add(timeout.toString());
        }
        if (maxSteps != null && maxSteps > AgentOptions.UNLIMITED) {
            args.add("-m");
            args.add(maxSteps.toString());
        }
        if (verbose) {
            args.add("-v");
        }
        if (check != null && !check.isBlank()) {
            args.add("-c");
            args.add(check);
        }
        if (classic) {
            args.add("--classic");
        }
        return execute(args.toArray(new String[0]));
    }

    /**
     * Renders a step budget for humans and for the model's system prompt.
     *
     * @param maxSteps the budget, or {@link AgentOptions#UNLIMITED}
     * @return {@code "as many steps as needed"} when unlimited, otherwise {@code "up to N steps"}
     */
    static String describeStepBudget(int maxSteps) {
        return maxSteps > AgentOptions.UNLIMITED ? "up to " + maxSteps + " steps" : "as many steps as needed";
    }

    @Override
    public int execute(String[] args) {
        // Recursion-depth guard: bounds nested agent launches across ANY indirect path (the
        // per-command verb guard in executeCommand only blocks the literal "agent"/"chat" verbs,
        // but agent -> unknown verb -> registry chat-fallback -> agent could still nest unbounded).
        // The whole dispatch chain runs on one thread, so a thread-local depth catches every nested
        // AgentCommand.execute regardless of how it was reached.
        int depth = AGENT_DEPTH.get();
        if (depth >= MAX_AGENT_DEPTH) {
            OutputFormatter.printWarning("Refusing to launch a nested agent (recursion-depth guard).");
            return 1;
        }
        AGENT_DEPTH.set(depth + 1);
        try {
            startCommandLogging("agent", args);
            logStep("Initializing AI agent execution");

            // Read here only to say what this invocation asked for, and to refuse one that asks
            // for nothing. The run itself is configured from the same vector, by the same parser,
            // in the step that builds the run context -- so the vector is passed on whole.
            AgentOptions options = AgentOptions.parse(args);
            options.warnings().forEach(OutputFormatter::printWarning);

            if (options.task().length == 0) {
                logErrorQuietly("execute", "No task description provided");
                OutputFormatter.printError("No task description provided");
                completeCommandLogging(1);
                return 1;
            }

            logStep("Starting iterative agent execution");
            logStep("Agent configuration", String.format("Steps: %s, Timeout: %s, Verbose: %b",
                describeStepBudget(options.maxSteps()),
                options.timeoutSeconds() > AgentOptions.UNLIMITED ? options.timeoutSeconds() + "s" : "no limit",
                options.verbose()));

            // Use iterative executor for better multi-step handling
            IterativeExecutor executor = new IterativeExecutor();
            int result;
            try (ResumeScope scope = ResumeScope.open()) {
                result = executor.execute(this, args);
                ResumePoint.Agent done = progress(executor.contextAtEnd());
                // Cut off from the model, the run is worth carrying on only when it did something.
                if (result == com.eonmux.cadetcoder.ExitCode.INTERRUPTED
                    || result == com.eonmux.cadetcoder.ExitCode.UNREACHABLE && done.didSomething()) {
                    scope.stopped(ResumePoint.agent(Arrays.asList(args), done));
                }
            }

            completeCommandLogging(result);
            return result;
        } catch (Exception e) {
            logError("execute", "Agent execution failed", e);
            completeCommandLogging(1);
            return 1;
        } finally {
            AGENT_DEPTH.set(depth);
        }
    }

    /**
     * What this run had done when it stopped.
     *
     * @param context the context the run ended with
     * @return the harness run's record, or the classic loop's actions, with what the attempts
     *         before this one did when this run was a resume
     */
    private ResumePoint.Agent progress(Map<String, Object> context) {
        String earlier = resumed == null ? null : AgentResumeNote.earlierWork(resumed);
        int    passed  = context.get("uberChecksPassed") instanceof Integer count ? count : 0;
        Object record  = context.get(RUN_RECORD);
        if (record instanceof String directory) {
            return new ResumePoint.Agent(directory, List.of(), earlier, passed);
        }
        AgentState state = context.get("agentState") instanceof AgentState classic ? classic : null;
        return new ResumePoint.Agent(null, AgentResumeNote.actionsOf(state), earlier, passed);
    }

    /**
     * Carries on an interrupted agent, with the options it was started with.
     *
     * <p>The task is what the run was asked for, followed by what the interrupted run did; see
     * {@link AgentResumeNote}. The run is not confirmed again: it was when it was first started,
     * and the resume asked for it once more.</p>
     *
     * @param agent     what the interrupted run left
     * @param arguments its arguments as it was started with them
     * @param briefing  what else the run is told about the interrupt, or empty
     * @return the exit code
     */
    public int resume(ResumePoint.Agent agent, List<String> arguments, String briefing) {
        resumeNote = AgentResumeNote.of(agent, briefing);
        resumed    = agent;
        try {
            return execute(arguments.toArray(new String[0]));
        } finally {
            resumeNote = "";
            resumed    = null;
        }
    }

    @Override
    public String getUsage() {
        return "agent <task_description> [options]\n"
             + "  -t, --timeout <seconds>  Give up after this long\n"
             + "  -m, --max-steps <num>    Stop after this many steps\n"
             + "  -c, --check <command>    A command that leaves with 0 once the task is done\n"
             + "  -v, --verbose            Show each step in full\n"
             + "  -y, --yes                Skip the \"shall I proceed?\" confirmation\n"
             + "      --classic            Run under the classic loop, which keeps no record\n"
             + "  By default the agent runs as many steps and for as long as the task needs.\n"
             + "  It stops when the task completes, when it fails, or when it runs out of what\n"
             + "  it was allowed. Use -m/-t only to impose your own budget.\n"
             + "  Without -c the run ends when the agent says it is done; with it, the check is\n"
             + "  run after every action and its exit code is what \"done\" means.\n"
             + "  Every run writes what really happened to a record of its own, and ends by\n"
             + "  saying where it is and what to type to read it back.";
    }

    // Protected methods for dependency injection in tests
    protected AIManager getAIManager() {
        return AIManager.getInstance();
    }

    /**
     * The clock this run is timed against.
     *
     * <p>Separated by the same convention as {@link #getAIManager()}. A test of the time budget
     * that had to wait one out would either take as long as the budget it is testing or, given a
     * budget short enough to be worth waiting for, pass on a slow machine for the wrong reason.</p>
     */
    protected long nowMillis() {
        return System.currentTimeMillis();
    }

    protected ConfigManager getConfigManager() {
        return ConfigManager.getInstance();
    }

    protected ContextEngine getContextEngine() {
        try {
            return ContextEngine.getInstance();
        } catch (IOException e) {
            throw new RuntimeException("Failed to get ContextEngine instance", e);
        }
    }

}
