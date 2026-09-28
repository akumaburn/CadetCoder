package com.eonmux.cadetcoder.harness.plan;

import com.eonmux.cadetcoder.harness.model.ModelException;
import com.eonmux.cadetcoder.harness.model.WorldModel;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.function.Predicate;

/**
 * The same search, in the order the model's own estimate suggests.
 *
 * <p>A heuristic is optional and unverified: nothing checks that a model's estimate never
 * overestimates, so this is not guaranteed to return the shortest plan. That is an acceptable trade
 * for a search that finishes on a problem breadth first cannot reach the end of, and it is why a
 * model without a heuristic gets breadth first rather than something invented on its behalf.</p>
 *
 * <p>The goal is checked as each state is produced, before the search decides it has been somewhere
 * already, for the same reason it is in breadth first: a {@code key} that leaves out a goal-relevant
 * field must not be able to throw the answer away.</p>
 */
final class BestFirst {

    private record Reached(double estimate, long order, int cost, Object state, String key) {
    }

    /** Ties are broken by arrival, so the same model and start always produce the same plan. */
    private static final Comparator<Reached> NEAREST =
            Comparator.comparingDouble(Reached::estimate).thenComparingLong(Reached::order);

    private BestFirst() {
    }

    static SearchResult search(WorldModel model, Object start, List<Object> offered,
                               Predicate<Object> goal, SearchLimits limits) {
        Clock                clock    = new Clock(limits);
        Trail                trail    = new Trail();
        Map<String, Integer> cheapest = new HashMap<>();
        Queue<Reached>       frontier = new PriorityQueue<>(NEAREST);
        int                  expanded = 0;
        int                  deepest  = 0;
        long                 arrival  = 0;
        try {
            if (goal.test(start)) {
                return SearchResult.found(List.of(), clock.effort(0, 1, 0));
            }
            String startKey = model.key(start);
            trail.start(startKey);
            cheapest.put(startKey, 0);
            frontier.add(new Reached(estimate(model, start), arrival++, 0, start, startKey));
            while (!frontier.isEmpty()) {
                SearchStatus stopped = clock.stopped(expanded);
                if (stopped != null) {
                    return SearchResult.stopped(stopped,
                                                clock.effort(expanded, trail.size(), deepest));
                }
                Reached reached = frontier.poll();
                if (reached.cost() > cheapest.getOrDefault(reached.key(), reached.cost())
                    || reached.cost() >= limits.maxDepth()) {
                    continue;
                }
                expanded++;
                for (Object action : model.actions(reached.state(), offered)) {
                    Object next    = model.step(reached.state(), action);
                    String nextKey = model.key(next);
                    int    cost    = reached.cost() + 1;
                    if (goal.test(next)) {
                        String at = trail.reach(nextKey, reached.key(), action);
                        return SearchResult.found(trail.plan(at),
                                                  clock.effort(expanded, trail.size(), cost));
                    }
                    if (cost >= cheapest.getOrDefault(nextKey, Integer.MAX_VALUE)) {
                        continue;
                    }
                    cheapest.put(nextKey, cost);
                    trail.link(nextKey, reached.key(), action);
                    deepest = Math.max(deepest, cost);
                    frontier.add(new Reached(cost + estimate(model, next), arrival++, cost, next,
                                             nextKey));
                }
            }
            return SearchResult.stopped(SearchStatus.EXHAUSTED,
                                        clock.effort(expanded, trail.size(), deepest));
        } catch (ModelException failure) {
            return SearchResult.broken(failure.getMessage(),
                                       clock.effort(expanded, trail.size(), deepest));
        }
    }

    private static double estimate(WorldModel model, Object state) {
        Double guess = model.heuristic(state);
        return guess == null ? 0.0 : guess;
    }
}
