package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.harness.budget.BudgetLimits;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * What one {@code agent} invocation was asked for: its task, its budgets, and whether it may skip
 * its own confirmation.
 *
 * <h2>Why it is a value rather than fields on the command</h2>
 *
 * <p>{@code CommandRegistry} builds one {@link AgentCommand} and hands it every {@code agent} for the
 * life of the session. Options parsed into that instance's fields therefore outlive the invocation
 * that supplied them: a step budget typed once capped every later run, a {@code --timeout} stayed on
 * the clock, and {@code --yes} made every later agent skip the question that asks whether to start
 * at all. A run is handed one of these instead and keeps nothing.</p>
 *
 * <h2>Why parsing reports rather than prints</h2>
 *
 * <p>An invocation is parsed where the task is needed and again where the run is configured, so a
 * parser that printed its own complaints would print each of them twice. It collects them in
 * {@link #warnings()} and the invocation shows them once.</p>
 */
public final class AgentOptions {

    /**
     * Sentinel for "no budget": the agent runs until the task completes, fails, or
     * {@link ActionLoopGuard} determines it has stopped making progress.
     */
    public static final int UNLIMITED = 0;

    /** The flags that take a value, so {@code --flag=value} can be written as well as {@code --flag value}. */
    private static final Set<String> VALUE_TAKING_FLAGS =
            Set.of("-t", "--timeout", "-m", "--max-steps", "-c", "--check");

    /** Seconds are what a person asks for; milliseconds are what a budget is kept in. */
    static final long MILLIS_PER_SECOND = 1000L;

    private final String[]     task;
    private final int          timeoutSeconds;
    private final int          maxSteps;
    private final boolean      verbose;
    private final boolean      preconfirmed;
    private final boolean      classic;
    private final String       goalCheck;
    private final List<String> warnings;

    private AgentOptions(String[] task, int timeoutSeconds, int maxSteps, boolean verbose,
                         boolean preconfirmed, boolean classic, String goalCheck,
                         List<String> warnings) {
        this.task           = task;
        this.timeoutSeconds = timeoutSeconds;
        this.maxSteps       = maxSteps;
        this.verbose        = verbose;
        this.preconfirmed   = preconfirmed;
        this.classic        = classic;
        this.goalCheck      = goalCheck;
        this.warnings       = List.copyOf(warnings);
    }

    /**
     * Reads an argument vector.
     *
     * <p>Options are recognized anywhere in the vector because the task is free text that is joined
     * back together, so there is no positional meaning to preserve -- unlike {@link BashCommand},
     * where a trailing flag belongs to the command being run. A value that is not a positive number,
     * and a flag given without one, leave the budget unset and are reported: the task itself is still
     * runnable, and refusing to run it would answer a typo with nothing.</p>
     *
     * @param argv the argument vector; {@code null} is an invocation with no arguments
     * @return what the vector asked for
     */
    public static AgentOptions parse(String[] argv) {
        String[]     normalized = CommandOptions.expandInlineValues(argv, VALUE_TAKING_FLAGS);
        List<String> task       = new ArrayList<>(normalized.length);
        List<String> warnings   = new ArrayList<>();

        int     timeoutSeconds = UNLIMITED;
        int     maxSteps       = UNLIMITED;
        boolean verbose        = false;
        boolean preconfirmed   = false;
        boolean classic        = false;
        String  goalCheck      = null;

        for (int i = 0; i < normalized.length; i++) {
            String token = normalized[i];
            if (token == null) {
                continue;
            }
            switch (token) {
                case "-t":
                case "--timeout":
                    if (i + 1 < normalized.length) {
                        timeoutSeconds = positiveOr(timeoutSeconds, normalized[++i], token, warnings);
                    } else {
                        warnings.add("Option " + token + " requires a value. Ignoring.");
                    }
                    break;
                case "-m":
                case "--max-steps":
                    if (i + 1 < normalized.length) {
                        maxSteps = positiveOr(maxSteps, normalized[++i], token, warnings);
                    } else {
                        warnings.add("Option " + token + " requires a value. Ignoring.");
                    }
                    break;
                case "-v":
                case "--verbose":
                    verbose = true;
                    break;
                case "-y":
                case "--yes":
                    preconfirmed = true;
                    break;
                case "-c":
                case "--check":
                    if (i + 1 < normalized.length) {
                        goalCheck = namedOr(goalCheck, normalized[++i], token, warnings);
                    } else {
                        warnings.add("Option " + token + " requires a value. Ignoring.");
                    }
                    break;
                case "--classic":
                    classic = true;
                    break;
                default:
                    task.add(token);
                    break;
            }
        }
        return new AgentOptions(task.toArray(new String[0]), timeoutSeconds, maxSteps, verbose,
                preconfirmed, classic, goalCheck, warnings);
    }

    /**
     * Reads a goal check, keeping {@code fallback} and recording why when the value is not one.
     *
     * <p>A check that names nothing would be run after every action and could never pass, so the run
     * would be told the task was unfinished however finished it was. Refused here, where saying so
     * still reaches the person who typed it.</p>
     */
    private static String namedOr(String fallback, String value, String flag,
                                  List<String> warnings) {
        String line = value == null ? "" : value.trim();
        if (!line.isEmpty()) {
            return line;
        }
        warnings.add(flag + " needs a command to run; ignoring an empty one.");
        return fallback;
    }

    /** Reads a positive budget, keeping {@code fallback} and recording why when the value is not one. */
    private static int positiveOr(int fallback, String value, String flag, List<String> warnings) {
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed > 0) {
                return parsed;
            }
            warnings.add(flag + " must be positive; ignoring '" + value + "'.");
        } catch (NumberFormatException e) {
            warnings.add("Invalid value for " + flag + ": '" + value + "'. Ignoring.");
        }
        return fallback;
    }

    /** @return the tokens that make up the task description */
    public String[] task() {
        return task.clone();
    }

    /** @return the task as the model is given it */
    public String taskDescription() {
        return String.join(" ", task);
    }

    /** @return the wall-clock budget in seconds, or {@link #UNLIMITED} */
    public int timeoutSeconds() {
        return timeoutSeconds;
    }

    /** @return the step budget, or {@link #UNLIMITED} */
    public int maxSteps() {
        return maxSteps;
    }

    /** @return whether the run shows the agent's reasoning */
    public boolean verbose() {
        return verbose;
    }

    /**
     * Whether the caller has already obtained the user's consent to run this agent.
     *
     * <p>The {@code confirm_task} step exists for someone typing {@code agent <task>} at the shell:
     * it echoes the task and the step budget and asks whether to go ahead. A worker has no such
     * person. The consent was given upstream, when the user asked for the workers in the first
     * place, and the worker's copy of the question would be collected into its own transcript where
     * nobody would ever see it.</p>
     *
     * <p>Stated by the caller with {@code --yes} rather than inferred. An agent that quietly decided
     * for itself that nobody was watching, and therefore that it could skip its own confirmation,
     * would be doing exactly what the confirmation exists to prevent.</p>
     *
     * @return whether this invocation may skip its confirmation
     */
    public boolean preconfirmed() {
        return preconfirmed;
    }

    /**
     * Whether the caller asked for the loop that came before the harness.
     *
     * <p>The harness keeps a ledger of what really happened and refuses a committed action its own
     * model of the world cannot account for, which needs a backend that can hold an executable
     * theory in its head. Not every backend can. The old loop asks for one action at a time and
     * keeps only a transcript, which is less but is still useful, so it stays -- asked for by name
     * rather than fallen into, because a run that quietly downgraded itself would report the same
     * work with none of the evidence behind it.</p>
     *
     * @return whether this invocation runs under the classic loop
     */
    public boolean classic() {
        return classic;
    }

    /**
     * The command line that settles whether the task is done.
     *
     * <p>Without one the run can only end on the agent's own say-so. With one, the check runs after
     * every action and its exit code is what {@code GOAL} means -- a verdict from outside the
     * agent's own account of itself.</p>
     *
     * @return the check, or {@code null} when nothing was given to check the work with
     */
    public String goalCheck() {
        return goalCheck;
    }

    /**
     * What this invocation may spend, as a run's budget.
     *
     * <p>Two of the four allowances have an option behind them and two do not. Tokens are left
     * unbounded because a token budget the person at the terminal did not ask for stops a run
     * mid-task for a reason they cannot see; actions are left unbounded because the number of
     * changes a task needs is a property of the task, not of the invocation.</p>
     *
     * @return the allowances, with {@link BudgetLimits#UNLIMITED} wherever nothing was asked for
     */
    public BudgetLimits budget() {
        return new BudgetLimits(BudgetLimits.UNLIMITED, BudgetLimits.UNLIMITED,
                                timeoutSeconds > UNLIMITED ? timeoutSeconds * MILLIS_PER_SECOND
                                                           : BudgetLimits.UNLIMITED,
                                maxSteps > UNLIMITED ? maxSteps : BudgetLimits.UNLIMITED);
    }

    /** @return what the vector said that could not be acted on, in the order it was read */
    public List<String> warnings() {
        return Collections.unmodifiableList(warnings);
    }
}
