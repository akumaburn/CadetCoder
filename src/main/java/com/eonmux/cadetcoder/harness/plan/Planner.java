package com.eonmux.cadetcoder.harness.plan;

import com.eonmux.cadetcoder.harness.model.ModelException;
import com.eonmux.cadetcoder.harness.model.WorldModel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Planning inside the model, which is the only place an agent is allowed to make mistakes for free.
 *
 * <h2>Why every search runs on a model of its own</h2>
 *
 * <p>Coverage is the record of which of a model's rules something has put to the test, and a
 * certificate's worth rests on it having been measured against the ledger. A search touches every
 * rule in the model within a few hundred states, so searching with the agent's own model would erase
 * the distinction between "reality exercised this rule" and "I imagined my way into it". Every entry
 * point here therefore plans with {@link WorldModel#fresh()} and leaves the caller's model as it
 * found it.</p>
 *
 * <h2>Why the plan is not the whole answer</h2>
 *
 * <p>A plan comes back with why the search stopped ({@link SearchStatus}), what it cost
 * ({@link SearchEffort}), and -- once {@link #audit} has compared it with a certificate -- which of
 * its steps turn on rules nothing has ever exercised. The commit gate needs all three; a bare list
 * of actions is the shape that lets an agent act confidently on a search that never ran.</p>
 */
public final class Planner {

    private Planner() {
    }

    /**
     * The shortest plan to the model's own goal.
     *
     * @param model   the theory to plan inside
     * @param start   where the world is
     * @param offered what the environment allows, or {@code null} when it allows anything
     * @return what the search found, and why it stopped
     */
    public static SearchResult bfs(WorldModel model, Object start, List<Object> offered) {
        return bfs(model, start, offered, SearchLimits.standard());
    }

    /** {@link #bfs(WorldModel, Object, List)} under budgets of the caller's choosing. */
    public static SearchResult bfs(WorldModel model, Object start, List<Object> offered,
                                   SearchLimits limits) {
        return bfs(model, start, offered, null, limits);
    }

    /**
     * {@link #bfs(WorldModel, Object, List)} towards something other than the model's own goal.
     *
     * @param goal what counts as arriving, or {@code null} to use the model's {@code is_goal}
     */
    public static SearchResult bfs(WorldModel model, Object start, List<Object> offered,
                                   Predicate<Object> goal, SearchLimits limits) {
        WorldModel planning = model.fresh();
        return BreadthFirst.search(planning, start, offered, arrival(planning, goal), limits);
    }

    /**
     * The best plan this model can be searched for: guided when it offers a heuristic, breadth
     * first when it does not.
     *
     * @param model   the theory to plan inside
     * @param start   where the world is
     * @param offered what the environment allows, or {@code null} when it allows anything
     * @return what the search found, and why it stopped
     */
    public static SearchResult search(WorldModel model, Object start, List<Object> offered) {
        return search(model, start, offered, SearchLimits.standard());
    }

    /** {@link #search(WorldModel, Object, List)} under budgets of the caller's choosing. */
    public static SearchResult search(WorldModel model, Object start, List<Object> offered,
                                      SearchLimits limits) {
        return search(model, start, offered, null, limits);
    }

    /**
     * {@link #search(WorldModel, Object, List)} towards something other than the model's own goal.
     *
     * @param goal what counts as arriving, or {@code null} to use the model's {@code is_goal}
     */
    public static SearchResult search(WorldModel model, Object start, List<Object> offered,
                                      Predicate<Object> goal, SearchLimits limits) {
        WorldModel        planning = model.fresh();
        Predicate<Object> arrival  = arrival(planning, goal);
        if (planning.heuristic(start) == null) {
            return BreadthFirst.search(planning, start, offered, arrival, limits);
        }
        return BestFirst.search(planning, start, offered, arrival, limits);
    }

    /**
     * Runs a plan through the model, one step at a time.
     *
     * @param model   the theory the plan was made in
     * @param start   where the world is
     * @param actions the plan
     * @return what the model says happens, and where it stops being able to say
     */
    public static Simulation simulate(WorldModel model, Object start, List<Object> actions) {
        WorldModel          running = model.fresh();
        List<SimulatedStep> steps   = new ArrayList<>();
        Set<String>         before  = new LinkedHashSet<>(running.coverage().hit());
        Object              state   = start;
        Integer             goalAt  = null;
        for (int index = 0; index < actions.size(); index++) {
            Object  action = actions.get(index);
            Object  next;
            Object  prediction;
            boolean goal;
            try {
                next       = running.step(state, action);
                prediction = running.predict(next);
                goal       = running.isGoal(next);
            } catch (ModelException failure) {
                return new Simulation(start, steps, goalAt, failure.getMessage(), index);
            }
            Set<String> after = running.coverage().hit();
            steps.add(new SimulatedStep(index, action, next, prediction, added(before, after), goal));
            before = after;
            state  = next;
            if (goal && goalAt == null) {
                goalAt = index;
            }
        }
        return new Simulation(start, steps, goalAt, null, null);
    }

    /**
     * Marks the steps of a simulated plan that reach rules the ledger has never exercised.
     *
     * @param simulation the plan as the model runs it
     * @param certified  the arms a certificate says a replay of the ledger reached
     * @return what to tell the commit gate
     */
    public static PlanAudit audit(Simulation simulation, Set<String> certified) {
        List<Integer>              steps = new ArrayList<>();
        Map<Integer, List<String>> arms  = new LinkedHashMap<>();
        for (SimulatedStep step : simulation.steps()) {
            List<String> untested = new ArrayList<>();
            for (String arm : step.arms()) {
                if (!certified.contains(arm)) {
                    untested.add(arm);
                }
            }
            if (!untested.isEmpty()) {
                steps.add(step.index());
                arms.put(step.index(), untested);
            }
        }
        return new PlanAudit(steps, arms);
    }

    private static Predicate<Object> arrival(WorldModel model, Predicate<Object> goal) {
        return goal == null ? model::isGoal : goal;
    }

    /**
     * The rules a step is the first to reach.
     *
     * <p>Coverage accumulates over an interpreter's life rather than resetting per call, so a step's
     * rules are the ones that appeared during it. A rule an earlier step in the same plan already
     * reached belongs to that earlier step, which is the step the commit gate would stop at
     * anyway.</p>
     */
    private static List<String> added(Set<String> before, Set<String> after) {
        List<String> fresh = new ArrayList<>();
        for (String arm : after) {
            if (!before.contains(arm)) {
                fresh.add(arm);
            }
        }
        fresh.sort(String::compareTo);
        return fresh;
    }
}
