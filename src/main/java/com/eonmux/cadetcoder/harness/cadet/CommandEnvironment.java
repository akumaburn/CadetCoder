package com.eonmux.cadetcoder.harness.cadet;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.harness.env.Environment;
import com.eonmux.cadetcoder.harness.env.Reversibility;
import com.eonmux.cadetcoder.harness.env.StepOutcome;
import com.eonmux.cadetcoder.commands.CommandCatalog;
import com.eonmux.cadetcoder.commands.CommandOutputBudget;
import com.eonmux.cadetcoder.commands.ModelDispatch;
import com.eonmux.cadetcoder.security.AllowedActions;
import com.eonmux.cadetcoder.ui.CapturedRun;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * This project, as a world the harness can act on: the workspace, reached through the same commands
 * a person types.
 *
 * <h2>Why every observation has the same fields</h2>
 *
 * <p>A model is written against the shape of an observation. An observation that carries what a
 * command printed after a step and leaves the field out before one is two shapes, and a model
 * written for either is contradicted by the other through no fault of its own -- which certification
 * reports as the model being wrong. So the first observation of an episode has the same fields as
 * every later one, with the command empty and its exit code zero, because nothing has failed.</p>
 *
 * <h2>Why a reset changes nothing</h2>
 *
 * <p>A workspace is one episode. There is no start to go back to: the files are where the last run
 * left them, and pretending otherwise would have the ledger record a restart that never happened.
 * {@link #reset()} is a re-grounding point and nothing more -- it reports where things stand, and
 * replay treats the transition after it as read rather than predicted.</p>
 *
 * <h2>Why the goal check is not in the observation</h2>
 *
 * <p>Whether the work is done is a verdict from outside what the agent can see, which is what flags
 * are for. Putting the check's output in the observation would make the model answerable for
 * predicting a test run it never asked for. It goes in {@code info}, which is recorded and shown and
 * never checked.</p>
 */
public final class CommandEnvironment implements Environment {

    /** What the agent is told when no way to check the work was supplied. */
    public static final String NO_CHECK =
            "There is no automatic check. Say DONE when you judge the task complete.";

    /** The line that was run, and the fields describing what it did. */
    private static final String COMMAND = "command";
    private static final String EXIT    = "exit";
    private static final String OUTPUT  = "output";

    /** The workspace, as much of it as an observation carries. */
    private static final String TREE       = "tree";
    private static final String TREE_TOTAL = "tree_total";
    private static final String TREE_HASH  = "tree_hash";

    /** What the goal check said, recorded but never predicted. */
    private static final String GOAL_CHECK = "goal_check";
    private static final String GOAL_EXIT  = "goal_exit";

    private static final int OK     = 0;
    private static final int REFUSED = 1;

    /** What the world answers an action it cannot read. */
    private static final String UNREADABLE =
            "That action named no command. An action is {\"command\": \"read\", "
            + "\"args\": [\"pom.xml\"]}, or the whole line in one string: "
            + "{\"command\": \"read pom.xml\"}.";

    private final CommandRegistry commands;
    private final Path            workspace;
    private final String          task;
    private final String          goalCheck;

    private String lastCommand = "";
    private int    lastExit    = OK;
    private String lastOutput  = "";

    /**
     * @param commands  the commands the agent may run
     * @param workspace the directory whose contents are the observable world
     * @param task      what the run is for, in the agent's own briefing
     * @param goalCheck a command line that leaves with zero when the task is done, or {@code null}
     *                  when there is nothing to check it with
     * @throws IllegalArgumentException if the workspace is not a directory, the task is blank, or
     *                                  the goal check names no command -- a check that silently
     *                                  never passes is worse than no check
     */
    public CommandEnvironment(CommandRegistry commands, Path workspace, String task,
                              String goalCheck) {
        if (commands == null) {
            throw new IllegalArgumentException("there has to be something to run commands with");
        }
        if (workspace == null || !Files.isDirectory(workspace)) {
            throw new IllegalArgumentException("there is no workspace at " + workspace);
        }
        if (task == null || task.isBlank()) {
            throw new IllegalArgumentException("there has to be a task for the run to be about");
        }
        if (goalCheck != null && !goalCheck.isBlank()
            && !CommandInvocation.from(Map.of(COMMAND, goalCheck)).named()) {
            throw new IllegalArgumentException("the goal check names no command: " + goalCheck);
        }
        this.commands  = commands;
        this.workspace = workspace;
        this.task      = task;
        this.goalCheck = goalCheck == null || goalCheck.isBlank() ? null : goalCheck;
    }

    @Override
    public Object reset() {
        return observe();
    }

    @Override
    public Object observe() {
        WorkspaceTree       tree = WorkspaceTree.of(workspace);
        Map<String, Object> seen = new LinkedHashMap<>();
        seen.put(COMMAND, lastCommand);
        seen.put(EXIT, lastExit);
        seen.put(OUTPUT, lastOutput);
        seen.put(TREE, tree.paths());
        seen.put(TREE_TOTAL, tree.total());
        seen.put(TREE_HASH, tree.digest());
        return seen;
    }

    /**
     * Runs one command for real.
     *
     * <p>An action the world cannot read is answered rather than thrown over. A model that emits one
     * has made a mistake worth a wasted step; a harness that dies of it has ended a run with budget
     * still in it, and the ledger records neither the mistake nor the correction.</p>
     *
     * <h2>Where security.allowedActions is applied</h2>
     *
     * <p>Here, because this is the only place this harness touches the world. The setting used to be
     * read by {@code SecurityValidator} alone, which screens the classic {@code chat} loop and is
     * never consulted on this path, so a user who narrowed the list narrowed nothing on the path the
     * tool runs by default. Both paths now ask {@link AllowedActions} the same question.</p>
     *
     * <p>A refusal is answered the same way an unreadable action is: the step is spent, the reason
     * is in the observation, and the run continues. The alternative ends a run the user configured
     * deliberately, and tells the model nothing it can act on.</p>
     */
    @Override
    public StepOutcome act(Object action) {
        CommandInvocation asked = CommandInvocation.from(action);
        if (!asked.named()) {
            lastCommand = "";
            lastExit    = REFUSED;
            lastOutput  = UNREADABLE;
            return StepOutcome.of(observe());
        }
        String notAllowed = AllowedActions.reasonToRefuse(asked.name());
        if (notAllowed != null) {
            lastCommand = asked.line();
            lastExit    = REFUSED;
            lastOutput  = notAllowed + ". Do the work with a command the list names.";
            return StepOutcome.of(observe());
        }
        CapturedRun.Result ran = run(asked);
        lastCommand = asked.line();
        lastExit    = ran.exitCode();
        lastOutput  = CommandOutputBudget.forPrompt(ran.output());
        return checked();
    }

    @Override
    public List<Object> actionSpace(Object observation) {
        return null;
    }

    @Override
    public Reversibility reversibility(Object action) {
        CommandInvocation asked = CommandInvocation.from(action);
        return CommandEffects.of(asked.name(), asked.argv());
    }

    @Override
    public String describe() {
        return "The world is a workspace of files. You act on it by running one CadetCoder command"
               + " at a time -- the same commands a person types at the prompt."
               + paragraph("TASK", task)
               + paragraph("AN ACTION", "  {\"" + COMMAND + "\": \"read\", \"args\": [\"pom.xml\"]}"
                                        + System.lineSeparator()
                                        + "The whole line in one string is read the same way:"
                                        + System.lineSeparator()
                                        + "  {\"" + COMMAND + "\": \"read pom.xml\"}")
               + paragraph("AN OBSERVATION", observationShape())
               + paragraph("SUCCESS", success())
               + paragraph("THE COMMANDS", CommandCatalog.coreCommands());
    }

    /**
     * What the goal check is, in the agent's own terms.
     *
     * <p>Naming the check is interface rather than rules: the agent has to know what it is being
     * measured by, and it could run the check itself and read the same exit code.</p>
     */
    private String success() {
        return goalCheck == null ? NO_CHECK
                                 : "The task is done when `" + goalCheck + "` leaves with exit code"
                                   + " 0. It is run after every action and what it said is reported"
                                   + " back to you.";
    }

    private String observationShape() {
        String eol = System.lineSeparator();
        return "Always these six fields, whatever happened:" + eol
               + "  " + COMMAND + "     the line that was run; empty before anything has been" + eol
               + "  " + EXIT + "        its exit code; 0 before anything has been run" + eol
               + "  " + OUTPUT + "      everything it printed, shortened if it ran long" + eol
               + "  " + TREE + "        up to " + WorkspaceTree.PATHS_SHOWN + " workspace paths,"
               + " sorted; build output and hidden directories are not in it" + eol
               + "  " + TREE_TOTAL + "  how many files the workspace has, listed or not" + eol
               + "  " + TREE_HASH + "   a digest of every file's path, size and modification time,"
               + " so a change past the end of the listing still shows";
    }

    private static String paragraph(String heading, String body) {
        String eol = System.lineSeparator();
        return eol + eol + heading + eol + body;
    }

    /**
     * The outcome of an action, with the goal check run afterwards when there is one.
     *
     * <p>The workspace is read after the check rather than before it, so the observation describes
     * the world as it stands at the end of the step. Reading it first would report a workspace that
     * a passing test run had already changed, and the next observation would differ from it with no
     * action in between -- a discontinuity no model can account for.</p>
     */
    private StepOutcome checked() {
        if (goalCheck == null) {
            return StepOutcome.of(observe());
        }
        CapturedRun.Result  ran   = run(CommandInvocation.from(Map.of(COMMAND, goalCheck)));
        Map<String, Object> flags = ran.exitCode() == OK ? Map.of(StepOutcome.GOAL_FLAG, true)
                                                         : Map.of();
        Map<String, Object> info  = new LinkedHashMap<>();
        info.put(GOAL_CHECK, CommandOutputBudget.forPrompt(ran.output()));
        info.put(GOAL_EXIT, ran.exitCode());
        return new StepOutcome(observe(), flags, info);
    }

    /**
     * Runs one invocation with its output collected.
     *
     * <p>Dispatched through {@link ModelDispatch}, which is what makes the command a model's doing
     * rather than a person's. Without it a run that emits {@code quit} closes the session of
     * whoever started it, and one that emits {@code agent} nests a second loop inside this one.</p>
     *
     * <p>Chat fallback is off: an unregistered name is reported as one, rather than being forwarded
     * to the model as a question. An agent that mistyped a command needs to be told it mistyped it,
     * not answered.</p>
     */
    private CapturedRun.Result run(CommandInvocation asked) {
        return CapturedRun.of(() -> ModelDispatch.run(
                asked.name(), () -> commands.executeCommand(asked.name(), asked.argv(), false)));
    }
}
