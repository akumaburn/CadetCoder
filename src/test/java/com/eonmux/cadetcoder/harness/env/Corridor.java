package com.eonmux.cadetcoder.harness.env;

import com.eonmux.cadetcoder.harness.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A corridor whose position is the whole of the observation: the world the harness is tested on.
 *
 * <h2>Why one environment serves every test</h2>
 *
 * <p>The gate, the toolbox and the driver each need a world that does exactly what they say it does,
 * and each grew its own. Three corridors that are almost the same is three places for a test to pass
 * against a world no other test has -- so a defect the gate's corridor exposes never reaches the
 * driver's. One corridor, with the surprises it can produce turned on by name, is a world every
 * layer is answerable to.</p>
 *
 * <h2>Why it does everything by asking</h2>
 *
 * <p>Nothing here happens on its own: it reaches no goal, ends no episode, drifts nowhere and blocks
 * nothing until a test says from where. A fixture with behaviour of its own is one whose surprises
 * turn up in tests that were not written for them.</p>
 */
public final class Corridor implements Environment {

    /** An action kind this corridor treats as impossible to take back. */
    public static final String DELETE = "delete";

    /** An action kind this corridor treats as reversible only at a price. */
    public static final String PAY = "pay";

    /** A step nothing ever reaches, which is how a surprise is turned back off. */
    public static final int NEVER = Integer.MAX_VALUE;

    private final List<Object> taken = new ArrayList<>();

    private int pos;
    private int goalAt     = NEVER;
    private int terminalAt = NEVER;
    private int driftFrom  = NEVER;
    private int blockFrom  = NEVER;

    /** One step along the corridor, as an action. */
    public static Object move(int steps) {
        return map("move", steps);
    }

    /** Every action really taken, in order. */
    public List<Object> taken() {
        return List.copyOf(taken);
    }

    /** How far along the corridor the world actually is. */
    public int reached() {
        return pos;
    }

    /** From this position on, the world reports the goal. */
    public void goalAt(int position) {
        goalAt = position;
    }

    /** From this position on, the world reports the episode over. */
    public void terminalAt(int position) {
        terminalAt = position;
    }

    /** From this action onwards the world moves further than anything predicted. */
    public void driftFrom(int step) {
        driftFrom = step;
    }

    /** From this action onwards the observation carries a field no model was written for. */
    public void blockFrom(int step) {
        blockFrom = step;
    }

    @Override
    public Object reset() {
        pos = 0;
        return observe();
    }

    @Override
    public Object observe() {
        return taken.size() > blockFrom ? map("pos", pos, "blocked", true) : map("pos", pos);
    }

    @Override
    public StepOutcome act(Object action) {
        int at = taken.size();
        taken.add(action);
        pos += ((Number) Json.at(action, "move")).intValue() + (at >= driftFrom ? 1 : 0);
        Map<String, Object> flags = new LinkedHashMap<>();
        if (pos >= goalAt) {
            flags.put(StepOutcome.GOAL_FLAG, true);
        }
        if (pos >= terminalAt) {
            flags.put(StepOutcome.TERMINAL_FLAG, true);
        }
        return new StepOutcome(observe(), flags, Map.of());
    }

    @Override
    public List<Object> actionSpace(Object observation) {
        return List.of(move(1));
    }

    @Override
    public Reversibility reversibility(Object action) {
        Object kind = Json.has(action, "kind") ? Json.at(action, "kind") : null;
        if (DELETE.equals(kind)) {
            return Reversibility.IRREVERSIBLE;
        }
        if (PAY.equals(kind)) {
            return Reversibility.COSTLY;
        }
        return Reversibility.REVERSIBLE;
    }

    @Override
    public String describe() {
        return "a corridor; an action is {\"move\": n} and an observation is {\"pos\": n}";
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> value = new LinkedHashMap<>();
        for (int at = 0; at < pairs.length; at += 2) {
            value.put((String) pairs[at], pairs[at + 1]);
        }
        return value;
    }
}
