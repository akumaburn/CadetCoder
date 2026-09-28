package com.eonmux.cadetcoder.harness.tools;

import com.eonmux.cadetcoder.harness.plan.PlanAudit;

import java.util.List;

/**
 * A plan the agent found, kept so it can be committed without being written out again.
 *
 * <h2>Why a plan is committed by name</h2>
 *
 * <p>A plan is a list of actions the search produced inside one model, and the audit that says which
 * of its steps reach rules reality has never exercised belongs to that same list. Retyping the
 * actions into a commit is how the two come apart: one action changes, the audit is still the old
 * one, and the gate is told a plan has been checked when what it is given never was. Naming it keeps
 * the actions, the model they were found in and what was found out about them together.</p>
 *
 * @param name        what to commit it by
 * @param modelDigest the version it was found in
 * @param goal        the function it was searching for, or {@code null} for the model's own goal
 * @param actions     the plan itself
 * @param audit       which of its steps turn on rules nothing has tested
 */
public record PlanRecord(String name, String modelDigest, String goal, List<Object> actions,
                         PlanAudit audit) {

    public PlanRecord {
        actions = List.copyOf(actions);
    }

    /** The plan as the agent is told about it. */
    public String render() {
        StringBuilder out = new StringBuilder(name).append(": ").append(actions.size())
                                                   .append(actions.size() == 1 ? " action" : " actions")
                                                   .append(" in model ").append(modelDigest)
                                                   .append(" towards ")
                                                   .append(goal == null ? "is_goal" : goal);
        if (!audit.clean()) {
            out.append(System.lineSeparator()).append("  ").append(audit.render());
        }
        return out.toString();
    }

    @Override
    public String toString() {
        return name;
    }
}
