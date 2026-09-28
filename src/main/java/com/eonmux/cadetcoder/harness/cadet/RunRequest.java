package com.eonmux.cadetcoder.harness.cadet;

import com.eonmux.cadetcoder.harness.budget.BudgetLimits;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * What one run was asked for: the work, where to do it, how to tell it is done, and what it may
 * spend.
 *
 * <h2>Why this is checked here and not where each part is used</h2>
 *
 * <p>A run reaches a backend within a second of starting and edits the project within a few more, so
 * every one of these mistakes is expensive by the time the part that needs it notices. An empty task
 * becomes a model asked to do nothing in particular; a project that is not there becomes a workspace
 * walk that finds nothing, which the agent reads as an empty repository and tries to work around.
 * Refusing here costs nothing and says which of the four was wrong.</p>
 *
 * <h2>Why narrowing returns a new request</h2>
 *
 * <p>One of these describes a run that is about to happen, and something that could be edited after
 * the run had been handed it would describe a different run from the one that is happening. The
 * budget and the check are added by returning another request, so what was asked for is fixed from
 * the moment it is asked.</p>
 *
 * @param task      the work, in the words whoever asked for it used
 * @param goalCheck the command that says whether the work is done, or {@code null} when there is none
 * @param project   the workspace to act on
 * @param limits    what the run may spend
 */
public record RunRequest(String task, String goalCheck, Path project, BudgetLimits limits) {

    /** Where this process was started, which is the project every command acts on. */
    private static final String WORKING_DIRECTORY = "user.dir";

    public RunRequest {
        if (task == null || task.isBlank()) {
            throw new IllegalArgumentException("a run has to be given a task to do");
        }
        if (project == null || !Files.isDirectory(project)) {
            throw new IllegalArgumentException("there is no project at " + project);
        }
        if (limits == null) {
            throw new IllegalArgumentException("a run has to say what it may spend; pass "
                                               + "BudgetLimits.unlimited() to set no bound");
        }
        task      = task.strip();
        goalCheck = goalCheck == null || goalCheck.isBlank() ? null : goalCheck.strip();
    }

    /**
     * A run with no bound and no way to check itself, which is what most tasks are.
     *
     * @param task    the work
     * @param project the workspace to act on
     * @return the request
     */
    public static RunRequest of(String task, Path project) {
        return new RunRequest(task, null, project, BudgetLimits.unlimited());
    }

    /**
     * A run on the project this process was started in.
     *
     * <p>Which project a command acts on is the directory it was run from, everywhere in this tool.
     * Named here so a run does not read the property itself and so the answer is the same one every
     * other command gives.</p>
     *
     * @param task the work
     * @return the request
     */
    public static RunRequest here(String task) {
        return of(task, Path.of(System.getProperty(WORKING_DIRECTORY)));
    }

    /**
     * The same run, with a command that says whether the work is done.
     *
     * @param check the command, as it would be typed at the prompt; blank means there is none
     * @return the narrowed request
     */
    public RunRequest checkedBy(String check) {
        return new RunRequest(task, check, project, limits);
    }

    /**
     * The same run, under an allowance.
     *
     * @param allowance what it may spend
     * @return the narrowed request
     */
    public RunRequest spending(BudgetLimits allowance) {
        return new RunRequest(task, goalCheck, project, allowance);
    }
}
