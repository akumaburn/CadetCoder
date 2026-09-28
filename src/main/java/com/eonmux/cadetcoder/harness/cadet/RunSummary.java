package com.eonmux.cadetcoder.harness.cadet;

import com.eonmux.cadetcoder.harness.loop.RunResult;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a run was asked to do and what became of it, in the run's own record.
 *
 * <h2>Why the record has to describe itself</h2>
 *
 * <p>Everything else a run leaves behind is evidence of the world: the ledger says what happened,
 * the models say what the agent believed, the notes say what it wanted to remember. None of them
 * says what the run was for. A directory named after the second it started in is therefore not
 * something anybody can find later -- picking the record of "fix the failing build" out of a dozen
 * meant opening ledgers and reading observations until one of them looked familiar.</p>
 *
 * <h2>Why it is written twice</h2>
 *
 * <p>The beginning is written before the harness runs and the ending is added when it stops. A run
 * that is killed at the terminal, runs out of wall clock, or takes the process down with it never
 * reaches the second write -- and those are exactly the runs somebody comes back to. Writing only at
 * the end would leave them anonymous, which is the failure this is here to prevent. A summary with
 * no ending says it did not finish rather than implying one.</p>
 *
 * @param task          the work, in the words whoever asked for it used
 * @param goalCheck     the command that says whether the work is done, or {@code null} if none
 * @param began         when the run started, as an instant
 * @param ended         when it stopped, or {@code null} if it never reached an ending
 * @param status        how it stopped, or {@code null} if it never reached an ending
 * @param deliberations how many times the agent thought
 * @param actions       how many things it did to the world
 * @param transitions   how long the ledger grew to
 * @param escalations   how many times it asked for more capable help
 * @param said          the last thing it said, which is its own account of the outcome
 */
public record RunSummary(String task, String goalCheck, String began, String ended, String status,
                         int deliberations, int actions, int transitions, int escalations,
                         String said) {

    public RunSummary {
        if (task == null || task.isBlank()) {
            throw new IllegalArgumentException("a summary is of a run, and a run has a task");
        }
        if (began == null || began.isBlank()) {
            throw new IllegalArgumentException("a summary has to say when the run began");
        }
    }

    /**
     * What is known about a run before it starts, which is everything except how it goes.
     *
     * @param request what the run was asked for
     * @return the summary to write down now
     */
    public static RunSummary beginning(RunRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("there is no summary of a run nobody asked for");
        }
        return new RunSummary(request.task(), request.goalCheck(), Instant.now().toString(),
                              null, null, 0, 0, 0, 0, null);
    }

    /**
     * The same summary with the ending added.
     *
     * @param result what the harness returned
     * @return a new summary; this one is left as it was
     */
    public RunSummary completed(RunResult result) {
        if (result == null) {
            throw new IllegalArgumentException("a run that returned nothing did not complete");
        }
        return new RunSummary(task, goalCheck, began, Instant.now().toString(),
                              result.status().name(), result.deliberations(), result.actions(),
                              result.ledgerLength(), result.escalations(), result.finalText());
    }

    /** Whether the run reached an ending, as opposed to stopping without recording one. */
    public boolean over() {
        return ended != null;
    }

    /** How the run turned out, in one line, for a listing of many runs. */
    public String outcome() {
        if (!over()) {
            return "did not finish";
        }
        StringBuilder out = new StringBuilder(status).append("  ")
                                                     .append(counted(deliberations, "deliberation"))
                                                     .append(", ")
                                                     .append(counted(actions, "action"))
                                                     .append(", ")
                                                     .append(counted(transitions, "transition"));
        if (escalations > 0) {
            out.append(", ").append(counted(escalations, "escalation"));
        }
        return out.toString();
    }

    /** How the run reads in a report of that one run. */
    public String render() {
        StringBuilder out = new StringBuilder(task);
        out.append("\n  check: ").append(goalCheck == null ? "no check" : goalCheck);
        out.append("\n  began: ").append(began);
        out.append("\n  ended: ").append(over() ? ended : "did not finish");
        out.append("\n  outcome: ").append(outcome());
        if (said != null && !said.isBlank()) {
            out.append("\n  said: ").append(said.strip());
        }
        return out.toString();
    }

    /** The summary as it is written down. */
    Map<String, Object> toValue() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("task", task);
        value.put("check", goalCheck);
        value.put("began", began);
        value.put("ended", ended);
        value.put("status", status);
        value.put("deliberations", deliberations);
        value.put("actions", actions);
        value.put("transitions", transitions);
        value.put("escalations", escalations);
        value.put("said", said);
        return value;
    }

    /**
     * Reads back what was written down.
     *
     * @param value the parsed file
     * @return the summary it denotes
     * @throws RunRecordException if it does not denote one
     */
    static RunSummary fromValue(Map<String, Object> value) {
        if (!(value.get("task") instanceof String task)
            || !(value.get("began") instanceof String began)) {
            throw new RunRecordException("a run summary says what the run was and when it began");
        }
        return new RunSummary(task, text(value.get("check")), began, text(value.get("ended")),
                              text(value.get("status")), count(value.get("deliberations")),
                              count(value.get("actions")), count(value.get("transitions")),
                              count(value.get("escalations")), text(value.get("said")));
    }

    private static String text(Object value) {
        return value instanceof String written ? written : null;
    }

    private static int count(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static String counted(int many, String thing) {
        return many + " " + thing + (many == 1 ? "" : "s");
    }

    @Override
    public String toString() {
        return render();
    }
}
