package com.eonmux.cadetcoder.harness.tools;

import com.eonmux.cadetcoder.harness.plan.PlanAudit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The plans this run has found, each under a name short enough to type back.
 *
 * <h2>Why they live for the run and no longer</h2>
 *
 * <p>A plan is only meaningful next to the model it was found in and the ledger it was found from,
 * and both of those move. Keeping plans on disk would mean a name that still resolves after a
 * restart to a list of actions nothing has re-checked, which is exactly the mistake the commit gate
 * exists to stop. A plan that outlives its run has to be searched for again, and searching again is
 * cheap next to acting on a stale one.</p>
 */
public final class Plans {

    /** What a plan's name starts with, so it cannot be mistaken for a model digest. */
    public static final String PREFIX = "P";

    private final Map<String, PlanRecord> saved = new LinkedHashMap<>();

    /**
     * Keeps a plan under the next free name.
     *
     * @param modelDigest the version it was found in
     * @param goal        the function it was searching for, or {@code null} for the model's own goal
     * @param actions     the plan itself
     * @param audit       which of its steps turn on rules nothing has tested
     * @return the kept plan, which knows its own name
     */
    public PlanRecord save(String modelDigest, String goal, List<Object> actions, PlanAudit audit) {
        PlanRecord record = new PlanRecord(PREFIX + (saved.size() + 1), modelDigest, goal, actions,
                                           audit);
        saved.put(record.name(), record);
        return record;
    }

    /**
     * The plan of a name.
     *
     * @param name what the agent called it
     * @return the plan, or {@code null} when this run has no such plan
     */
    public PlanRecord get(String name) {
        return name == null ? null : saved.get(name.trim());
    }

    /** Every plan this run has found, oldest first. */
    public List<PlanRecord> all() {
        return List.copyOf(saved.values());
    }

    /** Every plan's name. */
    public List<String> names() {
        return new ArrayList<>(saved.keySet());
    }

    /** How many plans this run has found. */
    public int size() {
        return saved.size();
    }
}
